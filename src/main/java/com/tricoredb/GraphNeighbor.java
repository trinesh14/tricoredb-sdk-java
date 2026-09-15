package com.tricoredb;

import java.util.Map;

/**
 * One neighbour reached from a node.
 *
 * @param direction how the edge was traversed to reach {@code nodeId} — under
 *                  {@link GraphDirection#BOTH} this is what tells an outgoing
 *                  neighbour from an incoming one
 */
public record GraphNeighbor(String edgeId, String nodeId, String label, GraphDirection direction) {

    static GraphNeighbor from(Map<String, Object> m) {
        return new GraphNeighbor(
                Wire.str(m, "edge_id"),
                Wire.str(m, "node_id"),
                Wire.str(m, "label"),
                GraphDirection.fromWire(Wire.str(m, "direction")));
    }
}
