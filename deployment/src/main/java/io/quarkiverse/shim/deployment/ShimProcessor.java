package io.quarkiverse.shim.deployment;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import io.quarkiverse.shim.AnnotationConflict;
import io.quarkiverse.shim.ShimRecorder;
import io.quarkiverse.shim.VersionMismatch;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

/**
 * Scans the application index for {@code @Shim} classes and registers a
 * bytecode transformer for every targeted class.
 */
public class ShimProcessor {

    private static final Logger LOG = Logger.getLogger(ShimProcessor.class);

    private static final String FEATURE = "shim";

    private static final String OBJECT_DESCRIPTOR = "Ljava/lang/Object;";

    /**
     * Shim weaves whole method bodies, so it wants to see the class after every
     * other extension has had its turn. Quarkus applies transformers in
     * ascending priority, so a high value puts shim last.
     */
    private static final int SHIM_TRANSFORMER_PRIORITY = 1000;

    /**
     * Compiler-generated members. Bridge methods delegate to the real method,
     * so weaving or annotating them duplicates the effect and makes it depend
     * on the caller's static type; they are only ever matched when a shim pins
     * their exact descriptor.
     */
    private static final int SYNTHETIC_MEMBER = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;

    static boolean isCompilerGenerated(MethodInfo method) {
        return (method.flags() & SYNTHETIC_MEMBER) != 0;
    }

    private static final DotName SHIM = DotName.createSimple("io.quarkiverse.shim.Shim");
    private static final DotName SHIM_BEFORE = DotName.createSimple("io.quarkiverse.shim.ShimBefore");
    private static final DotName SHIM_AFTER = DotName.createSimple("io.quarkiverse.shim.ShimAfter");
    private static final DotName SHIM_REPLACE = DotName.createSimple("io.quarkiverse.shim.ShimReplace");
    private static final DotName SHIM_AROUND = DotName.createSimple("io.quarkiverse.shim.ShimAround");
    private static final DotName SHIM_CATCH = DotName.createSimple("io.quarkiverse.shim.ShimCatch");
    private static final DotName SHIM_FINALLY = DotName.createSimple("io.quarkiverse.shim.ShimFinally");
    private static final DotName SHIM_PRIORITY = DotName.createSimple("io.quarkiverse.shim.ShimPriority");
    private static final DotName SHIM_ANNOTATE = DotName.createSimple("io.quarkiverse.shim.ShimAnnotate");
    private static final Set<DotName> SHIM_CONTROL_ANNOTATIONS = Set.of(
            SHIM, SHIM_BEFORE, SHIM_AFTER, SHIM_REPLACE, SHIM_AROUND, SHIM_CATCH, SHIM_FINALLY, SHIM_PRIORITY,
            SHIM_ANNOTATE);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void applyShims(ShimBuildTimeConfig config,
            CombinedIndexBuildItem combinedIndex,
            CurateOutcomeBuildItem curateOutcome,
            ApplicationArchivesBuildItem archives,
            OutputTargetBuildItem outputTarget,
            BuildProducer<BytecodeTransformerBuildItem> transformers,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BuildProducer<AppliedShimsBuildItem> applied) {
        if (!config.enabled()) {
            LOG.info("Shim processing is disabled (quarkus.shim.enabled=false); @Shim declarations are ignored");
            applied.produce(new AppliedShimsBuildItem(List.of(), List.of()));
            return;
        }
        IndexView index = combinedIndex.getIndex();
        Map<String, ClassPlan> plans = new LinkedHashMap<>();
        ShimVersionGate gate = new ShimVersionGate(curateOutcome, archives);
        List<Map<String, String>> retiredRows = new ArrayList<>();

        Map<String, String> claimedNames = new LinkedHashMap<>();
        Set<String> declaredNames = new LinkedHashSet<>();

        for (AnnotationInstance shimAnnotation : index.getAnnotations(SHIM)) {
            ClassInfo shimClass = shimAnnotation.target().asClass();
            String shimName = resolveShimName(shimAnnotation, shimClass);
            declaredNames.add(shimName);
            String previousOwner = claimedNames.putIfAbsent(shimName, shimClass.name().toString());
            if (previousOwner != null) {
                throw new IllegalStateException("Two shims share the name '" + shimName + "': " + previousOwner
                        + " and " + shimClass.name()
                        + ". The name keys quarkus.shim.instances.\"" + shimName + "\".enabled, so they could not be"
                        + " configured apart; give at least one an explicit @Shim(name = ...)");
            }
            if (!isInstanceEnabled(config, shimName)) {
                LOG.infof("Shim '%s' is disabled via configuration; skipping %s", shimName, shimClass.name());
                continue;
            }
            String targetClass = resolveTargetClass(shimAnnotation, shimClass);

            ShimVersionGate.Decision decision = gate.evaluate(shimClass.name().toString(), targetClass,
                    stringValue(shimAnnotation, "dependency"), stringValue(shimAnnotation, "versions"));
            if (!decision.applies()) {
                if (versionMismatchPolicy(shimAnnotation) == VersionMismatch.FAIL) {
                    throw new IllegalStateException("Shim '" + shimName + "' (" + shimClass.name()
                            + ") does not apply: " + decision.reason()
                            + ". Update the patch for the new version, re-pin it, or remove it"
                            + " (onVersionMismatch = VersionMismatch.SKIP retires it with a warning instead)");
                }
                LOG.warnf("Shim '%s' (%s) was not applied to %s: %s", shimName, shimClass.name(), targetClass,
                        decision.reason());
                retiredRows.add(row(shimName, targetClass, decision));
                continue;
            }

            ClassPlan plan = plans.computeIfAbsent(targetClass, k -> new ClassPlan());
            plan.shimClasses.add(shimClass.name().toString());

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
                collectOp(hook, targetClass, shimName, SHIM_CATCH, ShimOp.Kind.CATCH, plan.ops);
                collectOp(hook, targetClass, shimName, SHIM_FINALLY, ShimOp.Kind.FINALLY, plan.ops);
                collectMethodAnnotationPatch(hook, shimName, plan.annotationPatches);
            }
            for (FieldInfo field : shimClass.fields()) {
                collectFieldAnnotationPatch(field, shimName, plan.annotationPatches);
            }
        }

        warnAboutUnknownInstances(config, declaredNames);

        List<Map<String, String>> rows = new ArrayList<>();
        for (Map.Entry<String, ClassPlan> entry : plans.entrySet()) {
            String targetClass = entry.getKey();
            ClassPlan plan = entry.getValue();
            if (plan.isEmpty()) {
                LOG.warnf("@Shim targets %s but declares no hooks, no @ShimAnnotate template, no definalize entry"
                        + " and no widenAccess; nothing will be woven", targetClass);
                continue;
            }
            validateTargetIsTransformable(index, archives, targetClass, plan);
            validateExistence(index, targetClass, plan.ops);
            validateDefinalize(index, targetClass, plan.definalize);
            validateAnnotationTargets(index, targetClass, plan.annotationPatches);

            List<ShimOp> ops = List.copyOf(plan.ops);
            List<ShimAnnotationPatch> annotationPatches = List.copyOf(plan.annotationPatches);
            Set<String> definalize = Set.copyOf(plan.definalize);
            boolean widen = plan.widenAccess;
            boolean dump = config.dumpTransformedClasses();

            boolean verify = config.verifyTransformedClasses();
            Path dumpDir = outputTarget.getOutputDirectory().resolve("shim");

            transformers.produce(new BytecodeTransformerBuildItem.Builder()
                    .setClassToTransform(targetClass)
                    // LocalVariablesSorter (used for @ShimAfter return-value locals) needs expanded frames
                    .setClassReaderOptions(org.objectweb.asm.ClassReader.EXPAND_FRAMES)
                    // Run last, so the class shim sees is the one every other
                    // extension has already finished with. Pinning this keeps
                    // the chain position deliberate rather than incidental.
                    .setPriority(SHIM_TRANSFORMER_PRIORITY)
                    .setVisitorFunction((className, outputVisitor) -> {
                        ClassVisitor downstream = verify ? new CheckClassAdapter(outputVisitor, false) : outputVisitor;
                        if (dump) {
                            StringWriter sw = new StringWriter();
                            ClassVisitor trace = new TraceClassVisitor(downstream, new PrintWriter(sw));
                            // dump from the visitor itself rather than only on a
                            // clean visitEnd, so a weave that fails validation
                            // still leaves behind the trace explaining why
                            return new ShimDump.Dumping(
                                    new ShimClassVisitor(trace, ops, annotationPatches, definalize, widen, null),
                                    dumpDir, className, sw);
                        }
                        return new ShimClassVisitor(downstream, ops, annotationPatches, definalize, widen, null);
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
        AppliedShimsBuildItem result = new AppliedShimsBuildItem(rows, retiredRows);
        if (config.report()) {
            writeReport(result, outputTarget.getOutputDirectory().resolve("shim-report.txt"));
        }
        applied.produce(result);
    }

    /**
     * Writes a summary of what was woven and what was held back, so a reviewer
     * or a CI job can diff it across builds instead of scraping the log.
     */
    private static void writeReport(AppliedShimsBuildItem applied, Path file) {
        String newline = System.lineSeparator();
        StringBuilder report = new StringBuilder();
        report.append("# quarkus-shim build report").append(newline).append(newline);
        report.append("applied: ").append(applied.getRows().size()).append(newline);
        for (String description : applied.getDescriptions()) {
            report.append("  ").append(description).append(newline);
        }
        report.append(newline).append("retired: ").append(applied.getRetiredRows().size()).append(newline);
        for (String description : applied.getRetiredDescriptions()) {
            report.append("  ").append(description).append(newline);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report.toString());
            LOG.infof("Shim: wrote %s", file);
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Shim: could not write %s: %s", file, e.toString());
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void logAppliedAtStartup(ShimRecorder recorder, AppliedShimsBuildItem applied) {
        recorder.logApplied(applied.getDescriptions());
        recorder.logRetired(applied.getRetiredDescriptions());
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
        card.addBuildTimeData("retiredShims", applied.getRetiredRows());
        card.addPage(Page.tableDataPageBuilder("Retired shims")
                .icon("font-awesome-solid:calendar-xmark")
                .showColumn("shim")
                .showColumn("target")
                .showColumn("dependency")
                .showColumn("version")
                .showColumn("reason")
                .buildTimeDataKey("retiredShims"));
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

    private static Map<String, String> row(String shimName, String targetClass, ShimVersionGate.Decision decision) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("shim", shimName);
        row.put("target", targetClass);
        row.put("dependency", decision.coordinates());
        row.put("version", decision.actualVersion());
        row.put("reason", decision.reason());
        return row;
    }

    private static VersionMismatch versionMismatchPolicy(AnnotationInstance annotation) {
        AnnotationValue value = annotation.value("onVersionMismatch");
        return value == null ? VersionMismatch.SKIP : VersionMismatch.valueOf(value.asEnum());
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
        validateHookIsReachable(hook, hookRef, targetClass);
        if (kind != ShimOp.Kind.REPLACE && kind != ShimOp.Kind.AROUND
                && hook.returnType().kind() != Type.Kind.VOID) {
            throw new IllegalStateException("@Shim" + capitalize(kind) + " hook " + hookRef + " must return void");
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

        ShimOp op = new ShimOp(kind, priority, targetMethod, filter, paramsOnly,
                hook.declaringClass().name().toString().replace('.', '/'),
                hook.name(), methodDescriptor(hook), Modifier.isInterface(hook.declaringClass().flags()), shimName);
        if (kind == ShimOp.Kind.CATCH) {
            AnnotationValue exception = annotation.value("exception");
            if (exception != null) {
                op.caughtExceptionInternalName = exception.asClass().name().toString().replace('.', '/');
            }
        }
        ops.add(op);
    }

    private static String capitalize(ShimOp.Kind kind) {
        String name = kind.name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * The hook is invoked from the target class's own bytecode, so it must be
     * reachable from there. javac never sees that call site, so nothing else
     * catches a hook the target cannot call — it would surface as an
     * {@link IllegalAccessError} on the first invocation instead.
     */
    private static void validateHookIsReachable(MethodInfo hook, String hookRef, String targetClass) {
        String hookPackage = hook.declaringClass().name().packagePrefix();
        String targetPackage = packagePrefixOf(targetClass);
        boolean samePackage = hookPackage == null ? targetPackage == null : hookPackage.equals(targetPackage);
        if (samePackage) {
            return;
        }
        if (!Modifier.isPublic(hook.flags())) {
            throw new IllegalStateException("Shim hook " + hookRef + " must be public: it is invoked from "
                    + targetClass + ", which is in a different package"
                    + " (or move the shim into the target's package)");
        }
        if (!Modifier.isPublic(hook.declaringClass().flags())) {
            throw new IllegalStateException("Shim class " + hook.declaringClass().name()
                    + " must be public: its hooks are invoked from " + targetClass
                    + ", which is in a different package");
        }
    }

    private static String packagePrefixOf(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? null : className.substring(0, lastDot);
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
                    + " has no annotations to attach. Note that only annotations with CLASS or RUNTIME retention"
                    + " reach the class file — a RetentionPolicy.SOURCE annotation is discarded by javac and"
                    + " cannot be copied");
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
     * Quarkus can only transform a class it can locate in an application
     * archive; for anything else {@code ClassTransformingBuildStep} logs
     * "Cannot transform ..." and moves on. Without this check the shim is
     * reported as applied while the target keeps its original bytecode - which
     * is exactly what a typo in {@code targetName} looks like.
     */
    private void validateTargetIsTransformable(IndexView index, ApplicationArchivesBuildItem archives,
            String targetClass, ClassPlan plan) {
        if (archives.containingArchive(targetClass) != null) {
            return;
        }
        boolean indexed = index.getClassByName(DotName.createSimple(targetClass)) != null;
        String culprits = plan.shimClasses.isEmpty() ? "" : " (declared by " + String.join(", ", plan.shimClasses) + ")";
        throw new IllegalStateException("@Shim targets " + targetClass + culprits
                + ", which is not in any application archive, so it cannot be transformed"
                + (indexed
                        ? ". The class is indexed but its archive has no transformable form"
                        : ". Check the spelling, and note that a class in a dependency without a Jandex index"
                                + " cannot be patched - index it with the Jandex plugin or"
                                + " quarkus.index-dependency.*")
                + ". Nothing would have been woven, so the build fails rather than report a patch that"
                + " was never applied");
    }

    /**
     * A configured instance name that matches no shim is almost always a typo,
     * and silently doing nothing is the worst possible outcome for a key whose
     * whole job is to turn a patch off.
     */
    private static void warnAboutUnknownInstances(ShimBuildTimeConfig config, Set<String> declaredNames) {
        for (String configured : config.instances().keySet()) {
            if (!declaredNames.contains(configured)) {
                LOG.warnf("Configuration names shim '%s' (quarkus.shim.instances.\"%s\".*) but no @Shim declares"
                        + " that name; known shims: %s", configured, configured,
                        declaredNames.isEmpty() ? "(none)" : String.join(", ", declaredNames));
            }
        }
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
            // filter by name before building any descriptor: an unrelated method
            // must never be able to fail the build for a shim that never named it
            boolean found = target.methods().stream()
                    .filter(m -> op.targetMethodName.equals(m.name()))
                    .filter(m -> !isCompilerGenerated(m) || op.pinsExactDescriptor())
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
                        .filter(method -> patch.targetName.equals(method.name()))
                        .filter(method -> !isCompilerGenerated(method) || patch.pinsExactDescriptor())
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

    /**
     * The descriptor the JVM actually sees for {@code method}.
     * <p>
     * {@link MethodInfo#descriptorParameterTypes()} is used rather than
     * {@code parameterTypes()} because the latter reports the parameters as
     * they were written in source: it omits the synthetic leading parameters
     * javac adds to enum constructors ({@code String}, {@code int}) and to
     * inner-class constructors (the enclosing instance), and it keeps type
     * variables unerased. Both would produce a descriptor that never matches
     * the one the transformer is handed.
     */
    private static String methodDescriptor(MethodInfo method) {
        StringBuilder sb = new StringBuilder("(");
        for (Type parameter : method.descriptorParameterTypes()) {
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
            case TYPE_VARIABLE:
                // erasure: the first bound, or Object for an unbounded variable
                List<Type> bounds = type.asTypeVariable().bounds();
                return bounds.isEmpty() ? OBJECT_DESCRIPTOR : typeDescriptor(bounds.get(0));
            case UNRESOLVED_TYPE_VARIABLE:
            case TYPE_VARIABLE_REFERENCE:
                return OBJECT_DESCRIPTOR;
            default:
                throw new IllegalStateException("Unsupported type in a shim signature: " + type);
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
        /** Which @Shim classes contributed to this plan, for diagnostics. */
        final Set<String> shimClasses = new LinkedHashSet<>();
        boolean widenAccess;

        boolean isEmpty() {
            return ops.isEmpty() && annotationPatches.isEmpty() && definalize.isEmpty() && !widenAccess;
        }
    }

    private record MethodSelector(String descriptor, boolean paramsOnly) {
    }
}
