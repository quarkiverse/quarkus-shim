package io.quarkiverse.shim.test;

import java.util.ArrayList;
import java.util.List;

public final class CallLog {

    private static final List<String> ENTRIES = new ArrayList<>();

    private CallLog() {
    }

    public static void record(String entry) {
        ENTRIES.add(entry);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static List<String> entries() {
        return List.copyOf(ENTRIES);
    }
}
