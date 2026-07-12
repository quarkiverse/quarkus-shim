package io.quarkiverse.shim.it;

/**
 * Target for overload pinning (only the int overload is patched) and for
 * config gating (a second, named shim on the String overload is disabled in
 * application.properties).
 */
public class Formatter {

    public static String format(int value) {
        return "int:" + value;
    }

    public static String format(String value) {
        return "str:" + value;
    }
}
