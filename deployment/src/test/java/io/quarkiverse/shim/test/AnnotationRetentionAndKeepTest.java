package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

/** onConflict = KEEP on both paths, and CLASS-retention attachment. */
public class AnnotationRetentionAndKeepTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(KeepTarget.class, KeepTargetShim.class,
                    KeepMarker.class, ClassRetainedAnnotation.class));

    @Test
    void keepLeavesAnExistingAnnotationInPlace() throws Exception {
        KeepMarker attached = KeepTarget.class.getDeclaredMethod("annotated")
                .getAnnotation(KeepMarker.class);
        assertNotNull(attached);
        assertEquals("original", attached.value(), "the target's own annotation must win under KEEP");
    }

    @Test
    void keepStillAttachesWhenThereIsNoConflict() throws Exception {
        KeepMarker attached = KeepTarget.class.getDeclaredMethod("bare")
                .getAnnotation(KeepMarker.class);
        assertNotNull(attached, "KEEP only defers to an existing annotation; here there was none");
        assertEquals("from-shim", attached.value());
    }

    @Test
    void classRetainedAnnotationsReachTheClassFileButNotReflection() throws Exception {
        assertNull(KeepTarget.class.getAnnotation(ClassRetainedAnnotation.class),
                "CLASS retention is invisible to reflection, by definition");

        // ... but it is present in the bytes the transformer produced
        String resource = KeepTarget.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = KeepTarget.class.getClassLoader().getResourceAsStream(resource)) {
            bytes = in.readAllBytes();
        }
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("ClassRetainedAnnotation"), "the annotation should be written to the class file");
    }
}
