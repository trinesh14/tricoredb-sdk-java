package com.tricoredb;

import java.util.Map;

/**
 * How many records a graph batch removed.
 *
 * @param nodesRemoved nodes removed
 * @param edgesRemoved edges removed, including detached ones
 */
public record GraphRemoveResult(long nodesRemoved, long edgesRemoved) {

    static GraphRemoveResult from(Map<String, Object> m) {
        return new GraphRemoveResult(Wire.num(m, "nodes_removed"), Wire.num(m, "edges_removed"));
    }
}
