package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * @ShimPriority ordering plus before/after argument and return-value access.
 */
public class PriorityArgsTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Pipeline.class, PipelineShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void hooksRunInPriorityOrderWithArgsAndReturn() {
        String result = new Pipeline().process("x");
        assertEquals("out:x", result);
        assertEquals(List.of(
                "before1:x", // priority 1, arg only
                "before2:x", // priority 10, self + arg
                "process:x", // original body
                "after:out:x" // self + returned value
        ), CallLog.entries());
    }
}
