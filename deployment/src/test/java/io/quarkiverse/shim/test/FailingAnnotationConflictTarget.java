package io.quarkiverse.shim.test;

@AttachedAnnotation(value = "existing", number = 1, type = Object.class, level = AttachedAnnotation.Level.LOW, nested = @AttachedAnnotation.Nested("existing"), tags = "existing")
public class FailingAnnotationConflictTarget {
}
