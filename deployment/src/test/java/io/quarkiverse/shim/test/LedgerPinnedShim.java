package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/** Pinned to a range that the resolved quarkus-core version satisfies. */
@Shim(value = Ledger.class, name = "ledger-pinned", dependency = "io.quarkus:quarkus-core", versions = "[1.0,)")
public class LedgerPinnedShim {

    @ShimReplace(method = "balance")
    public static String balance(Ledger self) {
        return "patched";
    }
}
