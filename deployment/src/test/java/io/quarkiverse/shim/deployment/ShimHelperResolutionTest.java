package io.quarkiverse.shim.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimMethods;

/**
 * Overload resolution and member lookup in the runtime helpers, covering the
 * shapes that used to be reported as ambiguous or missing.
 */
class ShimHelperResolutionTest {

    // --- bridge methods ------------------------------------------------------

    @Test
    void aCovariantReturnOverrideResolvesToTheRealMethod() {
        assertEquals("sub", ShimMethods.invoke(new Sub(), "get"));
    }

    @Test
    void aGenericInterfaceImplementationResolvesToTheRealMethod() {
        assertEquals("applied:x", ShimMethods.invoke(new StringFunction(), "apply", "x"));
    }

    // --- conversions ---------------------------------------------------------

    @Test
    void wideningPrimitiveConversionIsApplied() {
        Numbers numbers = new Numbers();
        ShimMethods.invoke(numbers, "addLong", 5);
        ShimMethods.invoke(numbers, "addDouble", 2);
        assertEquals(5L, (long) ShimFields.get(numbers, "total"));
        assertEquals(2.0d, (double) ShimFields.get(numbers, "sum"));
    }

    @Test
    void varargsMethodsAreResolvedAndPacked() {
        Numbers numbers = new Numbers();
        assertEquals(3, (int) ShimMethods.invoke(numbers, "count", "a", "b", "c"));
        assertEquals(0, (int) ShimMethods.invoke(numbers, "count"));
    }

    @Test
    void theMostSpecificOverloadWins() {
        assertEquals("String", ShimMethods.invoke(new Overloads(), "pick", "text"));
        assertEquals("Object", ShimMethods.invoke(new Overloads(), "pick", 1));
    }

    @Test
    void anAmbiguousNullArgumentPointsAtInvokeExact() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ShimMethods.invoke(new Overloads(), "two", (Object) null));
        assertTrue(failure.getMessage().contains("invokeExact"), failure.getMessage());
        assertEquals("String", ShimMethods.invokeExact(new Overloads(), "two",
                new Class<?>[] { String.class }, (Object) null));
    }

    // --- hierarchy -----------------------------------------------------------

    @Test
    void interfaceDefaultMethodsAndConstantsAreReachable() {
        StringFunction target = new StringFunction();
        assertEquals("default", ShimMethods.invoke(target, "describe"));
        assertEquals("CONSTANT", ShimFields.get(target, "LABEL"));
    }

    @Test
    void getDeclaredNamesTheDeclaringClassWhenASubclassShadowsTheField() {
        Sub instance = new Sub();
        assertEquals("sub-field", ShimFields.get(instance, "name"), "the runtime class wins by default");
        assertEquals("base-field", ShimFields.getDeclared(Base.class, instance, "name"));
    }

    // --- exceptions and errors ----------------------------------------------

    @Test
    void aCheckedExceptionFromTheTargetPropagatesUnchanged() {
        IOException thrown = assertThrows(IOException.class, () -> ShimMethods.invoke(new Numbers(), "explode"));
        assertEquals("boom", thrown.getMessage());
    }

    @Test
    void aFailedFinalFieldWritePointsAtDefinalize() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ShimFields.setStatic(Numbers.class, "CONSTANT", 2));
        assertTrue(failure.getMessage().contains("definalize"), failure.getMessage());
    }

    @Test
    void invokeStaticOnAnInstanceMethodExplainsItself() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ShimMethods.invokeStatic(Numbers.class, "addLong", 1L));
        assertTrue(failure.getMessage().contains("instance method"), failure.getMessage());
    }

    @Test
    void aMissingMethodListsTheCandidatesItDidFind() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ShimMethods.invoke(new Overloads(), "pick", 1, 2, 3));
        assertTrue(failure.getMessage().contains("pick"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Candidates"), failure.getMessage());
    }

    @Test
    void newInstanceReachesAPrivateConstructor() {
        Object created = ShimMethods.newInstance(Overloads.class, "seeded");
        assertEquals("seeded", ShimFields.get(created, "origin"));
    }

    @Test
    void resolutionIsCachedPerOwner() {
        Sub instance = new Sub();
        assertSame(ShimMethods.<String> invoke(instance, "get"), ShimMethods.<String> invoke(instance, "get"));
        assertNull(ShimFields.<Object> get(instance, "unset"));
    }

    // --- fixtures ------------------------------------------------------------

    public static class Base {
        private final String name = "base-field";

        public Object get() {
            return "base";
        }
    }

    public static class Sub extends Base {
        private final String name = "sub-field";
        private final Object unset = null;

        @Override
        public String get() { // javac also emits a bridge returning Object
            return "sub";
        }
    }

    public interface Described {
        String LABEL = "CONSTANT";

        default String describe() {
            return "default";
        }
    }

    public static class StringFunction implements Function<String, String>, Described {
        @Override
        public String apply(String key) { // javac also emits a bridge taking Object
            return "applied:" + key;
        }
    }

    public static class Numbers {
        static final int CONSTANT = 1;
        private long total;
        private double sum;

        private void addLong(long value) {
            total += value;
        }

        private void addDouble(double value) {
            sum += value;
        }

        private int count(String... parts) {
            return parts.length;
        }

        private void explode() throws IOException {
            throw new IOException("boom");
        }
    }

    public static class Overloads {
        private final String origin;

        Overloads() {
            this("default");
        }

        private Overloads(String origin) {
            this.origin = origin;
        }

        private String pick(String value) {
            return "String";
        }

        private String pick(Object value) {
            return "Object";
        }

        private String two(String value) {
            return "String";
        }

        private String two(Integer value) {
            return "Integer";
        }
    }
}
