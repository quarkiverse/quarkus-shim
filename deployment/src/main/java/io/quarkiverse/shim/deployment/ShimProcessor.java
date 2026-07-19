package io.quarkiverse.shim.deployment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.util.TraceClassVisitor;

import io.quarkiverse.shim.AnnotationConflict;
import io.quarkiverse.shim.ShimRecorder;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * Scans the application index for {@code @Shim} classes and registers a
 * bytecode transformer for every targeted class.
 */
public class ShimProcessor {

    private static final Logger LOG = Logger.getLogger(ShimProcessor.class);

    private static final String FEATURE = "shim";

    private static final DotName SHIM = DotName.createSimple("io.quarkiverse.shim.Shim");
    private static final DotName SHIM_BEFORE = DotName.createSimple("io.quarkiverse.shim.ShimBefore");
    private static final DotName SHIM_AFTER = DotName.createSimple("io.quarkiverse.shim.ShimAfter");
    private static final DotName SHIM_REPLACE = DotName.createSimple("io.quarkiverse.shim.ShimReplace");
    private static final DotName SHIM_AROUND = DotName.createSimple("io.quarkiverse.shim.ShimAround");
    private static final DotName SHIM_PRIORITY = DotName.createSimple("io.quarkiverse.shim.ShimPriority");
    private static final DotName SHIM_ANNOTATE = DotName.createSimple("io.quarkiverse.shim.ShimAnnotate");
    private static final Set<DotName> SHIM_CONTROL_ANNOTATIONS = Set.of(
            SHIM, SHIM_BEFORE, SHIM_AFTER, SHIM_REPLACE, SHIM_AROUND, SHIM_PRIORITY, SHIM_ANNOTATE);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void applyShims(ShimBuildTimeConfig config,
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<AppliedShimsBuildItem> applied) {
        if (!config.enabled()) {
            LOG.info("Shim processing is disabled (quarkus.shim.enabled=false); @Shim declarations are ignored");
            applied.produce(new AppliedShimsBuildItem(List.of()));
            return;
        }
        IndexView index = combinedIndex.getIndex();
        Map<String, ClassPlan> plans = new LinkedHashMap<>();

        for (AnnotationInstance shimAnnotation : index.getAnnotations(SHIM)) {
            ClassInfo shimClass = shimAnnotation.target().asClass();
            String shimName = resolveShimName(shimAnnotation, shimClass);
            if (!isInstanceEnabled(config, shimName)) {
                LOG.infof("Shim '%s' is disabled via configuration; skipping %s", shimName, shimClass.name());
                continue;
            }
            String targetClass = resolveTargetClass(shimAnnotation, shimClass);
            ClassPlan plan = plans.computeIfAbsent(targetClass, k -> new ClassPlan());

            AnnotationValue definalizeValue = shimAnnotation.value("definalize");
            if (definalizeValue != null) {
                plan.definalize.addAll(List.of(definalizeValue.asStringArray()));
            }
            AnnotationValue widenValue = shimAnnotation.value("widenAccess");
            if (widenValue != null && widenValue.asBoolean()) {
                plan.widenAccess = true;
            }
            collectClassAnnotationPatch(shimClass, shimName, plan.annotationPatches);
            for (MethodInfo hook : shimClass.methods()) {
                collectOp(hook, targetClass, shimName, SHIM_BEFORE, ShimOp.Kind.BEFORE, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_AFTER, ShimOp.Kind.AFTER, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_REPLACE, ShimOp.Kind.REPLACE, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_AROUND, ShimOp.Kind.AROUND, plan.ops);
                collectMethodAnnotationPatch(hook, shimName, plan.annotationPatches);
            }
            for (FieldInfo field : shimClass.fields()) {
                collectFieldAnnotationPatch(field, shimName, plan.annotationPatches);
            }
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, ClassPlan> entry : plans.entrySet()) {
            String targetClass = entry.getKey();
            ClassPlan plan = entry.getValue();
            validateExistence(index, targetClass, plan.ops);
            validateDefinalize(index, targetClass, plan.definalize);
            validateAnnotationTargets(index, targetClass, plan.annotationPatches);

            List<ShimOp> ops = List.copyOf(plan.ops);
            List<ShimAnnotationPatch> annotationPatches = List.copyOf(plan.annotationPatches);
            Set<String> definalize = Set.copyOf(plan.definalize);
            boolean widen = plan.widenAccess;
            boolean dump = config.dumpTransformedClasses();

            transformers.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(targetClass)
                    // LocalVariablesSorter (used for @ShimAfter return-value locals) needs expanded frames
                    .setClassReaderOptions(org.objectweb.asm.ClassReader.EXPAND_FRAMES)
                    .setVisitorFunction((className, outputVisitor) -> {
                        if (dump) {
                            StringWriter sw = new StringWriter();
                            ClassVisitor trace = new TraceClassVisitor(outputVisitor, new PrintWriter(sw));
                            return new ShimClassVisitor(trace, ops, annotationPatches, definalize, widen,
                                    () -> ShimDump.write(className, sw.toString()));
                        }
                        return new ShimClassVisitor(outputVisitor, ops, annotationPatches, definalize, widen, null);
                    })
                    .build());
            // make ShimFields/ShimMethods reflection work in native image, including
            // members that the helpers discover while walking indexed superclasses
            for (String reflectionClass : reflectionHierarchy(index, targetClass)) {
                reflectiveClasses.produce(ReflectiveClassBuildItem.builder(reflectionClass).fields().methods().build());
            }

            for (ShimOp op : ops) {
                rows.add(row(targetClass, op));
                LOG.infof("Shim: %s", op.describe(targetClass));
            }
            for (ShimAnnotationPatch patch : annotationPatches) {
                rows.add(row(targetClass, patch));
                LOG.infof("Shim: %s", patch.describe(targetClass));
            }
        }
        applied.produce(new AppliedShimsBuildItem(rows));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void logAppliedAtStartup(ShimRecorder recorder, AppliedShimsBuildItem applied) {
        recorder.logApplied(applied.getDescriptions());
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    void devUiCard(AppliedShimsBuildItem applied, BuildProducer<CardPageBuildItem> cards) {
        CardPageBuildItem card = new CardPageBuildItem();
        card.addBuildTimeData("shims", applied.getRows());
        card.addPage(Page.tableDataPageBuilder("Applied shims")
                .icon("font-awesome-solid:bandage")
                .showColumn("target")
                .showColumn("method")
                .showColumn("kind")
                .showColumn("hook")
                .buildTimeDataKey("shims"));
        cards.produce(card);
    }

    // --- collection ----------------------------------------------------------

    private static Map<String, String> row(String targetClass, ShimOp op) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("target", targetClass);
        row.put("method", op.targetMethodName);
        row.put("kind", op.kind.name().toLowerCase());
        row.put("hook", op.hookRef());
        row.put("shim", op.shimName);
        return row;
    }

    private static Map<String, String> row(String targetClass, ShimAnnotationPatch patch) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("target", targetClass);
        row.put("method", patch.kind == ShimAnnotationPatch.Kind.CLASS ? "<class>" : patch.targetName);
        row.put("kind", "annotate-" + patch.kind.name().toLowerCase());
        row.put("hook", patch.sourceRef);
        row.put("shim", patch.shimName);
        return row;
    }

    private String resolveShimName(AnnotationInstance annotation, ClassInfo shimClass) {
        AnnotationValue name = annotation.value("name");
        if (name != null && !name.asString().isBlank()) {
            return name.asString();
        }
        return shimClass.name().withoutPackagePrefix();
    }

    private boolean isInstanceEnabled(ShimBuildTimeConfig config, String shimName) {
        var instance = config.instances().get(shimName);
        return instance == null || instance.enabled();
    }

    private String resolveTargetClass(AnnotationInstance annotation, ClassInfo shimClass) {
        AnnotationValue value = annotation.value();
        if (value != null) {
            String name = value.asClass().name().toString();
            if (!"void".equals(name) && !"java.lang.Void".equals(name)) {
                return name;
            }
        }
        AnnotationValue targetName = annotation.value("targetName");
        if (targetName != null && !targetName.asString().isBlank()) {
            return targetName.asString();
        }
        throw new IllegalStateException(
                "@Shim on " + shimClass.name() + " must specify the class to patch via value() or targetName()");
    }

    private void collectOp(MethodInfo hook, String targetClass, String shimName, DotName annotationName,
            ShimOp.Kind kind, List<ShimOp> ops) {
        AnnotationInstance annotation = hook.declaredAnnotation(annotationName);
        if (annotation == null) {
            return;
        }
        String hookRef = hook.declaringClass().name() + "#" + hook.name();
        if (!Modifier.isStatic(hook.flags())) {
            throw new IllegalStateException("Shim hook " + hookRef + " must be static");
        }
        if ((kind == ShimOp.Kind.BEFORE || kind == ShimOp.Kind.AFTER)
                && hook.returnType().kind() != Type.Kind.VOID) {
            throw new IllegalStateException("@ShimBefore/@ShimAfter hook " + hookRef + " must return void");
        }
        String targetMethod = annotation.value("method").asString();

        AnnotationValue descriptorValue = annotation.value("descriptor");
        AnnotationValue paramTypesValue = annotation.value("paramTypes");
        String filter = "";
        boolean paramsOnly = false;
        boolean hasDescriptor = descriptorValue != null && !descriptorValue.asString().isBlank();
        boolean hasParamTypes = paramTypesValue != null && paramTypesValue.asClassArray().length > 0;
        if (hasDescriptor && hasParamTypes) {
            throw new IllegalStateException("Shim hook " + hookRef
                    + " sets both descriptor() and paramTypes(); use one or the other");
        }
        if (hasDescriptor) {
            filter = descriptorValue.asString();
        } else if (hasParamTypes) {
            StringBuilder sb = new StringBuilder("(");
            for (Type t : paramTypesValue.asClassArray()) {
                sb.append(typeDescriptor(t));
            }
            filter = sb.append(')').toString();
            paramsOnly = true;
        }

        int priority = 0;
        AnnotationInstance priorityAnnotation = hook.declaredAnnotation(SHIM_PRIORITY);
        if (priorityAnnotation != null) {
            priority = priorityAnnotation.value().asInt();
        }

        ops.add(new ShimOp(kind, priority, targetMethod, filter, paramsOnly,
                hook.declaringClass().name().toString().replace('.', '/'),
                hook.name(), methodDescriptor(hook), shimName));
    }

    private void collectClassAnnotationPatch(ClassInfo shimClass, String shimName,
            List<ShimAnnotationPatch> patches) {
        AnnotationInstance marker = shimClass.declaredAnnotation(SHIM_ANNOTATE);
        if (marker == null) {
            return;
        }
        rejectMemberSelector(marker, shimClass.name().toString());
        patches.add(new ShimAnnotationPatch(ShimAnnotationPatch.Kind.CLASS, "", "", false,
                copyableAnnotations(shimClass.declaredAnnotations(), shimClass.name().toString()),
                conflictPolicy(marker),
                shimClass.name().toString(), shimName));
    }

    private void collectMethodAnnotationPatch(MethodInfo source, String shimName,
            List<ShimAnnotationPatch> patches) {
        AnnotationInstance marker = source.declaredAnnotation(SHIM_ANNOTATE);
        if (marker == null) {
            return;
        }
        String sourceRef = source.declaringClass().name() + "#" + source.name();
        String targetName = stringValue(marker, "target");
        if (targetName.isBlank()) {
            targetName = source.name();
        }
        MethodSelector selector = methodSelector(marker, sourceRef);
        patches.add(new ShimAnnotationPatch(ShimAnnotationPatch.Kind.METHOD, targetName,
                selector.descriptor, selector.paramsOnly,
                copyableAnnotations(source.declaredAnnotations(), sourceRef), conflictPolicy(marker), sourceRef, shimName));
    }

    private void collectFieldAnnotationPatch(FieldInfo source, String shimName,
            List<ShimAnnotationPatch> patches) {
        AnnotationInstance marker = source.declaredAnnotation(SHIM_ANNOTATE);
        if (marker == null) {
            return;
        }
        String sourceRef = source.declaringClass().name() + "#" + source.name();
        if (!stringValue(marker, "descriptor").isBlank() || hasParamTypes(marker)) {
            throw new IllegalStateException("@ShimAnnotate on field " + sourceRef
                    + " cannot set descriptor() or paramTypes(); those selectors apply only to methods");
        }
        String targetName = stringValue(marker, "target");
        if (targetName.isBlank()) {
            targetName = source.name();
        }
        patches.add(new ShimAnnotationPatch(ShimAnnotationPatch.Kind.FIELD, targetName, "", false,
                copyableAnnotations(source.declaredAnnotations(), sourceRef), conflictPolicy(marker), sourceRef, shimName));
    }

    private List<ShimAnnotation> copyableAnnotations(List<AnnotationInstance> declared, String sourceRef) {
        List<ShimAnnotation> annotations = declared.stream()
                .filter(annotation -> !SHIM_CONTROL_ANNOTATIONS.contains(annotation.name()))
                .map(ShimAnnotation::from)
                .toList();
        if (annotations.isEmpty()) {
            throw new IllegalStateException("@ShimAnnotate on " + sourceRef
                    + " has no non-Shim annotations to attach");
        }
        return annotations;
    }

    private MethodSelector methodSelector(AnnotationInstance marker, String sourceRef) {
        String descriptor = stringValue(marker, "descriptor");
        AnnotationValue paramTypesValue = marker.value("paramTypes");
        boolean hasParamTypes = paramTypesValue != null && paramTypesValue.asClassArray().length > 0;
        if (!descriptor.isBlank() && hasParamTypes) {
            throw new IllegalStateException("@ShimAnnotate on " + sourceRef
                    + " sets both descriptor() and paramTypes(); use one or the other");
        }
        if (!descriptor.isBlank()) {
            return new MethodSelector(descriptor, false);
        }
        if (hasParamTypes) {
            StringBuilder filter = new StringBuilder("(");
            for (Type type : paramTypesValue.asClassArray()) {
                filter.append(typeDescriptor(type));
            }
            return new MethodSelector(filter.append(')').toString(), true);
        }
        return new MethodSelector("", false);
    }

    private void rejectMemberSelector(AnnotationInstance marker, String sourceRef) {
        if (!stringValue(marker, "target").isBlank()
                || !stringValue(marker, "descriptor").isBlank()
                || hasParamTypes(marker)) {
            throw new IllegalStateException("@ShimAnnotate on class " + sourceRef
                    + " cannot set target(), descriptor(), or paramTypes()");
        }
    }

    private static boolean hasParamTypes(AnnotationInstance marker) {
        AnnotationValue value = marker.value("paramTypes");
        return value != null && value.asClassArray().length > 0;
    }

    private static AnnotationConflict conflictPolicy(AnnotationInstance marker) {
        AnnotationValue value = marker.value("onConflict");
        return value == null ? AnnotationConflict.REPLACE : AnnotationConflict.valueOf(value.asEnum());
    }

    private static String stringValue(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? "" : value.asString();
    }

    /**
     * When the target class is part of the application index we can fail the
     * build with a precise message instead of erroring later. Targets outside
     * the index (e.g. unindexed dependencies) are validated by the transformer.
     */
    private void validateExistence(IndexView index, String targetClass, List<ShimOp> ops) {
        ClassInfo target = index.getClassByName(DotName.createSimple(targetClass));
        if (target == null) {
            return;
        }
        for (ShimOp op : ops) {
            boolean found = target.methods().stream()
                    .anyMatch(m -> op.matches(m.name(), methodDescriptor(m)));
            if (!found) {
                if ("<clinit>".equals(op.targetMethodName)) {
                    throw new IllegalStateException("Shim hook " + op.hookRef()
                            + " targets the static initializer of " + targetClass
                            + " but the class does not have one");
                }
                throw new IllegalStateException("Shim hook " + op.hookRef() + " targets method '"
                        + op.targetMethodName + "' which does not exist on " + targetClass
                        + " (with the requested overload, if any)");
            }
        }
    }

    private void validateDefinalize(IndexView index, String targetClass, Set<String> fields) {
        if (fields.isEmpty()) {
            return;
        }
        ClassInfo target = index.getClassByName(DotName.createSimple(targetClass));
        if (target == null) {
            return;
        }
        for (String field : fields) {
            if (target.field(field) == null) {
                throw new IllegalStateException("@Shim definalize lists field '" + field
                        + "' which does not exist on " + targetClass);
            }
        }
    }

    private void validateAnnotationTargets(IndexView index, String targetClass,
            List<ShimAnnotationPatch> patches) {
        ClassInfo target = index.getClassByName(DotName.createSimple(targetClass));
        if (target == null) {
            return;
        }
        for (ShimAnnotationPatch patch : patches) {
            boolean found = switch (patch.kind) {
                case CLASS -> true;
                case FIELD -> target.field(patch.targetName) != null;
                case METHOD -> target.methods().stream()
                        .anyMatch(method -> patch.matchesMethod(method.name(), methodDescriptor(method)));
            };
            if (!found) {
                throw new IllegalStateException("@ShimAnnotate template " + patch.sourceRef + " targets "
                        + patch.kind.name().toLowerCase() + " '" + patch.targetName + "' which does not exist on "
                        + targetClass + (patch.kind == ShimAnnotationPatch.Kind.METHOD
                                ? " (with the requested overload, if any)"
                                : ""));
            }
        }
    }

    private static String methodDescriptor(MethodInfo method) {
        StringBuilder sb = new StringBuilder("(");
        for (Type parameter : method.parameterTypes()) {
            sb.append(typeDescriptor(parameter));
        }
        return sb.append(')').append(typeDescriptor(method.returnType())).toString();
    }

    static String typeDescriptor(Type type) {
        switch (type.kind()) {
            case VOID:
                return "V";
            case PRIMITIVE:
                switch (type.asPrimitiveType().primitive()) {
                    case BOOLEAN:
                        return "Z";
                    case BYTE:
                        return "B";
                    case CHAR:
                        return "C";
                    case SHORT:
                        return "S";
                    case INT:
                        return "I";
                    case LONG:
                        return "J";
                    case FLOAT:
                        return "F";
                    case DOUBLE:
                        return "D";
                    default:
                        throw new IllegalStateException("Unknown primitive: " + type);
                }
            case ARRAY:
                ArrayType arrayType = type.asArrayType();
                return "[".repeat(arrayType.dimensions()) + typeDescriptor(arrayType.constituent());
            case CLASS:
            case PARAMETERIZED_TYPE:
                return "L" + type.name().toString().replace('.', '/') + ";";
            default:
                throw new IllegalStateException(
                        "Unsupported type in shim hook signature (no generics/type variables): " + type);
        }
    }

    static List<String> reflectionHierarchy(IndexView index, String targetClass) {
        List<String> hierarchy = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String currentName = targetClass;
        while (currentName != null && !"java.lang.Object".equals(currentName) && seen.add(currentName)) {
            hierarchy.add(currentName);
            ClassInfo current = index.getClassByName(DotName.createSimple(currentName));
            if (current == null || current.superName() == null) {
                break;
            }
            currentName = current.superName().toString();
        }
        return List.copyOf(hierarchy);
    }

    /** Accumulates all ops, definalize fields and widen flag for one target class. */
    private static final class ClassPlan {
        final List<ShimOp> ops = new ArrayList<>();
        final List<ShimAnnotationPatch> annotationPatches = new ArrayList<>();
        final Set<String> definalize = new LinkedHashSet<>();
        boolean widenAccess;
    }

    private record MethodSelector(String descriptor, boolean paramsOnly) {
    }
}
