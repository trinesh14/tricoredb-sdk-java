package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One text of {@code vectorUpsertText}: the server embeds it and stores the vector.
 *
 * @param metadata stored with the vector, or {@code null} for none
 */
public record VectorText(String id, String text, Map<String, Object> metadata) {

    public VectorText {
        if (id == null || id.isEmpty() || text == null) {
            throw new IllegalArgumentException("a vector text needs an id and a text");
        }
    }

    public VectorText(String id, String text) {
        this(id, text, null);
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("text", text);
        if (metadata != null) {
            m.put("metadata", metadata);
        }
        return m;
    }
}
