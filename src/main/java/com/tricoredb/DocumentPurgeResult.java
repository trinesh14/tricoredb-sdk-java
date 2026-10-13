package com.tricoredb;

import java.util.Map;

/**
 * The outcome of purging expired documents.
 *
 * @param purged documents deleted
 * @param more   whether expired documents remain for another call
 */
public record DocumentPurgeResult(long purged, boolean more) {

    static DocumentPurgeResult from(Map<String, Object> m) {
        return new DocumentPurgeResult(Wire.num(m, "purged"), Wire.bool(m, "more"));
    }
}
