package io.quarkiverse.shim.it;

/**
 * Target for definalize + constructor after-hook: 'name' is final in source
 * but stripped of the modifier at build time so the shim can rewrite it.
 */
public class Widget {

    private final String name;

    public Widget(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }
}
