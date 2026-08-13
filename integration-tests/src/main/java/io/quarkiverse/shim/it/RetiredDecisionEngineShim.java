package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * Pinned to quarkus-core versions below 1.0 while it resolves to 3.x — the
 * shape of a patch left behind after an upgrade. It must not be applied, and
 * must not disturb {@link DecisionEngineShim} on the same target class.
 */
@Shim(value = DecisionEngine.class, name = "retired-decision", dependency = "io.quarkus:quarkus-core", versions = "(,1.0)")
public class RetiredDecisionEngineShim {

    @ShimReplace(method = "legacyFlag")
    public static String legacyFlag(DecisionEngine self) {
        return "should-never-appear";
    }
}
