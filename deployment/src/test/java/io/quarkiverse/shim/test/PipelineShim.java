package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimBefore;
import io.quarkiverse.shim.ShimPriority;

@Shim(Pipeline.class)
public class PipelineShim {

    // higher priority value -> runs later; takes self + the argument
    @ShimBefore(method = "process")
    @ShimPriority(10)
    public static void before2(Pipeline self, String input) {
        CallLog.record("before2:" + input);
    }

    // lower priority value -> runs first; takes only the argument
    @ShimBefore(method = "process")
    @ShimPriority(1)
    public static void before1(String input) {
        CallLog.record("before1:" + input);
    }

    // receives self and the value about to be returned
    @ShimAfter(method = "process")
    public static void after(Pipeline self, String returned) {
        CallLog.record("after:" + returned);
    }
}
