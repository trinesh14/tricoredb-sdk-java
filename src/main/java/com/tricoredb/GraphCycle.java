package com.tricoredb;

import java.util.Map;

/**
 * Whether a graph contains a cycle.
 *
 * @param hasCycle whether there is one
 * @param directed whether edge direction was followed
 * @param cycle    one cycle as evidence, when there is one; {@code null} otherwise
 */
public record GraphCycle(boolean hasCycle, boolean directed, GraphCyclePath cycle) {

    static GraphCycle from(Map<String, Object> m) {
        return new GraphCycle(
                Wire.bool(m, "hasCycle"),
                Wire.bool(m, "directed"),
                Wire.optObj(m, "cycle") == null ? null : GraphCyclePath.from(Wire.optObj(m, "cycle")));
    }
}
