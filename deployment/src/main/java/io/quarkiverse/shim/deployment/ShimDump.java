package io.quarkiverse.shim.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jboss.logging.Logger;

/**
 * Best-effort writer for the human-readable bytecode dump produced when
 * {@code quarkus.shim.dump-transformed-classes=true}. Failures never break the
 * build.
 */
final class ShimDump {

    private static final Logger LOG = Logger.getLogger(ShimDump.class);
    private static final Path DIR = Paths.get("target", "shim");

    private ShimDump() {
    }

    static void write(String internalClassName, String content) {
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(internalClassName.replace('/', '.') + ".txt");
            Files.writeString(file, content);
            LOG.infof("Shim: dumped transformed %s to %s", internalClassName.replace('/', '.'), file);
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Shim: failed to dump transformed %s: %s", internalClassName, e.toString());
        }
    }
}
