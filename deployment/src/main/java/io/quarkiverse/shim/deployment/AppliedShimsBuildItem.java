package io.quarkiverse.shim.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Carries the applied shims for diagnostics (startup log, Dev UI). Each row is
 * a {@code {target, method, kind, hook, shim}} map so it can be fed directly to
 * the Dev UI table and rendered as build-time data.
 * <p>
 * Shims that were pinned to a dependency version and did not apply are carried
 * alongside as {@code {shim, target, dependency, version, reason}} rows.
 */
public final class AppliedShimsBuildItem extends SimpleBuildItem {

    private final List<Map<String, String>> rows;
    private final List<Map<String, String>> retiredRows;

    public AppliedShimsBuildItem(List<Map<String, String>> rows, List<Map<String, String>> retiredRows) {
        this.rows = List.copyOf(rows);
        this.retiredRows = List.copyOf(retiredRows);
    }

    public List<Map<String, String>> getRows() {
        return rows;
    }

    public List<Map<String, String>> getRetiredRows() {
        return retiredRows;
    }

    public List<String> getDescriptions() {
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : rows) {
            out.add(row.get("target") + "#" + row.get("method") + " [" + row.get("kind") + "] <- " + row.get("hook"));
        }
        return out;
    }

    public List<String> getRetiredDescriptions() {
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : retiredRows) {
            out.add(row.get("shim") + " -> " + row.get("target") + ": " + row.get("reason"));
        }
        return out;
    }
}
