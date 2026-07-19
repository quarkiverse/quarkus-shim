package io.quarkiverse.shim;

/** Controls how {@link ShimAnnotate} handles an annotation already present on the target element. */
public enum AnnotationConflict {

    /** Remove the target annotation and attach the annotation declared by the shim. */
    REPLACE,

    /** Keep the target annotation and ignore the annotation declared by the shim. */
    KEEP,

    /** Fail augmentation when the target already declares the annotation. */
    FAIL
}
