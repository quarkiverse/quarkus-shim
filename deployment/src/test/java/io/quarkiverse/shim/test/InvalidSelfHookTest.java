package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class InvalidSelfHookTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeter.class, BrokenSelfShim.class, CallLog.class))
            .assertException(t -> {
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertTrue(root instanceof IllegalStateException,
                        "expected IllegalStateException but got " + root);
                assertTrue(root.getMessage().contains("'self' parameter"),
                        "unexpected message: " + root.getMessage());
            });

    @Test
    void buildFails() {
        // never reached: the deployment is expected to fail and
        // assertException above consumes the failure
        fail("The build should have failed");
    }
}
