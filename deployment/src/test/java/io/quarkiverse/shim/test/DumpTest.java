package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code quarkus.shim.dump-transformed-classes=true} a human-readable
 * dump of the transformed target is written to {@code target/shim/}.
 */
public class DumpTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeter.class, GreeterShim.class, CallLog.class))
            .overrideConfigKey("quarkus.shim.dump-transformed-classes", "true");

    @Test
    void transformedClassIsDumped() throws Exception {
        // the extension still patched the class as usual
        assertEquals("HEY from Greeter", new Greeter().shout());

        Path dump = Paths.get("target", "shim", "io.quarkiverse.shim.test.Greeter.txt");
        assertTrue(Files.exists(dump), "expected a dump at " + dump.toAbsolutePath());
        String text = Files.readString(dump);
        assertTrue(text.contains("shout"), "dump should contain the transformed methods");
    }
}
