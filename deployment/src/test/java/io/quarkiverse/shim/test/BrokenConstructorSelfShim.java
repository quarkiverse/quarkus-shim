package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimBefore;

/**
 * Invalid on purpose: a constructor before-hook cannot receive 'self'. Only
 * added to the archive of {@link InvalidConstructorBeforeSelfTest}.
 */
@Shim(Widget.class)
public class BrokenConstructorSelfShim {

    @ShimBefore(method = "<init>")
    public static void beforeConstruct(Widget self) {
    }
}
