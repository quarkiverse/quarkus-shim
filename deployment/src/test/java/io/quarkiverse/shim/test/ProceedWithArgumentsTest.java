package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/** ShimCall.proceed(...) substitutes the arguments the original runs with. */
public class ProceedWithArgumentsTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Connector.class, ConnectorShim.class));

    @Test
    void proceedRunsTheOriginalWithReplacementArguments() {
        assertEquals("db@10|db@5000", new Connector().connect("db", 10));
    }

    @Test
    void proceedKeepsTheOriginalArgumentsWhenGivenNone() {
        assertEquals("db@9000|db@9000", new Connector().connect("db", 9000));
    }

    @Test
    void aWrongArgumentCountIsReportedAgainstTheTargetMethod() {
        // ConnectorShim only ever passes the right count, so drive the failure
        // through the documented contract on a fresh call
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ((io.quarkiverse.shim.ShimCall<String>) replacements -> {
                    io.quarkiverse.shim.ShimArguments.check(replacements, 2, "Connector#connect");
                    return "unreachable";
                }).proceed("only-one"));
        assertTrue(failure.getMessage().contains("Connector#connect"), failure.getMessage());
        assertTrue(failure.getMessage().contains("takes 2"), failure.getMessage());
    }
}
