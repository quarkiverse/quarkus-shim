package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class ConstructorShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Widget.class, WidgetShim.class,
                    StaticInit.class, StaticInitShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void constructorBeforeAndAfterHooks() {
        Widget widget = new Widget("a");
        assertEquals(List.of("before-ctor", "ctor:a", "after-ctor:a"), CallLog.entries());
        // the after-hook rewrote the private fields on the constructed
        // instance - including the definalized 'name'
        assertEquals("a!", widget.name());
        assertEquals(99, widget.size());
    }

    @Test
    void replacesStaticInitializer() {
        // first touch triggers class initialization -> the shimmed <clinit>
        assertEquals("shimmed", StaticInit.mode());
        assertTrue(CallLog.entries().contains("shimmed-static-init"),
                "the shimmed static initializer should have run");
        assertFalse(CallLog.entries().contains("original-static-init"),
                "the original static initializer should not have run");
    }
}
