package com.tricoredb;

import java.util.Map;

/**
 * The outcome of trimming a list.
 *
 * @param length  elements the list now holds
 * @param removed elements dropped
 */
public record CacheTrimResult(long length, long removed) {

    static CacheTrimResult from(Map<String, Object> m) {
        return new CacheTrimResult(Wire.num(m, "length"), Wire.num(m, "removed"));
    }
}
