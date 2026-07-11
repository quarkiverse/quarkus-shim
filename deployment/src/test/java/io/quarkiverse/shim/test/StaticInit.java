package io.quarkiverse.shim.test;

/**
 * Target for static-initializer replacement.
 */
public class StaticInit {

    static String mode;

    static {
        CallLog.record("original-static-init");
        mode = "prod";
    }

    public static String mode() {
        return mode;
    }
}
