package io.quarkiverse.shim.test;

import java.io.IOException;

/** Target for @ShimCatch/@ShimFinally: fails, succeeds, or fails in a way it handles itself. */
public class FlakyService {

    public String send(int mode) throws IOException {
        try {
            if (mode == 1) {
                throw new IOException("network");
            }
            if (mode == 2) {
                throw new IllegalArgumentException("handled-internally");
            }
            if (mode == 3) {
                throw new IllegalStateException("not-an-ioexception");
            }
            return "sent";
        } catch (IllegalArgumentException own) {
            CallLog.record("own-handler");
            return "recovered";
        }
    }
}
