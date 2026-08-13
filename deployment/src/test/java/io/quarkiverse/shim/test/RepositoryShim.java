package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimBefore;
import io.quarkiverse.shim.ShimReplace;

@Shim(Repository.class)
public class RepositoryShim {

    // targets a non-generic method on a class full of generic ones
    @ShimReplace(method = "describe")
    public static String describe(Repository<?, ?> self) {
        return "patched";
    }

    // apply(Object) exists twice: the real method and javac's bridge
    @ShimBefore(method = "apply")
    public static void countApply() {
        CallLog.record("apply");
    }
}
