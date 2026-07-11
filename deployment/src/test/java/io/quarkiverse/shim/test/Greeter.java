package io.quarkiverse.shim.test;

/**
 * Stands in for a third-party class the application cannot modify at the
 * source level.
 */
public class Greeter {

    public final String label = "g1";

    private int greetCount;

    public String greet(String name) {
        return "Hello " + name;
    }

    public String shout() {
        return "hey";
    }

    public static int answer() {
        return 41;
    }

    public void touch() {
        CallLog.record("touch");
    }

    public String maybeTouch(boolean doIt) {
        if (doIt) {
            CallLog.record("touch");
            return "touched";
        }
        return "skipped";
    }

    public int greetCount() {
        return greetCount;
    }

    private String decorate(String value) {
        return "<" + value + ">";
    }
}
