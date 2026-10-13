package com.tricoredb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The operations added for server 0.1.0-rc.3, against a real server.
 *
 * <p>They run against the server named by {@code TRICORE_TEST_ADDR}
 * ({@code host:port}, with {@code TRICORE_TEST_USER} and
 * {@code TRICORE_TEST_PASSWORD}) when it is set, and against a private server
 * otherwise. A named server may be shared, so every name used here starts with
 * {@code java_} and carries a random suffix, and nothing this class did not
 * create is dropped, flushed or purged.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsLiveTest {

    private TestServer server;
    private TriCore db;
    private TriCore other;

    @BeforeAll
    void start() throws Exception {
        String addr = System.getenv("TRICORE_TEST_ADDR");
        if (addr == null || addr.isBlank()) {
            server = TestServer.start("sdk-java-operations");
            db = server.connect();
            other = server.connect();
        } else {
            db = connect(addr.trim());
            other = connect(addr.trim());
        }
    }

    private static TriCore connect(String addr) {
        int colon = addr.lastIndexOf(':');
        String user = System.getenv("TRICORE_TEST_USER");
        String password = System.getenv("TRICORE_TEST_PASSWORD");
        return TriCore.connect(addr.substring(0, colon), Integer.parseInt(addr.substring(colon + 1)),
                user == null || user.isEmpty() ? "admin" : user, password == null ? "" : password);
    }

    @AfterAll
    void stop() throws Exception {
        if (db != null) {
            db.close();
        }
        if (other != null) {
            other.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static byte[] b(String s) {
        return s.getBytes(UTF_8);
    }

    private static String s(byte[] bytes) {
        return new String(bytes, UTF_8);
    }

    private static List<String> texts(List<byte[]> values) {
        return values.stream().map(OperationsLiveTest::s).toList();
    }

    private static Map<String, Object> doc(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    // -- cache -------------------------------------------------------------------

    @Test
    void sortedSetsRankAndRange() {
        String ns = unique("java_z");
        CacheZAddResult added = db.cacheZAdd(ns, "board", List.of(
                ScoredMember.ofText(1, "ann"), ScoredMember.ofText(2.5, "bob"), ScoredMember.ofText(3, "cy")));
        assertEquals(new CacheZAddResult(3, 0), added);
        assertEquals(new CacheZAddResult(0, 1), db.cacheZAdd(ns, "board", List.of(ScoredMember.ofText(9, "ann"))));
        assertEquals(3, db.cacheZCard(ns, "board"));
        assertEquals(9.0, db.cacheZScore(ns, "board", b("ann")).orElseThrow());
        assertTrue(db.cacheZScore(ns, "board", b("nobody")).isEmpty());
        assertEquals(2, db.cacheZRank(ns, "board", b("ann"), false).orElseThrow());
        assertEquals(0, db.cacheZRank(ns, "board", b("ann"), true).orElseThrow());
        assertTrue(db.cacheZRank(ns, "board", b("nobody"), false).isEmpty());

        List<CacheZMember> all = db.cacheZRange(ns, "board", 0, -1, false, true);
        assertEquals(List.of("bob", "cy", "ann"), all.stream().map(m -> s(m.member())).toList());
        assertEquals(List.of(2.5, 3.0, 9.0), all.stream().map(m -> m.score().orElseThrow()).toList());
        List<CacheZMember> bare = db.cacheZRange(ns, "board", 0, 0, true, false);
        assertEquals(1, bare.size());
        assertEquals("ann", s(bare.get(0).member()));
        assertTrue(bare.get(0).score().isEmpty());

        List<CacheZMember> mid = db.cacheZRangeByScore(
                ns, "board", ScoreBound.exclusive(2.5), ScoreBound.max(), false, true);
        assertEquals(List.of("cy", "ann"), mid.stream().map(m -> s(m.member())).toList());
        List<CacheZMember> paged = db.cacheZRangeByScore(
                ns, "board", ScoreBound.min(), ScoreBound.max(), false, false, 1L, 1L);
        assertEquals(List.of("cy"), paged.stream().map(m -> s(m.member())).toList());

        assertEquals(4.5, db.cacheZIncrBy(ns, "board", b("cy"), 1.5));
        assertEquals(1, db.cacheZRem(ns, "board", List.of(b("cy"), b("nobody"))));
        assertEquals(2, db.cacheZCard(ns, "board"));
        db.cacheClearNamespace(ns);
    }

    @Test
    void locksFenceWithARisingToken() {
        String ns = unique("java_lock");
        CacheLock first = db.cacheLockAcquire(ns, "job", "worker-1", 60_000);
        assertTrue(first.acquired());
        assertTrue(first.token().isPresent());
        assertEquals("worker-1", first.holder());

        CacheLock blocked = db.cacheLockAcquire(ns, "job", "worker-2", 60_000);
        assertFalse(blocked.acquired());
        assertTrue(blocked.token().isEmpty());
        assertEquals("worker-1", blocked.holder());
        assertEquals(first.token(), db.cacheLockAcquire(ns, "job", "worker-1", 60_000).token());

        CacheLockRenewal renewed = db.cacheLockRenew(ns, "job", "worker-1", 60_000);
        assertTrue(renewed.renewed());
        assertEquals(first.token(), renewed.token());
        assertFalse(db.cacheLockRenew(ns, "job", "worker-2", 60_000).renewed());

        assertFalse(db.cacheLockRelease(ns, "job", "worker-2"));
        assertTrue(db.cacheLockRelease(ns, "job", "worker-1"));
        CacheLock next = db.cacheLockAcquire(ns, "job", "worker-2", 60_000);
        assertTrue(next.acquired());
        assertTrue(next.token().getAsLong() > first.token().getAsLong());
        assertTrue(db.cacheLockRelease(ns, "job", "worker-2"));
        db.cacheClearNamespace(ns);
    }

    @Test
    void bitmapsAndHyperLogLogs() {
        String ns = unique("java_bits");
        assertFalse(db.cacheSetBit(ns, "a", 7, true));
        assertTrue(db.cacheSetBit(ns, "a", 7, true));
        assertTrue(db.cacheGetBit(ns, "a", 7));
        assertFalse(db.cacheGetBit(ns, "a", 6));
        assertFalse(db.cacheGetBit(ns, "a", 4096));
        assertArrayEquals(new byte[] {1}, db.cacheGet(ns, "a").orElseThrow());
        db.cacheSetBit(ns, "b", 0, true);
        assertEquals(1, db.cacheBitCount(ns, "a"));
        assertEquals(1, db.cacheBitCount(ns, "a", 0L, -1L));
        assertEquals(7, db.cacheBitPos(ns, "a", true));
        assertEquals(1, db.cacheBitOp(ns, CacheBitOp.OR, "both", List.of("a", "b")));
        assertArrayEquals(new byte[] {(byte) 0x81}, db.cacheGet(ns, "both").orElseThrow());
        assertEquals(2, db.cacheBitCount(ns, "both"));

        assertTrue(db.cachePfAdd(ns, "h1", List.of(b("a"), b("b"), b("c"))));
        assertTrue(db.cachePfAdd(ns, "h2", List.of(b("c"), b("d"))));
        assertEquals(3, db.cachePfCount(ns, List.of("h1")));
        assertEquals(4, db.cachePfCount(ns, List.of("h1", "h2")));
        db.cachePfMerge(ns, "h3", List.of("h1", "h2"));
        assertEquals(4, db.cachePfCount(ns, List.of("h3")));
        db.cacheClearNamespace(ns);
    }

    @Test
    void manyKeysInOneRequest() {
        String ns = unique("java_multi");
        assertEquals(2, db.cacheMSet(ns, List.of(CacheEntry.ofText("a", "1"), CacheEntry.ofText("b", "2"))));
        List<Optional<byte[]>> values = db.cacheMGet(ns, List.of("a", "missing", "b", "a"));
        assertEquals(List.of("1", "<none>", "2", "1"),
                values.stream().map(v -> v.map(OperationsLiveTest::s).orElse("<none>")).toList());

        assertEquals(new CacheWarmResult(1, 1),
                db.cacheWarm(ns, List.of(CacheEntry.ofText("a", "x"), CacheEntry.ofText("c", "3"))));
        assertEquals("1", db.cacheGetText(ns, "a").orElseThrow());

        assertFalse(db.cacheSetIf(ns, "a", b("z"), CacheSetCondition.IF_ABSENT, CacheExpiry.clear()));
        assertTrue(db.cacheSetIf(ns, "a", b("z"), CacheSetCondition.IF_PRESENT, CacheExpiry.set(60_000)));
        assertTrue(db.cacheTtl(ns, "a").isPresent());
        assertTrue(db.cacheSetIf(ns, "a", b("y"), CacheSetCondition.ALWAYS, CacheExpiry.keep()));
        assertTrue(db.cacheTtl(ns, "a").isPresent());
        assertEquals("y", s(db.cacheGetEx(ns, "a", CacheExpiry.clear()).orElseThrow()));
        assertTrue(db.cacheTtl(ns, "a").isEmpty());

        assertEquals(2, db.cacheAppend(ns, "buf", b("ab")));
        assertEquals(4, db.cacheAppend(ns, "buf", b("cd")));
        assertTrue(db.cacheCompareAndSwap(ns, "buf", b("abcd"), b("next")).swapped());
        CacheSwapResult lost = db.cacheCompareAndSwap(ns, "buf", b("abcd"), b("other"));
        assertFalse(lost.swapped());
        assertEquals("next", lost.current());
        assertTrue(db.cacheCompareAndSwap(ns, "fresh-key", null, b("made")).swapped());
        assertTrue(db.cacheCompareAndSwap(ns, "fresh-key", b("made"), null).swapped());
        assertFalse(db.cacheExists(ns, "fresh-key"));

        assertTrue(db.cacheGetSet(ns, "gs", b("1")).isEmpty());
        assertEquals("1", s(db.cacheGetSet(ns, "gs", b("2"), 60_000L).orElseThrow()));
        assertEquals("2", s(db.cacheGetDel(ns, "gs").orElseThrow()));
        assertTrue(db.cacheGetDel(ns, "gs").isEmpty());

        long deadline = System.currentTimeMillis() + 600_000;
        assertEquals(new CacheExpireAtResult(true, false), db.cacheExpireAt(ns, "b", deadline));
        assertEquals(new CacheExpireAtResult(false, false), db.cacheExpireAt(ns, "missing", deadline));
        assertEquals(5, db.cacheHIncrBy(ns, "hash", b("n"), 5));
        assertEquals(3, db.cacheHIncrBy(ns, "hash", b("n"), -2));
        db.cacheClearNamespace(ns);
    }

    @Test
    void listsAndSets() {
        String ns = unique("java_coll");
        db.cacheRPush(ns, "src", List.of(b("a"), b("b"), b("c")));
        assertEquals("a", s(db.cacheLMove(ns, "src", "dst", CacheListEnd.LEFT, CacheListEnd.RIGHT).orElseThrow()));
        assertTrue(db.cacheLMove(ns, "empty", "dst", CacheListEnd.LEFT, CacheListEnd.RIGHT).isEmpty());
        assertEquals(new CacheTrimResult(1, 1), db.cacheLTrim(ns, "src", 0, 0));
        assertEquals(List.of("b"), texts(db.cacheLRange(ns, "src", 0, -1)));

        CachePopped popped = db.cacheBLPop(ns, List.of("empty", "dst"), 200).orElseThrow();
        assertEquals("dst", popped.key());
        assertEquals("a", s(popped.value()));
        assertTrue(db.cacheBRPop(ns, List.of("empty"), 100).isEmpty());
        assertEquals("b", s(db.cacheBRPop(ns, List.of("src"), 100).orElseThrow().value()));

        db.cacheSAdd(ns, "s1", List.of(b("a"), b("b")));
        db.cacheSAdd(ns, "s2", List.of(b("b"), b("c")));
        assertEquals(List.of("a", "b", "c"), texts(db.cacheSUnion(ns, List.of("s1", "s2"))));
        assertEquals(List.of("b"), texts(db.cacheSInter(ns, List.of("s1", "s2"))));
        assertEquals(List.of("a"), texts(db.cacheSDiff(ns, List.of("s1", "s2"))));
        assertEquals(1, db.cacheSRandMember(ns, "s1").size());
        List<String> sampled = texts(db.cacheSRandMember(ns, "s1", -5L));
        assertEquals(5, sampled.size());
        assertTrue(List.of("a", "b").containsAll(sampled));
        db.cacheClearNamespace(ns);
    }

    @Test
    void pipelinesTransactionsAndScripts() {
        String ns = unique("java_batch");
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("namespace", ns);
        push.put("key", "n");
        push.put("values", List.of(b("x")));
        List<CacheOpResult> piped = db.cachePipeline(List.of(
                CacheCommand.set(ns, "n", b("1"), null),
                CacheCommand.get(ns, "n"),
                CacheCommand.incr(ns, "n", 2),
                CacheCommand.of("LPush", push),
                CacheCommand.unit("Ping")));
        assertEquals(List.of(true, true, true, false, true), piped.stream().map(CacheOpResult::ok).toList());
        assertEquals("1", s(piped.get(1).value().orElseThrow()));
        assertEquals(3, num(piped.get(2).json().get("value")));
        assertTrue(piped.get(3).error().contains("lpush"), piped.get(3).error());

        List<CacheOpResult> txn = db.cacheTransaction(List.of(
                CacheCommand.set(ns, "t", b("5"), null), CacheCommand.incr(ns, "t", 1), CacheCommand.get(ns, "t")));
        assertTrue(txn.stream().allMatch(CacheOpResult::ok));
        assertEquals("6", s(txn.get(2).value().orElseThrow()));

        String script = unique("java_script");
        assertEquals(2, db.cacheScriptLoad(script, List.of(
                CacheCommand.set(ns, "$1", b("$2"), null), CacheCommand.get(ns, "$1"))));
        List<CacheOpResult> ran = db.cacheScriptRun(script, List.of("greeting", "hello"));
        assertEquals("hello", s(ran.get(1).value().orElseThrow()));
        assertEquals("hello", db.cacheGetText(ns, "greeting").orElseThrow());
        TriCoreException missing = assertThrows(TriCoreException.class,
                () -> db.cacheScriptRun(unique("java_noscript")));
        assertTrue(missing.getMessage().contains("no cache script"), missing.getMessage());
        db.cacheClearNamespace(ns);
    }

    @Test
    void publishAndPoll() {
        String channel = unique("java_ch");
        long first = db.cachePublish(channel, "one");
        long second = db.cachePublish(channel, "two");
        assertTrue(second > first);

        CachePollResult all = db.cachePoll(List.of(channel));
        assertEquals(List.of("one", "two"), all.messages().stream().map(CacheMessage::message).toList());
        assertEquals(List.of(first, second), all.messages().stream().map(CacheMessage::id).toList());
        assertTrue(all.messages().stream().allMatch(m -> m.channel().equals(channel)));
        assertEquals(second, all.next());

        CachePollResult later = db.cachePoll(List.of(channel), first, 10);
        assertEquals(List.of("two"), later.messages().stream().map(CacheMessage::message).toList());
        assertTrue(db.cachePoll(List.of(channel), second, 10).messages().isEmpty());
    }

    @Test
    void freshnessLimitsStatsAndDatabaseWidePurges() throws Exception {
        String ns = unique("java_fresh");
        assertTrue(db.cacheSetFresh(ns, "page", b("body"), 60_000) > System.currentTimeMillis());
        CacheFreshRead hit = db.cacheGetFresh(ns, "page", "reader", 1_000);
        assertEquals("fresh", hit.state());
        assertEquals("body", hit.value());

        CacheFreshRead miss = db.cacheGetFresh(ns, "absent", "reader-1", 5_000);
        assertEquals("miss", miss.state());
        assertTrue(miss.lease());
        CacheFreshRead waiting = db.cacheGetFresh(ns, "absent", "reader-2", 5_000);
        assertFalse(waiting.lease());
        assertTrue(waiting.retryAfterMs().isPresent());

        db.cacheSetMissing(ns, "gone", 60_000);
        assertEquals("missing", db.cacheGetFresh(ns, "gone", "reader", 1_000).state());

        db.cacheSetFresh(ns, "bin", new byte[] {(byte) 0xff, (byte) 0xfe}, 60_000);
        CacheFreshRead binary = db.cacheGetFresh(ns, "bin", "reader", 1_000);
        assertNull(binary.value());
        assertEquals("fffe", binary.valueHex());

        String limited = unique("java_lim");
        CacheNamespaceLimit limit = db.cacheSetNamespaceLimit(limited, 1L, 1_000L);
        assertEquals(1, limit.maxKeys().orElseThrow());
        assertEquals(1_000, limit.maxBytes().orElseThrow());
        db.cacheSet(limited, "a", b("1"));
        TriCoreException refused = assertThrows(TriCoreException.class, () -> db.cacheSet(limited, "b", b("1")));
        assertTrue(refused.getMessage().contains("limited to 1"), refused.getMessage());
        CacheNamespaceLimit held = db.cacheGetNamespaceLimit(limited);
        assertEquals(1, held.keys());
        assertEquals(limited, held.namespace());
        assertTrue(db.cacheSetNamespaceLimit(limited).maxKeys().isEmpty());
        db.cacheClearNamespace(limited);
        db.cacheClearNamespace(ns);

        Map<String, Object> stats = db.cacheStats();
        assertTrue(stats.containsKey("hits") && stats.containsKey("misses"), stats.toString());

        // Database-wide, so they run in a database nothing else uses.
        String own = unique("java_db");
        db.cacheSet("n", "keep", b("1"), null, own);
        db.cacheSet("n", "brief", b("1"), 1L, own);
        Thread.sleep(50);
        assertEquals(1, db.cachePurgeExpired(own));
        assertEquals(1, db.cacheFlushDatabase(own));
        assertTrue(db.cacheGet("n", "keep", own).isEmpty());
    }

    @Test
    void hotKeysIsRefusedUntilTheServerTracksThem() {
        try {
            db.cacheHotKeys(5);
        } catch (TriCoreException e) {
            assertTrue(e.getMessage().contains("hot_keys"), e.getMessage());
        }
    }

    // -- document ----------------------------------------------------------------

    @Test
    void documentsInBulk() {
        String coll = unique("java_docs");
        db.documentCreateCollection(coll);
        String current = coll;
        try {
            List<String> ids = db.documentInsertMany(coll, List.of(
                    doc("_id", "a", "n", 1, "city", "Pune"),
                    doc("_id", "b", "n", 2, "city", "Delhi"),
                    doc("n", 3, "city", "Pune")));
            assertEquals(3, ids.size());
            assertEquals(List.of("a", "b"), ids.subList(0, 2));
            assertThrows(TriCoreException.class, () -> db.documentInsertMany(coll,
                    List.of(doc("_id", "z", "n", 9), doc("_id", "a", "n", 9))));
            assertTrue(db.documentGet(coll, "z").isEmpty());

            DocumentReplaceResult replaced = db.documentReplace(coll, "a", doc("n", 10), false, ReturnDocument.AFTER, null);
            assertTrue(replaced.replaced());
            assertFalse(replaced.inserted());
            assertEquals(10, num(replaced.document().get("n")));
            assertFalse(replaced.document().containsKey("city"));
            assertEquals(10, num(db.documentReplace(coll, "a", doc("n", 11), false, ReturnDocument.BEFORE, null)
                    .document().get("n")));
            DocumentReplaceResult upserted = db.documentReplace(coll, "new", doc("n", 12), true, ReturnDocument.NONE, null);
            assertTrue(upserted.inserted());
            assertNull(upserted.document());
            assertThrows(TriCoreException.class, () -> db.documentReplace(coll, "nope", doc("n", 1)));

            Map<String, Object> plan = db.documentExplain(coll, DocumentFilter.eq("city", "Pune"), 5,
                    List.of(AggregateStage.desc("n")), null);
            assertEquals(false, plan.get("index_used"));
            assertTrue(plan.containsKey("rows_examined"));

            db.documentCreateIndex(coll, "by_n", "n", false);
            DocumentIndexRebuild rebuilt = db.documentRebuildIndex(coll, "by_n");
            assertEquals("by_n", rebuilt.rebuilt());
            assertEquals(4, rebuilt.documents());
            assertEquals(0, db.documentPurgeExpired(coll).purged());

            assertEquals(1, db.documentDeleteMany(coll, DocumentFilter.eq("city", "Pune")));
            assertEquals(0, db.documentDeleteMany(coll, DocumentFilter.eq("city", "Pune")));

            BulkWriteResult bulk = db.documentBulkWrite(coll, List.of(
                    BulkOp.insert("k1", doc("n", 1)),
                    BulkOp.insert(doc("n", 2)),
                    BulkOp.updateOne("k1", DocumentUpdate.set("n", 5)),
                    BulkOp.replace("k2", doc("n", 7), true, null),
                    BulkOp.delete("b")));
            assertEquals(2, bulk.inserted());
            assertEquals(1, bulk.updated());
            assertEquals(1, bulk.upserted());
            assertEquals(1, bulk.deleted());
            assertEquals("k1", bulk.insertedIds().get(0));
            assertEquals(5, num(db.documentGet(coll, "k1").orElseThrow().get("n")));
            assertTrue(db.documentGet(coll, "b").isEmpty());

            // One bad operation refuses the whole batch.
            assertThrows(TriCoreException.class, () -> db.documentBulkWrite(coll,
                    List.of(BulkOp.insert("k9", doc("n", 1)), BulkOp.insert("k1", doc("n", 1)))));
            assertTrue(db.documentGet(coll, "k9").isEmpty());

            db.documentRenameCollection(coll, coll + "_r");
            current = coll + "_r";
            assertEquals(5, num(db.documentGet(current, "k1").orElseThrow().get("n")));
        } finally {
            db.documentDropCollection(current);
        }
    }

    @Test
    void collectionOptionsValidateDefaultAndVersion() {
        String coll = unique("java_opts");
        db.documentCreateCollection(coll);
        try {
            CollectionOptions none = db.documentGetCollectionOptions(coll);
            assertNull(none.schema());
            assertFalse(none.versioned());

            db.documentInsert(coll, "a", doc("n", 1));
            Map<String, Object> schema = doc("required", List.of("n"),
                    "fields", doc("n", doc("type", "number", "minimum", 0)));
            assertEquals(1, db.documentSetCollectionOptions(coll,
                    new CollectionOptions(schema, doc("tier", "free"), true)));

            CollectionOptions read = db.documentGetCollectionOptions(coll);
            assertTrue(read.versioned());
            assertEquals("free", read.defaults().get("tier"));
            assertEquals(List.of("n"), read.schema().get("required"));

            assertThrows(TriCoreException.class, () -> db.documentInsert(coll, "bad", doc("n", -1)));
            db.documentInsert(coll, "b", doc("n", 2));
            Map<String, Object> stored = db.documentGet(coll, "b").orElseThrow();
            assertEquals("free", stored.get("tier"));
            assertEquals(1, num(stored.get("_version")));

            DocumentReplaceResult swapped = db.documentReplace(coll, "b", doc("n", 3), false, ReturnDocument.AFTER, 1L);
            assertEquals(2, num(swapped.document().get("_version")));
            TriCoreException stale = assertThrows(TriCoreException.class,
                    () -> db.documentReplace(coll, "b", doc("n", 4), false, ReturnDocument.NONE, 1L));
            assertTrue(stale.getMessage().contains("version"), stale.getMessage());
            assertThrows(TriCoreException.class, () -> db.documentBulkWrite(coll,
                    List.of(BulkOp.updateOne("b", DocumentUpdate.set("n", 6), false, 99L))));
            assertEquals(3, num(db.documentGet(coll, "b").orElseThrow().get("n")));
        } finally {
            db.documentDropCollection(coll);
        }
    }

    @Test
    void documentTransactionsSpanRequests() {
        String coll = unique("java_dtx");
        db.documentCreateCollection(coll);
        try {
            String txn = db.documentBeginTransaction();
            db.setRequestTxn(txn);
            db.documentInsert(coll, "t1", doc("n", 1));
            assertTrue(db.documentGet(coll, "t1").isPresent());
            assertTrue(other.documentGet(coll, "t1").isEmpty());
            assertTrue(db.documentCommitTransaction(txn) >= 1);
            assertNull(db.requestTxn());
            assertTrue(other.documentGet(coll, "t1").isPresent());

            String second = db.documentBeginTransaction(true);
            db.setRequestTxn(second);
            db.documentInsert(coll, "t2", doc("n", 2));
            db.documentRollbackTransaction(second);
            assertNull(db.requestTxn());
            assertTrue(db.documentGet(coll, "t2").isEmpty());
        } finally {
            db.setRequestTxn(null);
            db.documentDropCollection(coll);
        }
    }

    // -- vector ------------------------------------------------------------------

    private static List<String> ids(List<VectorMatch> matches) {
        return matches.stream().map(VectorMatch::id).toList();
    }

    @Test
    void vectorsInBatchesWithFiltersAndCounts() {
        String coll = unique("java_vec");
        db.vectorCreateCollection(coll, 3, VectorMetric.COSINE);
        try {
            assertEquals(4, db.vectorUpsertMany(coll, List.of(
                    new VectorItem("a", new float[] {1f, 0f, 0f}, doc("tier", "gold")),
                    new VectorItem("b", new float[] {0f, 1f, 0f}, doc("tier", "free")),
                    new VectorItem("c", new float[] {0f, 0f, 1f}, doc("tier", "free")),
                    new VectorItem("d", new float[] {0.9f, 0.1f, 0f}, doc("tier", "gold", "_namespace", "p1")))));
            assertEquals(4, db.vectorCount(coll));
            assertEquals(2, db.vectorCount(coll, doc("tier", "gold")));

            List<float[]> queries = List.of(new float[] {1f, 0f, 0f}, new float[] {0f, 1f, 0f});
            List<List<VectorMatch>> batches = db.vectorSearchBatch(coll, queries, 2);
            assertEquals(2, batches.size());
            assertEquals(List.of("a", "d"), ids(batches.get(0)));
            assertEquals("b", batches.get(1).get(0).id());
            List<List<VectorMatch>> filtered = db.vectorSearchBatch(coll, List.of(new float[] {0f, 1f, 0f}), 3,
                    new VectorSearchOptions().filter(doc("tier", "gold")).minScore(0.05).exact(true));
            assertEquals(List.of("d"), ids(filtered.get(0)));

            assertEquals(List.of("a", "d"),
                    ids(db.vectorSearchMulti(coll, queries, 3, VectorFusion.MAX, doc("tier", "gold"))));

            List<VectorMatch> like = db.vectorSearchByExamples(coll, List.of(VectorExample.id("a")), 2);
            assertEquals("d", like.get(0).id());
            assertFalse(ids(like).contains("a"));
            List<VectorMatch> withExample = db.vectorSearchByExamples(coll,
                    List.of(VectorExample.vector(new float[] {1f, 0f, 0f})), 1,
                    List.of(VectorExample.id("c")), false, null);
            assertEquals("a", withExample.get(0).id());

            assertEquals(2, db.vectorUpdateByFilter(coll, doc("tier", "gold"), doc("seen", true)));
            assertEquals(true, db.vectorGet(coll, "a").orElseThrow().metadata().get("seen"));

            VectorPartitions partitions = db.vectorListPartitions(coll);
            assertEquals("_namespace", partitions.field());
            assertEquals(List.of(new VectorPartition("p1", 1)), partitions.partitions());
            assertEquals(3, partitions.unpartitioned());

            assertEquals(1, db.vectorDeleteMany(coll, List.of("c", "nope")));
            assertEquals(1, db.vectorDeleteByFilter(coll, doc("tier", "free")));
            assertEquals(2, db.vectorCount(coll));

            assertEquals(true, db.vectorRebuildIndex(coll).get("rebuilt"));
            assertEquals(coll, db.vectorIndexStats(coll).get("collection"));
            assertTrue(db.vectorMeasureRecall(coll, 2, 2, null).containsKey("recall"));
            assertEquals(8, num(db.vectorSetCollectionConfig(coll, null, 8, null, null).get("m")));

            assertEquals(2, db.vectorCopyCollection(coll, coll + "_copy"));
            db.vectorRenameCollection(coll + "_copy", coll + "_moved");
            assertEquals(2, db.vectorCount(coll + "_moved"));
            db.vectorDropCollection(coll + "_moved");
        } finally {
            db.vectorDropCollection(coll);
        }
    }

    @Test
    void namedVectorsAliasesAndText() {
        String coll = unique("java_named");
        db.vectorCreateCollection(coll, 3, VectorMetric.COSINE);
        try {
            db.vectorAddNamedVector(coll, "title", 2, VectorMetric.DOT);
            db.vectorUpsertNamed(coll, "n1",
                    Map.of("default", new float[] {1f, 0f, 0f}, "title", new float[] {1f, 0f}), doc("k", 1));
            db.vectorUpsertNamed(coll, "n2",
                    Map.of("default", new float[] {0f, 1f, 0f}, "title", new float[] {0f, 1f}));
            assertEquals(2, db.vectorCount(coll));
            Object named = db.vectorSetCollectionConfig(coll, null, null, 100, null).get("named");
            assertEquals("title", ((Map<?, ?>) ((List<?>) named).get(0)).get("name"));

            String alias = unique("java_alias");
            db.vectorSetAlias(alias, coll);
            assertTrue(db.vectorListAliases().contains(new VectorAlias(alias, coll)));
            assertEquals(2, db.vectorCount(alias));
            db.vectorSetAlias(alias, null);
            assertTrue(db.vectorListAliases().stream().noneMatch(a -> a.alias().equals(alias)));
        } finally {
            db.vectorDropCollection(coll);
        }

        List<float[]> embedded = db.vectorEmbed(List.of("hello world", "goodbye"), 4);
        assertEquals(2, embedded.size());
        assertTrue(embedded.stream().allMatch(v -> v.length == 4));

        String texts = unique("java_text");
        db.vectorCreateCollection(texts, 8, VectorMetric.COSINE);
        try {
            assertEquals(2, db.vectorUpsertText(texts, List.of(
                    new VectorText("t1", "hello world", doc("lang", "en")), new VectorText("t2", "goodbye"))));
            VectorItem stored = db.vectorGet(texts, "t1").orElseThrow();
            assertEquals(8, stored.dimension());
            assertEquals("hello world", stored.metadata().get("text"));
            assertEquals("en", stored.metadata().get("lang"));
        } finally {
            db.vectorDropCollection(texts);
        }
    }

    // -- graph -------------------------------------------------------------------

    private static GraphNode node(String id, String name, String... labels) {
        return new GraphNode(id, List.of(labels), doc("name", name));
    }

    private static GraphEdge edge(String id, String from, String to, String label) {
        return new GraphEdge(id, from, to, label, Map.of());
    }

    private String seedGraph() {
        String graph = unique("java_g");
        db.graphCreate(graph);
        GraphBatchResult written = db.graphAddMany(graph,
                List.of(node("a", "A", "P"), node("b", "B", "P"), node("c", "C", "P", "Q"), node("d", "D"), node("e", "E")),
                List.of(new GraphEdge("ab", "a", "b", "K", doc("weight", 2)), edge("bc", "b", "c", "K"),
                        edge("ca", "c", "a", "K"), edge("ac", "a", "c", "L"), edge("cd", "c", "d", "K")));
        assertEquals(new GraphBatchResult(5, 5), written);
        return graph;
    }

    private static List<String> sorted(List<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(null);
        return out;
    }

    @Test
    void graphBatchesAndUpdates() {
        String graph = seedGraph();
        try {
            // An edge to a node that is neither stored nor in the batch refuses the batch.
            assertThrows(TriCoreException.class, () -> db.graphAddMany(graph,
                    List.of(node("x", "X")), List.of(edge("xz", "x", "nowhere", "K"))));
            assertTrue(db.graphGetNode(graph, "x").isEmpty());

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("name", null);
            patch.put("city", "Pune");
            GraphNode node = db.graphUpdateNode(graph, "a", patch, List.of("R"), List.of("P"));
            assertEquals(List.of("R"), node.labels());
            assertEquals("Pune", node.properties().get("city"));
            assertFalse(node.properties().containsKey("name"));

            GraphEdge edge = db.graphUpdateEdge(graph, "ab", doc("weight", 3), "M");
            assertEquals("M", edge.label());
            assertEquals(3, num(edge.properties().get("weight")));
            assertEquals("a", edge.from());

            String copy = graph + "_copy";
            assertEquals(new GraphBatchResult(5, 5), db.graphCopy(graph, copy));
            assertEquals(new GraphRemoveResult(1, 4), db.graphRemoveMany(copy, List.of("c", "nope"), List.of(), true));
            assertEquals(new GraphRemoveResult(0, 1), db.graphRemoveMany(copy, List.of(), List.of("ab"), false));
            assertEquals(4, db.graphListNodes(copy).total());
            db.graphDrop(copy);

            assertEquals(new GraphBatchResult(2, 1), db.graphImportCsv(graph,
                    "id,labels,name\nx1,P,Xavier\nx2,P,Yann\n", "id,from,to,label\nx1x2,x1,x2,K\n"));
            assertEquals("Xavier", db.graphGetNode(graph, "x1").orElseThrow().properties().get("name"));
        } finally {
            db.graphDrop(graph);
        }
    }

    @Test
    void graphTraversalsAndAlgorithms() {
        String graph = seedGraph();
        try {
            GraphTraversal walk = db.graphDfs(graph, "a");
            assertEquals("a", walk.nodes().get(0).id());
            assertEquals(List.of("a", "b", "c", "d"), sorted(walk.nodes().stream().map(GraphTraversalNode::id).toList()));
            assertEquals(2, walk.nodes().stream().filter(n -> n.id().equals("d")).findFirst().orElseThrow().depth());
            assertTrue(db.graphDfs(graph, "a", GraphDirection.BOTH, "K", 2, 2).truncated());

            GraphComponents weak = db.graphConnectedComponents(graph);
            assertEquals(2, weak.count());
            assertEquals(4, weak.largest());
            assertEquals(List.of("a", "b", "c", "d"), weak.components().get(0).nodes());
            GraphComponents strong = db.graphConnectedComponents(graph, GraphComponentKind.STRONG, "K", null);
            assertEquals(3, strong.count());
            assertEquals(3, strong.components().get(0).size());

            GraphCycle cycle = db.graphDetectCycle(graph);
            assertTrue(cycle.hasCycle());
            assertEquals(3, cycle.cycle().nodes().size());
            GraphCycle none = db.graphDetectCycle(graph, false, "L");
            assertFalse(none.hasCycle());
            assertNull(none.cycle());

            assertEquals(5, num(db.graphStats(graph).get("nodes")));
            assertEquals(5, num(db.graphSchema(graph).get("nodes")));
            assertEquals(true, db.graphCheckConsistency(graph).get("consistent"));

            GraphSubgraph near = db.graphSubgraph(graph, List.of("a"));
            assertEquals(List.of("a", "b", "c"), sorted(near.nodes().stream().map(GraphNode::id).toList()));
            assertEquals(List.of("ab", "ac", "bc", "ca"), sorted(near.edges().stream().map(GraphEdge::id).toList()));
            assertFalse(near.nodesTruncated());

            List<GraphRankedPath> shortest = db.graphKShortestPaths(graph, "a", "c");
            assertEquals(2, shortest.size());
            assertEquals(List.of("a", "c"), shortest.get(0).nodes());
            assertEquals(1, shortest.get(0).hops());
            assertEquals(List.of("ab", "bc"), shortest.get(1).edges());
            List<GraphRankedPath> weighted = db.graphKShortestPaths(graph, "a", "c", 1, GraphDirection.OUTGOING, "K", "weight");
            assertEquals(1, weighted.size());
            assertEquals(3.0, weighted.get(0).cost());
            assertTrue(db.graphKShortestPaths(graph, "a", "e").isEmpty());

            GraphPaths paths = db.graphAllPaths(graph, "a", "c");
            assertTrue(paths.complete());
            assertEquals(2, paths.paths().size());
            assertNull(paths.stoppedBy());

            for (GraphCentralityMeasure measure : GraphCentralityMeasure.values()) {
                List<GraphScore> scores = db.graphCentrality(graph, measure);
                assertEquals(5, scores.size());
                assertEquals("c", scores.get(0).id());
            }
            assertEquals(2, db.graphCentrality(graph, GraphCentralityMeasure.EIGENVECTOR, false, null, 2, 10, 0.001).size());

            GraphCommunities communities = db.graphCommunities(graph);
            assertEquals(5, communities.communities().stream().mapToLong(GraphComponent::size).sum());
            assertTrue(communities.count() >= 2);

            GraphTriangles triangles = db.graphTriangleCount(graph);
            assertEquals(1, triangles.triangles());
            assertEquals(2, db.graphTriangleCount(graph, null, 2).nodes().size());

            List<GraphScore> similar = db.graphNodeSimilarity(graph, "a");
            assertFalse(similar.isEmpty());
            assertTrue(similar.stream().allMatch(x -> x.score() >= 0 && x.score() <= 1));
            assertTrue(db.graphNodeSimilarity(graph, "a", GraphDirection.BOTH, null, GraphSimilarityMetric.OVERLAP, 1)
                    .size() <= 1);

            List<GraphScore> ranks = db.graphPageRank(graph);
            assertEquals(5, ranks.size());
            assertEquals("c", ranks.get(0).id());
            assertEquals(1.0, ranks.stream().mapToDouble(GraphScore::score).sum(), 1e-6);
            assertEquals(2, db.graphPageRank(graph, 0.5, 5, null, null, 2).size());
        } finally {
            db.graphDrop(graph);
        }
    }

    @Test
    void graphSchemaObjectsCypherWritesAndTransactions() {
        String graph = seedGraph();
        try {
            db.graphCreateNodeIndex(graph, "by_name", List.of("name"), "P");
            db.graphCreateConstraint(graph, "uniq_name", "P", "name", GraphConstraintKind.UNIQUE);
            db.graphCreateConstraint(graph, "req_w", "Z", "weight", GraphConstraintKind.REQUIRED, GraphConstraintTarget.EDGE);
            GraphSchemaObjects objects = db.graphListSchemaObjects(graph);
            assertEquals(List.of(new GraphIndex("by_name", "P", List.of("name"))), objects.indexes());
            assertEquals(List.of("uniq_name", "req_w"), objects.constraints().stream().map(GraphConstraint::name).toList());
            assertEquals("edge", objects.constraints().get(1).target());

            GraphFindResult found = db.graphFindNodes(graph, "P", doc("name", "B"), null);
            assertEquals(List.of("b"), found.nodes().stream().map(GraphNode::id).toList());
            assertTrue(found.plan().contains("by_name"), found.plan());
            assertEquals(5, db.graphFindNodes(graph).nodes().size());
            assertTrue(db.graphFindNodes(graph, null, null, 2).truncated());

            // The constraint now refuses a second P named B.
            assertThrows(TriCoreException.class, () -> db.graphAddMany(graph, List.of(node("b2", "B", "P"))));

            db.graphDropSchemaObject(graph, "req_w");
            assertEquals(1, db.graphListSchemaObjects(graph).constraints().size());
            assertThrows(TriCoreException.class, () -> db.graphDropSchemaObject(graph, "nope"));

            assertEquals(1, num(db.graphExecute(graph, "CREATE (n:P {name: 'Zed'})").get("nodesCreated")));
            assertEquals(1, num(db.graphExecute(graph, "MATCH (n:P {name: 'Zed'}) SET n.age = 3").get("propertiesSet")));

            String txn = db.graphBeginTransaction(graph);
            db.setRequestTxn(txn);
            assertEquals(1, num(db.graphExecute(graph, "CREATE (n:P {name: 'Tx1'})").get("queued")));
            assertTrue(other.graphFindNodes(graph, null, doc("name", "Tx1"), null).nodes().isEmpty());
            assertEquals(1, num(db.graphCommitTransaction(graph, txn).get("nodesCreated")));
            assertNull(db.requestTxn());
            assertEquals(1, db.graphFindNodes(graph, null, doc("name", "Tx1"), null).nodes().size());

            String second = db.graphBeginTransaction(graph);
            db.setRequestTxn(second);
            db.graphExecute(graph, "CREATE (n:P {name: 'Tx2'})");
            db.graphRollbackTransaction(graph, second);
            assertNull(db.requestTxn());
            assertTrue(db.graphFindNodes(graph, null, doc("name", "Tx2"), null).nodes().isEmpty());
        } finally {
            db.setRequestTxn(null);
            db.graphDrop(graph);
        }
    }
}
