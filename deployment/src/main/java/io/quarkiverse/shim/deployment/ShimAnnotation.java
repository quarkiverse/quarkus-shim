package io.quarkiverse.shim.deployment;

import java.util.ArrayList;
import java.util.List;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

/** An annotation copied from a shim template element to a target element. */
final class ShimAnnotation {

    final String descriptor;
    final boolean visible;
    private final AnnotationNode node;

    private ShimAnnotation(String descriptor, boolean visible, AnnotationNode node) {
        this.descriptor = descriptor;
        this.visible = visible;
        this.node = node;
    }

    static ShimAnnotation from(AnnotationInstance annotation) {
        String descriptor = descriptor(annotation.name().toString());
        AnnotationNode node = new AnnotationNode(descriptor);
        writeValues(node, annotation);
        node.visitEnd();
        return new ShimAnnotation(descriptor, annotation.runtimeVisible(), node);
    }

    void accept(AnnotationVisitor visitor) {
        node.accept(visitor);
    }

    private static void writeValues(AnnotationVisitor visitor, AnnotationInstance annotation) {
        for (AnnotationValue value : annotation.values()) {
            writeValue(visitor, value.name(), value);
        }
    }

    private static void writeValue(AnnotationVisitor visitor, String name, AnnotationValue value) {
        switch (value.kind()) {
            case NESTED -> {
                AnnotationInstance nested = value.asNested();
                AnnotationVisitor nestedVisitor = visitor.visitAnnotation(name,
                        descriptor(nested.name().toString()));
                writeValues(nestedVisitor, nested);
                nestedVisitor.visitEnd();
            }
            case ARRAY -> {
                AnnotationVisitor arrayVisitor = visitor.visitArray(name);
                for (AnnotationValue element : value.asArrayList()) {
                    writeValue(arrayVisitor, null, element);
                }
                arrayVisitor.visitEnd();
            }
            case ENUM -> visitor.visitEnum(name, descriptor(value.asEnumType().toString()), value.asEnum());
            case CLASS -> visitor.visit(name, Type.getType(ShimProcessor.typeDescriptor(value.asClass())));
            default -> visitor.visit(name, value.value());
        }
    }

    private static String descriptor(String className) {
        return "L" + className.replace('.', '/') + ";";
    }

    /** Creates a defensive copy suitable for combining patch lists. */
    static List<ShimAnnotation> copyOf(List<ShimAnnotation> annotations) {
        return List.copyOf(new ArrayList<>(annotations));
    }
}
