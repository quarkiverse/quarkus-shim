package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimPriority;
import io.quarkiverse.shim.ShimReplace;

/** A second, independently-named shim on the same target class. */
@Shim(value = Ledger2.class, name = "ledger2-patch", definalize = { "state" })
public class Ledger2PatchShim {

    @ShimReplace(method = "close")
    public static String close(Ledger2 self) {
        // 'state' is final in source; this shim's definalize entry makes it writable
        ShimFields.set(self, "state", "closed");
        return ShimFields.get(self, "state");
    }

    @ShimAfter(method = "post")
    @ShimPriority(2)
    public static void alsoAudit(Ledger2 self, String returned) {
        CallLog.record("patch:" + returned);
    }
}
