package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Two independently-named shims targeting one class. Their hooks, definalize
 * entries and priorities all merge into a single plan for that target.
 */
public class TwoShimsOnOneTargetTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Ledger2.class, Ledger2AuditShim.class,
                    Ledger2PatchShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void hooksFromBothShimsRunInPriorityOrder() {
        assertEquals("posted:x", new Ledger2("open").post("x"));
        assertEquals(List.of("audit:posted:x", "patch:posted:x"), CallLog.entries());
    }

    @Test
    void oneShimsDefinalizeEntryServesTheOtherShimsReplacement() {
        Ledger2 ledger = new Ledger2("open");
        assertEquals("closed", ledger.close());
        assertEquals("closed", ledger.state(), "the final field was rewritten in place");
    }
}
