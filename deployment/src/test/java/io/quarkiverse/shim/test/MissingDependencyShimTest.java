package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * {@code dependency} without {@code versions} is a presence gate: with the
 * dependency off the classpath the shim does not apply.
 */
public class MissingDependencyShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger.class, LedgerMissingDependencyShim.class));

    @Test
    void shimIsNotAppliedWhenTheDependencyIsAbsent() {
        assertEquals("unpatched", new Ledger().balance());
    }
}
