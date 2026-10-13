package com.tricoredb;

import java.util.Map;
import java.util.OptionalLong;

/**
 * The outcome of renewing a cache lock lease.
 *
 * @param renewed     whether the lease was extended
 * @param token       the fencing token, when renewed
 * @param expiresAtMs when the lease now ends, in Unix milliseconds
 */
public record CacheLockRenewal(boolean renewed, OptionalLong token, OptionalLong expiresAtMs) {

    static CacheLockRenewal from(Map<String, Object> m) {
        return new CacheLockRenewal(
                Wire.bool(m, "renewed"),
                Wire.optLong(m, "token"),
                Wire.optLong(m, "expiresAtMs"));
    }
}
