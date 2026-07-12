package io.quarkiverse.shim.it;

/**
 * Target for @ShimReplace (instance + static) and @ShimAround, with private
 * members reached via ShimFields/ShimMethods.
 */
public class Greeter {

    private int greetCount;

    public String greet(String name) {
        return "Hello " + name;
    }

    public static int answer() {
        return -1;
    }

    public String shout(String name) {
        return "hi " + name;
    }

    private String decorate(String message) {
        return "[" + message + "]";
    }
}
