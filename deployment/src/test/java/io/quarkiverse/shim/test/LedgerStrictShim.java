package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;
import io.quarkiverse.shim.VersionMismatch;

/** Demands a human decision instead of retiring itself silently. */
@Shim(value = Ledger.class, name = "ledger-strict", dependency = "io.quarkus:quarkus-core", versions = "(,1.0)", onVersionMismatch = VersionMismatch.FAIL)
public class LedgerStrictShim {

    @ShimReplace(method = "balance")
    public static String balance(Ledger self) {
        return "patched";
    }
}
