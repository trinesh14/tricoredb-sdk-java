package com.tricoredb;

import java.util.Map;
import java.util.OptionalLong;

/**
 * The outcome of trying to take a cache lock.
 *
 * @param acquired    whether the caller now holds the lock
 * @param token       the fencing token, when acquired
 * @param holder      the owner currently holding the lock; {@code null} otherwise
 * @param expiresAtMs when the current lease ends, in Unix milliseconds
 */
public record CacheLock(boolean acquired, OptionalLong token, String holder, OptionalLong expiresAtMs) {

    static CacheLock from(Map<String, Object> m) {
        return new CacheLock(
                Wire.bool(m, "acquired"),
                Wire.optLong(m, "token"),
                Wire.str(m, "holder"),
                Wire.optLong(m, "expiresAtMs"));
    }
}
