package io.quarkiverse.shim.test;

/**
 * Target for constructor shimming.
 */
public class Widget {

    // compile-time constant: used by InvalidDefinalizeTest, must stay final
    static final String CONST = "c";

    private final String name;
    private int size;

    public Widget(String name) {
        this.name = name;
        this.size = 1;
        CallLog.record("ctor:" + name);
    }

    public String name() {
        return name;
    }

    public int size() {
        return size;
    }
}
