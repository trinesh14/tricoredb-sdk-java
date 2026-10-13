package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * One path between two nodes.
 *
 * @param nodes node ids along the path
 * @param edges edge ids along the path
 * @param hops  number of edges
 * @param cost  summed edge cost
 */
public record GraphRankedPath(List<String> nodes, List<String> edges, long hops, double cost) {

    static GraphRankedPath from(Map<String, Object> m) {
        return new GraphRankedPath(
                Wire.strings(m, "nodes"),
                Wire.strings(m, "edges"),
                Wire.num(m, "hops"),
                Wire.dbl(m, "cost"));
    }
}
