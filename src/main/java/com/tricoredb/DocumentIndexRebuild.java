package com.tricoredb;

import java.util.Map;

/**
 * The outcome of rebuilding a document index.
 *
 * @param rebuilt   the index name
 * @param documents documents read
 * @param entries   index entries written
 */
public record DocumentIndexRebuild(String rebuilt, long documents, long entries) {

    static DocumentIndexRebuild from(Map<String, Object> m) {
        return new DocumentIndexRebuild(
                Wire.str(m, "rebuilt"),
                Wire.num(m, "documents"),
                Wire.num(m, "entries"));
    }
}
