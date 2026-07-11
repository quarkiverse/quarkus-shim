package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAround;
import io.quarkiverse.shim.ShimCall;
import io.quarkiverse.shim.ShimReplace;

@Shim(value = Calculator.class, widenAccess = true)
public class CalculatorShim {

    // wrap an instance method: run the original, then transform its result
    @ShimAround(method = "add")
    public static int add(ShimCall<Integer> original, Calculator self, int a, int b) {
        return original.proceed() * 2;
    }

    // wrap a static method: no 'self'
    @ShimAround(method = "tag")
    public static String tag(ShimCall<String> original, String s) {
        return original.proceed().toUpperCase();
    }

    // wrap a void method: run code before and after the original
    @ShimAround(method = "note")
    public static void note(ShimCall<Void> original, Calculator self, String msg) {
        CallLog.record("around-before");
        original.proceed();
        CallLog.record("around-after");
    }

    // replace only the format(int) overload, selected via paramTypes
    @ShimReplace(method = "format", paramTypes = { int.class })
    public static String formatInt(Calculator self, int v) {
        return "INT[" + v + "]";
    }
}
