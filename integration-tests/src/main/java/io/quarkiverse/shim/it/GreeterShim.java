package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAround;
import io.quarkiverse.shim.ShimCall;
import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimMethods;
import io.quarkiverse.shim.ShimReplace;

@Shim(Greeter.class)
public class GreeterShim {

    // replaces the instance method, using cached reflection for the private
    // field and private method — exercises native-image reflection registration
    @ShimReplace(method = "greet")
    public static String greet(Greeter self, String name) {
        int count = ShimFields.<Integer> get(self, "greetCount") + 1;
        ShimFields.set(self, "greetCount", count);
        return ShimMethods.invoke(self, "decorate", "Patched " + name + " #" + count);
    }

    @ShimReplace(method = "answer")
    public static int answer() {
        return 42;
    }

    @ShimAround(method = "shout")
    public static String shout(ShimCall<String> original, Greeter self, String name) {
        return original.proceed().toUpperCase() + "!";
    }
}
