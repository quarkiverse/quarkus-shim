package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * The auto-detected artifact is gated the same way an explicitly named one is.
 */
public class AutoDetectedDependencyRetiredTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger.class, LedgerAutoDetectRetiredShim.class));

    @Test
    void shimIsNotAppliedWhenTheContainingArtifactIsOutOfRange() {
        assertEquals("unpatched", new Ledger().balance());
    }
}
