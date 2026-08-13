package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * Pinned to a range the resolved quarkus-core version satisfies, so the
 * fail-closed patch is woven in.
 */
@Shim(value = DecisionEngine.class, name = "fail-closed-decision", dependency = "io.quarkus:quarkus-core", versions = "[1.0,)")
public class DecisionEngineShim {

    @ShimReplace(method = "isAllowed", paramTypes = String.class)
    public static boolean isAllowed(DecisionEngine self, String decision) {
        return "ALLOW".equalsIgnoreCase(decision);
    }
}
