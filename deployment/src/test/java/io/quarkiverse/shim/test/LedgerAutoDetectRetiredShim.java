package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * No {@code dependency}: the artifact containing {@link Ledger} is looked up
 * and rejected by a range no real version reaches.
 */
@Shim(value = Ledger.class, name = "ledger-auto-retired", versions = "[999,)")
public class LedgerAutoDetectRetiredShim {

    @ShimReplace(method = "balance")
    public static String balance(Ledger self) {
        return "patched";
    }
}
