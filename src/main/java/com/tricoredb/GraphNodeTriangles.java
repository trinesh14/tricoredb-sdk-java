package com.tricoredb;

import java.util.Map;

/**
 * One node's triangle count.
 *
 * @param id         the node id
 * @param triangles  triangles through the node
 * @param clustering its local clustering coefficient
 */
public record GraphNodeTriangles(String id, long triangles, double clustering) {

    static GraphNodeTriangles from(Map<String, Object> m) {
        return new GraphNodeTriangles(Wire.str(m, "id"), Wire.num(m, "triangles"), Wire.dbl(m, "clustering"));
    }
}
