package io.quarkiverse.shim.test;

import io.quarkiverse.shim.AnnotationConflict;
import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAnnotate;

@Shim(FailingAnnotationConflictTarget.class)
@ShimAnnotate(onConflict = AnnotationConflict.FAIL)
@AttachedAnnotation(value = "incoming", number = 2, type = String.class, level = AttachedAnnotation.Level.HIGH, nested = @AttachedAnnotation.Nested("incoming"), tags = "incoming")
public class FailingAnnotationConflictShim {
}
