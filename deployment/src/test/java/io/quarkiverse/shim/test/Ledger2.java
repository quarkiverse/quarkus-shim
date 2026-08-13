package io.quarkiverse.shim.test;

/** Target for two simultaneously-enabled shims that merge into one plan. */
public class Ledger2 {

    /** Assigned in the constructor, so it carries no ConstantValue and can be definalized. */
    private final String state;

    public Ledger2(String state) {
        this.state = state;
    }

    public String post(String entry) {
        return "posted:" + entry;
    }

    public String state() {
        return state;
    }

    public String close() {
        return "open";
    }
}
