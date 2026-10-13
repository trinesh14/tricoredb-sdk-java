package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * An induced subgraph.
 *
 * @param nodes          the nodes reached
 * @param edges          every edge joining two of them
 * @param nodesTruncated whether the node limit cut the walk short
 * @param edgesTruncated whether the edge list was cut short
 */
public record GraphSubgraph(
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        boolean nodesTruncated,
        boolean edgesTruncated) {

    static GraphSubgraph from(Map<String, Object> m) {
        return new GraphSubgraph(
                Wire.records(m, "nodes", GraphNode::from),
                Wire.records(m, "edges", GraphEdge::from),
                Wire.bool(m, "nodes_truncated"),
                Wire.bool(m, "edges_truncated"));
    }
}
