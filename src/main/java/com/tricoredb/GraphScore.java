package com.tricoredb;

import java.util.Map;

/**
 * A node with a score.
 *
 * @param id    the node id
 * @param score its score
 */
public record GraphScore(String id, double score) {

    static GraphScore from(Map<String, Object> m) {
        return new GraphScore(Wire.str(m, "id"), Wire.dbl(m, "score"));
    }
}
