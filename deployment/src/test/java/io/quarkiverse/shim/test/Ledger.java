package io.quarkiverse.shim.test;

/**
 * Stands in for a third-party class whose patch is pinned to the dependency
 * versions it was written for.
 */
public class Ledger {

    public String balance() {
        return "unpatched";
    }
}
