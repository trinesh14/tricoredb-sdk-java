package com.tricoredb;

import java.util.Map;

/**
 * The outcome of setting an absolute expiry.
 *
 * @param updated the key exists and now carries the deadline
 * @param deleted the deadline had already passed, so the key was deleted
 */
public record CacheExpireAtResult(boolean updated, boolean deleted) {

    static CacheExpireAtResult from(Map<String, Object> m) {
        return new CacheExpireAtResult(Wire.bool(m, "updated"), Wire.bool(m, "deleted"));
    }
}
