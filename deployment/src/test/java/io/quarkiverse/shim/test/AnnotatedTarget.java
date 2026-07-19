package io.quarkiverse.shim.test;

@AttachedAnnotation(value = "old-class", number = 10, type = Object.class, level = AttachedAnnotation.Level.LOW, nested = @AttachedAnnotation.Nested("old-class-nested"), tags = "old-class-tag")
public class AnnotatedTarget {

    @AttachedAnnotation(value = "old-field", number = 11, type = Object.class, level = AttachedAnnotation.Level.LOW, nested = @AttachedAnnotation.Nested("old-field-nested"), tags = "old-field-tag")
    public String label;

    public String format(String value) {
        return "original:" + value;
    }

    @AttachedAnnotation(value = "existing", number = 4, type = Double.class, level = AttachedAnnotation.Level.LOW, nested = @AttachedAnnotation.Nested("existing-nested"), tags = "existing-tag")
    public String format(int value) {
        return "number:" + value;
    }

    @AttachedAnnotation(value = "kept", number = 6, type = Short.class, level = AttachedAnnotation.Level.LOW, nested = @AttachedAnnotation.Nested("kept-nested"), tags = "kept-tag")
    public void keep() {
    }
}
