package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * Pinned to versions below 1.0 while quarkus-core resolves to 3.x — the shape
 * of a patch left behind after the dependency was upgraded.
 */
@Shim(value = Ledger.class, name = "ledger-upgraded", dependency = "io.quarkus:quarkus-core", versions = "(,1.0)")
public class LedgerUpgradedShim {

    @ShimReplace(method = "balance")
    public static String balance(Ledger self) {
        return "patched";
    }
}
