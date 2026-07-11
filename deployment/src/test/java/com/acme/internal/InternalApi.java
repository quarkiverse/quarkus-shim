package com.acme.internal;

/**
 * Public facade so tests outside this package can drive the package-private
 * {@link HiddenHelper}.
 */
public final class InternalApi {

    private InternalApi() {
    }

    public static int callCompute(int input) {
        return new HiddenHelper().compute(input);
    }
}
