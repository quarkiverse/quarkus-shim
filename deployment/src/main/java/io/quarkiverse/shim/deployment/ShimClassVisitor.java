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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;

import io.quarkiverse.shim.AnnotationConflict;

/**
 * Applies the collected {@link ShimOp}s to one target class.
 */
final class ShimClassVisitor extends ClassVisitor {

    private static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String SHIM_CALL_DESC = "Lio/quarkiverse/shim/ShimCall;";
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
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return classAnnotationOverlay.visitExisting(descriptor, visible, super::visitAnnotation);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        if (definalize.contains(name)) {
            matchedDefinalizeFields.add(name);
        }
        boolean stripFinal = definalize.contains(name) || widenAccess;
        if (stripFinal && (access & Opcodes.ACC_FINAL) != 0) {
            if ((access & Opcodes.ACC_STATIC) != 0 && value != null) {
                if (definalize.contains(name)) {
                    throw new IllegalStateException("Cannot definalize " + internalName.replace('/', '.') + "." + name
                            + ": it is a compile-time constant, javac inlined its value into every reader"
                            + " at compile time, so rewriting the field would not affect them");
                }
                // widenAccess is coarse: silently leave constants final
            } else {
                access &= ~Opcodes.ACC_FINAL;
            }
        }
        if (widenAccess) {
            access = widen(access);
        }
        FieldVisitor field = super.visitField(access, name, descriptor, signature, value);
        return annotateField(field, name);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        List<ShimAnnotationPatch> annotationPatches = annotationPatchesForMethod(name, descriptor);
        List<ShimOp> matching = new ArrayList<>();
        for (ShimOp op : ops) {
            if (op.matches(name, descriptor)) {
                matching.add(op);
                matchedOps.add(op);
            }
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
            }
        }

        if (around != null) {
            if (replace != null || !beforeOps.isEmpty() || !afterOps.isEmpty()) {
                throw new IllegalStateException("Method " + internalName.replace('/', '.') + "#" + name
                        + " has @ShimAround combined with another hook; @ShimAround must be alone");
            }
            if ("<init>".equals(name) || "<clinit>".equals(name)) {
                throw new IllegalStateException("@ShimAround cannot target " + name + " on "
                        + internalName.replace('/', '.'));
            }
            boolean hookSelf = validateAround(around, isStatic, descriptor);
            return annotateMethod(
                    captureAround(access, name, descriptor, signature, exceptions, isStatic, around, hookSelf),
                    annotationPatches, methodRef(name, descriptor));
        }

        if (replace != null) {
            if (!beforeOps.isEmpty() || !afterOps.isEmpty()) {
                throw new IllegalStateException("Method " + internalName.replace('/', '.') + "#" + name
                        + " has a @ShimReplace combined with @ShimBefore/@ShimAfter; @ShimReplace must be alone");
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
        if ("<init>".equals(name)) {
            for (AdviceBinding b : before) {
                if (b.self) {
                    throw new IllegalStateException("Shim hook " + b.op.hookRef()
                            + " cannot receive 'self' at constructor entry: 'this' is not initialized yet");
                }
            }
        }
        before.sort((a, b) -> Integer.compare(a.op.priority, b.op.priority));
        after.sort((a, b) -> Integer.compare(a.op.priority, b.op.priority));
        int emitted = emittedAccess(access, name);
        MethodVisitor mv = super.visitMethod(emitted, name, descriptor, signature, exceptions);
        return annotateMethod(new ShimAdviceMethodVisitor(emitted, descriptor, mv, before, after), annotationPatches,
                methodRef(name, descriptor));
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
        classAnnotationOverlay.emit(super::visitAnnotation);
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
            public void visitEnd() {
                overlay.emit(super::visitAnnotation);
                super.visitEnd();
            }
        };
    }

    private List<ShimAnnotationPatch> annotationPatchesForMethod(String methodName, String methodDescriptor) {
        List<ShimAnnotationPatch> patches = new ArrayList<>();
        for (ShimAnnotationPatch patch : annotationPatches) {
            if (patch.matchesMethod(methodName, methodDescriptor)) {
                matchedAnnotationPatches.add(patch);
                patches.add(patch);
            }
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
            public void visitEnd() {
                overlay.emit(super::visitAnnotation);
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

        AnnotationOverlay(List<ShimAnnotationPatch> patches, String targetRef) {
            this.targetRef = targetRef;
            for (ShimAnnotationPatch patch : patches) {
                for (ShimAnnotation annotation : patch.annotations) {
                    PlannedAnnotation incoming = new PlannedAnnotation(annotation, patch.onConflict, patch.sourceRef);
                    PlannedAnnotation previous = pending.get(annotation.descriptor);
                    if (previous == null) {
                        pending.put(annotation.descriptor, incoming);
                    } else {
                        switch (patch.onConflict) {
                            case REPLACE -> pending.put(annotation.descriptor, incoming);
                            case KEEP -> {
                                // The annotation from the earlier shim remains pending.
                            }
                            case FAIL -> throw new IllegalStateException("@ShimAnnotate template " + patch.sourceRef
                                    + " cannot attach " + annotationName(annotation.descriptor) + " to " + targetRef
                                    + ": another shim template already attaches it (" + previous.sourceRef + ")");
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

        void emit(AnnotationFactory factory) {
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
                    return new AdviceBinding(op, self, remaining, false);
                }
            } else {
                boolean[] retOptions = targetReturn.getSort() == Type.VOID
                        ? new boolean[] { false }
                        : new boolean[] { true, false };
                for (boolean ret : retOptions) {
                    int remaining = hookParams.length - idx;
                    if (ret) {
                        if (remaining == 1 && isReturnedType(hookParams[idx], targetReturn)) {
                            return new AdviceBinding(op, self, 0, true);
                        }
                    } else if (remaining == 0) {
                        return new AdviceBinding(op, self, 0, false);
                    }
                }
            }
        }
        throw new IllegalStateException("Cannot bind " + kind.name().toLowerCase() + " hook " + op.hookRef()
                + " " + op.shimMethodDescriptor + " to " + internalName.replace('/', '.') + "#" + methodName
                + " " + targetDescriptor + ". Expected parameters: [self?] "
                + (kind == ShimOp.Kind.BEFORE ? "+ a prefix of the target arguments"
                        : "+ an optional trailing returned value")
                + " (self typed as the target class or Object).");
    }

    /** Validates an @ShimAround hook and returns whether it declares a 'self' parameter. */
    private boolean validateAround(ShimOp op, boolean isStatic, String targetDescriptor) {
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
                    + targetReturn.getClassName() + " to match the target method");
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
                        + " on the instance method must declare 'self' (the target class or Object) after the"
                        + " ShimCall parameter");
            }
        }
        if (hookParams.length - idx != targetParams.length || !prefixMatches(hookParams, idx, targetParams,
                targetParams.length)) {
            throw new IllegalStateException("@ShimAround hook " + op.hookRef()
                    + " must declare the target method's parameters after ShimCall" + (self ? " and self" : ""));
        }
        return self;
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

    private void emitBoxBridge(AroundPlan plan) {
        Type[] args = Type.getArgumentTypes(plan.descriptor);
        Type ret = Type.getReturnType(plan.descriptor);
        String boxDescriptor = "(" + (plan.isStatic ? "" : selfDesc()) + argsDescriptor(args) + ")" + OBJECT_DESC;
        MethodVisitor mv = super.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                plan.box, boxDescriptor, null, plan.exceptions);
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
        mv.visitMethodInsn(plan.isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                internalName, plan.renamed, plan.descriptor, isInterface);
        boxReturn(mv, ret);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private MethodNode createAroundWrapper(AroundPlan plan) {
        Type[] args = Type.getArgumentTypes(plan.descriptor);
        Type ret = Type.getReturnType(plan.descriptor);
        String captured = (plan.isStatic ? "" : selfDesc()) + argsDescriptor(args);
        String boxDescriptor = "(" + captured + ")" + OBJECT_DESC;
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
        mv.visitInvokeDynamicInsn("proceed", "(" + captured + ")" + SHIM_CALL_DESC, METAFACTORY,
                Type.getMethodType("()" + OBJECT_DESC), impl, Type.getMethodType("()" + OBJECT_DESC));
        if (plan.hookSelf) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        slot = plan.isStatic ? 0 : 1;
        for (Type arg : args) {
            mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
            slot += arg.getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, plan.op.shimOwnerInternalName, plan.op.shimMethodName,
                plan.op.shimMethodDescriptor, false);
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
        if (widenAccess && !"<init>".equals(name) && !"<clinit>".equals(name)) {
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
                op.shimMethodDescriptor, false);
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
