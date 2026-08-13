package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * Targets a class by name rather than by class literal — the documented route
 * for a class you cannot reference from your own code. 'self' is typed Object
 * for the same reason.
 */
@Shim(targetName = "io.quarkiverse.shim.test.Dispatcher")
public class HiddenTargetShim {

    @ShimReplace(method = "dispatch", paramTypes = { String.class })
    public static String dispatchString(Object self, String value) {
        return "by-name:" + value;
    }
}
