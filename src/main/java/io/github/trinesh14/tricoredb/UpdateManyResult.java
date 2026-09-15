package io.github.trinesh14.tricoredb;

import java.util.Map;

/**
 * The outcome of a multi-document update.
 *
 * @param matched  documents the filter selected
 * @param modified documents the update actually changed — lower than
 *                 {@code matched} when a document already held the new value
 */
public record UpdateManyResult(long matched, long modified) {

    static UpdateManyResult from(Map<String, Object> m) {
        return new UpdateManyResult(Wire.num(m, "matched"), Wire.num(m, "modified"));
    }
}
