package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One read-only source contributing to an LLM context bundle.
 *
 * <p>Constructed through the factories rather than freely, so a source is always
 * one of the two shapes the server implements.
 */
public final class LlmSource {

    private final Map<String, Object> wire;

    private LlmSource(Map<String, Object> wire) {
        this.wire = wire;
    }

    /** A SQL {@code SELECT}. Requires the caller to hold SQL read permission. */
    public static LlmSource sql(String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Sql", body);
        return new LlmSource(m);
    }

    /** A document find. Requires the caller to hold document read permission. */
    public static LlmSource documentFind(String collection, DocumentFilter filter, Integer limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", collection);
        body.put("filter", (filter == null ? DocumentFilter.all() : filter).wire());
        body.put("limit", limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("DocumentFind", body);
        return new LlmSource(m);
    }

    public static LlmSource documentFind(String collection) {
        return documentFind(collection, null, null);
    }

    Map<String, Object> wire() {
        return wire;
    }
}
