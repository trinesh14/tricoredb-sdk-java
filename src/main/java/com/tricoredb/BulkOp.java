package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/** One operation of {@code documentBulkWrite}. */
public final class BulkOp {

    private final Map<String, Object> wire;

    private BulkOp(String variant, Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(variant, body);
        this.wire = m;
    }

    /**
     * Insert a document.
     *
     * @param id {@code null} to use the document's own {@code _id}, or a generated one
     */
    public static BulkOp insert(String id, Map<String, Object> document) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("document", notNull(document, "document"));
        return new BulkOp("Insert", m);
    }

    /** Insert a document under its own {@code _id}, or a generated one. */
    public static BulkOp insert(Map<String, Object> document) {
        return insert(null, document);
    }

    /**
     * Apply an update to one document by id.
     *
     * @param expectedVersion apply only if the document is still at this version
     *                        (a versioned collection only), or {@code null}
     */
    public static BulkOp updateOne(String id, DocumentUpdate update, boolean upsert, Long expectedVersion) {
        Map<String, Object> m = id(id);
        m.put("update", notNull(update, "update").wire());
        m.put("upsert", upsert);
        m.put("expected_version", expectedVersion);
        return new BulkOp("UpdateOne", m);
    }

    public static BulkOp updateOne(String id, DocumentUpdate update) {
        return updateOne(id, update, false, null);
    }

    /** Swap one document's whole body. */
    public static BulkOp replace(String id, Map<String, Object> document, boolean upsert, Long expectedVersion) {
        Map<String, Object> m = id(id);
        m.put("document", notNull(document, "document"));
        m.put("upsert", upsert);
        m.put("expected_version", expectedVersion);
        return new BulkOp("Replace", m);
    }

    public static BulkOp replace(String id, Map<String, Object> document) {
        return replace(id, document, false, null);
    }

    /** Delete one document by id. */
    public static BulkOp delete(String id, Long expectedVersion) {
        Map<String, Object> m = id(id);
        m.put("expected_version", expectedVersion);
        return new BulkOp("Delete", m);
    }

    public static BulkOp delete(String id) {
        return delete(id, null);
    }

    private static Map<String, Object> id(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be null or empty");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return m;
    }

    private static <T> T notNull(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        return value;
    }

    Map<String, Object> wire() {
        return wire;
    }

    @Override
    public String toString() {
        return "BulkOp" + Json.encode(wire);
    }
}
