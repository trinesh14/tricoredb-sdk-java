package com.tricoredb;

import java.util.Map;

/** A collection index: its name, the top-level field it covers, and uniqueness. */
public record DocumentIndex(String indexName, String field, boolean unique) {

    static DocumentIndex from(Map<String, Object> m) {
        return new DocumentIndex(
                Wire.str(m, "index_name"), Wire.str(m, "field"), Wire.bool(m, "unique"));
    }
}
