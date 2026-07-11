package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * Invalid on purpose: constructor bodies cannot be replaced. Only added to
 * the archive of {@link InvalidConstructorReplaceTest}.
 */
@Shim(Widget.class)
public class BrokenConstructorReplaceShim {

    @ShimReplace(method = "<init>")
    public static void init(Widget self, String name) {
    }
}
