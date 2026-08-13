package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Once the pinned dependency moves outside {@code versions}, the target class is
 * left untouched — upgrading retires the patch instead of weaving it into code
 * that has changed underneath it.
 */
public class VersionRetiredShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger.class, LedgerUpgradedShim.class));

    @Test
    void shimIsNotAppliedOnceTheDependencyIsUpgraded() {
        assertEquals("unpatched", new Ledger().balance());
    }
}
