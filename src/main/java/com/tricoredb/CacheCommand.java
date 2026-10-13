package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One command of a cache pipeline, transaction or script: a cache operation by
 * its server name, with its fields exactly as the server declares them.
 */
public final class CacheCommand {

    private final Object wire;

    private CacheCommand(Object wire) {
        this.wire = wire;
    }

    /**
     * Any cache operation, for example
     * {@code of("Incr", Map.of("namespace", "n", "key", "k", "by", 1))}. A
     * {@code byte[]} value is sent as the server expects bytes.
     */
    public static CacheCommand of(String variant, Map<String, Object> fields) {
        if (variant == null || variant.isEmpty() || fields == null) {
            throw new IllegalArgumentException("a cache command needs a name and its fields");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(variant, new LinkedHashMap<>(fields));
        return new CacheCommand(m);
    }

    /** An operation without fields, such as {@code Ping}. */
    public static CacheCommand unit(String variant) {
        if (variant == null || variant.isEmpty()) {
            throw new IllegalArgumentException("a cache command needs a name");
        }
        return new CacheCommand(variant);
    }

    /** {@code Set}; a {@code null} TTL stores the value without an expiry. */
    public static CacheCommand set(String namespace, String key, byte[] value, Long ttlMs) {
        Map<String, Object> m = key(namespace, key);
        m.put("value", value);
        m.put("ttl_ms", ttlMs);
        return of("Set", m);
    }

    public static CacheCommand get(String namespace, String key) {
        return of("Get", key(namespace, key));
    }

    public static CacheCommand delete(String namespace, String key) {
        return of("Delete", key(namespace, key));
    }

    public static CacheCommand incr(String namespace, String key, long by) {
        Map<String, Object> m = key(namespace, key);
        m.put("by", by);
        return of("Incr", m);
    }

    private static Map<String, Object> key(String namespace, String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("namespace", namespace);
        m.put("key", key);
        return m;
    }

    Object wire() {
        return wire;
    }
}
