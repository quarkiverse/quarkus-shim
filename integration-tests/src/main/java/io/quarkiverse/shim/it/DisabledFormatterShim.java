package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimReplace;

/**
 * Disabled via quarkus.shim.instances."disabled-formatter".enabled=false in
 * application.properties — format(String) must keep its original behavior.
 */
@Shim(value = Formatter.class, name = "disabled-formatter")
public class DisabledFormatterShim {

    @ShimReplace(method = "format", paramTypes = { String.class })
    public static String format(String value) {
        return "should-never-appear:" + value;
    }
}
