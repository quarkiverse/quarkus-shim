package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimBefore;
import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimMethods;
import io.quarkiverse.shim.ShimReplace;

@Shim(Greeter.class)
public class GreeterShim {

    // reads and writes the private field 'greetCount' and invokes the private
    // method 'decorate' on the target instance
    @ShimReplace(method = "greet")
    public static String greet(Greeter self, String name) {
        int count = ShimFields.<Integer> get(self, "greetCount") + 1;
        ShimFields.set(self, "greetCount", count);
        return ShimMethods.invoke(self, "decorate", "Patched " + name + " " + count);
    }

    // Object-typed 'self' for targets whose type is not visible in source
    @ShimReplace(method = "shout")
    public static String shout(Object self) {
        return "HEY from " + self.getClass().getSimpleName();
    }

    @ShimReplace(method = "answer")
    public static int answer() {
        return 42;
    }

    @ShimBefore(method = "touch")
    public static void beforeTouch(Greeter self) {
        CallLog.record("before:" + self.label);
    }

    @ShimAfter(method = "touch")
    public static void afterTouch(Object self) {
        CallLog.record("after:" + ((Greeter) self).label);
    }

    @ShimAfter(method = "maybeTouch")
    public static void afterMaybeTouch() {
        CallLog.record("after-maybe");
    }
}
