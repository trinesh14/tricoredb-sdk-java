package com.tricoredb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Ported from E2ETest and PoolTest, against a private live server. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PoolAndE2ELiveTest {

    private TestServer server;

    @BeforeAll
    void start() throws Exception {
        server = TestServer.start("sdk-java-pool-e2e");
    }

    @AfterAll
    void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private Pool pool(int size) {
        return new Pool(server.host, server.port, TestServer.USER, TestServer.SECRET, size);
    }

    @Test
    void cacheRoundTripsDistinguishMissFromEmptyAndSurviveFragmentation() {
        try (TriCore db = server.connect()) {
            db.ping();
            db.cacheSet("javat", "hello", "world".getBytes(StandardCharsets.UTF_8));
            assertEquals("world", new String(db.cacheGet("javat", "hello").orElseThrow(), StandardCharsets.UTF_8));
            db.cacheSet("javat", "empty", new byte[0]);
            Optional<byte[]> empty = db.cacheGet("javat", "empty");
            assertTrue(empty.isPresent());
            assertEquals(0, empty.get().length);
            assertTrue(db.cacheGet("javat", "missing-xyz").isEmpty());
            assertTrue(db.cacheDelete("javat", "hello"));
            assertTrue(db.cacheGet("javat", "hello").isEmpty());

            byte[] big = new byte[100 * 1024];
            new Random(42).nextBytes(big);
            db.cacheSet("javat", "big", big);
            assertArrayEquals(big, db.cacheGet("javat", "big").orElseThrow());
        }
    }

    @Test
    void sqlCrudAndTheReadWriteBoundary() {
        try (TriCore db = server.connect()) {
            db.execute("CREATE TABLE javat_users (id INT PRIMARY KEY, name TEXT)");
            db.execute("INSERT INTO javat_users VALUES (1, 'ada')");
            db.execute("INSERT INTO javat_users VALUES (2, 'grace')");
            Rows rows = db.query("SELECT id, name FROM javat_users ORDER BY id");
            assertEquals(List.of("id", "name"), rows.columns());
            assertEquals(List.of(List.of("1", "ada"), List.of("2", "grace")), rows.rows());
            assertThrows(TriCoreException.class, () -> db.execute("THIS IS NOT VALID SQL AT ALL"));
            assertThrows(TriCoreException.class, () -> db.query("INSERT INTO javat_users VALUES (3, 'refused')"));
            assertEquals(2, db.query("SELECT * FROM javat_users").size());
        }
    }

    @Test
    void poolIsLazyAndReusesTheSameConnection() {
        try (Pool pool = pool(4)) {
            assertEquals(0, pool.stats().created());
            TriCore[] first = new TriCore[1];
            pool.use(c -> {
                first[0] = c;
                c.ping();
            });
            Pool.Stats s = pool.stats();
            assertEquals(1, s.created());
            assertEquals(1, s.idle());
            assertEquals(0, s.inUse());
            TriCore[] second = new TriCore[1];
            pool.use(c -> second[0] = c);
            assertSame(first[0], second[0]);
            assertEquals(1, pool.stats().created());
        }
    }

    @Test
    void poolGivesExclusiveOwnershipAndStaysBoundedUnderLoad() throws Exception {
        try (TriCore admin = server.connect()) {
            admin.execute("CREATE TABLE javapool_concurrent (id INT PRIMARY KEY)");
        }
        int size = 3;
        int threads = 20;
        try (Pool pool = pool(size)) {
            ConcurrentHashMap<TriCore, Boolean> held = new ConcurrentHashMap<>();
            AtomicInteger violations = new AtomicInteger();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            AtomicInteger done = new AtomicInteger();
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                int id = i;
                exec.submit(() -> {
                    try {
                        pool.use(5_000L, c -> {
                            if (held.putIfAbsent(c, Boolean.TRUE) != null) {
                                violations.incrementAndGet();
                            }
                            int now = active.incrementAndGet();
                            peak.updateAndGet(p -> Math.max(p, now));
                            try {
                                c.execute("INSERT INTO javapool_concurrent VALUES (" + id + ")");
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                active.decrementAndGet();
                                held.remove(c);
                            }
                        });
                        done.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertTrue(latch.await(30, TimeUnit.SECONDS));
            exec.shutdown();
            assertEquals(0, violations.get());
            assertEquals(threads, done.get());
            assertTrue(pool.stats().created() <= size);
            assertEquals(size, peak.get());
        }
        try (TriCore admin = server.connect()) {
            assertEquals(threads, admin.query("SELECT * FROM javapool_concurrent").size());
        }
    }

    @Test
    void anExhaustedPoolTimesOutInsteadOfHangingOrGrowing() throws Exception {
        try (Pool pool = pool(1)) {
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> pool.use(5_000L, c -> {
                ready.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            holder.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertThrows(PoolTimeoutException.class, () -> pool.use(400L, TriCore::ping));
            long ms = (System.nanoTime() - start) / 1_000_000L;
            release.countDown();
            holder.join(5_000);
            assertTrue(ms < 2_000, "timed out after " + ms + "ms");
            assertEquals(1, pool.stats().created());
            assertFalse(holder.isAlive());
        }
    }
}
