package com.tricoredb;

import java.util.Map;
import java.util.OptionalLong;

/**
 * What a stale-while-revalidate cache read found.
 *
 * @param state        fresh, stale, missing (a cached absence) or miss
 * @param value        the value when it is valid UTF-8; {@code null} otherwise
 * @param valueHex     the value in hexadecimal when it is not valid UTF-8; {@code
 *                     null} otherwise
 * @param refresh      this caller holds the refresh lease of a stale value
 * @param lease        this caller should compute the missing value
 * @param retryAfterMs how long another caller's lease still has to run
 */
public record CacheFreshRead(
        String state,
        String value,
        String valueHex,
        boolean refresh,
        boolean lease,
        OptionalLong retryAfterMs) {

    static CacheFreshRead from(Map<String, Object> m) {
        return new CacheFreshRead(
                Wire.str(m, "state"),
                Wire.str(m, "value"),
                Wire.str(m, "valueHex"),
                Wire.bool(m, "refresh"),
                Wire.bool(m, "lease"),
                Wire.optLong(m, "retryAfterMs"));
    }
}
