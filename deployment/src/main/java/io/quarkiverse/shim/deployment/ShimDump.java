package io.quarkiverse.shim.deployment;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Best-effort writer for the human-readable bytecode dump produced when
 * {@code quarkus.shim.dump-transformed-classes=true}. Failures never break the
 * build.
 */
final class ShimDump {

    private static final Logger LOG = Logger.getLogger(ShimDump.class);

    private ShimDump() {
    }

    static void write(Path directory, String internalClassName, String content) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(internalClassName.replace('/', '.') + ".txt");
            Files.writeString(file, content);
            LOG.infof("Shim: dumped transformed %s to %s", internalClassName.replace('/', '.'), file);
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Shim: failed to dump transformed %s: %s", internalClassName, e.toString());
        }
    }

    /**
     * Writes the trace whether or not the weave completed.
     * <p>
     * The dump exists to explain what the extension did to a class, so the run
     * that matters most is the one that failed validation. Writing it only from
     * a clean {@code visitEnd} meant no dump was produced in exactly that case.
     */
    static final class Dumping extends ClassVisitor {

        private final Path directory;
        private final String internalClassName;
        private final StringWriter trace;
        private boolean written;

        Dumping(ClassVisitor delegate, Path directory, String internalClassName, StringWriter trace) {
            super(Opcodes.ASM9, delegate);
            this.directory = directory;
            this.internalClassName = internalClassName;
            this.trace = trace;
        }

        @Override
        public void visitEnd() {
            try {
                super.visitEnd();
            } catch (RuntimeException | Error weaveFailed) {
                flush(" (INCOMPLETE: the weave failed with " + weaveFailed + ")");
                throw weaveFailed;
            }
            flush("");
        }

        private void flush(String suffix) {
            if (written) {
                return;
            }
            written = true;
            write(directory, internalClassName, trace + suffix);
        }
    }
}
