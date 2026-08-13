package io.quarkiverse.shim.test;

/** Target for ShimCall.proceed(args): the shim clamps a bad timeout. */
public class Connector {

    public String connect(String url, int timeoutMs) {
        return url + "@" + timeoutMs;
    }
}
