package io.quarkiverse.shim.test;

/** Target for onConflict = KEEP with and without an existing annotation. */
public class KeepTarget {

    @KeepMarker("original")
    public String annotated() {
        return "annotated";
    }

    public String bare() {
        return "bare";
    }
}
