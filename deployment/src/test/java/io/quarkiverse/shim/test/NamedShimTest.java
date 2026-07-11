package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Disabling one named shim via configuration leaves the others active.
 */
public class NamedShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Pipeline.class, PipelineShim.class,
                    ExtraPipelineShim.class, CallLog.class))
            .overrideConfigKey("quarkus.shim.instances.\"pipeline-extra\".enabled", "false");

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void disabledNamedShimIsSkippedWhileOthersRun() {
        new Pipeline().process("x");
        assertFalse(CallLog.entries().contains("extra"),
                "the disabled 'pipeline-extra' shim should not have run");
        assertTrue(CallLog.entries().contains("before1:x"),
                "the enabled PipelineShim should still run");
        assertEquals("after:out:x", CallLog.entries().get(CallLog.entries().size() - 1));
    }
}
