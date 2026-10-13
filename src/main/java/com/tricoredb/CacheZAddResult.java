package com.tricoredb;

import java.util.Map;

/**
 * The outcome of adding members to a sorted set.
 *
 * @param added   members that were not present
 * @param updated members whose score changed
 */
public record CacheZAddResult(long added, long updated) {

    static CacheZAddResult from(Map<String, Object> m) {
        return new CacheZAddResult(Wire.num(m, "added"), Wire.num(m, "updated"));
    }
}
