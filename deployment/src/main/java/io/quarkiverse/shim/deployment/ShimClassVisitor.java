package io.quarkiverse.shim.deployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import io.quarkiverse.shim.AnnotationConflict;

/**
 * Applies the collected {@link ShimOp}s to one target class.
 */
final class ShimClassVisitor extends ClassVisitor {

    private static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String SHIM_CALL_DESC = "Lio/quarkiverse/shim/ShimCall;";
    private static final int JAVA_7_MAJOR = Opcodes.V1_7;
    private static final String OBJECT_ARRAY_DESC = "[Ljava/lang/Object;";
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String SHIM_ARGUMENTS = "io/quarkiverse/shim/ShimArguments";
    /** The single abstract method of ShimCall, which the generated call site implements. */
    private static final String SHIM_CALL_SAM = "invokeWith";
    private static final String SHIM_CALL_SAM_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";
    /** Bridge and synthetic methods are compiler artifacts, never a user's intended target. */
    private static final int COMPILER_GENERATED = Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;
    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;",
            false);

    private final List<ShimOp> ops;
    private final List<ShimAnnotationPatch> annotationPatches;
    private final Set<String> definalize;
    private final boolean widenAccess;
    private final Runnable onEnd;
    private final List<AroundPlan> aroundPlans = new ArrayList<>();
    private final List<ReplacementPlan> replacementPlans = new ArrayList<>();
    private final List<GuardPlan> guardPlans = new ArrayList<>();
    private final Set<ShimOp> matchedOps = new LinkedHashSet<>();
    private final Set<ShimAnnotationPatch> matchedAnnotationPatches = new LinkedHashSet<>();
    private final Set<String> matchedDefinalizeFields = new LinkedHashSet<>();
    private AnnotationOverlay classAnnotationOverlay;
    private String internalName;
    private boolean isInterface;

    ShimClassVisitor(ClassVisitor delegate, List<ShimOp> ops, Set<String> definalize, boolean widenAccess,
            Runnable onEnd) {
        this(delegate, ops, List.of(), definalize, widenAccess, onEnd);
    }

    ShimClassVisitor(ClassVisitor delegate, List<ShimOp> ops, List<ShimAnnotationPatch> annotationPatches,
            Set<String> definalize, boolean widenAccess, Runnable onEnd) {
        super(Opcodes.ASM9, delegate);
        this.ops = ops;
        this.annotationPatches = annotationPatches;
        this.definalize = definalize;
        this.widenAccess = widenAccess;
        this.onEnd = onEnd;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
        internalName = name;
        isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        classAnnotationOverlay = overlayForClass(name.replace('/', '.'));
        super.visit(emittedVersion(version), access, name, signature, superName, interfaces);
    }

    /**
     * {@code @ShimAround} weaves an {@code invokedynamic} plus the
     * {@code CONSTANT_MethodHandle}/{@code CONSTANT_MethodType} entries it
     * needs, and those are only legal from class file major version 51
     * (Java 7). A target still shipping older bytecode would otherwise be
     * transformed into a class the JVM refuses to load, with the build staying
     * green. Quarkus writes transformed classes with {@code COMPUTE_FRAMES},
     * so the {@code StackMapTable} that major 50+ requires is produced for us.
     */
    private int emittedVersion(int version) {
        int major = version & 0xFFFF;
        if (major >= JAVA_7_MAJOR) {
            return version;
        }
        boolean needsInvokeDynamic = ops.stream().anyMatch(op -> op.kind == ShimOp.Kind.AROUND);
        if (!needsInvokeDynamic) {
            return version;
        }
        // keep the preview bit (the high half) exactly as it was
        return (version & ~0xFFFF) | JAVA_7_MAJOR;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return classAnnotationOverlay.visitExisting(descriptor, visible, super::visitAnnotation);
    }

    @Override
    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        super.visitInnerClass(name, outerName, innerName, access);
    }

    @Override
    public void visitNestMember(String nestMember) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        super.visitNestMember(nestMember);
    }

    @Override
    public void visitPermittedSubclass(String permittedSubclass) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        super.visitPermittedSubclass(permittedSubclass);
    }

    @Override
    public org.objectweb.asm.RecordComponentVisitor visitRecordComponent(String name, String descriptor,
            String signature) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        return super.visitRecordComponent(name, descriptor, signature);
    }

    @Override
    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        super.visitAttribute(attribute);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        boolean requested = definalize.contains(name);
        if (requested) {
            matchedDefinalizeFields.add(name);
        }
        // JVMS 4.5: every interface field must be public static final. Clearing
        // ACC_FINAL there produces flags the class file parser rejects, so the
        // build would stay green and the class would fail to load.
        if (isInterface) {
            if (requested) {
                throw new IllegalStateException("Cannot definalize " + internalName.replace('/', '.') + "." + name
                        + ": it is declared on an interface, and the JVM requires every interface field to remain"
                        + " public static final");
            }
            return annotateField(super.visitField(access, name, descriptor, signature, value), name);
        }
        // a field carrying a ConstantValue is a compile-time constant: javac
        // inlined it into every reader, so un-finalizing it changes nothing for
        // them while making the class disagree with the code compiled against it
        boolean isConstant = value != null;
        if (requested && isConstant) {
            throw new IllegalStateException("Cannot definalize " + internalName.replace('/', '.') + "." + name
                    + ": it is a compile-time constant, javac inlined its value into every reader"
                    + " at compile time, so rewriting the field would not affect them");
        }
        if ((requested || widenAccess) && !isConstant) {
            access &= ~Opcodes.ACC_FINAL;
        }
        if (widenAccess) {
            // widen() also clears ACC_FINAL, so constants must keep it explicitly
            access = isConstant ? widen(access) | Opcodes.ACC_FINAL : widen(access);
        }
        FieldVisitor field = super.visitField(access, name, descriptor, signature, value);
        return annotateField(field, name);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        boolean compilerGenerated = (access & COMPILER_GENERATED) != 0;
        List<ShimAnnotationPatch> annotationPatches = annotationPatchesForMethod(name, descriptor, compilerGenerated);
        List<ShimOp> matching = new ArrayList<>();
        for (ShimOp op : ops) {
            if (!op.matches(name, descriptor)) {
                continue;
            }
            // A bridge delegates to the real method, so weaving both fires the
            // hook twice - and only for callers that happen to go through the
            // bridge. Count it as matched so the "hook targets a method that
            // does not exist" check still passes, but leave its body alone
            // unless the shim pinned this exact descriptor.
            matchedOps.add(op);
            if (compilerGenerated && !op.pinsExactDescriptor()) {
                continue;
            }
            matching.add(op);
        }
        if (matching.isEmpty()) {
            return annotateMethod(
                    super.visitMethod(emittedAccess(access, name), name, descriptor, signature, exceptions),
                    annotationPatches, methodRef(name, descriptor));
        }
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IllegalStateException(
                    "Cannot shim abstract or native method " + internalName.replace('/', '.') + "#" + name);
        }
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

        ShimOp replace = null;
        ShimOp around = null;
        List<ShimOp> beforeOps = new ArrayList<>();
        List<ShimOp> afterOps = new ArrayList<>();
        List<ShimOp> catchOps = new ArrayList<>();
        List<ShimOp> finallyOps = new ArrayList<>();
        for (ShimOp op : matching) {
            switch (op.kind) {
                case REPLACE -> {
                    if (replace != null) {
                        throw new IllegalStateException("Method " + internalName.replace('/', '.') + "#" + name
                                + " has multiple @ShimReplace hooks: " + replace.hookRef() + " and " + op.hookRef());
                    }
                    replace = op;
                }
                case AROUND -> {
                    if (around != null) {
                        throw new IllegalStateException("Method " + internalName.replace('/', '.') + "#" + name
                                + " has multiple @ShimAround hooks: " + around.hookRef() + " and " + op.hookRef());
                    }
                    around = op;
                }
                case BEFORE -> beforeOps.add(op);
                case AFTER -> afterOps.add(op);
                case CATCH -> catchOps.add(op);
                case FINALLY -> finallyOps.add(op);
            }
        }

        boolean guarded = !catchOps.isEmpty() || !finallyOps.isEmpty();

        if (around != null) {
            if (replace != null || !beforeOps.isEmpty() || !afterOps.isEmpty() || guarded) {
                throw new IllegalStateException("Method " + internalName.replace('/', '.') + "#" + name
                        + " has @ShimAround combined with another hook; @ShimAround must be alone");
            }
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                throw new IllegalStateException("@ShimAround cannot target " + name + " on "
                        + internalName.replace('/', '.'));
            }
            boolean hookSelf = validateAround(around, isStatic, descriptor, name);
            return annotateMethod(
                    captureAround(access, name, descriptor, signature, exceptions, isStatic, around, hookSelf),
                    annotationPatches, methodRef(name, descriptor));
        }

        if (replace != null) {
            if (!beforeOps.isEmpty() || !afterOps.isEmpty() || guarded) {
                throw new IllegalStateException("Method " + internalName.replace('/', '.') + "#" + name
                        + " has a @ShimReplace combined with another hook; @ShimReplace must be alone");
            }
            if ("<init>".equals(name)) {
                throw new IllegalStateException("Cannot replace constructor of " + internalName.replace('/', '.')
                        + ": constructors must call super()/this() and cannot be delegated");
            }
            MethodNode original = new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions);
            replacementPlans.add(new ReplacementPlan(original, isStatic, replace));
            return annotateMethod(original, annotationPatches, methodRef(name, descriptor));
        }

        List<AdviceBinding> before = new ArrayList<>();
        for (ShimOp op : beforeOps) {
            before.add(resolveAdvice(op, ShimOp.Kind.BEFORE, isStatic, descriptor, name));
        }
        List<AdviceBinding> after = new ArrayList<>();
        for (ShimOp op : afterOps) {
            after.add(resolveAdvice(op, ShimOp.Kind.AFTER, isStatic, descriptor, name));
        }
        // @ShimFinally also runs on the normal path, which is what an after-hook
        // does minus the returned value
        for (ShimOp op : finallyOps) {
            after.add(resolveGuard(op, isStatic, name, false));
        }
        List<AdviceBinding> guards = new ArrayList<>();
        for (ShimOp op : catchOps) {
            guards.add(resolveGuard(op, isStatic, name, true));
        }
        for (ShimOp op : finallyOps) {
            guards.add(resolveGuard(op, isStatic, name, false));
        }
        if ("<init>".equals(name)) {
            for (AdviceBinding b : before) {
                if (b.self) {
                    throw new IllegalStateException("Shim hook " + b.op.hookRef()
                            + " cannot receive 'self' at constructor entry: 'this' is not initialized yet");
                }
            }
            if (guarded) {
                throw new IllegalStateException("@ShimCatch/@ShimFinally cannot target the constructor of "
                        + internalName.replace('/', '.')
                        + ": a handler covering the constructor body could observe 'this' before super() has run,"
                        + " which the JVM verifier rejects");
            }
        }
        before.sort((a, b) -> Integer.compare(a.op.priority, b.op.priority));
        after.sort((a, b) -> Integer.compare(a.op.priority, b.op.priority));
        guards.sort((a, b) -> Integer.compare(a.op.priority, b.op.priority));
        int emitted = emittedAccess(access, name);
        if (guarded) {
            // Capture into a MethodNode so the handler can be appended to the
            // exception table *after* the target's own entries. The table is
            // searched in order, so an earlier entry would steal exceptions the
            // method already handles itself.
            MethodNode captured = new MethodNode(Opcodes.ASM9, emitted, name, descriptor, signature, exceptions);
            guardPlans.add(new GuardPlan(captured, guards));
            return annotateMethod(new ShimAdviceMethodVisitor(emitted, descriptor, captured, before, after),
                    annotationPatches, methodRef(name, descriptor));
        }
        MethodVisitor mv = super.visitMethod(emitted, name, descriptor, signature, exceptions);
        return annotateMethod(new ShimAdviceMethodVisitor(emitted, descriptor, mv, before, after), annotationPatches,
                methodRef(name, descriptor));
    }

    /**
     * Binds a {@code @ShimCatch}/{@code @ShimFinally} hook: an optional
     * {@code self}, then for catch an optional trailing exception parameter.
     */
    private AdviceBinding resolveGuard(ShimOp op, boolean isStatic, String methodName, boolean canTakeThrowable) {
        Type[] hookParams = Type.getArgumentTypes(op.shimMethodDescriptor);
        String selfDesc = "L" + internalName + ";";
        String label = op.kind == ShimOp.Kind.CATCH ? "@ShimCatch" : "@ShimFinally";

        int idx = 0;
        boolean self = false;
        if (!isStatic && hookParams.length > 0 && isSelfType(hookParams[0], selfDesc)) {
            self = true;
            idx = 1;
        }
        int remaining = hookParams.length - idx;
        if (remaining == 0) {
            return new AdviceBinding(op, self, 0, false, false);
        }
        if (canTakeThrowable && remaining == 1 && hookParams[idx].getSort() == Type.OBJECT) {
            return new AdviceBinding(op, self, 0, false, true);
        }
        throw new IllegalStateException(label + " hook " + op.hookRef() + " " + op.shimMethodDescriptor
                + " does not fit " + internalName.replace('/', '.') + "#" + methodName
                + ". Expected parameters: [self?]"
                + (canTakeThrowable ? " + an optional trailing exception parameter" : "")
                + (isStatic ? " (the target is static, so there is no 'self')" : ""));
    }

    @Override
    public void visitEnd() {
        Set<ShimOp> missingOps = new LinkedHashSet<>(ops);
        missingOps.removeAll(matchedOps);
        if (!missingOps.isEmpty()) {
            ShimOp missing = missingOps.iterator().next();
            throw new IllegalStateException("Shim hook " + missing.hookRef() + " targets method '"
                    + missing.targetMethodName + "' which does not exist on " + internalName.replace('/', '.')
                    + " (with the requested overload, if any)");
        }
        Set<String> missingFields = new LinkedHashSet<>(definalize);
        missingFields.removeAll(matchedDefinalizeFields);
        if (!missingFields.isEmpty()) {
            throw new IllegalStateException("@Shim definalize lists field '" + missingFields.iterator().next()
                    + "' which does not exist on " + internalName.replace('/', '.'));
        }
        classAnnotationOverlay.emitOnce(super::visitAnnotation);
        Set<ShimAnnotationPatch> missingAnnotationPatches = new LinkedHashSet<>(annotationPatches);
        missingAnnotationPatches.removeAll(matchedAnnotationPatches);
        if (!missingAnnotationPatches.isEmpty()) {
            ShimAnnotationPatch missing = missingAnnotationPatches.iterator().next();
            throw new IllegalStateException("@ShimAnnotate template " + missing.sourceRef + " targets "
                    + missing.kind.name().toLowerCase() + " '" + missing.targetName + "' which does not exist on "
                    + internalName.replace('/', '.')
                    + (missing.kind == ShimAnnotationPatch.Kind.METHOD
                            ? " (with the requested overload, if any)"
                            : ""));
        }
        for (GuardPlan plan : guardPlans) {
            emitGuarded(plan);
        }
        for (ReplacementPlan plan : replacementPlans) {
            emitReplacement(plan);
        }
        for (AroundPlan plan : aroundPlans) {
            MethodNode wrapper = createAroundWrapper(plan);
            emitRenamedOriginal(plan);
            emitBoxBridge(plan);
            accept(wrapper);
        }
        super.visitEnd();
        if (onEnd != null) {
            onEnd.run();
        }
    }

    private FieldVisitor annotateField(FieldVisitor visitor, String fieldName) {
        List<ShimAnnotationPatch> patches = new ArrayList<>();
        for (ShimAnnotationPatch patch : annotationPatches) {
            if (patch.matchesField(fieldName)) {
                matchedAnnotationPatches.add(patch);
                patches.add(patch);
            }
        }
        if (patches.isEmpty()) {
            return visitor;
        }
        AnnotationOverlay overlay = new AnnotationOverlay(patches,
                internalName.replace('/', '.') + "#" + fieldName);
        return new FieldVisitor(Opcodes.ASM9, visitor) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return overlay.visitExisting(descriptor, visible, super::visitAnnotation);
            }

            @Override
            public void visitAttribute(org.objectweb.asm.Attribute attribute) {
                overlay.emitOnce(super::visitAnnotation);
                super.visitAttribute(attribute);
            }

            @Override
            public void visitEnd() {
                overlay.emitOnce(super::visitAnnotation);
                super.visitEnd();
            }
        };
    }

    private List<ShimAnnotationPatch> annotationPatchesForMethod(String methodName, String methodDescriptor,
            boolean compilerGenerated) {
        List<ShimAnnotationPatch> patches = new ArrayList<>();
        for (ShimAnnotationPatch patch : annotationPatches) {
            if (!patch.matchesMethod(methodName, methodDescriptor)) {
                continue;
            }
            matchedAnnotationPatches.add(patch);
            // annotating the bridge as well makes the annotation appear twice to
            // anything enumerating getDeclaredMethods() without an isBridge() check
            if (compilerGenerated && !patch.pinsExactDescriptor()) {
                continue;
            }
            patches.add(patch);
        }
        return patches;
    }

    private static MethodVisitor annotateMethod(MethodVisitor visitor, List<ShimAnnotationPatch> patches,
            String targetRef) {
        if (patches.isEmpty()) {
            return visitor;
        }
        AnnotationOverlay overlay = new AnnotationOverlay(patches, targetRef);
        return new MethodVisitor(Opcodes.ASM9, visitor) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return overlay.visitExisting(descriptor, visible, super::visitAnnotation);
            }

            @Override
            public void visitAttribute(org.objectweb.asm.Attribute attribute) {
                overlay.emitOnce(super::visitAnnotation);
                super.visitAttribute(attribute);
            }

            @Override
            public void visitCode() {
                overlay.emitOnce(super::visitAnnotation);
                super.visitCode();
            }

            @Override
            public void visitEnd() {
                overlay.emitOnce(super::visitAnnotation);
                super.visitEnd();
            }
        };
    }

    private AnnotationOverlay overlayForClass(String targetRef) {
        List<ShimAnnotationPatch> patches = new ArrayList<>();
        for (ShimAnnotationPatch patch : annotationPatches) {
            if (patch.kind == ShimAnnotationPatch.Kind.CLASS) {
                matchedAnnotationPatches.add(patch);
                patches.add(patch);
            }
        }
        return new AnnotationOverlay(patches, targetRef);
    }

    private String methodRef(String name, String descriptor) {
        return internalName.replace('/', '.') + "#" + name + descriptor;
    }

    private static final class AnnotationOverlay {
        private final Map<String, PlannedAnnotation> pending = new LinkedHashMap<>();
        private final String targetRef;
        private boolean emitted;

        AnnotationOverlay(List<ShimAnnotationPatch> patches, String targetRef) {
            this.targetRef = targetRef;
            for (ShimAnnotationPatch patch : patches) {
                for (ShimAnnotation annotation : patch.annotations) {
                    PlannedAnnotation incoming = new PlannedAnnotation(annotation, patch.onConflict, patch.sourceRef);
                    PlannedAnnotation previous = pending.get(annotation.descriptor);
                    if (previous == null) {
                        pending.put(annotation.descriptor, incoming);
                    } else if (patch.onConflict == AnnotationConflict.FAIL
                            || previous.onConflict() == AnnotationConflict.FAIL) {
                        // either side asking to fail must win, otherwise whether
                        // FAIL is honoured would depend on shim discovery order
                        throw new IllegalStateException("@ShimAnnotate template " + patch.sourceRef
                                + " cannot attach " + annotationName(annotation.descriptor) + " to " + targetRef
                                + ": another shim template already attaches it (" + previous.sourceRef + ")");
                    } else {
                        switch (patch.onConflict) {
                            case REPLACE -> pending.put(annotation.descriptor, incoming);
                            case KEEP -> {
                                // The annotation from the earlier shim remains pending.
                            }
                            case FAIL -> throw new IllegalStateException("unreachable: handled above");
                        }
                    }
                }
            }
        }

        AnnotationVisitor visitExisting(String descriptor, boolean visible, AnnotationFactory delegate) {
            PlannedAnnotation planned = pending.get(descriptor);
            if (planned == null) {
                return delegate.create(descriptor, visible);
            }
            return switch (planned.onConflict) {
                case REPLACE -> null;
                case KEEP -> {
                    pending.remove(descriptor);
                    yield delegate.create(descriptor, visible);
                }
                case FAIL -> throw new IllegalStateException("@ShimAnnotate template " + planned.sourceRef
                        + " cannot attach " + annotationName(descriptor) + " to " + targetRef
                        + ": the target already declares that annotation");
            };
        }

        /**
         * Emits the annotations that were not consumed by an existing
         * declaration. Called on the first event that ends the annotation
         * phase, because ASM's visit order requires annotations before
         * attributes, members and code — emitting them from visitEnd would
         * produce a valid class file but an invalid visitor call sequence,
         * which CheckClassAdapter rightly rejects.
         */
        void emitOnce(AnnotationFactory factory) {
            if (emitted) {
                return;
            }
            emitted = true;
            for (PlannedAnnotation planned : pending.values()) {
                ShimAnnotation annotation = planned.annotation;
                AnnotationVisitor visitor = factory.create(annotation.descriptor, annotation.visible);
                if (visitor != null) {
                    annotation.accept(visitor);
                }
            }
        }

        private static String annotationName(String descriptor) {
            return "@" + Type.getType(descriptor).getClassName();
        }
    }

    private record PlannedAnnotation(ShimAnnotation annotation, AnnotationConflict onConflict, String sourceRef) {
    }

    @FunctionalInterface
    private interface AnnotationFactory {
        AnnotationVisitor create(String descriptor, boolean visible);
    }

    // --- binding resolution --------------------------------------------------

    private AdviceBinding resolveAdvice(ShimOp op, ShimOp.Kind kind, boolean isStatic, String targetDescriptor,
            String methodName) {
        Type[] hookParams = Type.getArgumentTypes(op.shimMethodDescriptor);
        Type[] targetParams = Type.getArgumentTypes(targetDescriptor);
        Type targetReturn = Type.getReturnType(targetDescriptor);
        String selfDesc = "L" + internalName + ";";

        if (isStatic && hookParams.length > 0 && hookParams[0].getDescriptor().equals(selfDesc)
                && !"<clinit>".equals(methodName)) {
            // only flag when it cannot instead be interpreted as a matching first argument
            if (targetParams.length == 0 || !targetParams[0].getDescriptor().equals(hookParams[0].getDescriptor())) {
                throw new IllegalStateException("Shim hook " + op.hookRef()
                        + " declares a 'self' parameter but the target method "
                        + internalName.replace('/', '.') + "#" + methodName + " is static");
            }
        }
        boolean[] selfOptions = isStatic ? new boolean[] { false } : new boolean[] { true, false };
        List<AdviceBinding> candidates = new ArrayList<>();
        for (boolean self : selfOptions) {
            int idx = 0;
            if (self) {
                if (hookParams.length == 0 || !isSelfType(hookParams[0], selfDesc)) {
                    continue;
                }
                idx = 1;
            }
            if (kind == ShimOp.Kind.BEFORE) {
                int remaining = hookParams.length - idx;
                if (remaining <= targetParams.length && prefixMatches(hookParams, idx, targetParams, remaining)) {
                    candidates.add(new AdviceBinding(op, self, remaining, false));
                }
            } else {
                boolean[] retOptions = targetReturn.getSort() == Type.VOID
                        ? new boolean[] { false }
                        : new boolean[] { true, false };
                for (boolean ret : retOptions) {
                    int remaining = hookParams.length - idx;
                    if (ret) {
                        if (remaining == 1 && isReturnedType(hookParams[idx], targetReturn)) {
                            candidates.add(new AdviceBinding(op, self, 0, true));
                        }
                    } else if (remaining == 0) {
                        candidates.add(new AdviceBinding(op, self, 0, false));
                    }
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            // Almost always a lone Object parameter, which reads equally well as
            // 'self' and as the argument/returned value. Picking one silently
            // hands the hook the wrong object with nothing to notice it by.
            throw new IllegalStateException("Cannot bind " + kind.name().toLowerCase() + " hook " + op.hookRef()
                    + " " + op.shimMethodDescriptor + " to " + internalName.replace('/', '.') + "#" + methodName
                    + " " + targetDescriptor + ": its parameters are ambiguous, they match "
                    + describeCandidates(candidates, kind)
                    + ". Type the parameter as " + internalName.replace('/', '.') + " to mean 'self', or as "
                    + (kind == ShimOp.Kind.BEFORE
                            ? "the argument's own type to mean the argument"
                            : "the target's return type (" + targetReturn.getClassName() + ") to mean the returned"
                                    + " value; declare both to receive both")
                    + ".");
        }
        throw new IllegalStateException("Cannot bind " + kind.name().toLowerCase() + " hook " + op.hookRef()
                + " " + op.shimMethodDescriptor + " to " + internalName.replace('/', '.') + "#" + methodName
                + " " + targetDescriptor + ". Expected parameters: [self?] "
                + (kind == ShimOp.Kind.BEFORE ? "+ a prefix of the target arguments"
                        : "+ an optional trailing returned value")
                + " (self typed as the target class or Object).");
    }

    private static String describeCandidates(List<AdviceBinding> candidates, ShimOp.Kind kind) {
        List<String> readings = new ArrayList<>();
        for (AdviceBinding candidate : candidates) {
            List<String> parts = new ArrayList<>();
            if (candidate.self) {
                parts.add("self");
            }
            if (kind == ShimOp.Kind.BEFORE && candidate.argCount > 0) {
                parts.add(candidate.argCount == 1 ? "1 argument" : candidate.argCount + " arguments");
            }
            if (candidate.returned) {
                parts.add("the returned value");
            }
            readings.add(parts.isEmpty() ? "nothing" : String.join(" + ", parts));
        }
        return "[" + String.join("] and [", readings) + "]";
    }

    /** Validates an @ShimAround hook and returns whether it declares a 'self' parameter. */
    private boolean validateAround(ShimOp op, boolean isStatic, String targetDescriptor, String methodName) {
        Type[] hookParams = Type.getArgumentTypes(op.shimMethodDescriptor);
        Type[] targetParams = Type.getArgumentTypes(targetDescriptor);
        Type targetReturn = Type.getReturnType(targetDescriptor);
        Type hookReturn = Type.getReturnType(op.shimMethodDescriptor);
        String selfDesc = "L" + internalName + ";";

        if (hookParams.length == 0 || !hookParams[0].getDescriptor().equals(SHIM_CALL_DESC)) {
            throw new IllegalStateException("@ShimAround hook " + op.hookRef()
                    + " must take a " + SHIM_CALL_DESC + " (ShimCall) as its first parameter");
        }
        if (!hookReturn.getDescriptor().equals(targetReturn.getDescriptor())) {
            throw new IllegalStateException("@ShimAround hook " + op.hookRef() + " must return "
                    + targetReturn.getClassName() + " to match "
                    + overloadRef(methodName, targetDescriptor) + overloadHint(op));
        }
        int idx = 1;
        boolean self = false;
        if (!isStatic) {
            // self is required for instance targets
            if (hookParams.length > idx && isSelfType(hookParams[idx], selfDesc)
                    && hookParams.length - idx - 1 == targetParams.length) {
                self = true;
                idx++;
            } else {
                throw new IllegalStateException("@ShimAround hook " + op.hookRef()
                        + " must declare 'self' (the target class or Object) after the ShimCall parameter:"
                        + " " + overloadRef(methodName, targetDescriptor) + " is an instance method"
                        + overloadHint(op));
            }
        }
        if (hookParams.length - idx != targetParams.length || !prefixMatches(hookParams, idx, targetParams,
                targetParams.length)) {
            throw new IllegalStateException("@ShimAround hook " + op.hookRef()
                    + " must declare the parameters of " + overloadRef(methodName, targetDescriptor)
                    + " after ShimCall" + (self ? " and self" : "") + overloadHint(op));
        }
        return self;
    }

    private String overloadRef(String methodName, String descriptor) {
        return internalName.replace('/', '.') + "#" + methodName + descriptor;
    }

    /**
     * An op with no descriptor matches every overload of the name, so a hook
     * written for one of them fails against the others. Point at the selector
     * rather than leaving the user to change a hook that was already correct.
     */
    private static String overloadHint(ShimOp op) {
        return op.pinsExactDescriptor() ? ""
                : ". This hook matches every overload named '" + op.targetMethodName
                        + "'; pin the one you meant with paramTypes() or descriptor()";
    }

    private static boolean isSelfType(Type type, String selfDesc) {
        return type.getDescriptor().equals(selfDesc) || type.getDescriptor().equals(OBJECT_DESC);
    }

    private static boolean isReturnedType(Type type, Type targetReturn) {
        return type.getDescriptor().equals(targetReturn.getDescriptor()) || type.getDescriptor().equals(OBJECT_DESC);
    }

    private static boolean prefixMatches(Type[] hookParams, int from, Type[] targetParams, int count) {
        for (int i = 0; i < count; i++) {
            if (!hookParams[from + i].getDescriptor().equals(targetParams[i].getDescriptor())) {
                return false;
            }
        }
        return true;
    }

    // --- @ShimAround ---------------------------------------------------------

    private MethodVisitor captureAround(int access, String name, String descriptor, String signature,
            String[] exceptions, boolean isStatic, ShimOp op, boolean hookSelf) {
        String suffix = Integer.toHexString(descriptor.hashCode());
        String renamed = name + "$shim$orig$" + suffix;
        String box = name + "$shim$box$" + suffix;
        MethodNode original = new MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions);
        aroundPlans.add(new AroundPlan(name, descriptor, signature, exceptions, emittedAccess(access, name), isStatic,
                op, renamed, box, hookSelf, original));
        return original;
    }

    private void emitRenamedOriginal(AroundPlan plan) {
        MethodNode original = plan.original;
        original.name = plan.renamed;
        original.access = (original.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL))
                | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
        accept(original);
    }

    /**
     * The body behind {@code ShimCall}: it captures {@code self} and the
     * arguments the target was called with, and takes a trailing
     * {@code Object[]}. A null array means {@code proceed()} - run the original
     * with the captured arguments; a non-null array means
     * {@code proceed(args...)} - unbox that array into the original's
     * parameters instead.
     */
    private void emitBoxBridge(AroundPlan plan) {
        Type[] args = Type.getArgumentTypes(plan.descriptor);
        Type ret = Type.getReturnType(plan.descriptor);
        String boxDescriptor = "(" + (plan.isStatic ? "" : selfDesc()) + argsDescriptor(args)
                + OBJECT_ARRAY_DESC + ")" + OBJECT_DESC;
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                plan.box, boxDescriptor, null, plan.exceptions);
        mv.visitCode();

        int capturedSlots = plan.isStatic ? 0 : 1;
        for (Type arg : args) {
            capturedSlots += arg.getSize();
        }
        int replacementSlot = capturedSlots;

        Label useCaptured = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, replacementSlot);
        mv.visitJumpInsn(Opcodes.IFNULL, useCaptured);

        // proceed(args...): validate the count, then unbox each element
        mv.visitVarInsn(Opcodes.ALOAD, replacementSlot);
        mv.visitIntInsn(Opcodes.SIPUSH, args.length);
        mv.visitLdcInsn(internalName.replace('/', '.') + "#" + plan.name);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM_ARGUMENTS, "check",
                "([Ljava/lang/Object;ILjava/lang/String;)V", false);
        if (!plan.isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        for (int i = 0; i < args.length; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, replacementSlot);
            mv.visitIntInsn(Opcodes.SIPUSH, i);
            mv.visitInsn(Opcodes.AALOAD);
            unbox(mv, args[i]);
        }
        mv.visitMethodInsn(plan.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                internalName, plan.renamed, plan.descriptor, isInterface);
        boxReturn(mv, ret);
        mv.visitInsn(Opcodes.ARETURN);

        // proceed(): the arguments the target was called with
        mv.visitLabel(useCaptured);
        int slot = 0;
        if (!plan.isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
        }
        for (Type arg : args) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(plan.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                internalName, plan.renamed, plan.descriptor, isInterface);
        boxReturn(mv, ret);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Casts an element of the replacement-argument array to {@code target}, unboxing a primitive. */
    private static void unbox(MethodVisitor mv, Type target) {
        switch (target.getSort()) {
            case Type.BOOLEAN -> unboxPrimitive(mv, "java/lang/Boolean", "booleanValue", "()Z");
            case Type.BYTE -> unboxPrimitive(mv, "java/lang/Byte", "byteValue", "()B");
            case Type.CHAR -> unboxPrimitive(mv, "java/lang/Character", "charValue", "()C");
            case Type.SHORT -> unboxPrimitive(mv, "java/lang/Short", "shortValue", "()S");
            case Type.INT -> unboxPrimitive(mv, "java/lang/Integer", "intValue", "()I");
            case Type.LONG -> unboxPrimitive(mv, "java/lang/Long", "longValue", "()J");
            case Type.FLOAT -> unboxPrimitive(mv, "java/lang/Float", "floatValue", "()F");
            case Type.DOUBLE -> unboxPrimitive(mv, "java/lang/Double", "doubleValue", "()D");
            default -> mv.visitTypeInsn(Opcodes.CHECKCAST, target.getSort() == Type.ARRAY
                    ? target.getDescriptor()
                    : target.getInternalName());
        }
    }

    private static void unboxPrimitive(MethodVisitor mv, String wrapper, String accessor, String descriptor) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, accessor, descriptor, false);
    }

    private MethodNode createAroundWrapper(AroundPlan plan) {
        Type[] args = Type.getArgumentTypes(plan.descriptor);
        Type ret = Type.getReturnType(plan.descriptor);
        String captured = (plan.isStatic ? "" : selfDesc()) + argsDescriptor(args);
        String boxDescriptor = "(" + captured + OBJECT_ARRAY_DESC + ")" + OBJECT_DESC;
        MethodNode mv = new MethodNode(Opcodes.ASM9, plan.access, plan.name, plan.descriptor, plan.signature,
                plan.exceptions);
        moveDeclarationMetadata(plan.original, mv);
        mv.visitCode();
        int slot = 0;
        if (!plan.isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
        }
        for (Type arg : args) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        Handle impl = new Handle(Opcodes.H_INVOKESTATIC, internalName, plan.box, boxDescriptor, isInterface);
        mv.visitInvokeDynamicInsn(SHIM_CALL_SAM, "(" + captured + ")" + SHIM_CALL_DESC, METAFACTORY,
                Type.getMethodType(SHIM_CALL_SAM_DESC), impl, Type.getMethodType(SHIM_CALL_SAM_DESC));
        if (plan.hookSelf) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        slot = plan.isStatic ? 0 : 1;
        for (Type arg : args) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, plan.op.shimOwnerInternalName, plan.op.shimMethodName,
                plan.op.shimMethodDescriptor, plan.op.shimOwnerIsInterface);
        mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return mv;
    }

    private static void moveDeclarationMetadata(MethodNode source, MethodNode target) {
        target.parameters = source.parameters;
        source.parameters = null;
        target.annotationDefault = source.annotationDefault;
        source.annotationDefault = null;
        target.visibleAnnotations = source.visibleAnnotations;
        source.visibleAnnotations = null;
        target.invisibleAnnotations = source.invisibleAnnotations;
        source.invisibleAnnotations = null;
        target.visibleTypeAnnotations = source.visibleTypeAnnotations;
        source.visibleTypeAnnotations = null;
        target.invisibleTypeAnnotations = source.invisibleTypeAnnotations;
        source.invisibleTypeAnnotations = null;
        target.visibleAnnotableParameterCount = source.visibleAnnotableParameterCount;
        source.visibleAnnotableParameterCount = 0;
        target.visibleParameterAnnotations = source.visibleParameterAnnotations;
        source.visibleParameterAnnotations = null;
        target.invisibleAnnotableParameterCount = source.invisibleAnnotableParameterCount;
        source.invisibleAnnotableParameterCount = 0;
        target.invisibleParameterAnnotations = source.invisibleParameterAnnotations;
        source.invisibleParameterAnnotations = null;
        target.attrs = source.attrs;
        source.attrs = null;
    }

    private void emitReplacement(ReplacementPlan plan) {
        MethodNode method = plan.original;
        method.access = emittedAccess(method.access, method.name);
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        if (method.visibleLocalVariableAnnotations != null) {
            method.visibleLocalVariableAnnotations.clear();
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            method.invisibleLocalVariableAnnotations.clear();
        }
        method.maxStack = 0;
        method.maxLocals = 0;
        emitDelegation(method, plan.isStatic, method.name, method.desc, plan.op);
        accept(method);
    }

    private void accept(MethodNode method) {
        MethodVisitor output = super.visitMethod(method.access, method.name, method.desc, method.signature,
                method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
        method.accept(output);
    }

    private static void boxReturn(MethodVisitor mv, Type ret) {
        switch (ret.getSort()) {
            case Type.VOID -> mv.visitInsn(Opcodes.ACONST_NULL);
            case Type.BOOLEAN -> box(mv, "java/lang/Boolean", "(Z)Ljava/lang/Boolean;");
            case Type.BYTE -> box(mv, "java/lang/Byte", "(B)Ljava/lang/Byte;");
            case Type.CHAR -> box(mv, "java/lang/Character", "(C)Ljava/lang/Character;");
            case Type.SHORT -> box(mv, "java/lang/Short", "(S)Ljava/lang/Short;");
            case Type.INT -> box(mv, "java/lang/Integer", "(I)Ljava/lang/Integer;");
            case Type.LONG -> box(mv, "java/lang/Long", "(J)Ljava/lang/Long;");
            case Type.FLOAT -> box(mv, "java/lang/Float", "(F)Ljava/lang/Float;");
            case Type.DOUBLE -> box(mv, "java/lang/Double", "(D)Ljava/lang/Double;");
            default -> {
                /* reference type already Object-compatible */ }
        }
    }

    private static void box(MethodVisitor mv, String owner, String descriptor) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false);
    }

    private String selfDesc() {
        return "L" + internalName + ";";
    }

    private static String argsDescriptor(Type[] args) {
        StringBuilder sb = new StringBuilder();
        for (Type arg : args) {
            sb.append(arg.getDescriptor());
        }
        return sb.toString();
    }

    private int emittedAccess(int access, String name) {
        // <clinit> is only ever ACC_STATIC and is invoked by the JVM itself, so
        // widening it would be meaningless; constructors are widened like any
        // other member, so a private one becomes reflectively constructible.
        if (widenAccess && !"<clinit>".equals(name)) {
            return widen(access);
        }
        return access;
    }

    private static int widen(int access) {
        return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL)) | Opcodes.ACC_PUBLIC;
    }

    // --- @ShimReplace --------------------------------------------------------

    private void emitDelegation(MethodVisitor mv, boolean isStatic, String name, String descriptor, ShimOp op) {
        int closing = descriptor.lastIndexOf(')');
        String params = descriptor.substring(1, closing);
        String returnPart = descriptor.substring(closing + 1);

        String expectedExact = "(" + (isStatic ? "" : "L" + internalName + ";") + params + ")" + returnPart;
        String expectedObject = isStatic ? null : "(" + OBJECT_DESC + params + ")" + returnPart;
        if (!op.shimMethodDescriptor.equals(expectedExact)
                && (expectedObject == null || !op.shimMethodDescriptor.equals(expectedObject))) {
            throw new IllegalStateException("@ShimReplace hook " + op.hookRef() + " has descriptor "
                    + op.shimMethodDescriptor + " but replacing " + internalName.replace('/', '.') + "#" + name
                    + " requires " + expectedExact
                    + (isStatic ? "" : " (first parameter receives 'this'; java.lang.Object is also accepted)"));
        }

        mv.visitCode();
        int slot = 0;
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
        }
        for (Type arg : Type.getArgumentTypes(descriptor)) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, op.shimOwnerInternalName, op.shimMethodName,
                op.shimMethodDescriptor, op.shimOwnerIsInterface);
        Type returnType = Type.getReturnType(descriptor);
        mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(Math.max(1, slot + returnType.getSize()), Math.max(1, slot));
        mv.visitEnd();
    }

    /** Captures everything needed to weave an @ShimAround target in visitEnd(). */
    private static final class AroundPlan {
        final String name;
        final String descriptor;
        final String signature;
        final String[] exceptions;
        final int access;
        final boolean isStatic;
        final ShimOp op;
        final String renamed;
        final String box;
        final boolean hookSelf;
        final MethodNode original;

        AroundPlan(String name, String descriptor, String signature, String[] exceptions, int access,
                boolean isStatic, ShimOp op, String renamed, String box, boolean hookSelf, MethodNode original) {
            this.name = name;
            this.descriptor = descriptor;
            this.signature = signature;
            this.exceptions = exceptions;
            this.access = access;
            this.isStatic = isStatic;
            this.op = op;
            this.renamed = renamed;
            this.box = box;
            this.hookSelf = hookSelf;
            this.original = original;
        }
    }

    /**
     * Wraps the captured method body in a catch-all handler that runs the
     * {@code @ShimCatch}/{@code @ShimFinally} hooks and rethrows.
     */
    private void emitGuarded(GuardPlan plan) {
        MethodNode method = plan.method;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        method.instructions.insert(start);
        method.instructions.add(end);
        method.instructions.add(handler);

        int throwableSlot = Math.max(method.maxLocals, 1);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, throwableSlot));
        for (AdviceBinding binding : plan.guards) {
            String caught = binding.op.caughtExceptionInternalName;
            boolean narrowed = binding.op.kind == ShimOp.Kind.CATCH && !THROWABLE.equals(caught);
            LabelNode skip = new LabelNode();
            if (narrowed) {
                // the handler catches everything, so a hook that asked for one
                // exception type filters itself here
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableSlot));
                method.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, caught));
                method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            }
            if (binding.self) {
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            if (binding.throwable) {
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableSlot));
                // the local is typed Throwable; the hook may declare something narrower
                Type[] hookParams = Type.getArgumentTypes(binding.op.shimMethodDescriptor);
                String declared = hookParams[hookParams.length - 1].getInternalName();
                if (!THROWABLE.equals(declared) && !"java/lang/Object".equals(declared)) {
                    method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, declared));
                }
            }
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, binding.op.shimOwnerInternalName,
                    binding.op.shimMethodName, binding.op.shimMethodDescriptor, binding.op.shimOwnerIsInterface));
            if (narrowed) {
                method.instructions.add(skip);
            }
        }
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableSlot));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.maxLocals = throwableSlot + 1;

        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
        }
        // One catch-all entry, appended last so the target's own handlers are
        // still searched first. Per-hook exception types are filtered above,
        // which keeps the exception table simple and the ranges identical.
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        accept(method);
    }

    /** A method that needs an exception handler woven around its body. */
    private static final class GuardPlan {
        final MethodNode method;
        final List<AdviceBinding> guards;

        GuardPlan(MethodNode method, List<AdviceBinding> guards) {
            this.method = method;
            this.guards = guards;
        }
    }

    private static final class ReplacementPlan {
        final MethodNode original;
        final boolean isStatic;
        final ShimOp op;

        ReplacementPlan(MethodNode original, boolean isStatic, ShimOp op) {
            this.original = original;
            this.isStatic = isStatic;
            this.op = op;
        }
    }
}
