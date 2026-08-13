package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimBefore;
import io.quarkiverse.shim.ShimReplace;

@Shim(Dispatcher.class)
public class DispatcherShim {

    // raw JVM descriptor picks exactly the long overload
    @ShimReplace(method = "dispatch", descriptor = "(J)Ljava/lang/String;")
    public static String dispatchLong(Dispatcher self, long value) {
        return "patched-long:" + value;
    }

    // before/after on the static initializer
    @ShimBefore(method = "<clinit>")
    public static void beforeStaticInit() {
        CallLog.record("before-clinit");
    }

    @ShimAfter(method = "<clinit>")
    public static void afterStaticInit() {
        CallLog.record("after-clinit");
    }
}
