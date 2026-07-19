package io.quarkiverse.shim.deployment;

import java.util.List;

import io.quarkiverse.shim.AnnotationConflict;

/** A set of annotations and the target element to which they are attached. */
final class ShimAnnotationPatch {

    enum Kind {
        CLASS,
        METHOD,
        FIELD
    }

    final Kind kind;
    final String targetName;
    final String targetMethodDescriptor;
    final boolean matchParamsOnly;
    final List<ShimAnnotation> annotations;
    final AnnotationConflict onConflict;
    final String sourceRef;
    final String shimName;

    ShimAnnotationPatch(Kind kind, String targetName, String targetMethodDescriptor, boolean matchParamsOnly,
            List<ShimAnnotation> annotations, AnnotationConflict onConflict, String sourceRef, String shimName) {
        this.kind = kind;
        this.targetName = targetName;
        this.targetMethodDescriptor = targetMethodDescriptor;
        this.matchParamsOnly = matchParamsOnly;
        this.annotations = ShimAnnotation.copyOf(annotations);
        this.onConflict = onConflict;
        this.sourceRef = sourceRef;
        this.shimName = shimName;
    }

    boolean matchesMethod(String methodName, String methodDescriptor) {
        if (kind != Kind.METHOD || !targetName.equals(methodName)) {
            return false;
        }
        if (targetMethodDescriptor.isEmpty()) {
            return true;
        }
        if (matchParamsOnly) {
            return methodDescriptor.substring(0, methodDescriptor.indexOf(')') + 1)
                    .equals(targetMethodDescriptor);
        }
        return targetMethodDescriptor.equals(methodDescriptor);
    }

    boolean matchesField(String fieldName) {
        return kind == Kind.FIELD && targetName.equals(fieldName);
    }

    String describe(String targetClass) {
        String target = kind == Kind.CLASS ? targetClass : targetClass + "#" + targetName;
        return target + " [annotate-" + kind.name().toLowerCase() + "] <- " + sourceRef;
    }
}
