package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAround;
import io.quarkiverse.shim.ShimCall;

/**
 * Wraps a second target and substitutes arguments, so the invokedynamic-based
 * ShimCall bridge is exercised in native image too.
 */
@Shim(value = Retryable.class, name = "retry-policy")
public class RetryPolicyShim {

    @ShimAround(method = "describe")
    public static String describe(ShimCall<String> original, Retryable self, String label, int retries) {
        return original.proceed() + "|" + original.proceed(label.toUpperCase(), Math.max(retries, 3));
    }
}
