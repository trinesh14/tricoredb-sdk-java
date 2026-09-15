package io.github.trinesh14.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/** Session transactions against a live server, ported from SessionTxnTest. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class SessionTxnLiveTest {

    private static final String TABLE = "txn_t_java";

    private TestServer server;
    private TriCore db;
    private TriCore other;

    @BeforeAll
    void start() throws Exception {
        server = TestServer.start("sdk-java-session-txn");
        db = server.connect();
        other = server.connect();
        db.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v INT)");
    }

    @AfterAll
    void stop() throws Exception {
        if (other != null) {
            other.close();
        }
        if (db != null) {
            db.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void clean() {
        if (db.inTransaction()) {
            db.rollback();
        }
        db.execute("DELETE FROM " + TABLE);
    }

    private static long count(TriCore c) {
        return Long.parseLong(c.query("SELECT COUNT(*) FROM " + TABLE).rows().get(0).get(0));
    }

    @Test
    void a_theServerGrantedSessionTxn() {
        assertTrue(db.sessionTxnGranted());
        assertTrue((db.grantedFeatures() & TriCore.FEATURE_SESSION_TXN) != 0);
    }

    @Test
    void b_rollbackDiscards() {
        TransactionResult began = db.begin();
        assertEquals("began", began.outcome());
        assertTrue(db.inTransaction());
        db.execute("INSERT INTO " + TABLE + " VALUES (1, 100)");
        assertEquals(1, count(db));
        TransactionResult out = db.rollback();
        assertEquals("rolled_back", out.outcome());
        assertEquals(1, out.discardedWrites());
        assertFalse(db.inTransaction());
        assertEquals(0, count(db));
    }

    @Test
    void c_commitPersistsAndIsVisibleElsewhere() {
        db.begin();
        db.execute("INSERT INTO " + TABLE + " VALUES (2, 200)");
        assertEquals(0, count(other), "uncommitted row is invisible to another connection");
        TransactionResult out = db.commit();
        assertEquals("committed", out.outcome());
        assertEquals(1, out.committedWrites());
        assertEquals(1, count(other));
        assertEquals("200", other.query("SELECT v FROM " + TABLE + " WHERE id = 2").rows().get(0).get(0));
    }

    @Test
    void d_anErrorInsideTheBlockAbortsIt() {
        db.begin();
        db.execute("INSERT INTO " + TABLE + " VALUES (3, 300)");
        assertThrows(TriCoreException.class, () -> db.execute("INSERT INTO no_such_table VALUES (1, 1)"));
        TriCoreException next = assertThrows(TriCoreException.class,
                () -> db.execute("INSERT INTO " + TABLE + " VALUES (4, 400)"));
        assertTrue(next.getMessage().contains("aborted") && next.getMessage().contains("ROLLBACK"), next.getMessage());
        assertTrue(db.inTransaction());
        TriCoreException commit = assertThrows(TriCoreException.class, db::commit);
        assertTrue(commit.getMessage().contains("`COMMIT` is refused"), commit.getMessage());
        assertFalse(db.inTransaction());
        assertEquals(0, count(db));
        db.execute("INSERT INTO " + TABLE + " VALUES (9, 900)");
        assertEquals(1, count(db));
    }

    @Test
    void e_anotherConnectionCannotCommitTheBlock() {
        db.begin();
        db.execute("INSERT INTO " + TABLE + " VALUES (5, 500)");
        TriCoreException e = assertThrows(TriCoreException.class, other::commit);
        assertTrue(e.getMessage().contains("no transaction is open"), e.getMessage());
        assertEquals(0, count(other));
        db.commit();
        assertEquals(1, count(other));
    }

    @Test
    void f_nestingIsRefused() {
        db.begin();
        TriCoreException e = assertThrows(TriCoreException.class, db::begin);
        assertTrue(e.getMessage().contains("already open"), e.getMessage());
        assertTrue(db.inTransaction());
        db.rollback();
    }

    @Test
    void g_withTransactionRollsBackAndRethrowsTheSameException() {
        RuntimeException boom = new IllegalStateException("application error after a write");
        RuntimeException seen = assertThrows(IllegalStateException.class, () -> db.withTransaction(tx -> {
            tx.execute("INSERT INTO " + TABLE + " VALUES (7, 700)");
            throw boom;
        }));
        assertSame(boom, seen);
        assertFalse(db.inTransaction());
        assertEquals(0, count(db));
        db.withTransaction(tx -> tx.execute("INSERT INTO " + TABLE + " VALUES (8, 800)"));
        assertEquals(1, count(other));
    }

    @Test
    void h_tryWithResourcesRollsBackUnlessCommitted() {
        try (Transaction tx = db.transactionBlock()) {
            db.execute("INSERT INTO " + TABLE + " VALUES (14, 1400)");
            assertTrue(tx.open());
        }
        assertFalse(db.inTransaction());
        assertEquals(0, count(db));
        try (Transaction tx = db.transactionBlock()) {
            db.execute("INSERT INTO " + TABLE + " VALUES (14, 1400)");
            tx.commit();
        }
        assertEquals(1, count(other));
    }

    @Test
    void i_withoutSessionTxnBeginRefusesByNameAndSendsNothing() {
        try (TriCore plain = server.connect(TriCore.FEATURES & ~TriCore.FEATURE_SESSION_TXN)) {
            assertFalse(plain.sessionTxnGranted());
            TriCoreException e = assertThrows(TriCoreException.class, plain::begin);
            assertTrue(e.getMessage().contains("did not grant session transactions"), e.getMessage());
            assertFalse(plain.inTransaction());
            TransactionResult script = plain.transaction(List.of(
                    SqlStatement.of("INSERT INTO " + TABLE + " VALUES (10, 1000)"),
                    SqlStatement.of("INSERT INTO " + TABLE + " VALUES (11, 1100)")));
            assertEquals("committed", script.outcome());
            assertEquals(2, count(other));
        }
    }

    @Test
    void j_aPoolNeverReturnsAConnectionMidBlock() {
        try (Pool pool = new Pool(server.host, server.port, TestServer.USER, TestServer.SECRET, 2)) {
            TriCoreException e = assertThrows(TriCoreException.class, () -> pool.use(c -> {
                c.begin();
                c.execute("INSERT INTO " + TABLE + " VALUES (11, 1100)");
            }));
            assertTrue(e.getMessage().contains("still open"), e.getMessage());
            assertEquals(0, count(other));
            long[] reused = new long[2];
            pool.use(c -> {
                reused[0] = c.inTransaction() ? 1 : 0;
                reused[1] = count(c);
            });
            assertEquals(0, reused[0]);
            assertEquals(0, reused[1]);
            assertEquals(1, pool.stats().created());
        }
    }
}
