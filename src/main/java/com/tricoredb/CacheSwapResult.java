package com.tricoredb;

import java.util.Map;

/**
 * The outcome of a compare-and-swap.
 *
 * @param swapped         whether the write happened
 * @param current         on a mismatch, the value actually present when it is valid
 *                        UTF-8; {@code null} otherwise
 * @param currentIsBinary on a mismatch, the value present is not valid UTF-8 and
 *                        must be read back
 */
public record CacheSwapResult(boolean swapped, String current, boolean currentIsBinary) {

    static CacheSwapResult from(Map<String, Object> m) {
        return new CacheSwapResult(
                Wire.bool(m, "swapped"),
                Wire.str(m, "current"),
                Wire.bool(m, "currentIsBinary"));
    }
}
