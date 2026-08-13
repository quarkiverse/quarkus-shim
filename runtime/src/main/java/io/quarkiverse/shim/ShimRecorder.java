package io.quarkiverse.shim;

import java.util.List;

import org.jboss.logging.Logger;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Runtime recorder that logs the shims applied during the build, once, at
 * application startup.
 */
@Recorder
public class ShimRecorder {

    private static final Logger LOG = Logger.getLogger("io.quarkiverse.shim");

    public void logApplied(List<String> descriptions) {
        if (descriptions.isEmpty()) {
            return;
        }
        LOG.infof("Shim applied %d patch(es):", descriptions.size());
        for (String description : descriptions) {
            LOG.infof("  - %s", description);
        }
    }

    /**
     * Reports shims that were pinned to a dependency version and did not apply,
     * so an obsolete patch does not go unnoticed after an upgrade.
     */
    public void logRetired(List<String> descriptions) {
        if (descriptions.isEmpty()) {
            return;
        }
        LOG.warnf("Shim did not apply %d pinned patch(es); they can be removed once verified:", descriptions.size());
        for (String description : descriptions) {
            LOG.warnf("  - %s", description);
        }
    }
}
