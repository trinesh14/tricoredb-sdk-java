package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * The partitions of a vector collection.
 *
 * @param field         the metadata field partitioned on
 * @param partitions    each distinct value with its count
 * @param unpartitioned vectors that carry no value for the field
 */
public record VectorPartitions(String field, List<VectorPartition> partitions, long unpartitioned) {

    static VectorPartitions from(Map<String, Object> m) {
        return new VectorPartitions(
                Wire.str(m, "field"),
                Wire.records(m, "partitions", VectorPartition::from),
                Wire.num(m, "unpartitioned"));
    }
}
