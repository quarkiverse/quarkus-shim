package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code onVersionMismatch = FAIL} an upgrade past the pinned range stops
 * the build instead of silently dropping the patch.
 */
public class VersionMismatchFailsBuildTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger.class, LedgerStrictShim.class))
            .assertException(t -> {
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertTrue(root instanceof IllegalStateException,
                        "expected IllegalStateException but got " + root);
                assertTrue(root.getMessage().contains("ledger-strict"),
                        "unexpected message: " + root.getMessage());
                assertTrue(root.getMessage().contains("outside the pinned range"),
                        "unexpected message: " + root.getMessage());
            });

    @Test
    void buildFails() {
        // never reached: the deployment is expected to fail and
        // assertException above consumes the failure
        fail("The build should have failed");
    }
}
