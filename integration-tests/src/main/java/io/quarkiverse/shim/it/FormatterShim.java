package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

@Shim(Formatter.class)
public class FormatterShim {

    // pinned to the int overload; format(String) keeps its original body
    @ShimReplace(method = "format", paramTypes = { int.class })
    public static String format(int value) {
        return "patched-int:" + value;
    }
}
