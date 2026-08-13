package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimReplace;

/** Shim declarations that must each be rejected at build time, with a useful message. */
public final class BuildFailureShims {

    private BuildFailureShims() {
    }

    /** The target class does not exist, so it could never be transformed. */
    @Shim(targetName = "com.acme.absolutely.NoSuchClass")
    public static class GhostTarget {

        @ShimReplace(method = "whatever")
        public static String whatever(Object self) {
            return "never";
        }
    }

    /** A hook the woven target cannot reach: not public, and in another package. */
    @Shim(Greeter.class)
    public static class NonPublicHook {

        @ShimReplace(method = "greet")
        static String greet(Greeter self, String name) {
            return "never";
        }
    }

    /** A lone Object parameter reads equally well as self or as the returned value. */
    @Shim(Greeter.class)
    public static class AmbiguousHook {

        @ShimAfter(method = "greet")
        public static void after(Object ambiguous) {
        }
    }
}
