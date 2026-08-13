package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/** descriptor() selection, @Shim(targetName), and advice on a static initializer. */
public class SelectorAndClinitTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Dispatcher.class, DispatcherShim.class,
                    HiddenTargetShim.class, CallLog.class));

    @Test
    void aRawDescriptorSelectsExactlyOneOverload() {
        Dispatcher dispatcher = new Dispatcher();
        assertEquals("patched-long:7", dispatcher.dispatch(7L));
        assertEquals("int:7", dispatcher.dispatch(7), "the int overload is untouched");
    }

    @Test
    void targetNameSelectsTheSameClassAsAClassLiteral() {
        assertEquals("by-name:x", new Dispatcher().dispatch("x"));
    }

    @Test
    void beforeAndAfterHooksRunAroundTheStaticInitializer() {
        Dispatcher.mode(); // force class initialization
        assertTrue(CallLog.entries().contains("before-clinit"), CallLog.entries().toString());
        assertTrue(CallLog.entries().contains("original-static-init"), CallLog.entries().toString());
        assertTrue(CallLog.entries().contains("after-clinit"), CallLog.entries().toString());
        assertEquals(CallLog.entries().indexOf("before-clinit") + 1,
                CallLog.entries().indexOf("original-static-init"), "the before-hook runs first");
    }
}
