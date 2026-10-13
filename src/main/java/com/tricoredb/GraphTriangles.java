package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * A graph's triangles and clustering.
 *
 * @param triangles         triangles in the graph
 * @param transitivity      the global clustering coefficient
 * @param averageClustering the mean local clustering coefficient
 * @param nodes             the listed nodes
 */
public record GraphTriangles(
        long triangles,
        double transitivity,
        double averageClustering,
        List<GraphNodeTriangles> nodes) {

    static GraphTriangles from(Map<String, Object> m) {
        return new GraphTriangles(
                Wire.num(m, "triangles"),
                Wire.dbl(m, "transitivity"),
                Wire.dbl(m, "average_clustering"),
                Wire.records(m, "nodes", GraphNodeTriangles::from));
    }
}
