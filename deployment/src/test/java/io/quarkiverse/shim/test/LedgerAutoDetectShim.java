package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * No {@code dependency}: the artifact containing {@link Ledger} is looked up
 * and matched against a range that accepts any version.
 */
@Shim(value = Ledger.class, name = "ledger-auto", versions = "[0,)")
public class LedgerAutoDetectShim {

    @ShimReplace(method = "balance")
    public static String balance(Ledger self) {
        return "patched";
    }
}
