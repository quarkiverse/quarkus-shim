package io.quarkiverse.shim;

/**
 * Support code called from woven bytecode. Not part of the API a shim writes
 * against.
 */
public final class ShimArguments {

    private ShimArguments() {
    }

    /**
     * Validates the replacement arguments handed to
     * {@link ShimCall#proceed(Object...)} before they are unboxed into the
     * original method's parameters, so a wrong count is reported against the
     * target method rather than as a bare {@code ArrayIndexOutOfBoundsException}.
     */
    public static void check(Object[] arguments, int expected, String targetMethod) {
        if (arguments.length != expected) {
            throw new IllegalArgumentException("ShimCall.proceed() was given " + arguments.length
                    + " argument(s) but " + targetMethod + " takes " + expected
                    + "; pass one argument per target parameter, or call proceed() to reuse the original arguments");
        }
    }
}
