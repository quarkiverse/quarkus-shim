package io.quarkiverse.shim.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShimVersionGateTest {

    private static boolean matches(String versions, String version) {
        return ShimVersionGate.matches("com.acme.FooShim", versions, version);
    }

    @Test
    void halfOpenRangeIncludesLowerBoundOnly() {
        assertTrue(matches("[1.2,1.5)", "1.2"));
        assertTrue(matches("[1.2,1.5)", "1.3.7"));
        assertTrue(matches("[1.2,1.5)", "1.4.9"));
        assertFalse(matches("[1.2,1.5)", "1.5"));
        assertFalse(matches("[1.2,1.5)", "1.1.9"));
    }

    @Test
    void unboundedRanges() {
        assertTrue(matches("(,2.0)", "1.9.9"));
        assertFalse(matches("(,2.0)", "2.0"));
        assertTrue(matches("[2.0,)", "7.1"));
        assertFalse(matches("[2.0,)", "1.999"));
    }

    @Test
    void bareVersionMeansExactlyThatVersion() {
        assertTrue(matches("1.4.2", "1.4.2"));
        assertFalse(matches("1.4.2", "1.4.3"));
        assertFalse(matches("1.4.2", "1.4.1"));
        assertTrue(matches("[1.4.2]", "1.4.2"));
        assertFalse(matches("[1.4.2]", "1.4.3"));
    }

    @Test
    void unionOfRanges() {
        assertTrue(matches("[1.2,1.3],[1.5,1.6]", "1.2.5"));
        assertTrue(matches("[1.2,1.3],[1.5,1.6]", "1.5"));
        assertFalse(matches("[1.2,1.3],[1.5,1.6]", "1.4"));
        assertFalse(matches("[1.2,1.3],[1.5,1.6]", "1.7"));
    }

    /** Segments compare numerically: a lexical comparison would put 1.10 below 1.9. */
    @Test
    void versionSegmentsCompareNumerically() {
        assertTrue(matches("[1.9,1.11)", "1.10"));
        assertFalse(matches("[1.9,1.11)", "1.11"));
        assertTrue(matches("[1.9,1.11)", "1.9"));
    }

    /** A pre-release sorts below the release it leads up to. */
    @Test
    void snapshotsAndQualifiersSortBeforeTheRelease() {
        assertTrue(matches("[1.0,2.0)", "1.5-SNAPSHOT"));
        assertTrue(matches("(,2.0)", "2.0-SNAPSHOT"));
        assertFalse(matches("[2.0,)", "2.0-alpha1"));
        assertTrue(matches("[2.0-alpha1,)", "2.0"));
    }

    @Test
    void malformedRangeFailsTheBuildWithTheShimNamed() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> matches("[1.2,1.5", "1.3"));
        assertTrue(failure.getMessage().contains("com.acme.FooShim"),
                "unexpected message: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("invalid version range"),
                "unexpected message: " + failure.getMessage());
    }

    @Test
    void decisionCarriesTheDiagnosticsShownToTheUser() {
        ShimVersionGate.Decision retired = ShimVersionGate.Decision.retired("com.acme:engine", "2.0",
                "com.acme:engine is at 2.0, outside the pinned range '[1.2,1.5)'");
        assertFalse(retired.applies());
        assertEquals("com.acme:engine", retired.coordinates());
        assertEquals("2.0", retired.actualVersion());
        assertTrue(ShimVersionGate.Decision.applied().applies());
    }

    @Test
    void aQualifierSortsBelowTheReleaseItPrecedes() {
        // documented behaviour: a pre-release is inside the range that ends at
        // its release, and outside the range that starts there
        assertTrue(matches("[1.2,1.5)", "1.5-SNAPSHOT"));
        assertFalse(matches("[1.5,)", "1.5-SNAPSHOT"));
        assertTrue(matches("[1.5,)", "1.5"));
    }

    @Test
    void aBareVersionMeansExactlyThatVersion() {
        assertTrue(matches("1.4.2", "1.4.2"));
        assertFalse(matches("1.4.2", "1.4.3"));
        assertFalse(matches("1.4.2", "1.4.2-SNAPSHOT"));
    }

    @Test
    void anInvalidRangeIsReportedAgainstTheShimThatDeclaredIt() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> matches("[1.2", "1.3"));
        assertTrue(failure.getMessage().contains("com.acme.FooShim"), failure.getMessage());
        assertTrue(failure.getMessage().contains("invalid version range"), failure.getMessage());
    }
}
