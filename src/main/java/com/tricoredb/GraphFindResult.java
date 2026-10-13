package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * The nodes a property lookup found.
 *
 * @param nodes     the matching nodes, in id order
 * @param plan      how the request was answered: an index, the label index, or a
 *                  scan
 * @param truncated whether the limit cut the result short
 */
public record GraphFindResult(List<GraphNode> nodes, String plan, boolean truncated) {

    static GraphFindResult from(Map<String, Object> m) {
        return new GraphFindResult(
                Wire.records(m, "nodes", GraphNode::from),
                Wire.str(m, "plan"),
                Wire.bool(m, "truncated"));
    }
}
