package com.tricoredb;

import java.util.Map;
import java.util.OptionalLong;

/**
 * A cache namespace's limit and what it holds now.
 *
 * @param namespace the namespace
 * @param maxKeys   the cap on live keys, when there is one
 * @param maxBytes  the cap on value bytes, when there is one
 * @param keys      live keys held now
 * @param bytes     value bytes held now
 */
public record CacheNamespaceLimit(
        String namespace,
        OptionalLong maxKeys,
        OptionalLong maxBytes,
        long keys,
        long bytes) {

    static CacheNamespaceLimit from(Map<String, Object> m) {
        return new CacheNamespaceLimit(
                Wire.str(m, "namespace"),
                Wire.optLong(m, "max_keys"),
                Wire.optLong(m, "max_bytes"),
                Wire.num(m, "keys"),
                Wire.num(m, "bytes"));
    }
}
