package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimBefore;
import io.quarkiverse.shim.ShimPriority;

@Shim(Task.class)
public class TaskShim {

    // lower priority runs first; takes self + the target's argument
    @ShimBefore(method = "run")
    @ShimPriority(1)
    public static void first(Task self, String input) {
        self.log().add("before1:" + input);
    }

    @ShimBefore(method = "run")
    @ShimPriority(2)
    public static void second(Task self) {
        self.log().add("before2");
    }

    // receives self and the value about to be returned
    @ShimAfter(method = "run")
    public static void after(Task self, String returned) {
        self.log().add("after:" + returned);
    }
}
