package io.quarkiverse.shim.it;

import java.io.IOException;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimCatch;
import io.quarkiverse.shim.ShimFields;
import io.quarkiverse.shim.ShimFinally;

@Shim(Uploader.class)
public class UploaderShim {

    @ShimCatch(method = "upload", exception = IOException.class)
    public static void onFailure(Uploader self, IOException failure) {
        record(self, "catch:" + failure.getMessage());
    }

    @ShimFinally(method = "upload")
    public static void always(Uploader self) {
        record(self, "finally");
    }

    private static void record(Uploader self, String entry) {
        ShimFields.<java.util.List<String>> get(self, "log").add(entry);
    }
}
