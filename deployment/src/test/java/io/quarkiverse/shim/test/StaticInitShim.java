package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimReplace;

@Shim(StaticInit.class)
public class StaticInitShim {

    // <clinit> has no 'this', so full replacement is legal — note that static
    // field initializers written at the declaration site are discarded too
    @ShimReplace(method = "<clinit>")
    public static void staticInit() {
        CallLog.record("shimmed-static-init");
        ShimFields.setStatic(StaticInit.class, "mode", "shimmed");
    }
}
