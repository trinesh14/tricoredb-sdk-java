package com.tricoredb;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The request each graph method added for server 0.1.0-rc.3 puts on the wire,
 * field for field. The peer answers with a payload captured from a real server,
 * so the same call also proves the method can read what the server sends back.
 */
class GraphRequestEncodingTest {

    @Test
    void addManySendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"atomic\":true,\"edges\":5,\"nodes\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.graphAddMany(
                        "s_graph", List.of(new GraphNode("a", List.of("P"), Map.of("k", "v"))), List.of(new GraphEdge("e", "a", "b", "K", Map.of("k", "v"))));
            }
            peer.assertSent(
                    "{\"Graph\":{\"AddMany\":{\"graph\":\"s_graph\",\"nodes\":[{\"id\":\"a\",\"labels\":[\"P\"],"
                    + "\"properties\":{\"k\":\"v\"}}],\"edges\":[{\"id\":\"e\",\"from\":\"a\",\"to\":\"b\",\"label"
                    + "\":\"K\",\"properties\":{\"k\":\"v\"}}]}}}");
        }
    }

    @Test
    void addManySendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"atomic\":true,\"edges\":5,\"nodes\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.graphAddMany("s_graph", List.of(new GraphNode("a", List.of("P"), Map.of("k", "v"))));
            }
            peer.assertSent(
                    "{\"Graph\":{\"AddMany\":{\"graph\":\"s_graph\",\"nodes\":[{\"id\":\"a\",\"labels\":[\"P\"],"
                    + "\"properties\":{\"k\":\"v\"}}],\"edges\":[]}}}");
        }
    }

    @Test
    void removeManySendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"atomic\":true,\"edges_removed\":1,\"nodes_removed\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphRemoveMany("s_graph", List.of("a", "b"), List.of("a", "b"), true);
            }
            peer.assertSent(
                    "{\"Graph\":{\"RemoveMany\":{\"graph\":\"s_graph\",\"nodes\":[\"a\",\"b\"],\"edges\":[\"a\","
                    + "\"b\"],\"detach\":true}}}");
        }
    }

    @Test
    void removeManySendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"atomic\":true,\"edges_removed\":1,\"nodes_removed\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphRemoveMany("s_graph", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Graph\":{\"RemoveMany\":{\"graph\":\"s_graph\",\"nodes\":[\"a\",\"b\"],\"edges\":[],\"det"
                    + "ach\":false}}}");
        }
    }

    @Test
    void updateNodeSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"id\":\"a\",\"labels\":[\"R\"],\"properties\":{\"city\":\"X\",\"name\":\"A\"}}}")) {
            try (TriCore db = peer.connect()) {
                db.graphUpdateNode("s_graph", "s_id", Map.of("k", "v"), List.of("a", "b"), List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Graph\":{\"UpdateNode\":{\"graph\":\"s_graph\",\"id\":\"s_id\",\"properties\":{\"k\":\"v"
                    + "\"},\"add_labels\":[\"a\",\"b\"],\"remove_labels\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void updateNodeSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"id\":\"a\",\"labels\":[\"R\"],\"properties\":{\"city\":\"X\",\"name\":\"A\"}}}")) {
            try (TriCore db = peer.connect()) {
                db.graphUpdateNode("s_graph", "s_id");
            }
            peer.assertSent(
                    "{\"Graph\":{\"UpdateNode\":{\"graph\":\"s_graph\",\"id\":\"s_id\",\"add_labels\":[],\"remove"
                    + "_labels\":[]}}}");
        }
    }

    @Test
    void updateEdgeSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"from\":\"a\",\"id\":\"ab\",\"label\":\"M\",\"properties\":{\"weight\":3},\"to\""
                        + ":\"b\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphUpdateEdge("s_graph", "s_id", Map.of("k", "v"), "s_label");
            }
            peer.assertSent(
                    "{\"Graph\":{\"UpdateEdge\":{\"graph\":\"s_graph\",\"id\":\"s_id\",\"properties\":{\"k\":\"v"
                    + "\"},\"label\":\"s_label\"}}}");
        }
    }

    @Test
    void updateEdgeSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"from\":\"a\",\"id\":\"ab\",\"label\":\"M\",\"properties\":{\"weight\":3},\"to\""
                        + ":\"b\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphUpdateEdge("s_graph", "s_id");
            }
            peer.assertSent(
                    "{\"Graph\":{\"UpdateEdge\":{\"graph\":\"s_graph\",\"id\":\"s_id\",\"label\":null}}}");
        }
    }

    @Test
    void copySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"copied\":true,\"edges\":0,\"nodes\":2,\"to\":\"b\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCopy("s_graph", "s_to");
            }
            peer.assertSent(
                    "{\"Graph\":{\"CopyGraph\":{\"graph\":\"s_graph\",\"to\":\"s_to\"}}}");
        }
    }

    @Test
    void dfsSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"direction\":\"outgoing\",\"limit\":100,\"max_depth\":3,\"nodes\":[{"
                        + "\"depth\":0,\"id\":\"a\",\"labels\":[\"R\"],\"properties\":{\"name\":\"A\"}},{\"depth\":1,\""
                        + "id\":\"c\",\"labels\":[\"P\",\"Q\"],\"properties\":null}],\"order\":\"depth_first\",\"start"
                        + "\":\"a\",\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphDfs("s_graph", "s_start", GraphDirection.BOTH, "s_label", 3, 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"Dfs\":{\"graph\":\"s_graph\",\"start\":\"s_start\",\"direction\":\"both\",\"la"
                    + "bel\":\"s_label\",\"max_depth\":3,\"limit\":3}}}");
        }
    }

    @Test
    void dfsSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"direction\":\"outgoing\",\"limit\":100,\"max_depth\":3,\"nodes\":[{"
                        + "\"depth\":0,\"id\":\"a\",\"labels\":[\"R\"],\"properties\":{\"name\":\"A\"}},{\"depth\":1,\""
                        + "id\":\"c\",\"labels\":[\"P\",\"Q\"],\"properties\":null}],\"order\":\"depth_first\",\"start"
                        + "\":\"a\",\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphDfs("s_graph", "s_start");
            }
            peer.assertSent(
                    "{\"Graph\":{\"Dfs\":{\"graph\":\"s_graph\",\"start\":\"s_start\",\"direction\":\"outgoing\","
                    + "\"label\":null,\"max_depth\":null,\"limit\":null}}}");
        }
    }

    @Test
    void connectedComponentsSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"components\":[{\"nodes\":[\"a\",\"b\"],\"size\":2,\"truncated\":false}],\"count"
                        + "\":2,\"kind\":\"weak\",\"largest\":4,\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphConnectedComponents("s_graph", GraphComponentKind.STRONG, "s_label", 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"ConnectedComponents\":{\"graph\":\"s_graph\",\"kind\":\"strong\",\"label\":\"s"
                    + "_label\",\"limit\":3}}}");
        }
    }

    @Test
    void connectedComponentsSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"components\":[{\"nodes\":[\"a\",\"b\"],\"size\":2,\"truncated\":false}],\"count"
                        + "\":2,\"kind\":\"weak\",\"largest\":4,\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphConnectedComponents("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"ConnectedComponents\":{\"graph\":\"s_graph\",\"kind\":\"weak\",\"label\":null,"
                    + "\"limit\":null}}}");
        }
    }

    @Test
    void detectCycleSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"cycle\":{\"edges\":[\"ab\",\"bc\",\"ca\"],\"nodes\":[\"a\",\"b\",\"c\"]},\"dire"
                        + "cted\":true,\"hasCycle\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.graphDetectCycle("s_graph", false, "s_label");
            }
            peer.assertSent(
                    "{\"Graph\":{\"DetectCycle\":{\"graph\":\"s_graph\",\"directed\":false,\"label\":\"s_label\"}}}");
        }
    }

    @Test
    void detectCycleSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"cycle\":{\"edges\":[\"ab\",\"bc\",\"ca\"],\"nodes\":[\"a\",\"b\",\"c\"]},\"dire"
                        + "cted\":true,\"hasCycle\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.graphDetectCycle("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"DetectCycle\":{\"graph\":\"s_graph\",\"directed\":true,\"label\":null}}}");
        }
    }

    @Test
    void statsSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"avgOutDegree\":1.0,\"danglingEdges\":0,\"edgeTypes\":{\"K\":4},\"edges\":5,\"is"
                        + "olatedNodes\":1,\"maxInDegree\":2,\"maxOutDegree\":2,\"nodeLabels\":{\"P\":2},\"nodes\":5,\""
                        + "selfLoops\":0,\"unlabeledNodes\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.graphStats("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"GraphStats\":{\"graph\":\"s_graph\"}}}");
        }
    }

    @Test
    void subgraphSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"direction\":\"both\",\"edge_count\":1,\"edges\":[{\"from\":\"a\",\"id\":\"ab\","
                        + "\"label\":\"K\",\"properties\":null,\"to\":\"b\"}],\"edges_truncated\":false,\"limit\":1000,"
                        + "\"max_depth\":1,\"node_count\":2,\"nodes\":[{\"depth\":0,\"id\":\"a\",\"labels\":[],\"proper"
                        + "ties\":{}},{\"depth\":1,\"id\":\"b\",\"labels\":[],\"properties\":null}],\"nodes_truncated\""
                        + ":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphSubgraph("s_graph", List.of("a", "b"), GraphDirection.OUTGOING, "s_label", 3, 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"Subgraph\":{\"graph\":\"s_graph\",\"seeds\":[\"a\",\"b\"],\"direction\":\"outg"
                    + "oing\",\"label\":\"s_label\",\"max_depth\":3,\"limit\":3}}}");
        }
    }

    @Test
    void subgraphSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"direction\":\"both\",\"edge_count\":1,\"edges\":[{\"from\":\"a\",\"id\":\"ab\","
                        + "\"label\":\"K\",\"properties\":null,\"to\":\"b\"}],\"edges_truncated\":false,\"limit\":1000,"
                        + "\"max_depth\":1,\"node_count\":2,\"nodes\":[{\"depth\":0,\"id\":\"a\",\"labels\":[],\"proper"
                        + "ties\":{}},{\"depth\":1,\"id\":\"b\",\"labels\":[],\"properties\":null}],\"nodes_truncated\""
                        + ":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphSubgraph("s_graph", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Graph\":{\"Subgraph\":{\"graph\":\"s_graph\",\"seeds\":[\"a\",\"b\"],\"direction\":\"both"
                    + "\",\"label\":null,\"max_depth\":null,\"limit\":null}}}");
        }
    }

    @Test
    void schemaSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"edge_types\":[{\"count\":4,\"properties\":[],\"type\":\"K\"}],\"edges\":5,\"nod"
                        + "e_labels\":[{\"count\":2,\"label\":\"P\",\"properties\":[]}],\"nodes\":5,\"unlabeled_nodes\""
                        + ":{\"count\":2,\"properties\":[]}}}")) {
            try (TriCore db = peer.connect()) {
                db.graphSchema("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"GraphSchema\":{\"graph\":\"s_graph\"}}}");
        }
    }

    @Test
    void kShortestPathsSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"direction\":\"outgoing\",\"from\":\"a\",\"k\":3,\"paths\":[{\"cost"
                        + "\":2.0,\"edges\":[\"ab\",\"bc\"],\"hops\":2,\"nodes\":[\"a\",\"b\",\"c\"]}],\"to\":\"c\",\"w"
                        + "eight_property\":null}}")) {
            try (TriCore db = peer.connect()) {
                db.graphKShortestPaths(
                        "s_graph", "s_from", "s_to", 3, GraphDirection.BOTH, "s_label", "s_weight_property");
            }
            peer.assertSent(
                    "{\"Graph\":{\"KShortestPaths\":{\"graph\":\"s_graph\",\"from\":\"s_from\",\"to\":\"s_to\",\""
                    + "k\":3,\"direction\":\"both\",\"label\":\"s_label\",\"weight_property\":\"s_weight_property\""
                    + "}}}");
        }
    }

    @Test
    void kShortestPathsSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"direction\":\"outgoing\",\"from\":\"a\",\"k\":3,\"paths\":[{\"cost"
                        + "\":2.0,\"edges\":[\"ab\",\"bc\"],\"hops\":2,\"nodes\":[\"a\",\"b\",\"c\"]}],\"to\":\"c\",\"w"
                        + "eight_property\":null}}")) {
            try (TriCore db = peer.connect()) {
                db.graphKShortestPaths("s_graph", "s_from", "s_to");
            }
            peer.assertSent(
                    "{\"Graph\":{\"KShortestPaths\":{\"graph\":\"s_graph\",\"from\":\"s_from\",\"to\":\"s_to\",\""
                    + "k\":null,\"direction\":\"outgoing\",\"label\":null,\"weight_property\":null}}}");
        }
    }

    @Test
    void allPathsSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"complete\":true,\"count\":1,\"direction\":\"outgoing\",\"from\":\"a\",\"limit\""
                        + ":100,\"max_depth\":5,\"paths\":[{\"cost\":1.0,\"edges\":[\"ac\"],\"hops\":1,\"nodes\":[\"a\""
                        + ",\"c\"]}],\"stopped_by\":null,\"to\":\"c\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphAllPaths("s_graph", "s_from", "s_to", GraphDirection.BOTH, "s_label", 3, 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"AllPaths\":{\"graph\":\"s_graph\",\"from\":\"s_from\",\"to\":\"s_to\",\"direct"
                    + "ion\":\"both\",\"label\":\"s_label\",\"max_depth\":3,\"limit\":3}}}");
        }
    }

    @Test
    void allPathsSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"complete\":true,\"count\":1,\"direction\":\"outgoing\",\"from\":\"a\",\"limit\""
                        + ":100,\"max_depth\":5,\"paths\":[{\"cost\":1.0,\"edges\":[\"ac\"],\"hops\":1,\"nodes\":[\"a\""
                        + ",\"c\"]}],\"stopped_by\":null,\"to\":\"c\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphAllPaths("s_graph", "s_from", "s_to");
            }
            peer.assertSent(
                    "{\"Graph\":{\"AllPaths\":{\"graph\":\"s_graph\",\"from\":\"s_from\",\"to\":\"s_to\",\"direct"
                    + "ion\":\"outgoing\",\"label\":null,\"max_depth\":null,\"limit\":null}}}");
        }
    }

    @Test
    void centralitySendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"directed\":true,\"measure\":\"betweenness\",\"nodes\":5,\"results\":[{\"id\":\""
                        + "c\",\"score\":0.25},{\"id\":\"b\",\"score\":0.0}]}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCentrality("s_graph", GraphCentralityMeasure.CLOSENESS, false, "s_label", 3, 3, 1.5);
            }
            peer.assertSent(
                    "{\"Graph\":{\"Centrality\":{\"graph\":\"s_graph\",\"measure\":\"closeness\",\"directed\":fal"
                    + "se,\"label\":\"s_label\",\"limit\":3,\"iterations\":3,\"tolerance\":1.5}}}");
        }
    }

    @Test
    void centralitySendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"directed\":true,\"measure\":\"betweenness\",\"nodes\":5,\"results\":[{\"id\":\""
                        + "c\",\"score\":0.25},{\"id\":\"b\",\"score\":0.0}]}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCentrality("s_graph", GraphCentralityMeasure.CLOSENESS);
            }
            peer.assertSent(
                    "{\"Graph\":{\"Centrality\":{\"graph\":\"s_graph\",\"measure\":\"closeness\",\"directed\":tru"
                    + "e,\"label\":null,\"limit\":null,\"iterations\":null,\"tolerance\":null}}}");
        }
    }

    @Test
    void communitiesSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"algorithm\":\"louvain\",\"communities\":[{\"nodes\":[\"a\",\"b\"],\"size\":2,\""
                        + "truncated\":false}],\"converged\":true,\"count\":3,\"iterations\":3,\"modularity\":0.0}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCommunities("s_graph", "s_label", 3, 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"Communities\":{\"graph\":\"s_graph\",\"label\":\"s_label\",\"iterations\":3,\""
                    + "limit\":3}}}");
        }
    }

    @Test
    void communitiesSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"algorithm\":\"louvain\",\"communities\":[{\"nodes\":[\"a\",\"b\"],\"size\":2,\""
                        + "truncated\":false}],\"converged\":true,\"count\":3,\"iterations\":3,\"modularity\":0.0}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCommunities("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"Communities\":{\"graph\":\"s_graph\",\"label\":null,\"iterations\":null,\"limi"
                    + "t\":null}}}");
        }
    }

    @Test
    void triangleCountSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"average_clustering\":0.5,\"nodes\":[{\"clustering\":1.0,\"id\":\"a\",\"triangle"
                        + "s\":1}],\"transitivity\":0.6,\"triangles\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphTriangleCount("s_graph", "s_label", 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"TriangleCount\":{\"graph\":\"s_graph\",\"label\":\"s_label\",\"limit\":3}}}");
        }
    }

    @Test
    void triangleCountSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"average_clustering\":0.5,\"nodes\":[{\"clustering\":1.0,\"id\":\"a\",\"triangle"
                        + "s\":1}],\"transitivity\":0.6,\"triangles\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphTriangleCount("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"TriangleCount\":{\"graph\":\"s_graph\",\"label\":null,\"limit\":null}}}");
        }
    }

    @Test
    void nodeSimilaritySendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"direction\":\"both\",\"metric\":\"jaccard\",\"node\":\"a\",\"result"
                        + "s\":[{\"id\":\"d\",\"score\":0.5}]}}")) {
            try (TriCore db = peer.connect()) {
                db.graphNodeSimilarity(
                        "s_graph", "s_node", GraphDirection.OUTGOING, "s_label", GraphSimilarityMetric.OVERLAP, 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"NodeSimilarity\":{\"graph\":\"s_graph\",\"node\":\"s_node\",\"direction\":\"ou"
                    + "tgoing\",\"label\":\"s_label\",\"metric\":\"overlap\",\"limit\":3}}}");
        }
    }

    @Test
    void nodeSimilaritySendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"direction\":\"both\",\"metric\":\"jaccard\",\"node\":\"a\",\"result"
                        + "s\":[{\"id\":\"d\",\"score\":0.5}]}}")) {
            try (TriCore db = peer.connect()) {
                db.graphNodeSimilarity("s_graph", "s_node");
            }
            peer.assertSent(
                    "{\"Graph\":{\"NodeSimilarity\":{\"graph\":\"s_graph\",\"node\":\"s_node\",\"direction\":\"bo"
                    + "th\",\"label\":null,\"metric\":\"jaccard\",\"limit\":null}}}");
        }
    }

    @Test
    void checkConsistencySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"consistent\":true,\"dangling_edges\":{\"count\":0,\"listed\":[]},\"edges\":5,\""
                        + "mirror\":{\"differences\":{\"count\":0,\"listed\":[]},\"loaded\":true},\"misfiled\":{\"count"
                        + "\":0,\"listed\":[]},\"nodes\":5,\"undecodable\":{\"count\":0,\"listed\":[]}}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCheckConsistency("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"CheckConsistency\":{\"graph\":\"s_graph\"}}}");
        }
    }

    @Test
    void createNodeIndexSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"created\":true,\"name\":\"by_name\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCreateNodeIndex("s_graph", "s_name", List.of("a", "b"), "s_label");
            }
            peer.assertSent(
                    "{\"Graph\":{\"CreateNodeIndex\":{\"graph\":\"s_graph\",\"name\":\"s_name\",\"label\":\"s_lab"
                    + "el\",\"properties\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void createNodeIndexSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"created\":true,\"name\":\"by_name\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCreateNodeIndex("s_graph", "s_name", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Graph\":{\"CreateNodeIndex\":{\"graph\":\"s_graph\",\"name\":\"s_name\",\"label\":null,\""
                    + "properties\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void createConstraintSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"created\":true,\"kind\":\"unique\",\"name\":\"uniq_name\",\"target\":\"node\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCreateConstraint(
                        "s_graph", "s_name", "s_label", "s_property", GraphConstraintKind.REQUIRED, GraphConstraintTarget.EDGE);
            }
            peer.assertSent(
                    "{\"Graph\":{\"CreateConstraint\":{\"graph\":\"s_graph\",\"name\":\"s_name\",\"label\":\"s_la"
                    + "bel\",\"property\":\"s_property\",\"kind\":\"required\",\"target\":\"edge\"}}}");
        }
    }

    @Test
    void createConstraintSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"created\":true,\"kind\":\"unique\",\"name\":\"uniq_name\",\"target\":\"node\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCreateConstraint(
                        "s_graph", "s_name", "s_label", "s_property", GraphConstraintKind.REQUIRED);
            }
            peer.assertSent(
                    "{\"Graph\":{\"CreateConstraint\":{\"graph\":\"s_graph\",\"name\":\"s_name\",\"label\":\"s_la"
                    + "bel\",\"property\":\"s_property\",\"kind\":\"required\",\"target\":\"node\"}}}");
        }
    }

    @Test
    void dropSchemaObjectSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"dropped\":true,\"name\":\"by_two\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphDropSchemaObject("s_graph", "s_name");
            }
            peer.assertSent(
                    "{\"Graph\":{\"DropSchemaObject\":{\"graph\":\"s_graph\",\"name\":\"s_name\"}}}");
        }
    }

    @Test
    void listSchemaObjectsSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"constraints\":[{\"kind\":\"unique\",\"label\":\"P\",\"name\":\"uniq_name\",\"pr"
                        + "operty\":\"name\",\"target\":\"node\"}],\"indexes\":[{\"label\":\"P\",\"name\":\"by_name\","
                        + "\"properties\":[\"name\"]},{\"label\":null,\"name\":\"by_two\",\"properties\":[\"name\",\"ag"
                        + "e\"]}]}}")) {
            try (TriCore db = peer.connect()) {
                db.graphListSchemaObjects("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"ListSchemaObjects\":{\"graph\":\"s_graph\"}}}");
        }
    }

    @Test
    void findNodesSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"nodes\":[{\"id\":\"b\",\"labels\":[\"P\"],\"properties\":{\"name\":"
                        + "\"B\"}}],\"plan\":\"index `by_name`\",\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphFindNodes("s_graph", "s_label", Map.of("k", "v"), 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"FindNodes\":{\"graph\":\"s_graph\",\"label\":\"s_label\",\"properties\":{\"k\""
                    + ":\"v\"},\"limit\":3}}}");
        }
    }

    @Test
    void findNodesSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"nodes\":[{\"id\":\"b\",\"labels\":[\"P\"],\"properties\":{\"name\":"
                        + "\"B\"}}],\"plan\":\"index `by_name`\",\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphFindNodes("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"FindNodes\":{\"graph\":\"s_graph\",\"label\":null,\"limit\":null}}}");
        }
    }

    @Test
    void importCsvSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"atomic\":true,\"edges\":1,\"nodes\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.graphImportCsv("s_graph", "s_nodes", "s_edges");
            }
            peer.assertSent(
                    "{\"Graph\":{\"ImportCsv\":{\"graph\":\"s_graph\",\"nodes\":\"s_nodes\",\"edges\":\"s_edges\""
                    + "}}}");
        }
    }

    @Test
    void importCsvSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"atomic\":true,\"edges\":1,\"nodes\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.graphImportCsv("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"ImportCsv\":{\"graph\":\"s_graph\",\"nodes\":null,\"edges\":null}}}");
        }
    }

    @Test
    void pageRankSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"converged\":true,\"count\":2,\"damping\":0.85,\"iterations\":19,\"ranks\":[{\"i"
                        + "d\":\"c\",\"score\":0.25}],\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphPageRank("s_graph", 0.5, 3, 0.25, "s_label", 3);
            }
            peer.assertSent(
                    "{\"Graph\":{\"PageRank\":{\"graph\":\"s_graph\",\"damping\":0.5,\"iterations\":3,\"tolerance"
                    + "\":0.25,\"label\":\"s_label\",\"limit\":3}}}");
        }
    }

    @Test
    void pageRankSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"converged\":true,\"count\":2,\"damping\":0.85,\"iterations\":19,\"ranks\":[{\"i"
                        + "d\":\"c\",\"score\":0.25}],\"truncated\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.graphPageRank("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"PageRank\":{\"graph\":\"s_graph\",\"damping\":null,\"iterations\":null,\"toler"
                    + "ance\":null,\"label\":null,\"limit\":null}}}");
        }
    }

    @Test
    void executeSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"graph\":\"g\",\"labelsAdded\":0,\"labelsRemoved\":0,\"nodesCreated\":1,\"nodesD"
                        + "eleted\":0,\"propertiesSet\":0,\"relationshipsCreated\":0,\"relationshipsDeleted\":0,\"state"
                        + "ments\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphExecute("s_graph", "s_cypher");
            }
            peer.assertSent(
                    "{\"Graph\":{\"Execute\":{\"graph\":\"s_graph\",\"cypher\":\"s_cypher\"}}}");
        }
    }

    @Test
    void beginTransactionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"transaction\":\"gtx-1-1\"}}")) {
            try (TriCore db = peer.connect()) {
                db.graphBeginTransaction("s_graph");
            }
            peer.assertSent(
                    "{\"Graph\":{\"BeginTransaction\":{\"graph\":\"s_graph\"}}}");
        }
    }

    @Test
    void commitTransactionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"committed\":1,\"cypher\":\"CREATE (n:P)\",\"graph\":\"g\",\"labelsAdded\":0,\"l"
                        + "abelsRemoved\":0,\"nodesCreated\":1,\"nodesDeleted\":0,\"propertiesSet\":0,\"relationshipsCr"
                        + "eated\":0,\"relationshipsDeleted\":0,\"statements\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphCommitTransaction("s_graph", "tx-1");
            }
            peer.assertSent(
                    "{\"Graph\":{\"CommitTransaction\":{\"graph\":\"s_graph\"}}}",
                    "tx-1");
        }
    }

    @Test
    void rollbackTransactionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"rolled_back\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.graphRollbackTransaction("s_graph", "tx-1");
            }
            peer.assertSent(
                    "{\"Graph\":{\"RollbackTransaction\":{\"graph\":\"s_graph\"}}}",
                    "tx-1");
        }
    }
}
