package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * A graph's indexes and constraints.
 *
 * @param indexes     the indexes
 * @param constraints the constraints
 */
public record GraphSchemaObjects(List<GraphIndex> indexes, List<GraphConstraint> constraints) {

    static GraphSchemaObjects from(Map<String, Object> m) {
        return new GraphSchemaObjects(
                Wire.records(m, "indexes", GraphIndex::from),
                Wire.records(m, "constraints", GraphConstraint::from));
    }
}
