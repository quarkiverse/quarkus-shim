package io.quarkiverse.shim.test;

import io.quarkiverse.shim.Shim;
import io.quarkiverse.shim.ShimAfter;
import io.quarkiverse.shim.ShimBefore;
import io.quarkiverse.shim.ShimFields;

@Shim(value = Widget.class, definalize = { "name" })
public class WidgetShim {

    // runs at constructor entry, before super(); no 'self' allowed here
    @ShimBefore(method = "<init>")
    public static void beforeConstruct() {
        CallLog.record("before-ctor");
    }

    // runs after construction with the fully initialized instance
    @ShimAfter(method = "<init>")
    public static void afterConstruct(Widget self) {
        CallLog.record("after-ctor:" + self.name());
        ShimFields.set(self, "size", 99); // fix up a bad default post-construction
        // 'name' is declared final but listed in definalize, so this is an
        // ordinary field write - no reflective final-field mutation involved
        ShimFields.set(self, "name", self.name() + "!");
    }
}
