package io.github.trinesh14.tricoredb;

import java.util.Map;

/** A graph edge, directed {@code from} → {@code to}. */
public record GraphEdge(
        String id, String from, String to, String label, Map<String, Object> properties) {

    static GraphEdge from(Map<String, Object> m) {
        return new GraphEdge(
                Wire.str(m, "id"),
                Wire.str(m, "from"),
                Wire.str(m, "to"),
                Wire.str(m, "label"),
                Wire.obj(m, "properties"));
    }
}
