package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.acme.internal.InternalApi;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Proves the same-package trick: a shim declared in the target's package can
 * patch a package-private class, call its package-private methods directly,
 * and reach private fields via ShimFields — all through the Quarkus
 * ClassLoader.
 */
public class SamePackageShimTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addPackage("com.acme.internal"));

    @Test
    void patchesPackagePrivateClass() {
        // original compute(3) would return 6;
        // patched: bump(secret=5) + input = 10 + 3
        assertEquals(13, InternalApi.callCompute(3));
    }
}
