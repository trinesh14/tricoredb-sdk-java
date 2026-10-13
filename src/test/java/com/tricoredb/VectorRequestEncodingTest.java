package com.tricoredb;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The request each vector method added for server 0.1.0-rc.3 puts on the wire,
 * field for field. The peer answers with a payload captured from a real server,
 * so the same call also proves the method can read what the server sends back.
 */
class VectorRequestEncodingTest {

    @Test
    void embedSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"dimension\":4,\"embedder\":\"hash-v1\",\"sparse\":[{\"indices\":[822003],\"valu"
                        + "es\":[1.0]}],\"vectors\":[[0.5,0.5,0.0,-0.5]]}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorEmbed(List.of("a", "b"), 4);
            }
            peer.assertSent(
                    "{\"Vector\":{\"Embed\":{\"texts\":[\"a\",\"b\"],\"dimension\":4}}}");
        }
    }

    @Test
    void upsertTextSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"embedder\":\"hash-v1\",\"upserted\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorUpsertText("s_collection", List.of(new VectorText("a", "hello", Map.of("k", "v"))));
            }
            peer.assertSent(
                    "{\"Vector\":{\"UpsertText\":{\"collection\":\"s_collection\",\"items\":[{\"id\":\"a\",\"text"
                    + "\":\"hello\",\"metadata\":{\"k\":\"v\"}}]}}}");
        }
    }

    @Test
    void setAliasSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"alias\":\"a\",\"collection\":\"c\"}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSetAlias("s_alias", "s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"SetAlias\":{\"alias\":\"s_alias\",\"collection\":\"s_collection\"}}}");
        }
    }

    @Test
    void listAliasesSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"aliases\":[{\"alias\":\"a\",\"collection\":\"c\"}]}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorListAliases();
            }
            peer.assertSent(
                    "{\"Vector\":\"ListAliases\"}");
        }
    }

    @Test
    void addNamedVectorSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"dimension\":2,\"name\":\"title\"}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorAddNamedVector("s_collection", "s_name", 3, VectorMetric.DOT);
            }
            peer.assertSent(
                    "{\"Vector\":{\"AddNamedVector\":{\"collection\":\"s_collection\",\"name\":\"s_name\",\"dimen"
                    + "sion\":3,\"metric\":\"dot\"}}}");
        }
    }

    @Test
    void upsertNamedSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"id\":\"n1\",\"vectors\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorUpsertNamed(
                        "s_collection", "s_id", Map.of("default", new float[] {0.5f, 0.25f}), Map.of("k", "v"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"UpsertNamed\":{\"collection\":\"s_collection\",\"id\":\"s_id\",\"vectors\":{"
                    + "\"default\":[0.5,0.25]},\"metadata\":{\"k\":\"v\"}}}}");
        }
    }

    @Test
    void upsertNamedSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"id\":\"n1\",\"vectors\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorUpsertNamed("s_collection", "s_id", Map.of("default", new float[] {0.5f, 0.25f}));
            }
            peer.assertSent(
                    "{\"Vector\":{\"UpsertNamed\":{\"collection\":\"s_collection\",\"id\":\"s_id\",\"vectors\":{"
                    + "\"default\":[0.5,0.25]}}}}");
        }
    }

    @Test
    void upsertManySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"upserted\":4}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorUpsertMany(
                        "s_collection", List.of(new VectorItem("a", new float[] {0.5f, 0.25f}, Map.of("k", "v"))));
            }
            peer.assertSent(
                    "{\"Vector\":{\"UpsertMany\":{\"collection\":\"s_collection\",\"items\":[{\"id\":\"a\",\"vect"
                    + "or\":[0.5,0.25],\"metadata\":{\"k\":\"v\"}}]}}}");
        }
    }

    @Test
    void deleteManySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"deleted\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorDeleteMany("s_collection", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"DeleteMany\":{\"collection\":\"s_collection\",\"ids\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void searchBatchSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"results\":[[{\"id\":\"a\",\"metadata\":{\"tier\":\"gold\"},\"score"
                        + "\":1.0}],[{\"id\":\"b\",\"score\":0.25}]]}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSearchBatch(
                        "s_collection", List.of(new float[] {0.5f, 0.25f}, new float[] {0.75f, 0.5f}), 3, new VectorSearchOptions().filter(Map.of("k", "v")).minScore(0.25).includeVectors(true).offset(3)
                        .includeMetadata(false).maxDistance(1.5).efSearch(50).exact(true)
                        .using("title").tenant("t1").namespace("n1"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"SearchBatch\":{\"collection\":\"s_collection\",\"vectors\":[[0.5,0.25],[0.75,"
                    + "0.5]],\"top_k\":3,\"filter\":{\"k\":\"v\"},\"min_score\":0.25,\"include_vectors\":true,\"off"
                    + "set\":3,\"include_metadata\":false,\"max_distance\":1.5,\"ef_search\":50,\"exact\":true,\"us"
                    + "ing\":\"title\",\"tenant\":\"t1\",\"namespace\":\"n1\"}}}");
        }
    }

    @Test
    void searchBatchSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"results\":[[{\"id\":\"a\",\"metadata\":{\"tier\":\"gold\"},\"score"
                        + "\":1.0}],[{\"id\":\"b\",\"score\":0.25}]]}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSearchBatch(
                        "s_collection", List.of(new float[] {0.5f, 0.25f}, new float[] {0.75f, 0.5f}), 3);
            }
            peer.assertSent(
                    "{\"Vector\":{\"SearchBatch\":{\"collection\":\"s_collection\",\"vectors\":[[0.5,0.25],[0.75,"
                    + "0.5]],\"top_k\":3}}}");
        }
    }

    @Test
    void deleteByFilterSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"deleted\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorDeleteByFilter("s_collection", Map.of("k", "v"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"DeleteByFilter\":{\"collection\":\"s_collection\",\"filter\":{\"k\":\"v\"}}}}");
        }
    }

    @Test
    void updateByFilterSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"updated\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorUpdateByFilter("s_collection", Map.of("k", "v"), Map.of("seen", true));
            }
            peer.assertSent(
                    "{\"Vector\":{\"UpdateByFilter\":{\"collection\":\"s_collection\",\"filter\":{\"k\":\"v\"},\""
                    + "set\":{\"seen\":true}}}}");
        }
    }

    @Test
    void countSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":4,\"filtered\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorCount("s_collection", Map.of("k", "v"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"Count\":{\"collection\":\"s_collection\",\"filter\":{\"k\":\"v\"}}}}");
        }
    }

    @Test
    void countSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":4,\"filtered\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorCount("s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"Count\":{\"collection\":\"s_collection\",\"filter\":null}}}");
        }
    }

    @Test
    void listPartitionsSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"field\":\"_namespace\",\"partitions\":[{\"count\":1,\"name\":\"p1\"}],\"unparti"
                        + "tioned\":3}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorListPartitions("s_collection", "_tenant");
            }
            peer.assertSent(
                    "{\"Vector\":{\"ListPartitions\":{\"collection\":\"s_collection\",\"field\":\"_tenant\"}}}");
        }
    }

    @Test
    void listPartitionsSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"field\":\"_namespace\",\"partitions\":[{\"count\":1,\"name\":\"p1\"}],\"unparti"
                        + "tioned\":3}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorListPartitions("s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"ListPartitions\":{\"collection\":\"s_collection\",\"field\":\"_namespace\"}}}");
        }
    }

    @Test
    void renameCollectionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"from\":\"a\",\"moved\":1,\"renamed\":true,\"to\":\"b\"}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorRenameCollection("s_collection", "s_to");
            }
            peer.assertSent(
                    "{\"Vector\":{\"RenameCollection\":{\"collection\":\"s_collection\",\"to\":\"s_to\"}}}");
        }
    }

    @Test
    void rebuildIndexSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"build_ms\":9,\"persisted\":true,\"rebuilt\":true,\"tombstones_cleared\":0,\"vec"
                        + "tors\":4}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorRebuildIndex("s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"RebuildIndex\":{\"collection\":\"s_collection\"}}}");
        }
    }

    @Test
    void indexStatsSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"flat_scan_threshold\":256,\"index\":\"flat\",\"stored\":4}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorIndexStats("s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"IndexStats\":{\"collection\":\"s_collection\"}}}");
        }
    }

    @Test
    void measureRecallSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"index\":\"flat\",\"measured\":false,\"reason\":\"small\","
                        + "\"recall\":1.0}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorMeasureRecall("s_collection", 3, 3, 3);
            }
            peer.assertSent(
                    "{\"Vector\":{\"MeasureRecall\":{\"collection\":\"s_collection\",\"sample\":3,\"top_k\":3,\"e"
                    + "f_search\":3}}}");
        }
    }

    @Test
    void measureRecallSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"index\":\"flat\",\"measured\":false,\"reason\":\"small\","
                        + "\"recall\":1.0}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorMeasureRecall("s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"MeasureRecall\":{\"collection\":\"s_collection\",\"sample\":null,\"top_k\":nu"
                    + "ll,\"ef_search\":null}}}");
        }
    }

    @Test
    void searchMultiSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"depth\":12,\"fusion\":\"rrf\",\"queries\":2,\"results\":[{\"id\":\"a\",\"matche"
                        + "d\":2,\"metadata\":{\"tier\":\"gold\"},\"score\":0.5}]}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSearchMulti(
                        "s_collection", List.of(new float[] {0.5f, 0.25f}, new float[] {0.75f, 0.5f}), 3, VectorFusion.MAX, Map.of("k", "v"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"SearchMulti\":{\"collection\":\"s_collection\",\"vectors\":[[0.5,0.25],[0.75,"
                    + "0.5]],\"top_k\":3,\"fusion\":\"max\",\"filter\":{\"k\":\"v\"}}}}");
        }
    }

    @Test
    void searchMultiSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"depth\":12,\"fusion\":\"rrf\",\"queries\":2,\"results\":[{\"id\":\"a\",\"matche"
                        + "d\":2,\"metadata\":{\"tier\":\"gold\"},\"score\":0.5}]}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSearchMulti(
                        "s_collection", List.of(new float[] {0.5f, 0.25f}, new float[] {0.75f, 0.5f}), 3);
            }
            peer.assertSent(
                    "{\"Vector\":{\"SearchMulti\":{\"collection\":\"s_collection\",\"vectors\":[[0.5,0.25],[0.75,"
                    + "0.5]],\"top_k\":3,\"fusion\":\"rrf\",\"filter\":null}}}");
        }
    }

    @Test
    void setCollectionConfigSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"ef_construction\":100,\"m\":8,\"multi_tenant\":false,\"nam"
                        + "ed\":[],\"normalize\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSetCollectionConfig("s_collection", true, 3, 3, true);
            }
            peer.assertSent(
                    "{\"Vector\":{\"SetCollectionConfig\":{\"collection\":\"s_collection\",\"normalize\":true,\"m"
                    + "\":3,\"ef_construction\":3,\"multi_tenant\":true}}}");
        }
    }

    @Test
    void setCollectionConfigSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"collection\":\"c\",\"ef_construction\":100,\"m\":8,\"multi_tenant\":false,\"nam"
                        + "ed\":[],\"normalize\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSetCollectionConfig("s_collection");
            }
            peer.assertSent(
                    "{\"Vector\":{\"SetCollectionConfig\":{\"collection\":\"s_collection\",\"normalize\":null,\"m"
                    + "\":null,\"ef_construction\":null,\"multi_tenant\":null}}}");
        }
    }

    @Test
    void copyCollectionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"copied\":1,\"to\":\"b\"}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorCopyCollection("s_collection", "s_to");
            }
            peer.assertSent(
                    "{\"Vector\":{\"CopyCollection\":{\"collection\":\"s_collection\",\"to\":\"s_to\"}}}");
        }
    }

    @Test
    void searchByExamplesSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"examples\":{\"negative\":1,\"positive\":2},\"index\":\"flat\",\"results\":[{\"i"
                        + "d\":\"d\",\"metadata\":{\"tier\":\"gold\"},\"score\":0.5}],\"top_k\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSearchByExamples(
                        "s_collection", List.of(VectorExample.id("a"), VectorExample.vector(new float[] {0.5f, 0.25f})), 3, List.of(VectorExample.id("a"), VectorExample.vector(new float[] {0.5f, 0.25f})), false, new VectorSearchOptions().filter(Map.of("k", "v")).minScore(0.25).includeVectors(true).offset(3)
                        .includeMetadata(false).maxDistance(1.5).efSearch(50).exact(true)
                        .using("title").tenant("t1").namespace("n1"));
            }
            peer.assertSent(
                    "{\"Vector\":{\"SearchByExamples\":{\"collection\":\"s_collection\",\"positive\":[\"a\",[0.5,"
                    + "0.25]],\"negative\":[\"a\",[0.5,0.25]],\"top_k\":3,\"exclude_examples\":false,\"filter\":{\""
                    + "k\":\"v\"},\"min_score\":0.25,\"include_vectors\":true,\"offset\":3,\"include_metadata\":fal"
                    + "se,\"max_distance\":1.5,\"ef_search\":50,\"exact\":true,\"using\":\"title\",\"tenant\":\"t1"
                    + "\",\"namespace\":\"n1\"}}}");
        }
    }

    @Test
    void searchByExamplesSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"examples\":{\"negative\":1,\"positive\":2},\"index\":\"flat\",\"results\":[{\"i"
                        + "d\":\"d\",\"metadata\":{\"tier\":\"gold\"},\"score\":0.5}],\"top_k\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.vectorSearchByExamples(
                        "s_collection", List.of(VectorExample.id("a"), VectorExample.vector(new float[] {0.5f, 0.25f})), 3);
            }
            peer.assertSent(
                    "{\"Vector\":{\"SearchByExamples\":{\"collection\":\"s_collection\",\"positive\":[\"a\",[0.5,"
                    + "0.25]],\"negative\":[],\"top_k\":3,\"exclude_examples\":true}}}");
        }
    }
}
