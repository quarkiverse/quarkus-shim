package io.quarkiverse.shim.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.AnnotatedElement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;

public class AnnotationAttachmentTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(AnnotatedTarget.class, AnnotatedTargetShim.class,
                    AttachedAnnotation.class, AttachedAnnotation.Nested.class, AttachedAnnotation.Level.class));

    @Test
    void attachesAnnotationsAndPreservesTheirValues() throws Exception {
        assertAnnotation(AnnotatedTarget.class, "class", 1, String.class,
                AttachedAnnotation.Level.HIGH, "class-nested", "one", "two");
        assertAnnotation(AnnotatedTarget.class.getDeclaredField("label"), "field", 2, Long.class,
                AttachedAnnotation.Level.LOW, "field-nested", "field-tag");
        assertAnnotation(AnnotatedTarget.class.getDeclaredMethod("format", String.class), "method", 3, Integer.class,
                AttachedAnnotation.Level.HIGH, "method-nested", "method-a", "method-b");
    }

    @Test
    void replacesExistingTargetAnnotationsByDefault() throws Exception {
        assertAnnotation(AnnotatedTarget.class.getDeclaredMethod("format", int.class), "incoming", 5, Float.class,
                AttachedAnnotation.Level.HIGH, "incoming-nested", "incoming-tag");
    }

    @Test
    void canKeepAnExistingTargetAnnotation() throws Exception {
        assertAnnotation(AnnotatedTarget.class.getDeclaredMethod("keep"), "kept", 6, Short.class,
                AttachedAnnotation.Level.LOW, "kept-nested", "kept-tag");
    }

    @Test
    void annotationAttachmentComposesWithReplacement() {
        assertEquals("patched:value", new AnnotatedTarget().format("value"));
    }

    private static void assertAnnotation(AnnotatedElement element, String value, int number, Class<?> type,
            AttachedAnnotation.Level level, String nested, String... tags) {
        AttachedAnnotation annotation = element.getAnnotation(AttachedAnnotation.class);
        assertNotNull(annotation);
        assertEquals(value, annotation.value());
        assertEquals(number, annotation.number());
        assertEquals(type, annotation.type());
        assertEquals(level, annotation.level());
        assertEquals(nested, annotation.nested().value());
        assertArrayEquals(tags, annotation.tags());
    }
}
