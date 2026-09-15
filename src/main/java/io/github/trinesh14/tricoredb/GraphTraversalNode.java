package io.github.trinesh14.tricoredb;

import java.util.List;
import java.util.Map;

/** A node reached by a traversal, with the hop count at which it was found. */
public record GraphTraversalNode(
        String id, long depth, List<String> labels, Map<String, Object> properties) {

    static GraphTraversalNode from(Map<String, Object> m) {
        return new GraphTraversalNode(
                Wire.str(m, "id"),
                Wire.num(m, "depth"),
                Wire.strings(m, "labels"),
                Wire.obj(m, "properties"));
    }
}
