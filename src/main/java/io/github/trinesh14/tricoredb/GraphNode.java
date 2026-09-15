package io.github.trinesh14.tricoredb;

import java.util.List;
import java.util.Map;

/** A graph node: its id, labels, and properties. */
public record GraphNode(String id, List<String> labels, Map<String, Object> properties) {

    static GraphNode from(Map<String, Object> m) {
        return new GraphNode(Wire.str(m, "id"), Wire.strings(m, "labels"), Wire.obj(m, "properties"));
    }
}
