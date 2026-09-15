package io.github.trinesh14.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Document, vector, graph, cache and LLM round-trips, ported from ModelsTest. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelsLiveTest {

    private TestServer server;
    private TriCore db;

    @BeforeAll
    void start() throws Exception {
        server = TestServer.start("sdk-java-models");
        db = server.connect();
    }

    @AfterAll
    void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static Map<String, Object> doc(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static int intOf(Object o) {
        return ((Number) o).intValue();
    }

    @Test
    void documents() {
        String coll = "jm_users";
        db.documentCreateCollection(coll);
        assertTrue(db.documentListCollections().contains(coll));

        db.documentInsert(coll, "u1", doc("name", "asha", "city", "Pune", "age", 31));
        db.documentInsert(coll, "u2", doc("name", "ravi", "city", "Delhi", "age", 24));
        Map<String, Object> u1 = db.documentGet(coll, "u1").orElseThrow();
        assertEquals("asha", u1.get("name"));
        assertEquals("Pune", u1.get("city"));
        assertTrue(db.documentGet(coll, "no-such-id").isEmpty());

        String generated = db.documentInsert(coll, doc("name", "tmp"));
        assertFalse(generated.isEmpty());
        assertTrue(db.documentGet(coll, generated).isPresent());
        db.documentDelete(coll, generated);
        assertTrue(db.documentGet(coll, generated).isEmpty());

        List<Map<String, Object>> pune = db.documentFind(coll, DocumentFilter.eq("city", "Pune"));
        assertEquals(1, pune.size());
        assertEquals("asha", pune.get(0).get("name"));
        assertEquals(1, db.documentFind(coll,
                DocumentFilter.and(DocumentFilter.gt("age", 30), DocumentFilter.eq("city", "Pune"))).size());
        assertTrue(db.documentFind(coll,
                DocumentFilter.and(DocumentFilter.gt("age", 30), DocumentFilter.eq("city", "Delhi"))).isEmpty());
        assertEquals(2, db.documentFind(coll, DocumentFilter.inValues("city", List.of("Pune", "Delhi"))).size());
        assertEquals(1, db.documentFind(coll, DocumentFilter.all(), 1).size());

        db.documentUpdate(coll, "u1", Map.of("city", "Mumbai"));
        assertEquals("Mumbai", db.documentGet(coll, "u1").orElseThrow().get("city"));
        assertEquals("asha", db.documentGet(coll, "u1").orElseThrow().get("name"));
        assertFalse(db.documentUpdateOne(coll, "u1", DocumentUpdate.inc("age", 2), false));
        assertEquals(33, intOf(db.documentGet(coll, "u1").orElseThrow().get("age")));
        assertTrue(db.documentUpdateOne(coll, "u9", DocumentUpdate.builder().set("name", "new").build(), true));

        db.documentInsert(coll, "u3", doc("name", "kim", "city", "Mumbai", "age", 40));
        UpdateManyResult many = db.documentUpdateMany(coll, DocumentFilter.eq("city", "Mumbai"),
                DocumentUpdate.set("tier", "gold"));
        assertEquals(2, many.matched());
        assertEquals(2, many.modified());
        assertNull(db.documentGet(coll, "u2").orElseThrow().get("tier"));

        db.documentCreateIndex(coll, "by_city", "city", false);
        assertTrue(db.documentListIndexes(coll).stream()
                .anyMatch(i -> "by_city".equals(i.indexName()) && "city".equals(i.field())));
        db.documentDropIndex(coll, "by_city");
        assertTrue(db.documentListIndexes(coll).stream().noneMatch(i -> "by_city".equals(i.indexName())));

        String sales = "jm_sales";
        db.documentCreateCollection(sales);
        db.documentInsert(sales, "s1", doc("tier", "gold", "amount", 100));
        db.documentInsert(sales, "s2", doc("tier", "gold", "amount", 50));
        db.documentInsert(sales, "s3", doc("tier", "silver", "amount", 7));
        List<Map<String, Object>> grouped = db.documentAggregate(sales, List.of(
                AggregateStage.group(AggregateStage.byField("tier"),
                        AggregateStage.sum("total", "amount"), AggregateStage.countInto("n")),
                AggregateStage.sort(AggregateStage.desc("total"))));
        assertEquals(2, grouped.size());
        assertEquals("gold", grouped.get(0).get("_id"));
        assertEquals(150, intOf(grouped.get(0).get("total")));
        assertEquals(2, intOf(grouped.get(0).get("n")));
        assertEquals(7, intOf(grouped.get(1).get("total")));
        assertEquals(3, db.documentAnalyze(sales).documentCount());

        assertThrows(TriCoreException.class, () -> db.documentGet("jm_no_such_collection", "x"));
        assertThrows(TriCoreException.class,
                () -> db.documentUpdateOne(coll, "u2", DocumentUpdate.inc("name", 1), false));

        db.documentDropCollection(sales);
        db.documentDropCollection(coll);
        assertFalse(db.documentListCollections().contains(coll));
    }

    @Test
    void vectors() {
        String coll = "jm_vecs";
        db.vectorCreateCollection(coll, 3, VectorMetric.COSINE);
        VectorCollectionInfo info = db.vectorDescribeCollection(coll);
        assertEquals(3, info.dimension());
        assertEquals(VectorMetric.COSINE, info.metric());
        assertEquals(0, info.count());

        db.vectorUpsert(coll, "alpha", new float[] {1f, 0f, 0f}, Map.of("tier", "gold"));
        db.vectorUpsert(coll, "beta", new float[] {0f, 1f, 0f}, Map.of("tier", "silver"));
        db.vectorUpsert(coll, "gamma", new float[] {0f, 0f, 1f}, Map.of("tier", "gold"));
        VectorItem alpha = db.vectorGet(coll, "alpha").orElseThrow();
        assertEquals(1f, alpha.vector()[0]);
        assertEquals(0f, alpha.vector()[1]);
        assertEquals("gold", alpha.metadata().get("tier"));
        assertTrue(db.vectorGet(coll, "none").isEmpty());

        List<VectorMatch> hits = db.vectorSearch(coll, new float[] {0.9f, 0.1f, 0f}, 1);
        assertEquals(1, hits.size());
        assertEquals("alpha", hits.get(0).id());
        List<VectorMatch> silver = db.vectorSearch(coll, new float[] {1f, 0f, 0f}, 3, Map.of("tier", "silver"));
        assertEquals(1, silver.size());
        assertEquals("beta", silver.get(0).id());

        String l2 = "jm_vecs_l2";
        db.vectorCreateCollection(l2, 3, VectorMetric.L2);
        db.vectorUpsert(l2, "near", new float[] {1f, 0f, 0f});
        db.vectorUpsert(l2, "mid", new float[] {2f, 0f, 0f});
        db.vectorUpsert(l2, "far", new float[] {10f, 0f, 0f});
        List<VectorMatch> byL2 = db.vectorSearch(l2, new float[] {1f, 0f, 0f}, 3);
        List<String> order = new ArrayList<>();
        byL2.forEach(m -> order.add(m.id()));
        assertEquals(List.of("near", "mid", "far"), order);
        assertEquals(-81.0, byL2.get(2).score(), 1e-3);

        VectorPage page = db.vectorListVectors(coll, 2, 0);
        assertEquals(2, page.vectors().size());
        assertTrue(page.truncated());
        assertEquals(3, page.total());

        assertThrows(TriCoreException.class, () -> db.vectorUpsert(coll, "bad", new float[] {1f, 2f}));
        db.vectorDelete(coll, "beta");
        assertTrue(db.vectorGet(coll, "beta").isEmpty());
        db.vectorDropCollection(l2);
        db.vectorDropCollection(coll);
        assertFalse(db.vectorListCollections().contains(coll));
    }

    @Test
    void graphs() {
        String g = "jm_graph";
        db.graphCreate(g);
        assertTrue(db.graphList().contains(g));
        db.graphAddNode(g, "a", List.of("Person"), Map.of("name", "Asha", "city", "Pune"));
        db.graphAddNode(g, "b", List.of("Person"), Map.of("name", "Ravi", "city", "Delhi"));
        db.graphAddNode(g, "c", List.of("Person"), Map.of("name", "Kim", "city", "Pune"));
        db.graphAddNode(g, "island", List.of("Person"), Map.of("name", "Nobody"));
        GraphNode a = db.graphGetNode(g, "a").orElseThrow();
        assertEquals(List.of("Person"), a.labels());
        assertEquals("Asha", a.properties().get("name"));

        db.graphAddEdge(g, "e_ab", "a", "b", "KNOWS", Map.of("weight", 1));
        db.graphAddEdge(g, "e_bc", "b", "c", "KNOWS", Map.of("weight", 1));
        db.graphAddEdge(g, "e_ac", "a", "c", "KNOWS", Map.of("weight", 10));
        GraphEdge e = db.graphGetEdge(g, "e_ab").orElseThrow();
        assertEquals("a", e.from());
        assertEquals("b", e.to());
        assertEquals("KNOWS", e.label());

        assertEquals(2, db.graphNeighbors(g, "a", GraphDirection.OUTGOING).size());
        assertEquals(2, db.graphDegree(g, "c", GraphDirection.INCOMING));
        assertEquals(0, db.graphDegree(g, "island", GraphDirection.BOTH));

        GraphPath hops = db.graphShortestPath(g, "a", "c", GraphDirection.OUTGOING);
        assertEquals(List.of("a", "c"), hops.nodePath());
        GraphPath cheap = db.graphWeightedShortestPath(g, "a", "c", GraphDirection.OUTGOING);
        assertEquals(List.of("a", "b", "c"), cheap.nodePath());
        assertEquals(2.0, cheap.totalCost().orElseThrow());
        GraphPath none = db.graphShortestPath(g, "a", "island", GraphDirection.OUTGOING);
        assertFalse(none.found());
        assertNotNull(none.message());

        GraphQueryResult q = db.graphQuery(g, "MATCH (n:Person) WHERE n.city = 'Pune' RETURN n.name ORDER BY n.name");
        assertEquals(List.of("n.name"), q.columns());
        assertEquals("Asha", q.rows().get(0).get(0));
        assertEquals("Kim", q.rows().get(1).get(0));
        assertEquals(2, q.rows().size());
        assertThrows(TriCoreException.class, () -> db.graphQuery(g, "CREATE (n:Person {name: 'Mallory'})"));

        assertEquals(4, db.graphListNodes(g).total());
        assertEquals(3, db.graphListEdges(g).total());
        db.graphDeleteEdge(g, "e_ac");
        assertTrue(db.graphGetEdge(g, "e_ac").isEmpty());
        db.graphDrop(g);
        assertFalse(db.graphList().contains(g));
    }

    @Test
    void cacheStructuresAndLlmExport() {
        String ns = "jm_cache";
        assertEquals(2, db.cacheRPush(ns, "q", List.of("a".getBytes(), "b".getBytes())));
        assertEquals("a", new String(db.cacheLPop(ns, "q").orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(5, db.cacheIncr(ns, "n", 5));
        assertEquals(1, db.cacheHSet(ns, "h", List.of(CachePair.ofText("city", "Pune"))));
        Optional<byte[]> city = db.cacheHGet(ns, "h", "city".getBytes());
        assertEquals("Pune", new String(city.orElseThrow(), StandardCharsets.UTF_8));
        String id = db.cacheXAdd(ns, "s", List.of(CachePair.ofText("k", "v")), null);
        assertEquals(1, db.cacheXRange(ns, "s", "-", "+", null).size());
        assertEquals(id, db.cacheXRange(ns, "s", "-", "+", null).get(0).id());

        db.execute("CREATE TABLE jm_llm (id INT PRIMARY KEY, name TEXT)");
        db.execute("INSERT INTO jm_llm VALUES (1, 'zebra-marker')");
        String ctx = db.llmContext(List.of(LlmSource.sql("SELECT id, name FROM jm_llm")),
                OutputFormat.JSON, null, "main");
        assertTrue(ctx.contains("zebra-marker"), ctx);
        assertTrue(db.llmSchema(OutputFormat.MARKDOWN, null, "main").contains("jm_llm"));
        db.adminPing();
        assertFalse(db.adminStatus().isEmpty());
    }
}
