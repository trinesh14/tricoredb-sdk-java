package io.github.trinesh14.tricoredb;

import java.util.Map;

/** Approximate collection statistics collected by {@code documentAnalyze}. */
public record DocumentStats(String collection, long documentCount, long indexedFields) {

    static DocumentStats from(Map<String, Object> m) {
        return new DocumentStats(
                Wire.str(m, "analyzed"),
                Wire.num(m, "document_count"),
                Wire.num(m, "indexed_fields"));
    }
}
