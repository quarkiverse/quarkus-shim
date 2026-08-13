package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * With no {@code dependency} declared, the version is read from the artifact
 * that contains the target class.
 */
public class AutoDetectedDependencyShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger.class, LedgerAutoDetectShim.class));

    @Test
    void shimAppliesWhenTheContainingArtifactMatches() {
        assertEquals("patched", new Ledger().balance());
    }
}
