package io.quarkiverse.shim.test;

/** Overload set for descriptor()-based selection, plus a static initializer. */
public class Dispatcher {

    static String mode = "original";

    static {
        CallLog.record("original-static-init");
        mode = "original";
    }

    public String dispatch(int value) {
        return "int:" + value;
    }

    public String dispatch(long value) {
        return "long:" + value;
    }

    public String dispatch(String value) {
        return "str:" + value;
    }

    public static String mode() {
        return mode;
    }
}
