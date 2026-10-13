package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * One cycle in a graph.
 *
 * @param nodes node ids around the cycle
 * @param edges edge ids around the cycle
 */
public record GraphCyclePath(List<String> nodes, List<String> edges) {

    static GraphCyclePath from(Map<String, Object> m) {
        return new GraphCyclePath(Wire.strings(m, "nodes"), Wire.strings(m, "edges"));
    }
}
