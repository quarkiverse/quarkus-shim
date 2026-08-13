package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Each of these used to build cleanly and then misbehave at runtime — or, for
 * the ghost target, never apply at all while being reported as applied.
 */
public class BuildFailureTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNREACHABLE_TARGET = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(BuildFailureShims.GhostTarget.class))
            .assertException(expect("is not in any application archive"));

    @Test
    void aTargetThatCannotBeTransformedFailsTheBuild() {
        // the assertion above runs during augmentation
    }

    private static Consumer<Throwable> expect(String fragment) {
        return thrown -> {
            Throwable root = thrown;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            assertInstanceOf(IllegalStateException.class, root, root.toString());
            assertTrue(root.getMessage().contains(fragment),
                    "expected a message containing '" + fragment + "' but got: " + root.getMessage());
        };
    }
}
