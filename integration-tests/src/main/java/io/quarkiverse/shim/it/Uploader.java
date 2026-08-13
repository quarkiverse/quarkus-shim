package io.quarkiverse.shim.it;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Target for @ShimCatch/@ShimFinally and for ShimCall.proceed(args), exercised
 * in JVM and native mode alike.
 */
public class Uploader {

    private final List<String> log = new ArrayList<>();

    public String upload(String name, int retries) throws IOException {
        if ("boom".equals(name)) {
            throw new IOException("upload failed");
        }
        return name + "/" + retries;
    }

    public List<String> log() {
        return log;
    }
}
