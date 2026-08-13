package io.quarkiverse.shim.it;

/** Target for ShimCall.proceed(args). */
public class Retryable {

    public String describe(String label, int retries) {
        return label + ":" + retries;
    }
}
