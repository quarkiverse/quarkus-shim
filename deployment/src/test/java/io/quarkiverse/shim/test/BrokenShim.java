package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimBefore;

/**
 * Invalid on purpose: hook methods must be static. Only added to the archive
 * of {@link InvalidShimTest}.
 */
@Shim(Greeter.class)
public class BrokenShim {

    @ShimBefore(method = "touch")
    public void notStatic() {
    }
}
