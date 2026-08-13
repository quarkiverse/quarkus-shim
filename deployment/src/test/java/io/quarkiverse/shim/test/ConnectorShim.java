package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAround;
import io.quarkiverse.shim.ShimCall;

@Shim(Connector.class)
public class ConnectorShim {

    @ShimAround(method = "connect")
    public static String connect(ShimCall<String> original, Connector self, String url, int timeoutMs) {
        // the original arguments, then the same call with replacements
        return original.proceed() + "|" + original.proceed(url, Math.max(timeoutMs, 5_000));
    }
}
