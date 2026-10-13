package com.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One end of a score range.
 *
 * JSON has no infinity, so an open end is the largest finite double:
 * {@link #min()} and {@link #max()}.
 */
public record ScoreBound(double value, boolean exclusive) {

    /** A bound that admits {@code value} itself. */
    public static ScoreBound inclusive(double value) {
        return new ScoreBound(value, false);
    }

    /** A bound that stops short of {@code value}. */
    public static ScoreBound exclusive(double value) {
        return new ScoreBound(value, true);
    }

    /** Below every finite score. */
    public static ScoreBound min() {
        return new ScoreBound(-Double.MAX_VALUE, false);
    }

    /** Above every finite score. */
    public static ScoreBound max() {
        return new ScoreBound(Double.MAX_VALUE, false);
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("exclusive", exclusive);
        return m;
    }
}
