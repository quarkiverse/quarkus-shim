package io.quarkiverse.shim.deployment;

import java.util.List;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Weaves static hook calls at method entry ({@code @ShimBefore}) and before
 * every normal return ({@code @ShimAfter}).
 * <p>
 * Before-hooks may receive {@code self} and a prefix of the target arguments
 * (loaded from their parameter slots at entry). After-hooks may receive
 * {@code self} and the returned value (captured into a fresh local so it can be
 * passed as the trailing argument and then restored for the return).
 * The caller passes bindings pre-sorted by {@code @ShimPriority}.
 */
final class ShimAdviceMethodVisitor extends LocalVariablesSorter {

    private final List<AdviceBinding> before;
    private final List<AdviceBinding> after;
    private final int[] paramSlots;
    private final Type[] paramTypes;
    private final Type returnType;

    ShimAdviceMethodVisitor(int access, String descriptor, MethodVisitor delegate,
            List<AdviceBinding> before, List<AdviceBinding> after) {
        super(Opcodes.ASM9, access, descriptor, delegate);
        this.before = before;
        this.after = after;
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        this.paramTypes = Type.getArgumentTypes(descriptor);
        this.returnType = Type.getReturnType(descriptor);
        this.paramSlots = new int[paramTypes.length];
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < paramTypes.length; i++) {
            paramSlots[i] = slot;
            slot += paramTypes[i].getSize();
        }
    }

    @Override
    public void visitCode() {
        super.visitCode();
        for (AdviceBinding b : before) {
            if (b.self) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
            }
            for (int i = 0; i < b.argCount; i++) {
                super.visitVarInsn(paramTypes[i].getOpcode(Opcodes.ILOAD), paramSlots[i]);
            }
            invokeHook(b);
        }
    }

    @Override
    public void visitInsn(int opcode) {
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
            emitReturnHooks(opcode);
        }
        super.visitInsn(opcode);
    }

    private void emitReturnHooks(int opcode) {
        if (after.isEmpty()) {
            return;
        }
        boolean anyReturned = false;
        for (AdviceBinding b : after) {
            anyReturned |= b.returned;
        }
        if (anyReturned && opcode != Opcodes.RETURN) {
            int retLocal = newLocal(returnType);
            super.visitVarInsn(returnType.getOpcode(Opcodes.ISTORE), retLocal);
            for (AdviceBinding b : after) {
                if (b.self) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                }
                if (b.returned) {
                    super.visitVarInsn(returnType.getOpcode(Opcodes.ILOAD), retLocal);
                    boxReturnedValueIfNeeded(b);
                }
                invokeHook(b);
            }
            super.visitVarInsn(returnType.getOpcode(Opcodes.ILOAD), retLocal);
        } else {
            for (AdviceBinding b : after) {
                if (b.self) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                }
                invokeHook(b);
            }
        }
    }

    private void invokeHook(AdviceBinding b) {
        super.visitMethodInsn(Opcodes.INVOKESTATIC, b.op.shimOwnerInternalName, b.op.shimMethodName,
                b.op.shimMethodDescriptor, false);
    }

    private void boxReturnedValueIfNeeded(AdviceBinding binding) {
        Type[] hookParameters = Type.getArgumentTypes(binding.op.shimMethodDescriptor);
        Type hookReturnParameter = hookParameters[hookParameters.length - 1];
        if (hookReturnParameter.getSort() != Type.OBJECT || !"java/lang/Object".equals(hookReturnParameter.getInternalName())) {
            return;
        }
        switch (returnType.getSort()) {
            case Type.BOOLEAN -> box("java/lang/Boolean", "(Z)Ljava/lang/Boolean;");
            case Type.BYTE -> box("java/lang/Byte", "(B)Ljava/lang/Byte;");
            case Type.CHAR -> box("java/lang/Character", "(C)Ljava/lang/Character;");
            case Type.SHORT -> box("java/lang/Short", "(S)Ljava/lang/Short;");
            case Type.INT -> box("java/lang/Integer", "(I)Ljava/lang/Integer;");
            case Type.LONG -> box("java/lang/Long", "(J)Ljava/lang/Long;");
            case Type.FLOAT -> box("java/lang/Float", "(F)Ljava/lang/Float;");
            case Type.DOUBLE -> box("java/lang/Double", "(D)Ljava/lang/Double;");
            default -> {
                // references are already Object-compatible
            }
        }
    }

    private void box(String owner, String descriptor) {
        super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false);
    }
}
