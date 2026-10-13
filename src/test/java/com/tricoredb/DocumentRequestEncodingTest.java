package com.tricoredb;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The request each document method added for server 0.1.0-rc.3 puts on the wire,
 * field for field. The peer answers with a payload captured from a real server,
 * so the same call also proves the method can read what the server sends back.
 */
class DocumentRequestEncodingTest {

    @Test
    void replaceSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"document\":{\"_id\":\"a\",\"n\":12},\"id\":\"a\",\"inserted\":false,\"replaced"
                        + "\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.documentReplace("s_collection", "s_id", Map.of("k", "v"), true, ReturnDocument.BEFORE, 7L);
            }
            peer.assertSent(
                    "{\"Document\":{\"Replace\":{\"collection\":\"s_collection\",\"id\":\"s_id\",\"document\":{\""
                    + "k\":\"v\"},\"upsert\":true,\"return\":\"Before\",\"expected_version\":7}}}");
        }
    }

    @Test
    void replaceSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"document\":{\"_id\":\"a\",\"n\":12},\"id\":\"a\",\"inserted\":false,\"replaced"
                        + "\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.documentReplace("s_collection", "s_id", Map.of("k", "v"));
            }
            peer.assertSent(
                    "{\"Document\":{\"Replace\":{\"collection\":\"s_collection\",\"id\":\"s_id\",\"document\":{\""
                    + "k\":\"v\"},\"upsert\":false,\"return\":\"None\",\"expected_version\":null}}}");
        }
    }

    @Test
    void insertManySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"ids\":[\"a\",\"b\",\"6d342854f347a969\"],\"inserted\":3}}")) {
            try (TriCore db = peer.connect()) {
                db.documentInsertMany("s_collection", List.of(Map.of("k", "v"), Map.of("n", 2)));
            }
            peer.assertSent(
                    "{\"Document\":{\"InsertMany\":{\"collection\":\"s_collection\",\"documents\":[{\"k\":\"v\"},"
                    + "{\"n\":2}]}}}");
        }
    }

    @Test
    void deleteManySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"deleted\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.documentDeleteMany("s_collection", DocumentFilter.eq("city", "Pune"));
            }
            peer.assertSent(
                    "{\"Document\":{\"DeleteMany\":{\"collection\":\"s_collection\",\"filter\":{\"Eq\":{\"field\""
                    + ":\"city\",\"value\":\"Pune\"}}}}}");
        }
    }

    @Test
    void renameCollectionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"documents\":7,\"from\":\"a\",\"indexes\":1,\"renamed\":true,\"to\":\"b\"}}")) {
            try (TriCore db = peer.connect()) {
                db.documentRenameCollection("s_collection", "s_to");
            }
            peer.assertSent(
                    "{\"Document\":{\"RenameCollection\":{\"collection\":\"s_collection\",\"to\":\"s_to\"}}}");
        }
    }

    @Test
    void purgeExpiredSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"more\":false,\"purged\":0}}")) {
            try (TriCore db = peer.connect()) {
                db.documentPurgeExpired("s_collection");
            }
            peer.assertSent(
                    "{\"Document\":{\"PurgeExpired\":{\"collection\":\"s_collection\"}}}");
        }
    }

    @Test
    void explainSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"elapsed_us\":121,\"estimated_cost\":1000,\"estimated_rows\":1000,\"index\":null"
                        + ",\"index_used\":false,\"plan\":\"FindScan\",\"rows_examined\":5,\"rows_returned\":1,\"scan_r"
                        + "eason\":\"no index on field `city`\",\"scan_source\":\"memory_index\"}}")) {
            try (TriCore db = peer.connect()) {
                db.documentExplain(
                        "s_collection", DocumentFilter.eq("city", "Pune"), 3, List.of(AggregateStage.desc("n")), 3);
            }
            peer.assertSent(
                    "{\"Document\":{\"Explain\":{\"collection\":\"s_collection\",\"filter\":{\"Eq\":{\"field\":\""
                    + "city\",\"value\":\"Pune\"}},\"limit\":3,\"sort\":[{\"field\":\"n\",\"descending\":true}],\"s"
                    + "kip\":3}}}");
        }
    }

    @Test
    void explainSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"elapsed_us\":121,\"estimated_cost\":1000,\"estimated_rows\":1000,\"index\":null"
                        + ",\"index_used\":false,\"plan\":\"FindScan\",\"rows_examined\":5,\"rows_returned\":1,\"scan_r"
                        + "eason\":\"no index on field `city`\",\"scan_source\":\"memory_index\"}}")) {
            try (TriCore db = peer.connect()) {
                db.documentExplain("s_collection", DocumentFilter.eq("city", "Pune"));
            }
            peer.assertSent(
                    "{\"Document\":{\"Explain\":{\"collection\":\"s_collection\",\"filter\":{\"Eq\":{\"field\":\""
                    + "city\",\"value\":\"Pune\"}},\"limit\":null,\"sort\":[],\"skip\":null}}}");
        }
    }

    @Test
    void beginTransactionSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"serializable\":false,\"txn\":\"dtx-1-3\"}}")) {
            try (TriCore db = peer.connect()) {
                db.documentBeginTransaction(true);
            }
            peer.assertSent(
                    "{\"Document\":{\"BeginTransaction\":{\"serializable\":true}}}");
        }
    }

    @Test
    void beginTransactionSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"serializable\":false,\"txn\":\"dtx-1-3\"}}")) {
            try (TriCore db = peer.connect()) {
                db.documentBeginTransaction();
            }
            peer.assertSent(
                    "{\"Document\":{\"BeginTransaction\":{\"serializable\":false}}}");
        }
    }

    @Test
    void commitTransactionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"committed\":true,\"writes\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.documentCommitTransaction("tx-1");
            }
            peer.assertSent(
                    "{\"Document\":\"CommitTransaction\"}",
                    "tx-1");
        }
    }

    @Test
    void rollbackTransactionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"rolled_back\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.documentRollbackTransaction("tx-1");
            }
            peer.assertSent(
                    "{\"Document\":\"RollbackTransaction\"}",
                    "tx-1");
        }
    }

    @Test
    void rebuildIndexSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"documents\":4,\"entries\":4,\"rebuilt\":\"by_n\"}}")) {
            try (TriCore db = peer.connect()) {
                db.documentRebuildIndex("s_collection", "s_index_name");
            }
            peer.assertSent(
                    "{\"Document\":{\"RebuildIndex\":{\"collection\":\"s_collection\",\"index_name\":\"s_index_na"
                    + "me\"}}}");
        }
    }

    @Test
    void setCollectionOptionsSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"documents_checked\":4,\"updated\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.documentSetCollectionOptions(
                        "s_collection", new CollectionOptions(Map.of("required", List.of("n")), Map.of("k", "v"), true));
            }
            peer.assertSent(
                    "{\"Document\":{\"SetCollectionOptions\":{\"collection\":\"s_collection\",\"options\":{\"sche"
                    + "ma\":{\"required\":[\"n\"]},\"defaults\":{\"k\":\"v\"},\"versioned\":true}}}}");
        }
    }

    @Test
    void getCollectionOptionsSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"options\":{\"defaults\":{\"tier\":\"free\"},\"schema\":{\""
                        + "additional_fields\":true,\"fields\":{\"n\":{\"minimum\":0.0,\"type\":\"number\"}},\"required"
                        + "\":[\"n\"]},\"versioned\":true}}}")) {
            try (TriCore db = peer.connect()) {
                db.documentGetCollectionOptions("s_collection");
            }
            peer.assertSent(
                    "{\"Document\":{\"GetCollectionOptions\":{\"collection\":\"s_collection\"}}}");
        }
    }

    @Test
    void bulkWriteSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"deleted\":1,\"inserted\":2,\"inserted_ids\":[\"k1\",\"ddf70967be50e053\"],\"rep"
                        + "laced\":0,\"updated\":1,\"upserted\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.documentBulkWrite(
                        "s_collection", List.of(BulkOp.insert("a", Map.of("k", "v")), BulkOp.updateOne("a", DocumentUpdate.set("n", 5), true, 7L),
                BulkOp.replace("a", Map.of("k", "v")), BulkOp.delete("a")));
            }
            peer.assertSent(
                    "{\"Document\":{\"BulkWrite\":{\"collection\":\"s_collection\",\"ops\":[{\"Insert\":{\"id\":"
                    + "\"a\",\"document\":{\"k\":\"v\"}}},{\"UpdateOne\":{\"id\":\"a\",\"update\":{\"set\":{\"n\":5"
                    + "},\"inc\":{}},\"upsert\":true,\"expected_version\":7}},{\"Replace\":{\"id\":\"a\",\"document"
                    + "\":{\"k\":\"v\"},\"upsert\":false,\"expected_version\":null}},{\"Delete\":{\"id\":\"a\",\"ex"
                    + "pected_version\":null}}]}}}");
        }
    }
}
