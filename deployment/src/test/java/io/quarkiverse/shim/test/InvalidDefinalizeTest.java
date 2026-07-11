package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class InvalidDefinalizeTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Widget.class, BrokenDefinalizeShim.class, CallLog.class))
            .assertException(t -> {
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertTrue(root instanceof IllegalStateException,
                        "expected IllegalStateException but got " + root);
                assertTrue(root.getMessage().contains("compile-time constant"),
                        "unexpected message: " + root.getMessage());
            });

    @Test
    void buildFails() {
        fail("The build should have failed");
    }
}
