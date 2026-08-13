package io.quarkiverse.shim.test;

import io.quarkiverse.shim.AnnotationConflict;
import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAnnotate;

@Shim(KeepTarget.class)
@ShimAnnotate
@ClassRetainedAnnotation("on-class")
public class KeepTargetShim {

    /** The target already declares one, so KEEP must leave it alone. */
    @ShimAnnotate(target = "annotated", onConflict = AnnotationConflict.KEEP)
    @KeepMarker("from-shim")
    void annotated() {
    }

    /** No conflict, so KEEP still attaches. */
    @ShimAnnotate(target = "bare", onConflict = AnnotationConflict.KEEP)
    @KeepMarker("from-shim")
    void bare() {
    }
}
