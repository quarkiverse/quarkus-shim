package io.quarkiverse.shim.it;

/**
 * Stands in for a third-party library whose patches are pinned to the versions
 * they were written for.
 */
public class DecisionEngine {

    /** Dangerously permissive: anything that is not an explicit DENY gets through. */
    public boolean isAllowed(String decision) {
        return !"DENY".equalsIgnoreCase(decision);
    }

    public String legacyFlag() {
        return "vendor";
    }
}
