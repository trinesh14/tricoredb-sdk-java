package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a document bulk write.
 *
 * @param inserted    documents inserted
 * @param updated     documents updated
 * @param replaced    documents replaced
 * @param deleted     documents deleted
 * @param upserted    documents created by an upsert
 * @param insertedIds the ids of the inserted documents, in order
 */
public record BulkWriteResult(
        long inserted,
        long updated,
        long replaced,
        long deleted,
        long upserted,
        List<String> insertedIds) {

    static BulkWriteResult from(Map<String, Object> m) {
        return new BulkWriteResult(
                Wire.num(m, "inserted"),
                Wire.num(m, "updated"),
                Wire.num(m, "replaced"),
                Wire.num(m, "deleted"),
                Wire.num(m, "upserted"),
                Wire.strings(m, "inserted_ids"));
    }
}
