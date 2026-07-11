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
}
