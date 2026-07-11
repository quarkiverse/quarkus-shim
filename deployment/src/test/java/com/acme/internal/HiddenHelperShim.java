package com.acme.internal;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimReplace;

/**
 * Declared in the same package as the (package-private) target class, so it
 * can name the type directly and call its package-private members — the
 * "same-package trick". Private members still go through {@link ShimFields}.
 */
@Shim(HiddenHelper.class)
public class HiddenHelperShim {

    @ShimReplace(method = "compute")
    public static int compute(HiddenHelper self, int input) {
        int secret = ShimFields.<Integer> get(self, "secret"); // private field
        return self.bump(secret) + input; // package-private method, called directly
    }
}
