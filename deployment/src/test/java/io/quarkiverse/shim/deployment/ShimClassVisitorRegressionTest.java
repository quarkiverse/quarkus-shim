package io.quarkiverse.shim.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkiverse.shim.ShimCall;

class ShimClassVisitorRegressionTest {

    private static final String HOOK_OWNER = Type.getInternalName(Hooks.class);
    private static final String MARKER_DESC = Type.getDescriptor(Marker.class);

    @Test
    void replaceAndAroundPreserveRuntimeMethodAnnotations() throws Exception {
        Class<?> replaced = define("test.generated.MetadataReplace",
                transform(targetClass("test/generated/MetadataReplace", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, true),
                        List.of(op(ShimOp.Kind.REPLACE, "value", "replace", "()I")), Set.of(), false));
        assertNotNull(replaced.getDeclaredMethod("value").getAnnotation(Marker.class));

        Class<?> wrapped = define("test.generated.MetadataAround",
                transform(targetClass("test/generated/MetadataAround", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, true),
                        List.of(op(ShimOp.Kind.AROUND, "value", "around", "(Lio/quarkiverse/shim/ShimCall;)I")),
                        Set.of(), false));
        assertNotNull(wrapped.getDeclaredMethod("value").getAnnotation(Marker.class));
        Method original = List.of(wrapped.getDeclaredMethods()).stream()
                .filter(method -> method.getName().contains("$shim$orig$"))
                .findFirst().orElseThrow();
        assertFalse(original.isAnnotationPresent(Marker.class), "metadata belongs on the callable wrapper");
    }

    @Test
    void primitiveReturnCanBePassedToObjectAfterHook() throws Exception {
        Hooks.lastReturned = null;
        Class<?> target = define("test.generated.PrimitiveAfter",
                transform(targetClass("test/generated/PrimitiveAfter", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false),
                        List.of(op(ShimOp.Kind.AFTER, "value", "afterObject", "(Ljava/lang/Object;)V")), Set.of(), false));

        Object result = assertDoesNotThrow(() -> target.getDeclaredMethod("value").invoke(null));
        assertEquals(7, result);
        assertEquals(7, Hooks.lastReturned);
    }

    @Test
    void unmatchedOperationsAndFieldsFailTransformation() {
        byte[] target = targetClass("test/generated/Unmatched", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false);
        assertThrows(IllegalStateException.class,
                () -> transform(target, List.of(op(ShimOp.Kind.BEFORE, "missing", "before", "()V")), Set.of(), false));
        assertThrows(IllegalStateException.class,
                () -> transform(target, List.of(), Set.of("missingField"), false));
    }

    @Test
    void multipleTerminalHooksAreRejected() {
        byte[] target = targetClass("test/generated/Duplicate", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false);
        assertThrows(IllegalStateException.class,
                () -> transform(target,
                        List.of(op(ShimOp.Kind.REPLACE, "value", "replace", "()I"),
                                op(ShimOp.Kind.REPLACE, "value", "replaceAgain", "()I")),
                        Set.of(), false));
        assertThrows(IllegalStateException.class,
                () -> transform(target,
                        List.of(op(ShimOp.Kind.AROUND, "value", "around", "(Lio/quarkiverse/shim/ShimCall;)I"),
                                op(ShimOp.Kind.AROUND, "value", "aroundAgain", "(Lio/quarkiverse/shim/ShimCall;)I")),
                        Set.of(), false));
    }

    @Test
    void widenAccessAlsoAppliesToAroundWrapper() throws Exception {
        Class<?> target = define("test.generated.WidenAround",
                transform(targetClass("test/generated/WidenAround", Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC
                        | Opcodes.ACC_FINAL, false),
                        List.of(op(ShimOp.Kind.AROUND, "value", "around", "(Lio/quarkiverse/shim/ShimCall;)I")),
                        Set.of(), true));

        int modifiers = target.getDeclaredMethod("value").getModifiers();
        assertTrue(Modifier.isPublic(modifiers));
        assertFalse(Modifier.isFinal(modifiers));
    }

    @Test
    void aroundSupportsInterfaceDefaultMethods() throws Exception {
        String binaryName = "test.generated.AroundInterface";
        ByteArrayClassLoader loader = new ByteArrayClassLoader(getClass().getClassLoader());
        Class<?> target = loader.define(binaryName,
                transform(targetInterface(binaryName.replace('.', '/')),
                        List.of(op(ShimOp.Kind.AROUND, "value", "aroundInstance",
                                "(Lio/quarkiverse/shim/ShimCall;Ljava/lang/Object;)I")),
                        Set.of(), false));
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] { target },
                (instance, method, args) -> InvocationHandler.invokeDefault(instance, method, args));

        assertEquals(8, target.getMethod("value").invoke(proxy));
    }

    private static ShimOp op(ShimOp.Kind kind, String targetMethod, String hookMethod, String hookDescriptor) {
        return new ShimOp(kind, 0, targetMethod, "()I", false, HOOK_OWNER, hookMethod, hookDescriptor, "test");
    }

    private static byte[] transform(byte[] input, List<ShimOp> ops, Set<String> definalize, boolean widen) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ShimClassVisitor(writer, ops, definalize, widen, null), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] targetClass(String internalName, int methodAccess, boolean annotateMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        emitValueMethod(writer, methodAccess, annotateMethod);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetInterface(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        emitValueMethod(writer, Opcodes.ACC_PUBLIC, false);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitValueMethod(ClassWriter writer, int access, boolean annotateMethod) {
        MethodVisitor method = writer.visitMethod(access, "value", "()I", null, null);
        if (annotateMethod) {
            method.visitAnnotation(MARKER_DESC, true).visitEnd();
        }
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, 7);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0);
        method.visitEnd();
    }

    private static Class<?> define(String binaryName, byte[] bytes) {
        return new ByteArrayClassLoader(ShimClassVisitorRegressionTest.class.getClassLoader()).define(binaryName, bytes);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {
    }

    public static final class Hooks {
        static Object lastReturned;

        public static int replace() {
            return 9;
        }

        public static int replaceAgain() {
            return 10;
        }

        public static int around(ShimCall<Integer> original) {
            return original.proceed() + 1;
        }

        public static int aroundAgain(ShimCall<Integer> original) {
            return original.proceed() + 2;
        }

        public static int aroundInstance(ShimCall<Integer> original, Object self) {
            return original.proceed() + 1;
        }

        public static void afterObject(Object returned) {
            lastReturned = returned;
        }

        public static void before() {
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
