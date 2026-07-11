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
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.util.TraceClassVisitor;

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
            for (MethodInfo hook : shimClass.methods()) {
                collectOp(hook, targetClass, shimName, SHIM_BEFORE, ShimOp.Kind.BEFORE, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_AFTER, ShimOp.Kind.AFTER, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_REPLACE, ShimOp.Kind.REPLACE, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_AROUND, ShimOp.Kind.AROUND, plan.ops);
            }
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, ClassPlan> entry : plans.entrySet()) {
            String targetClass = entry.getKey();
            ClassPlan plan = entry.getValue();
            validateExistence(index, targetClass, plan.ops);
            validateDefinalize(index, targetClass, plan.definalize);

            List<ShimOp> ops = List.copyOf(plan.ops);
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
                            return new ShimClassVisitor(trace, ops, definalize, widen,
                                    () -> ShimDump.write(className, sw.toString()));
                        }
                        return new ShimClassVisitor(outputVisitor, ops, definalize, widen, null);
                    })
                    .build());
            // make ShimFields/ShimMethods reflection work in native image
            reflectiveClasses.produce(ReflectiveClassBuildItem.builder(targetClass).fields().methods().build());

            for (ShimOp op : ops) {
                rows.add(row(targetClass, op));
                LOG.infof("Shim: %s", op.describe(targetClass));
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

    private static String methodDescriptor(MethodInfo method) {
        StringBuilder sb = new StringBuilder("(");
        for (Type parameter : method.parameterTypes()) {
            sb.append(typeDescriptor(parameter));
        }
        return sb.append(')').append(typeDescriptor(method.returnType())).toString();
    }

    private static String typeDescriptor(Type type) {
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
                return "[".repeat(arrayType.dimensions()) + typeDescriptor(arrayType.component());
            case CLASS:
            case PARAMETERIZED_TYPE:
                return "L" + type.name().toString().replace('.', '/') + ";";
            default:
                throw new IllegalStateException(
                        "Unsupported type in shim hook signature (no generics/type variables): " + type);
        }
    }

    /** Accumulates all ops, definalize fields and widen flag for one target class. */
    private static final class ClassPlan {
        final List<ShimOp> ops = new ArrayList<>();
        final Set<String> definalize = new LinkedHashSet<>();
        boolean widenAccess;
    }
}
