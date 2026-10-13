package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The optional settings of a vector search. A setting that is never called is
 * left to the server.
 *
 * <pre>{@code
 * new VectorSearchOptions().filter(Map.of("tier", "gold")).minScore(0.2).exact(true)
 * }</pre>
 */
public final class VectorSearchOptions {

    private final Map<String, Object> wire = new LinkedHashMap<>();

    /** Exact-equality metadata predicates on top-level fields. */
    public VectorSearchOptions filter(Map<String, Object> filter) {
        return put("filter", filter);
    }

    /** Drop hits scoring below this. */
    public VectorSearchOptions minScore(double minScore) {
        return put("min_score", minScore);
    }

    /** Have the server send each hit's stored vector. */
    public VectorSearchOptions includeVectors(boolean includeVectors) {
        return put("include_vectors", includeVectors);
    }

    /** Skip this many ranked hits first. */
    public VectorSearchOptions offset(int offset) {
        return put("offset", offset);
    }

    /** Whether each hit carries its metadata; the server's default is true. */
    public VectorSearchOptions includeMetadata(boolean includeMetadata) {
        return put("include_metadata", includeMetadata);
    }

    /** Only hits within this distance of the query, in the metric's own units. */
    public VectorSearchOptions maxDistance(double maxDistance) {
        return put("max_distance", maxDistance);
    }

    /** The HNSW beam width for this query. */
    public VectorSearchOptions efSearch(int efSearch) {
        return put("ef_search", efSearch);
    }

    /** Score every stored vector instead of walking the ANN index. */
    public VectorSearchOptions exact(boolean exact) {
        return put("exact", exact);
    }

    /** Search this named vector rather than the collection's default one. */
    public VectorSearchOptions using(String using) {
        return put("using", using);
    }

    /** Confine the search to one tenant's vectors. */
    public VectorSearchOptions tenant(String tenant) {
        return put("tenant", tenant);
    }

    /** Confine the search to one namespace of the collection. */
    public VectorSearchOptions namespace(String namespace) {
        return put("namespace", namespace);
    }

    private VectorSearchOptions put(String key, Object value) {
        if (value == null) {
            wire.remove(key);
        } else {
            wire.put(key, value);
        }
        return this;
    }

    void addTo(Map<String, Object> body) {
        body.putAll(wire);
    }
}
