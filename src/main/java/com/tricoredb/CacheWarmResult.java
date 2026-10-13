package com.tricoredb;

import java.util.Map;

/**
 * The outcome of warming a cache namespace.
 *
 * @param written       entries written
 * @param alreadyCached entries left alone because a live value was there
 */
public record CacheWarmResult(long written, long alreadyCached) {

    static CacheWarmResult from(Map<String, Object> m) {
        return new CacheWarmResult(Wire.num(m, "written"), Wire.num(m, "alreadyCached"));
    }
}
