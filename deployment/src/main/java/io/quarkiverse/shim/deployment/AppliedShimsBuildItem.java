package io.quarkiverse.shim.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Carries the applied shims for diagnostics (startup log, Dev UI). Each row is
 * a {@code {target, method, kind, hook, shim}} map so it can be fed directly to
 * the Dev UI table and rendered as build-time data.
 */
public final class AppliedShimsBuildItem extends SimpleBuildItem {

    private final List<Map<String, String>> rows;

    public AppliedShimsBuildItem(List<Map<String, String>> rows) {
        this.rows = List.copyOf(rows);
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public List<String> getDescriptions() {
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : rows) {
            out.add(row.get("target") + "#" + row.get("method") + " [" + row.get("kind") + "] <- " + row.get("hook"));
        }
        return out;
    }
}
