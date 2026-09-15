package com.tricoredb;

import java.util.Map;
import java.util.OptionalLong;

/**
 * One live key in a cache namespace.
 *
 * @param ttlMs remaining time to live, or empty when the key never expires
 * @param bytes size of the stored value
 */
public record CacheKeyInfo(String key, OptionalLong ttlMs, long bytes) {

    static CacheKeyInfo from(Map<String, Object> m) {
        Object ttl = m.get("ttl_ms");
        return new CacheKeyInfo(
                Wire.str(m, "key"),
                ttl instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty(),
                m.get("bytes") instanceof Number b ? b.longValue() : 0L);
    }
}
