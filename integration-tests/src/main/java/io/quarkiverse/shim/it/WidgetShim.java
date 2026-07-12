package io.quarkiverse.shim.it;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimFields;

@Shim(value = Widget.class, definalize = { "name" })
public class WidgetShim {

    @ShimAfter(method = "<init>")
    public static void afterConstruct(Widget self) {
        ShimFields.set(self, "name", ShimFields.<String> get(self, "name") + "-patched");
    }
}
