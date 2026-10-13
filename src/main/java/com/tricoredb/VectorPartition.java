package com.tricoredb;

import java.util.Map;

/**
 * One partition of a vector collection.
 *
 * @param name  the partition value
 * @param count vectors in it
 */
public record VectorPartition(String name, long count) {

    static VectorPartition from(Map<String, Object> m) {
        return new VectorPartition(Wire.str(m, "name"), Wire.num(m, "count"));
    }
}
