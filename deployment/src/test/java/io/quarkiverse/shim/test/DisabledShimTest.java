package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * With {@code quarkus.shim.enabled=false} nothing is transformed: the
 * original behavior of the target classes is preserved even though the shim
 * classes are present in the application.
 */
public class DisabledShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeter.class, GreeterShim.class, CallLog.class))
            .overrideConfigKey("quarkus.shim.enabled", "false");

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void originalBehaviorIsPreserved() {
        Greeter greeter = new Greeter();
        assertEquals("Hello World", greeter.greet("World"));
        assertEquals("hey", greeter.shout());
        assertEquals(41, Greeter.answer());
        greeter.touch();
        assertEquals(List.of("touch"), CallLog.entries());
    }
}
