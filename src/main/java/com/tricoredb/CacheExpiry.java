package com.tricoredb;

import java.util.Map;

/** What {@code cacheGetEx} and {@code cacheSetIf} do to a key's expiry. */
public final class CacheExpiry {

    private static final CacheExpiry KEEP = new CacheExpiry("Keep");
    private static final CacheExpiry CLEAR = new CacheExpiry("Clear");

    private final Object wire;

    private CacheExpiry(Object wire) {
        this.wire = wire;
    }

    /** Leave the expiry as it is. */
    public static CacheExpiry keep() {
        return KEEP;
    }

    /** Remove the expiry, making the key permanent. */
    public static CacheExpiry clear() {
        return CLEAR;
    }

    /** Expire {@code ttlMs} milliseconds from now. */
    public static CacheExpiry set(long ttlMs) {
        return new CacheExpiry(Map.of("Set", Map.of("ttl_ms", ttlMs)));
    }

    Object wire() {
        return wire;
    }
}
