package io.quarkiverse.shim.test;

/**
 * Target for @ShimPriority ordering and before/after argument + return access.
 */
public class Pipeline {

    public String process(String input) {
        CallLog.record("process:" + input);
        return "out:" + input;
    }
}
