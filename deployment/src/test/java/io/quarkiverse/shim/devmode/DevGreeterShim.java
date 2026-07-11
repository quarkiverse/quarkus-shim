package io.quarkiverse.shim.devmode;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAround;
import io.quarkiverse.shim.ShimCall;

@Shim(DevGreeter.class)
public class DevGreeterShim {

    // exercises the invokedynamic/LambdaMetafactory path in the dev-mode classloader
    @ShimAround(method = "greet")
    public static String greet(ShimCall<String> original, DevGreeter self, String name) {
        return original.proceed().toUpperCase();
    }
}
