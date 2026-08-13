package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/** @ShimCatch and @ShimFinally across the normal, thrown, and self-handled paths. */
public class ExceptionHooksTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(FlakyService.class, FlakyServiceShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void onlyTheFinallyHookRunsOnANormalReturn() throws Exception {
        assertEquals("sent", new FlakyService().send(0));
        assertEquals(List.of("finally"), CallLog.entries());
    }

    @Test
    void catchHookSeesTheExceptionAndItIsRethrownUnchanged() {
        IOException thrown = assertThrows(IOException.class, () -> new FlakyService().send(1));
        assertEquals("network", thrown.getMessage(), "the original exception must propagate unchanged");
        assertEquals(List.of("catch:network", "finally"), CallLog.entries());
    }

    @Test
    void aCatchHookNarrowedToOneTypeIgnoresOtherExceptions() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> new FlakyService().send(3));
        assertEquals("not-an-ioexception", thrown.getMessage());
        // the handler catches everything, but @ShimCatch(exception = IOException.class) filters itself
        assertEquals(List.of("finally"), CallLog.entries());
    }

    @Test
    void anExceptionTheTargetHandlesItselfNeverReachesTheCatchHook() throws Exception {
        assertEquals("recovered", new FlakyService().send(2));
        // the target's own catch keeps priority; the method then returns normally
        assertEquals(List.of("own-handler", "finally"), CallLog.entries());
    }
}
