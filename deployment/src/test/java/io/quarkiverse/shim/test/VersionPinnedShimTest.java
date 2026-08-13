package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * A shim pinned to a version range that the resolved dependency satisfies is
 * applied exactly like an unpinned one.
 */
public class VersionPinnedShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger.class, LedgerPinnedShim.class));

    @Test
    void shimAppliesWhileTheDependencyIsInRange() {
        assertEquals("patched", new Ledger().balance());
    }
}
