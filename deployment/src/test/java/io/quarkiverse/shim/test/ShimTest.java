package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class ShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeter.class, GreeterShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void replacementAccessesPrivateFieldAndPrivateMethod() {
        Greeter greeter = new Greeter();
        assertEquals("<Patched World 1>", greeter.greet("World"));
        assertEquals("<Patched Again 2>", greeter.greet("Again"));
        // the private counter really was written on the instance
        assertEquals(2, greeter.greetCount());
    }

    @Test
    void replacementWithObjectTypedSelf() {
        assertEquals("HEY from Greeter", new Greeter().shout());
    }

    @Test
    void replacesStaticMethod() {
        assertEquals(42, Greeter.answer());
    }

    @Test
    void beforeAndAfterHooksReceiveSelf() {
        new Greeter().touch();
        assertEquals(List.of("before:g1", "touch", "after:g1"), CallLog.entries());
    }

    @Test
    void runsAfterHookOnEveryReturnPath() {
        Greeter greeter = new Greeter();
        assertEquals("touched", greeter.maybeTouch(true));
        assertEquals("skipped", greeter.maybeTouch(false));
        assertEquals(List.of("touch", "after-maybe", "after-maybe"), CallLog.entries());
    }
}
