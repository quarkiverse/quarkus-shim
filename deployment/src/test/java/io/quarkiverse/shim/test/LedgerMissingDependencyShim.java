package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/** Gated on a dependency that is not on the classpath at all. */
@Shim(value = Ledger.class, name = "ledger-missing", dependency = "com.acme:decision-engine")
public class LedgerMissingDependencyShim {

    @ShimReplace(method = "balance")
    public static String balance(Ledger self) {
        return "patched";
    }
}
