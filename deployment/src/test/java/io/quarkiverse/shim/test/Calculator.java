package io.quarkiverse.shim.test;

/**
 * Target for @ShimAround, paramTypes overload selection, and widenAccess.
 */
public class Calculator {

    private int base = 100;

    public int add(int a, int b) {
        return a + b;
    }

    public String format(int v) {
        return "int:" + v;
    }

    public String format(String v) {
        return "str:" + v;
    }

    public static String tag(String s) {
        return "<" + s + ">";
    }

    public void note(String msg) {
        CallLog.record("note:" + msg);
    }

    private int secret() {
        return 7;
    }
}
