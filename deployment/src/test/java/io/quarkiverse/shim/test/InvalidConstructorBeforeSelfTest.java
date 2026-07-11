package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class InvalidConstructorBeforeSelfTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Widget.class, BrokenConstructorSelfShim.class, CallLog.class))
            .assertException(t -> {
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertTrue(root instanceof IllegalStateException,
                        "expected IllegalStateException but got " + root);
                assertTrue(root.getMessage().contains("not initialized"),
                        "unexpected message: " + root.getMessage());
            });

    @Test
    void buildFails() {
        fail("The build should have failed");
    }
}
