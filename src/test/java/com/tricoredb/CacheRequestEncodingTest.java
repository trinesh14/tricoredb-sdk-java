package com.tricoredb;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The request each cache method added for server 0.1.0-rc.3 puts on the wire,
 * field for field. The peer answers with a payload captured from a real server,
 * so the same call also proves the method can read what the server sends back.
 */
class CacheRequestEncodingTest {

    @Test
    void setFreshSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"freshUntilMs\":1791870377760,\"set\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetFresh("s_namespace", "s_key", new byte[] {1, 2}, 7L, 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetFresh\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"value\":[1,2],\""
                    + "fresh_ms\":7,\"ttl_ms\":7}}}");
        }
    }

    @Test
    void setFreshSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"freshUntilMs\":1791870377760,\"set\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetFresh("s_namespace", "s_key", new byte[] {1, 2}, 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetFresh\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"value\":[1,2],\""
                    + "fresh_ms\":7,\"ttl_ms\":null}}}");
        }
    }

    @Test
    void getFreshSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"refresh\":true,\"state\":\"stale\",\"value\":\"v\"}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetFresh("s_namespace", "s_key", "s_owner", 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetFresh\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"owner\":\"s_owne"
                    + "r\",\"lease_ms\":7}}}");
        }
    }

    @Test
    void setMissingSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"set\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetMissing("s_namespace", "s_key", 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetMissing\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"ttl_ms\":7}}}");
        }
    }

    @Test
    void warmSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"alreadyCached\":1,\"written\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheWarm("s_namespace", List.of(new CacheEntry("k1", new byte[] {1, 2})), 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"Warm\":{\"namespace\":\"s_namespace\",\"entries\":[{\"key\":\"k1\",\"value\":["
                    + "1,2]}],\"ttl_ms\":7}}}");
        }
    }

    @Test
    void warmSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"alreadyCached\":1,\"written\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheWarm("s_namespace", List.of(new CacheEntry("k1", new byte[] {1, 2})));
            }
            peer.assertSent(
                    "{\"Cache\":{\"Warm\":{\"namespace\":\"s_namespace\",\"entries\":[{\"key\":\"k1\",\"value\":["
                    + "1,2]}],\"ttl_ms\":null}}}");
        }
    }

    @Test
    void mGetSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValues\":[[97],null,[98],[97]]}")) {
            try (TriCore db = peer.connect()) {
                db.cacheMGet("s_namespace", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"MGet\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void mSetSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"set\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheMSet("s_namespace", List.of(new CacheEntry("k1", new byte[] {1, 2})), 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"MSet\":{\"namespace\":\"s_namespace\",\"entries\":[{\"key\":\"k1\",\"value\":["
                    + "1,2]}],\"ttl_ms\":7}}}");
        }
    }

    @Test
    void mSetSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"set\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheMSet("s_namespace", List.of(new CacheEntry("k1", new byte[] {1, 2})));
            }
            peer.assertSent(
                    "{\"Cache\":{\"MSet\":{\"namespace\":\"s_namespace\",\"entries\":[{\"key\":\"k1\",\"value\":["
                    + "1,2]}],\"ttl_ms\":null}}}");
        }
    }

    @Test
    void setIfSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"set\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetIf(
                        "s_namespace", "s_key", new byte[] {1, 2}, CacheSetCondition.IF_ABSENT, CacheExpiry.set(9000));
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetIf\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"value\":[1,2],\"con"
                    + "dition\":\"IfAbsent\",\"expiry\":{\"Set\":{\"ttl_ms\":9000}}}}}");
        }
    }

    @Test
    void zAddSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"added\":3,\"updated\":0}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZAdd("s_namespace", "s_key", List.of(new ScoredMember(1.5, new byte[] {1, 2})));
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZAdd\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"members\":[{\"score"
                    + "\":1.5,\"member\":[1,2]}]}}}");
        }
    }

    @Test
    void zRemSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"removed\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZRem("s_namespace", "s_key", List.of(new byte[] {1}, new byte[] {2}));
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZRem\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"members\":[[1],[2]]}}}");
        }
    }

    @Test
    void zScoreSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"score\":1.0}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZScore("s_namespace", "s_key", new byte[] {1, 2});
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZScore\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"member\":[1,2]}}}");
        }
    }

    @Test
    void zCardSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"cardinality\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZCard("s_namespace", "s_key");
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZCard\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\"}}}");
        }
    }

    @Test
    void zRankSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"rank\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZRank("s_namespace", "s_key", new byte[] {1, 2}, true);
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZRank\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"member\":[1,2],\"re"
                    + "v\":true}}}");
        }
    }

    @Test
    void zRangeSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"members\":[{\"member\":[97],\"score\":1.0},{\"member\":[98],\"score"
                        + "\":2.5}]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZRange("s_namespace", "s_key", 0L, -1L, true, true);
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZRange\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"start\":0,\"stop\""
                    + ":-1,\"rev\":true,\"with_scores\":true}}}");
        }
    }

    @Test
    void zRangeByScoreSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"members\":[[97],[98]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZRangeByScore(
                        "s_namespace", "s_key", new ScoreBound(1.5, true), new ScoreBound(1.5, true), true, true, 2L, 10L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZRangeByScore\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"min\":{\"va"
                    + "lue\":1.5,\"exclusive\":true},\"max\":{\"value\":1.5,\"exclusive\":true},\"rev\":true,\"with"
                    + "_scores\":true,\"limit\":[2,10]}}}");
        }
    }

    @Test
    void zRangeByScoreSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":2,\"members\":[[97],[98]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZRangeByScore(
                        "s_namespace", "s_key", new ScoreBound(1.5, true), new ScoreBound(1.5, true), true, true);
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZRangeByScore\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"min\":{\"va"
                    + "lue\":1.5,\"exclusive\":true},\"max\":{\"value\":1.5,\"exclusive\":true},\"rev\":true,\"with"
                    + "_scores\":true,\"limit\":null}}}");
        }
    }

    @Test
    void zIncrBySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"score\":2.5}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheZIncrBy("s_namespace", "s_key", new byte[] {1, 2}, 1.5);
            }
            peer.assertSent(
                    "{\"Cache\":{\"ZIncrBy\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"member\":[1,2],\""
                    + "by\":1.5}}}");
        }
    }

    @Test
    void lockAcquireSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"acquired\":true,\"expiresAtMs\":1791870378335,\"holder\":\"o1\",\"token\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheLockAcquire("s_namespace", "s_key", "s_owner", 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"LockAcquire\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"owner\":\"s_o"
                    + "wner\",\"ttl_ms\":7}}}");
        }
    }

    @Test
    void lockRenewSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"expiresAtMs\":null,\"renewed\":false,\"token\":null}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheLockRenew("s_namespace", "s_key", "s_owner", 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"LockRenew\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"owner\":\"s_own"
                    + "er\",\"ttl_ms\":7}}}");
        }
    }

    @Test
    void lockReleaseSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"released\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheLockRelease("s_namespace", "s_key", "s_owner");
            }
            peer.assertSent(
                    "{\"Cache\":{\"LockRelease\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"owner\":\"s_o"
                    + "wner\"}}}");
        }
    }

    @Test
    void setBitSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"previous\":0}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetBit("s_namespace", "s_key", 7L, true);
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetBit\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"offset\":7,\"value"
                    + "\":true}}}");
        }
    }

    @Test
    void getBitSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"bit\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetBit("s_namespace", "s_key", 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetBit\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"offset\":7}}}");
        }
    }

    @Test
    void bitCountSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBitCount("s_namespace", "s_key", 0L, -1L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"BitCount\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"range\":[0,-1]}}}");
        }
    }

    @Test
    void bitCountSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBitCount("s_namespace", "s_key");
            }
            peer.assertSent(
                    "{\"Cache\":{\"BitCount\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"range\":null}}}");
        }
    }

    @Test
    void bitPosSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"position\":7}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBitPos("s_namespace", "s_key", true, 0L, -1L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"BitPos\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"bit\":true,\"range"
                    + "\":[0,-1]}}}");
        }
    }

    @Test
    void bitPosSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"position\":7}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBitPos("s_namespace", "s_key", true);
            }
            peer.assertSent(
                    "{\"Cache\":{\"BitPos\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"bit\":true,\"range"
                    + "\":null}}}");
        }
    }

    @Test
    void bitOpSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"length\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBitOp("s_namespace", CacheBitOp.OR, "s_dest", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"BitOp\":{\"namespace\":\"s_namespace\",\"op\":\"Or\",\"dest\":\"s_dest\",\"key"
                    + "s\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void pfAddSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"changed\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePfAdd("s_namespace", "s_key", List.of(new byte[] {1}, new byte[] {2}));
            }
            peer.assertSent(
                    "{\"Cache\":{\"PfAdd\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"elements\":[[1],[2]"
                    + "]}}}");
        }
    }

    @Test
    void pfCountSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":4}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePfCount("s_namespace", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"PfCount\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void pfMergeSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"merged\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePfMerge("s_namespace", "s_dest", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"PfMerge\":{\"namespace\":\"s_namespace\",\"dest\":\"s_dest\",\"sources\":[\"a"
                    + "\",\"b\"]}}}");
        }
    }

    @Test
    void lMoveSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValue\":[97]}")) {
            try (TriCore db = peer.connect()) {
                db.cacheLMove(
                        "s_namespace", "s_source", "s_destination", CacheListEnd.RIGHT, CacheListEnd.RIGHT);
            }
            peer.assertSent(
                    "{\"Cache\":{\"LMove\":{\"namespace\":\"s_namespace\",\"source\":\"s_source\",\"destination\""
                    + ":\"s_destination\",\"from\":\"Right\",\"to\":\"Right\"}}}");
        }
    }

    @Test
    void expireAtSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"deleted\":false,\"updated\":true}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheExpireAt("s_namespace", "s_key", 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"ExpireAt\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"at_ms\":7}}}");
        }
    }

    @Test
    void statsSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"eviction\":{\"evicted\":0,\"policy\":\"lru\"},\"hitRatio\":0.72,\"hits\":18,\"m"
                        + "emory\":{\"enabled\":true,\"lowWaterBytes\":241591860,\"maxBytes\":268435456,\"measurements"
                        + "\":0,\"residentBytes\":null},\"misses\":7}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheStats();
            }
            peer.assertSent(
                    "{\"Cache\":\"Stats\"}");
        }
    }

    @Test
    void flushDatabaseSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"cleared\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheFlushDatabase();
            }
            peer.assertSent(
                    "{\"Cache\":\"FlushDatabase\"}");
        }
    }

    @Test
    void appendSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"length\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheAppend("s_namespace", "s_key", new byte[] {1, 2});
            }
            peer.assertSent(
                    "{\"Cache\":{\"Append\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"value\":[1,2]}}}");
        }
    }

    @Test
    void compareAndSwapSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"current\":\"cd\",\"swapped\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheCompareAndSwap("s_namespace", "s_key", new byte[] {1, 2}, new byte[] {3, 4}, 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"CompareAndSwap\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"expected\""
                    + ":[1,2],\"value\":[3,4],\"ttl_ms\":7}}}");
        }
    }

    @Test
    void compareAndSwapSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"current\":\"cd\",\"swapped\":false}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheCompareAndSwap("s_namespace", "s_key", new byte[] {1, 2}, new byte[] {3, 4});
            }
            peer.assertSent(
                    "{\"Cache\":{\"CompareAndSwap\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"expected\""
                    + ":[1,2],\"value\":[3,4],\"ttl_ms\":null}}}");
        }
    }

    @Test
    void getDelSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValue\":[99,100]}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetDel("s_namespace", "s_key");
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetDel\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\"}}}");
        }
    }

    @Test
    void getSetSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValue\":null}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetSet("s_namespace", "s_key", new byte[] {1, 2}, 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetSet\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"value\":[1,2],\"tt"
                    + "l_ms\":7}}}");
        }
    }

    @Test
    void getSetSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValue\":null}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetSet("s_namespace", "s_key", new byte[] {1, 2});
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetSet\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"value\":[1,2],\"tt"
                    + "l_ms\":null}}}");
        }
    }

    @Test
    void getExSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValue\":[50]}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetEx("s_namespace", "s_key", CacheExpiry.set(9000));
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetEx\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"expiry\":{\"Set\":{"
                    + "\"ttl_ms\":9000}}}}}");
        }
    }

    @Test
    void lTrimSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"length\":1,\"removed\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheLTrim("s_namespace", "s_key", 0L, -1L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"LTrim\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"start\":0,\"stop\":"
                    + "-1}}}");
        }
    }

    @Test
    void sUnionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":3,\"members\":[[97],[98],[99]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSUnion("s_namespace", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"SUnion\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void sInterSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"members\":[[98]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSInter("s_namespace", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"SInter\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void sDiffSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"members\":[[97]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSDiff("s_namespace", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"SDiff\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void sRandMemberSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"members\":[[98]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSRandMember("s_namespace", "s_key", -3L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"SRandMember\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"count\":-3}}}");
        }
    }

    @Test
    void sRandMemberSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"count\":1,\"members\":[[98]]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSRandMember("s_namespace", "s_key");
            }
            peer.assertSent(
                    "{\"Cache\":{\"SRandMember\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"count\":null}}}");
        }
    }

    @Test
    void hIncrBySendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"value\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheHIncrBy("s_namespace", "s_key", new byte[] {1, 2}, 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"HIncrBy\":{\"namespace\":\"s_namespace\",\"key\":\"s_key\",\"field\":[1,2],\"b"
                    + "y\":7}}}");
        }
    }

    @Test
    void pipelineSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"results\":[{\"ok\":{\"Json\":{\"set\":true}}},{\"ok\":{\"CacheValue\":[49]}},{"
                        + "\"error\":\"invalid request: wrong type\"},{\"ok\":{\"Message\":\"pong\"}}]}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePipeline(
                        List.of(CacheCommand.set("n", "k", new byte[] {1}, null), CacheCommand.unit("Ping")));
            }
            peer.assertSent(
                    "{\"Cache\":{\"Pipeline\":{\"ops\":[{\"Set\":{\"namespace\":\"n\",\"key\":\"k\",\"value\":[1]"
                    + ",\"ttl_ms\":null}},\"Ping\"]}}}");
        }
    }

    @Test
    void transactionSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"results\":[{\"ok\":{\"Json\":{\"set\":true}}},{\"ok\":{\"Json\":{\"value\":3}}}"
                        + ",{\"ok\":{\"CacheValue\":[51]}}]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheTransaction(
                        List.of(CacheCommand.set("n", "k", new byte[] {1}, null), CacheCommand.unit("Ping")));
            }
            peer.assertSent(
                    "{\"Cache\":{\"Transaction\":{\"ops\":[{\"Set\":{\"namespace\":\"n\",\"key\":\"k\",\"value\":"
                    + "[1],\"ttl_ms\":null}},\"Ping\"]}}}");
        }
    }

    @Test
    void scriptLoadSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"loaded\":\"s\",\"ops\":2}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheScriptLoad(
                        "s_name", List.of(CacheCommand.set("n", "k", new byte[] {1}, null), CacheCommand.unit("Ping")));
            }
            peer.assertSent(
                    "{\"Cache\":{\"ScriptLoad\":{\"name\":\"s_name\",\"ops\":[{\"Set\":{\"namespace\":\"n\",\"key"
                    + "\":\"k\",\"value\":[1],\"ttl_ms\":null}},\"Ping\"]}}}");
        }
    }

    @Test
    void scriptRunSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"ops\":[{\"Get\":{\"key\":\"sk\",\"namespace\":\"n\"}}],\"results\":[{\"ok\":{\""
                        + "CacheValue\":[115,118]}}]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheScriptRun("s_name", List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"ScriptRun\":{\"name\":\"s_name\",\"args\":[\"a\",\"b\"]}}}");
        }
    }

    @Test
    void scriptRunSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"ops\":[{\"Get\":{\"key\":\"sk\",\"namespace\":\"n\"}}],\"results\":[{\"ok\":{\""
                        + "CacheValue\":[115,118]}}]}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheScriptRun("s_name");
            }
            peer.assertSent(
                    "{\"Cache\":{\"ScriptRun\":{\"name\":\"s_name\",\"args\":[]}}}");
        }
    }

    @Test
    void hotKeysSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.refusing()) {
            try (TriCore db = peer.connect()) {
                assertThrows(TriCoreException.class, () -> db.cacheHotKeys(3));
            }
            peer.assertSent(
                    "{\"Cache\":{\"HotKeys\":{\"limit\":3}}}");
        }
    }

    @Test
    void hotKeysSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.refusing()) {
            try (TriCore db = peer.connect()) {
                assertThrows(TriCoreException.class, () -> db.cacheHotKeys());
            }
            peer.assertSent(
                    "{\"Cache\":{\"HotKeys\":{\"limit\":null}}}");
        }
    }

    @Test
    void purgeExpiredSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"purged\":1}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePurgeExpired();
            }
            peer.assertSent(
                    "{\"Cache\":\"PurgeExpired\"}");
        }
    }

    @Test
    void bLPopSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValues\":[[108,50],[97]]}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBLPop("s_namespace", List.of("a", "b"), 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"BLPop\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"],\"timeout_ms\":7"
                    + "}}}");
        }
    }

    @Test
    void bRPopSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"CacheValues\":[null,null]}")) {
            try (TriCore db = peer.connect()) {
                db.cacheBRPop("s_namespace", List.of("a", "b"), 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"BRPop\":{\"namespace\":\"s_namespace\",\"keys\":[\"a\",\"b\"],\"timeout_ms\":7"
                    + "}}}");
        }
    }

    @Test
    void setNamespaceLimitSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"bytes\":0,\"keys\":0,\"max_bytes\":1000,\"max_keys\":2,\"namespace\":\"n\"}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetNamespaceLimit("s_namespace", 7L, 7L);
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetNamespaceLimit\":{\"namespace\":\"s_namespace\",\"max_keys\":7,\"max_bytes"
                    + "\":7}}}");
        }
    }

    @Test
    void setNamespaceLimitSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"bytes\":0,\"keys\":0,\"max_bytes\":1000,\"max_keys\":2,\"namespace\":\"n\"}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheSetNamespaceLimit("s_namespace");
            }
            peer.assertSent(
                    "{\"Cache\":{\"SetNamespaceLimit\":{\"namespace\":\"s_namespace\",\"max_keys\":null,\"max_byt"
                    + "es\":null}}}");
        }
    }

    @Test
    void getNamespaceLimitSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"bytes\":2,\"keys\":2,\"max_bytes\":null,\"max_keys\":null,\"namespace\":\"n\"}}")) {
            try (TriCore db = peer.connect()) {
                db.cacheGetNamespaceLimit("s_namespace");
            }
            peer.assertSent(
                    "{\"Cache\":{\"GetNamespaceLimit\":{\"namespace\":\"s_namespace\"}}}");
        }
    }

    @Test
    void publishSendsItsFields() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"channel\":\"c\",\"id\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePublish("s_channel", "s_message");
            }
            peer.assertSent(
                    "{\"Cache\":{\"Publish\":{\"channel\":\"s_channel\",\"message\":\"s_message\"}}}");
        }
    }

    @Test
    void pollSendsEveryField() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"messages\":[{\"at_ms\":1791870319843,\"channel\":\"c\",\"id\":5,\"message\":\"h"
                        + "ello\"}],\"next\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePoll(List.of("a", "b"), 7L, 3);
            }
            peer.assertSent(
                    "{\"Cache\":{\"Poll\":{\"channels\":[\"a\",\"b\"],\"after\":7,\"limit\":3}}}");
        }
    }

    @Test
    void pollSendsOnlyWhatIsRequired() throws Exception {
        try (RecordingPeer peer = RecordingPeer.answering(
                "{\"Json\":{\"messages\":[{\"at_ms\":1791870319843,\"channel\":\"c\",\"id\":5,\"message\":\"h"
                        + "ello\"}],\"next\":5}}")) {
            try (TriCore db = peer.connect()) {
                db.cachePoll(List.of("a", "b"));
            }
            peer.assertSent(
                    "{\"Cache\":{\"Poll\":{\"channels\":[\"a\",\"b\"],\"after\":0,\"limit\":100}}}");
        }
    }
}
