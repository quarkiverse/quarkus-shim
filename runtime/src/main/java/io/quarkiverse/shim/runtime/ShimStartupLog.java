package io.quarkiverse.shim.runtime;

import java.util.List;

import org.jboss.logging.Logger;

/**
 * Logs the shims applied during the build, once, at application startup.
 */
public final class ShimStartupLog {

    private static final Logger LOG = Logger.getLogger("io.quarkiverse.shim");

    private ShimStartupLog() {
    }

    public static void logApplied(List<String> descriptions) {
        if (descriptions.isEmpty()) {
            return;
        }
        LOG.infof("Shim applied %d patch(es):", descriptions.size());
        for (String description : descriptions) {
            LOG.infof("  - %s", description);
        }
    }

    /**
     * Reports shims held back by their dependency gate — either the dependency
     * is absent, or its version falls outside {@code versions()} — so an
     * obsolete patch does not go unnoticed after an upgrade.
     */
    public static void logRetired(List<String> descriptions) {
        if (descriptions.isEmpty()) {
            return;
        }
        LOG.warnf("Shim did not apply %d gated patch(es); they can be removed once verified:", descriptions.size());
        for (String description : descriptions) {
            LOG.warnf("  - %s", description);
        }
    }
}
