package com.tricoredb;

import java.util.Map;

/**
 * How many records a graph batch wrote or copied.
 *
 * @param nodes nodes
 * @param edges edges
 */
public record GraphBatchResult(long nodes, long edges) {

    static GraphBatchResult from(Map<String, Object> m) {
        return new GraphBatchResult(Wire.num(m, "nodes"), Wire.num(m, "edges"));
    }
}
