package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimPriority;

@Shim(value = Ledger2.class, name = "ledger2-audit")
public class Ledger2AuditShim {

    @ShimAfter(method = "post")
    @ShimPriority(1)
    public static void audit(Ledger2 self, String returned) {
        CallLog.record("audit:" + returned);
    }
}
