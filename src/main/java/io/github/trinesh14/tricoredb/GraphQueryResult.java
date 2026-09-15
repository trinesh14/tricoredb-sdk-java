package io.github.trinesh14.tricoredb;

import java.util.List;

/**
 * Rows from a read-only Cypher-subset query.
 *
 * Values are decoded JSON ({@code String}, {@code Long}, {@code Double},
 * {@code Boolean}, {@code List}, {@code Map}, or {@code null}) rather than
 * stringified, so a query returning a node's properties hands back a usable
 * map instead of its {@code toString}.
 */
public record GraphQueryResult(
        String graph, List<String> columns, List<List<Object>> rows, boolean truncated) {

    /** Convenience: the number of rows returned. */
    public int count() {
        return rows.size();
    }
}
