package com.acme.internal;

/**
 * Package-private class standing in for a library internal. Only reachable
 * from this package.
 */
class HiddenHelper {

    private int secret = 5;

    int bump(int by) { // package-private method
        return secret + by;
    }

    public int compute(int input) {
        return input * 2;
    }
}
