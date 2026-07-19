package io.quarkiverse.shim.test;

import io.quarkiverse.shim.AnnotationConflict;
import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAnnotate;
import io.quarkiverse.shim.ShimReplace;

@Shim(AnnotatedTarget.class)
@ShimAnnotate
@AttachedAnnotation(value = "class", number = 1, type = String.class, level = AttachedAnnotation.Level.HIGH, nested = @AttachedAnnotation.Nested("class-nested"), tags = {
        "one", "two" })
public class AnnotatedTargetShim {

    @ShimAnnotate(target = "label")
    @AttachedAnnotation(value = "field", number = 2, type = Long.class, level = AttachedAnnotation.Level.LOW, nested = @AttachedAnnotation.Nested("field-nested"), tags = {
            "field-tag" })
    Object fieldAnnotations;

    @ShimReplace(method = "format", paramTypes = String.class)
    @ShimAnnotate(target = "format", paramTypes = String.class)
    @AttachedAnnotation(value = "method", number = 3, type = Integer.class, level = AttachedAnnotation.Level.HIGH, nested = @AttachedAnnotation.Nested("method-nested"), tags = {
            "method-a", "method-b" })
    public static String format(AnnotatedTarget self, String value) {
        return "patched:" + value;
    }

    @ShimAnnotate(target = "format", paramTypes = int.class)
    @AttachedAnnotation(value = "incoming", number = 5, type = Float.class, level = AttachedAnnotation.Level.HIGH, nested = @AttachedAnnotation.Nested("incoming-nested"), tags = "incoming-tag")
    void existingAnnotationIsReplaced() {
    }

    @ShimAnnotate(target = "keep", onConflict = AnnotationConflict.KEEP)
    @AttachedAnnotation(value = "ignored", number = 7, type = Byte.class, level = AttachedAnnotation.Level.HIGH, nested = @AttachedAnnotation.Nested("ignored-nested"), tags = "ignored-tag")
    void keepExistingAnnotation() {
    }
}
