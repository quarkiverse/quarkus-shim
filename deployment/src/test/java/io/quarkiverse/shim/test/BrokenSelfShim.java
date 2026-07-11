package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimBefore;

/**
 * Invalid on purpose: declares a 'self' parameter for a static target method.
 * Only added to the archive of {@link InvalidSelfHookTest}.
 */
@Shim(Greeter.class)
public class BrokenSelfShim {

    @ShimBefore(method = "answer") // Greeter.answer() is static
    public static void beforeAnswer(Greeter self) {
    }
}
