package com.tricoredb;

import java.util.Map;

/**
 * The outcome of replacing a document.
 *
 * @param id       the document id
 * @param replaced whether the body was written
 * @param inserted whether an upsert created the document
 * @param document the document asked for with ReturnDocument, when there is one;
 *                 {@code null} otherwise
 */
public record DocumentReplaceResult(
        String id,
        boolean replaced,
        boolean inserted,
        Map<String, Object> document) {

    static DocumentReplaceResult from(Map<String, Object> m) {
        return new DocumentReplaceResult(
                Wire.str(m, "id"),
                Wire.bool(m, "replaced"),
                Wire.bool(m, "inserted"),
                Wire.optObj(m, "document"));
    }
}
