package com.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Ported from the driver's HardeningTest: hostile peers, then a real server. */
class HardeningTest {

    private static final Duration WATCHDOG = Duration.ofSeconds(25);
    private static final String HELLO_OK = "{\"ok\":true,\"features\":1}";

    @Nested
    class HostilePeer {

        @Test
        void aResponseDeclaring4GiBIsRefusedBeforeAllocating() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeHeader(s, 1, 3, 0xFFFFFFFFL);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    ProtocolException e = assertThrows(ProtocolException.class, () -> db.execute("SELECT 1"));
                    assertTrue(e.getMessage().contains("exceeds the protocol ceiling"), e.getMessage());
                    assertTrue(db.isPoisoned());
                }
            });
        }

        @Test
        void aControlFrameTakesThe64KiBCeiling() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeHeader(s, 1, 5, 1024 * 1024);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    ProtocolException e = assertThrows(ProtocolException.class, db::ping);
                    assertTrue(e.getMessage().contains("65536"), e.getMessage());
                }
            });
        }

        @Test
        void anUnknownFrameVersionIsRefusedByNameAndPoisons() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeHeader(s, 99, 5, 0);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    ProtocolException e = assertThrows(ProtocolException.class, db::ping);
                    assertTrue(e.getMessage().contains("version 99"), e.getMessage());
                    ProtocolException again = assertThrows(ProtocolException.class, db::ping);
                    assertTrue(again.getMessage().contains("cannot be reused"), again.getMessage());
                }
            });
        }

        @Test
        void a2MiBResponseStillArrivesWhole() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                String big = "a".repeat(2 * 1024 * 1024);
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 3,
                            "{\"request_id\":\"x\",\"status\":\"ok\",\"data\":{\"Message\":\"" + big + "\"}}");
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    Response resp = db.execute("SELECT 1");
                    assertEquals("ok", resp.status());
                    assertEquals(big.length(), String.valueOf(((Map<?, ?>) resp.data()).get("Message")).length());
                }
            });
        }

        @Test
        void anUnknownStatusFailsClosed() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 3, "{\"request_id\":\"x\",\"status\":\"degraded\","
                            + "\"data\":{\"Message\":\"partially applied\"}}");
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    TriCoreException e = assertThrows(TriCoreException.class, () -> db.execute("SELECT 1"));
                    assertTrue(e.getMessage().contains("degraded"), e.getMessage());
                }
            });
        }

        @Test
        void closeIsBoundedWhenThePeerNeverAnswersBye() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    long start = System.nanoTime();
                    db.close();
                    long ms = (System.nanoTime() - start) / 1_000_000L;
                    assertTrue(ms < 5_000, "close() took " + ms + "ms");
                }
            });
        }

        @Test
        void aReadDeadlineIsATypedFatalTimeout() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect(null, null, 10_000, 300);
                    TriCoreTimeoutException e = assertThrows(TriCoreTimeoutException.class, db::ping);
                    assertTrue(e instanceof TriCoreException);
                    assertTrue(db.isPoisoned());
                }
            });
        }

        @Test
        void theConnectBudgetIsNotAPerQueryDeadline() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    Thread.sleep(1_200);
                    ScriptedPeer.writeFrame(s, 3,
                            "{\"request_id\":\"x\",\"status\":\"ok\",\"data\":{\"Message\":\"slow\"}}");
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect(null, null, 500, 0);
                    assertEquals("ok", db.execute("SELECT 1").status());
                }
            });
        }

        @Test
        void helloOkWithOkFalseIsARefusal() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, "{\"ok\":false,\"message\":\"this node is draining\"}");
                    ScriptedPeer.sleepForever();
                })) {
                    ProtocolException e = assertThrows(ProtocolException.class, peer::connect);
                    assertTrue(e.getMessage().contains("draining"), e.getMessage());
                }
            });
        }

        @Test
        void authOkWithOkFalseIsARefusalAndDoesNotEchoTheSecret() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                String secret = "s3cr3t-do-not-echo";
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 9, "{\"ok\":false,\"message\":\"bad credentials\"}");
                    ScriptedPeer.sleepForever();
                })) {
                    AuthException e = assertThrows(AuthException.class,
                            () -> peer.connect("admin", secret, 10_000, 0));
                    StringBuilder text = new StringBuilder();
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        text.append(t).append('\n');
                    }
                    assertFalse(text.toString().contains(secret));
                }
            });
        }

        @Test
        void helloOkWithoutFeaturesGrantsNothing() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, "{\"ok\":true}");
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    assertEquals(0L, db.grantedFeatures());
                    assertFalse(db.serverParamsGranted());
                    assertFalse(db.sessionTxnGranted());
                    TriCoreException params = assertThrows(TriCoreException.class,
                            () -> db.query("SELECT ?", List.of(1), "main"));
                    assertTrue(params.getMessage().contains("SERVER_PARAMS"), params.getMessage());
                    TriCoreException txn = assertThrows(TriCoreException.class, db::begin);
                    assertTrue(txn.getMessage().contains("did not grant session transactions"), txn.getMessage());
                }
            });
        }

        @Test
        void pingCannotStealARequestsReply() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect(null, null, 10_000, 3_000);
                    Thread worker = new Thread(() -> {
                        try {
                            db.execute("SELECT 1");
                        } catch (RuntimeException ignored) {
                            // the read deadline ends it
                        }
                    });
                    worker.setDaemon(true);
                    worker.start();
                    Thread.sleep(300);
                    TriCoreException e = assertThrows(TriCoreException.class, db::ping);
                    assertTrue(e.getMessage().contains("already in flight"), e.getMessage());
                    worker.join(5_000);
                }
            });
        }

        @Test
        void anOutboundControlFrameOver64KiBIsRefusedLocally() {
            assertTimeoutPreemptively(WATCHDOG, () -> {
                try (ScriptedPeer peer = ScriptedPeer.start(s -> {
                    ScriptedPeer.expectFrame(s);
                    ScriptedPeer.writeFrame(s, 8, HELLO_OK);
                    ScriptedPeer.sleepForever();
                })) {
                    TriCore db = peer.connect();
                    ProtocolException e = assertThrows(ProtocolException.class, () -> db.cancel("k".repeat(70 * 1024)));
                    assertTrue(e.getMessage().contains("control frames"), e.getMessage());
                }
            });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RealServer {

        private TestServer server;

        @BeforeAll
        void start() throws Exception {
            server = TestServer.start("sdk-java-hardening");
        }

        @AfterAll
        void stop() throws Exception {
            if (server != null) {
                server.close();
            }
        }

        private Map<String, Object> sql(String statement) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("sql", statement);
            return Map.of("Sql", Map.of("Query", query));
        }

        @Test
        void twoConnectionsOfOnePrincipalGetDifferentRequestIds() {
            try (TriCore a = server.connect(); TriCore b = server.connect()) {
                Response ra = a.request(sql("SELECT 1"));
                Response rb = b.request(sql("SELECT 1"));
                assertFalse(ra.requestId().equals(rb.requestId()), ra.requestId() + " vs " + rb.requestId());
                assertTrue(ra.requestId().startsWith("java-"), ra.requestId());
            }
        }

        @Test
        void sixConcurrentPooledRequestsReachTheServerUnderSixIds() throws Exception {
            try (Pool pool = new Pool(server.host, server.port, TestServer.USER, TestServer.SECRET, 6)) {
                Set<String> ids = ConcurrentHashMap.newKeySet();
                CountDownLatch allBorrowed = new CountDownLatch(6);
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    Thread t = new Thread(() -> pool.use(20_000L, db -> {
                        allBorrowed.countDown();
                        try {
                            allBorrowed.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        ids.add(db.request(sql("SELECT 1")).requestId());
                    }));
                    t.setDaemon(true);
                    threads.add(t);
                    t.start();
                }
                for (Thread t : threads) {
                    t.join(30_000);
                }
                assertEquals(6, ids.size(), "distinct server-echoed ids: " + ids);
            }
        }

        @Test
        void aNotImplementedStatusIsAFailure() {
            try (TriCore db = server.connect()) {
                TriCoreException e = assertThrows(TriCoreException.class,
                        () -> db.request(Map.of("Admin", "RebalanceStatus")));
                assertTrue(e.getMessage().contains("not_implemented"), e.getMessage());
            }
        }

        @Test
        void anOrdinaryServerErrorDoesNotRetireAPooledConnection() {
            try (Pool pool = new Pool(server.host, server.port, TestServer.USER, TestServer.SECRET, 1)) {
                assertThrows(TriCoreException.class,
                        () -> pool.use(db -> db.query("SELECT * FROM no_such_table")));
                assertEquals(1, pool.stats().idle());
            }
        }
    }
}
