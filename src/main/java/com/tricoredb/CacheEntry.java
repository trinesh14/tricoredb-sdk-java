package com.tricoredb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.LinkedHashMap;
import java.util.Map;

/** One key/value pair of {@code cacheMSet} or {@code cacheWarm}. */
public record CacheEntry(String key, byte[] value) {

    public CacheEntry {
        if (key == null || key.isEmpty() || value == null) {
            throw new IllegalArgumentException("a cache entry needs both a key and a value");
        }
    }

    /** An entry whose value is a string, encoded UTF-8. */
    public static CacheEntry ofText(String key, String value) {
        return new CacheEntry(key, value.getBytes(UTF_8));
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value);
        return m;
    }
}
