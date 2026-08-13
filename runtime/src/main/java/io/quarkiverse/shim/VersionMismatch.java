package io.quarkiverse.shim;

/**
 * Controls what happens when the dependency a {@link Shim} is pinned to does
 * not match {@link Shim#versions()} — typically because it has been upgraded
 * past the version the patch was written for.
 */
public enum VersionMismatch {

    /**
     * Leave the target class untouched and carry on with the build. A warning
     * is logged at build time and again at startup, so the now-obsolete shim
     * class can be deleted.
     */
    SKIP,

    /**
     * Fail augmentation. Use for patches that must be reviewed by a human
     * before the dependency moves — the build stops until the shim is updated,
     * re-pinned, or removed.
     */
    FAIL
}
