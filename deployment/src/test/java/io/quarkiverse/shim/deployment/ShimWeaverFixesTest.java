package io.quarkiverse.shim.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.CheckClassAdapter;

import io.quarkiverse.shim.ShimCall;

/**
 * Bytecode-level coverage for the weaver behaviours that used to produce
 * unloadable classes, duplicated advice, or silently wrong bindings.
 */
class ShimWeaverFixesTest {

    private static final String HOOKS = Type.getInternalName(Hooks.class);

    @BeforeEach
    void reset() {
        Hooks.log.clear();
    }

    // --- bridge methods ------------------------------------------------------

    @Test
    void adviceIsNotWovenIntoBridgeMethods() throws Exception {
        Class<?> woven = define("gen.BridgeTarget", transform(bridgeTarget("gen/BridgeTarget"),
                List.of(op(ShimOp.Kind.BEFORE, "handle", "", "record", "(Ljava/lang/Object;)V"))));
        Object instance = woven.getDeclaredConstructor().newInstance();

        woven.getMethod("handle", String.class).invoke(instance, "a");
        assertEquals(1, Hooks.log.size(), "the real method fires the hook once");

        Hooks.log.clear();
        // the bridge is what an interface-typed caller reaches; it must not add a second call
        woven.getMethod("handle", Object.class).invoke(instance, "a");
        assertEquals(1, Hooks.log.size(), "going through the bridge must not fire the hook twice");
    }

    @Test
    void aBridgeCanStillBePinnedExplicitly() throws Exception {
        Class<?> woven = define("gen.PinnedBridge", transform(bridgeTarget("gen/PinnedBridge"),
                List.of(op(ShimOp.Kind.BEFORE, "handle", "(Ljava/lang/Object;)Ljava/lang/String;",
                        "recordNoArgs", "()V"))));
        Object instance = woven.getDeclaredConstructor().newInstance();

        woven.getMethod("handle", String.class).invoke(instance, "a");
        assertEquals(0, Hooks.log.size(), "the descriptor named the bridge, not the real method");
        woven.getMethod("handle", Object.class).invoke(instance, "a");
        assertEquals(1, Hooks.log.size());
    }

    // --- interface fields ----------------------------------------------------

    @Test
    void definalizingAnInterfaceFieldIsRejected() {
        byte[] target = interfaceWithField("gen/IfaceField");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transform(target, List.of(), Set.of("REGISTRY"), false));
        assertTrue(failure.getMessage().contains("public static final"), failure.getMessage());
    }

    @Test
    void widenAccessLeavesInterfaceFieldsLoadable() throws Exception {
        Class<?> woven = define("gen.IfaceWiden",
                transform(interfaceWithField("gen/IfaceWiden"), List.of(), Set.of(), true));
        // the JVM rejects an interface field that is not public static final,
        // so merely defining the class proves the modifiers survived
        assertTrue(Modifier.isFinal(woven.getDeclaredField("REGISTRY").getModifiers()));
    }

    // --- compile-time constants and constructors -----------------------------

    @Test
    void widenAccessKeepsCompileTimeConstantsFinalButWidensEverythingElse() throws Exception {
        Class<?> woven = define("gen.Widen", transform(constantsTarget("gen/Widen"), List.of(), Set.of(), true));

        assertTrue(Modifier.isFinal(woven.getDeclaredField("CONST").getModifiers()),
                "a ConstantValue field must stay final: readers already inlined it");
        assertTrue(Modifier.isPublic(woven.getDeclaredField("CONST").getModifiers()));
        assertFalse(Modifier.isFinal(woven.getDeclaredField("mutable").getModifiers()));
        assertTrue(Modifier.isPublic(woven.getDeclaredConstructor().getModifiers()),
                "widenAccess covers constructors too");
    }

    @Test
    void definalizingACompileTimeConstantIsRejected() {
        byte[] target = constantsTarget("gen/Const");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transform(target, List.of(), Set.of("CONST"), false));
        assertTrue(failure.getMessage().contains("compile-time constant"), failure.getMessage());
    }

    // --- binding ambiguity ---------------------------------------------------

    @Test
    void anObjectParameterThatCouldBeSelfOrTheReturnedValueIsRejected() {
        byte[] target = valueTarget("gen/Ambiguous", Opcodes.ACC_PUBLIC);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> transform(target, List.of(op(ShimOp.Kind.AFTER, "value", "", "record",
                        "(Ljava/lang/Object;)V"))));
        assertTrue(failure.getMessage().contains("ambiguous"), failure.getMessage());
        assertTrue(failure.getMessage().contains("returned value"), failure.getMessage());
    }

    @Test
    void anUnambiguousReturnedValueStillBinds() throws Exception {
        Class<?> woven = define("gen.Unambiguous",
                transform(valueTarget("gen/Unambiguous", Opcodes.ACC_PUBLIC),
                        List.of(op(ShimOp.Kind.AFTER, "value", "", "recordString", "(Ljava/lang/String;)V"))));
        Object instance = woven.getDeclaredConstructor().newInstance();
        woven.getMethod("value").invoke(instance);
        assertEquals(List.of("ok"), Hooks.log);
    }

    // --- class file version --------------------------------------------------

    @Test
    void aroundRaisesAPreJava7ClassToTheVersionInvokedynamicNeeds() throws Exception {
        byte[] woven = transform(valueTarget("gen/Old", Opcodes.ACC_PUBLIC, Opcodes.V1_6),
                List.of(op(ShimOp.Kind.AROUND, "value", "", "around",
                        "(Lio/quarkiverse/shim/ShimCall;Ljava/lang/Object;)Ljava/lang/String;")));

        assertEquals(Opcodes.V1_7, new ClassReader(woven).readShort(6));
        Class<?> loaded = define("gen.Old", woven);
        assertEquals("[ok]", loaded.getMethod("value").invoke(loaded.getDeclaredConstructor().newInstance()));
    }

    @Test
    void adviceLeavesTheClassVersionAloneWhenNoInvokedynamicIsNeeded() {
        byte[] woven = transform(valueTarget("gen/OldAdvice", Opcodes.ACC_PUBLIC, Opcodes.V1_6),
                List.of(op(ShimOp.Kind.BEFORE, "value", "", "record", "(Ljava/lang/Object;)V")));
        assertEquals(Opcodes.V1_6, new ClassReader(woven).readShort(6));
    }

    // --- exceptional-exit advice --------------------------------------------

    @Test
    void catchAndFinallyHooksRunOnTheRightPaths() throws Exception {
        Class<?> woven = define("gen.Guarded", transform(throwingTarget("gen/Guarded"),
                List.of(op(ShimOp.Kind.CATCH, "run", "", "onCatch", "(Ljava/lang/Object;Ljava/lang/Object;)V"),
                        op(ShimOp.Kind.FINALLY, "run", "", "onFinally", "(Ljava/lang/Object;)V"))));
        Object instance = woven.getDeclaredConstructor().newInstance();

        assertEquals("ok", woven.getMethod("run", boolean.class).invoke(instance, false));
        assertEquals(List.of("finally"), Hooks.log, "a normal return runs only the finally hook");

        Hooks.log.clear();
        Throwable thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> woven.getMethod("run", boolean.class).invoke(instance, true)).getCause();
        assertEquals("bang", thrown.getMessage(), "the original exception is rethrown unchanged");
        assertEquals(List.of("catch:bang", "finally"), Hooks.log);
    }

    // --- ShimCall.proceed(args) ---------------------------------------------

    @Test
    void proceedCanReplaceTheArgumentsTheTargetWasCalledWith() throws Exception {
        Class<?> woven = define("gen.Substitute", transform(echoTarget("gen/Substitute"),
                List.of(op(ShimOp.Kind.AROUND, "echo", "", "aroundSubstituting",
                        "(Lio/quarkiverse/shim/ShimCall;Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/String;"))));
        Object instance = woven.getDeclaredConstructor().newInstance();

        assertEquals("a:1|replaced:9",
                woven.getMethod("echo", String.class, int.class).invoke(instance, "a", 1));
    }

    // --- harness -------------------------------------------------------------

    private static ShimOp op(ShimOp.Kind kind, String targetMethod, String targetDescriptor, String hook,
            String hookDescriptor) {
        return new ShimOp(kind, 0, targetMethod, targetDescriptor, false, HOOKS, hook, hookDescriptor, "test");
    }

    private static byte[] transform(byte[] input, List<ShimOp> ops) {
        return transform(input, ops, Set.of(), false);
    }

    private static byte[] transform(byte[] input, List<ShimOp> ops, Set<String> definalize, boolean widen) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ShimClassVisitor(new CheckClassAdapter(writer, false), ops, definalize, widen, null),
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static Class<?> define(String binaryName, byte[] bytes) {
        return new Loader(ShimWeaverFixesTest.class.getClassLoader()).define(binaryName, bytes);
    }

    private static void constructor(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code String handle(String)} plus the bridge javac emits for {@code handle(Object)}. */
    private static byte[] bridgeTarget(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        constructor(w);
        MethodVisitor real = w.visitMethod(Opcodes.ACC_PUBLIC, "handle", "(Ljava/lang/String;)Ljava/lang/String;",
                null, null);
        real.visitCode();
        real.visitVarInsn(Opcodes.ALOAD, 1);
        real.visitInsn(Opcodes.ARETURN);
        real.visitMaxs(0, 0);
        real.visitEnd();
        MethodVisitor bridge = w.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC,
                "handle", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitVarInsn(Opcodes.ALOAD, 1);
        bridge.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName, "handle",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        bridge.visitInsn(Opcodes.ARETURN);
        bridge.visitMaxs(0, 0);
        bridge.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] interfaceWithField(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "REGISTRY",
                "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor clinit = w.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "REGISTRY", "Ljava/lang/Object;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] constantsTarget(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CONST", "I", null, 5).visitEnd();
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mutable", "I", null, null).visitEnd();
        constructor(w);
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] valueTarget(String internalName, int access) {
        return valueTarget(internalName, access, Opcodes.V17);
    }

    /** {@code String value() { return "ok"; }} */
    private static byte[] valueTarget(String internalName, int access, int version) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(version, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        constructor(w);
        MethodVisitor mv = w.visitMethod(access, "value", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("ok");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** {@code String run(boolean boom)} that throws when asked. */
    private static byte[] throwingTarget(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        constructor(w);
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC, "run", "(Z)Ljava/lang/String;", null, null);
        Label fine = new Label();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitJumpInsn(Opcodes.IFEQ, fine);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("bang");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(fine);
        mv.visitLdcInsn("ok");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** {@code String echo(String text, int count)}. */
    private static byte[] echoTarget(String internalName) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        constructor(w);
        MethodVisitor mv = w.visitMethod(Opcodes.ACC_PUBLIC, "echo", "(Ljava/lang/String;I)Ljava/lang/String;",
                null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn(":");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(I)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    public static final class Hooks {
        static final List<String> log = new ArrayList<>();

        public static void record(Object self) {
            log.add("hit");
        }

        public static void recordNoArgs() {
            log.add("hit");
        }

        public static void recordString(String returned) {
            log.add(returned);
        }

        public static String around(ShimCall<String> original, Object self) {
            return "[" + original.proceed() + "]";
        }

        public static String aroundSubstituting(ShimCall<String> original, Object self, String text, int count) {
            return original.proceed() + "|" + original.proceed("replaced", 9);
        }

        public static void onCatch(Object self, Object failure) {
            log.add("catch:" + ((Throwable) failure).getMessage());
        }

        public static void onFinally(Object self) {
            log.add("finally");
        }
    }

    private static final class Loader extends ClassLoader {
        Loader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
