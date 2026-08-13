package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * A target carrying type variables and a bridge method: the build must succeed,
 * and a hook must fire once per call whichever static type the caller holds.
 */
public class GenericTargetShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Repository.class, RepositoryShim.class, CallLog.class));

    @BeforeEach
    void clearLog() {
        CallLog.clear();
    }

    @Test
    void aGenericTargetDoesNotBreakAShimOnAnUnrelatedMethod() {
        assertEquals("patched", new Repository<String, String>().describe());
    }

    @Test
    void hooksFireOncePerCallThroughEitherTheMethodOrItsBridge() {
        Repository<String, String> repository = new Repository<>();

        repository.apply("a");
        assertEquals(List.of("apply"), CallLog.entries(), "called through the real method");

        CallLog.clear();
        Function<String, String> asFunction = repository;
        asFunction.apply("a");
        assertEquals(List.of("apply"), CallLog.entries(), "called through the bridge");
    }
}
