package io.github.trinesh14.tricoredb;

import java.util.Map;

/**
 * One search hit.
 *
 * @param score higher is closer for every metric — the server negates L2 so
 *              ranking is uniform, which means a raw score is comparable
 *              within one search but not across metrics
 */
public record VectorMatch(String id, double score, Map<String, Object> metadata) {

    static VectorMatch from(Map<String, Object> m) {
        return new VectorMatch(Wire.str(m, "id"), Wire.dbl(m, "score"), Wire.obj(m, "metadata"));
    }
}
