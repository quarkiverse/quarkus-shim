package io.quarkiverse.shim.test;

import java.io.IOException;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimCatch;
import io.quarkiverse.shim.ShimFinally;

@Shim(FlakyService.class)
public class FlakyServiceShim {

    @ShimCatch(method = "send", exception = IOException.class)
    public static void onFailure(FlakyService self, IOException failure) {
        CallLog.record("catch:" + failure.getMessage());
    }

    @ShimFinally(method = "send")
    public static void always(FlakyService self) {
        CallLog.record("finally");
    }
}
