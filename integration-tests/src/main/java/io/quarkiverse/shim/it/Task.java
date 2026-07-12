package io.quarkiverse.shim.it;

import java.util.ArrayList;
import java.util.List;

/**
 * Target for @ShimBefore/@ShimAfter ordering via @ShimPriority; records every
 * step in a per-instance log so tests can assert execution order.
 */
public class Task {

    private final List<String> log = new ArrayList<>();

    public String run(String input) {
        log.add("body:" + input);
        return "result:" + input;
    }

    public List<String> log() {
        return log;
    }
}
