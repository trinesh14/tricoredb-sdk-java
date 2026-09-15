package io.github.trinesh14.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Server-side parameter binding against a live server, reading every value back. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParamsLiveTest {

    private static final String TABLE = "params_t_java";
    private static final String USERS = "params_users_java";

    private TestServer server;
    private TriCore db;

    @BeforeAll
    void start() throws Exception {
        server = TestServer.start("sdk-java-params");
        db = server.connect();
        db.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, t TEXT, b BLOB, f DOUBLE, k BOOL)");
        db.execute("CREATE TABLE " + USERS + " (id INT PRIMARY KEY)");
        db.execute("CREATE TABLE dec_t_java (id INT PRIMARY KEY, d DECIMAL, w DECIMAL)");
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

    private String one(String sql, Object... args) {
        Rows rows = db.query(sql, Arrays.asList(args), "main");
        assertFalse(rows.rows().isEmpty(), "no row for " + sql);
        return rows.rows().get(0).get(0);
    }

    @Test
    void theServerGrantedServerParams() {
        assertTrue(db.serverParamsGranted());
        assertTrue((db.grantedFeatures() & TriCore.FEATURE_SERVER_PARAMS) != 0);
    }

    @Test
    void aSingleQuoteAndABackslashRoundTripExactly() {
        String quote = "O'Hara said 'hi'";
        String back = "C:\\Users\\trine\\a\\'b";
        db.execute("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", List.of(1, quote), "main");
        db.execute("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", List.of(2, back), "main");
        assertEquals(quote, one("SELECT t FROM " + TABLE + " WHERE id = ?", 1));
        assertEquals(back, one("SELECT t FROM " + TABLE + " WHERE id = ?", 2));
    }

    @Test
    void aBoundNullIsSqlNull() {
        db.execute("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", Arrays.asList(3, null), "main");
        assertEquals("1", one("SELECT COUNT(*) FROM " + TABLE + " WHERE id = ? AND t IS NULL", 3));
    }

    @Test
    void aByteArrayGoesOutAsHexAndRoundTripsThroughABlob() {
        byte[] blob = {0x00, 0x01, (byte) 0xff, (byte) 0xfe, '\'', '\\', 'h', 'i', 0x00};
        db.execute("INSERT INTO " + TABLE + " (id, b) VALUES (?, ?)", List.of(4, blob), "main");
        assertEquals("0x0001fffe275c686900", one("SELECT b FROM " + TABLE + " WHERE id = ?", 4));
    }

    @Test
    void booleansAndFloatsKeepTheirType() {
        db.execute("INSERT INTO " + TABLE + " (id, k) VALUES (?, ?)", List.of(5, true), "main");
        db.execute("INSERT INTO " + TABLE + " (id, k) VALUES (?, ?)", List.of(6, false), "main");
        db.execute("INSERT INTO " + TABLE + " (id, f) VALUES (?, ?)", List.of(7, -0.125d), "main");
        assertEquals("true", one("SELECT k FROM " + TABLE + " WHERE id = ?", 5));
        assertEquals("false", one("SELECT k FROM " + TABLE + " WHERE id = ?", 6));
        assertEquals("1", one("SELECT COUNT(*) FROM " + TABLE + " WHERE k = ?", true));
        assertEquals("-0.125", one("SELECT f FROM " + TABLE + " WHERE id = ?", 7));
    }

    @Test
    void aValueThatIsItselfSqlStaysData() {
        String inject = "'; DROP TABLE " + USERS + "; --";
        db.execute("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", List.of(8, inject), "main");
        assertEquals(inject, one("SELECT t FROM " + TABLE + " WHERE id = ?", 8));
        assertEquals("1", one("SELECT COUNT(*) FROM " + TABLE + " WHERE t = ?", inject));
        assertEquals(0, db.query("SELECT id FROM " + USERS).rows().size());
    }

    @Test
    void bigDecimalRoundTripsEveryDigitThroughADecimalColumn() {
        BigDecimal exact = new BigDecimal("12345678901234567890.123456789012345678");
        db.execute("INSERT INTO dec_t_java (id, d) VALUES (?, ?)", List.of(1, exact), "main");
        assertEquals("12345678901234567890.123456789012345678",
                one("SELECT d FROM dec_t_java WHERE id = ?", 1));

        db.execute("INSERT INTO dec_t_java (id, w) VALUES (?, ?)", List.of(2, new BigDecimal("1.5E+3")), "main");
        assertEquals("1500", one("SELECT w FROM dec_t_java WHERE id = ?", 2));

        db.execute("INSERT INTO dec_t_java (id, w) VALUES (?, ?)", List.of(3, new BigDecimal("0.10")), "main");
        assertEquals("0.10", one("SELECT w FROM dec_t_java WHERE id = ?", 3));
        assertEquals("1", one("SELECT COUNT(*) FROM dec_t_java WHERE w = ?", new BigDecimal("0.10")));
    }

    @Test
    void bigDecimalIntoADoubleColumnFailsByNameInsteadOfLosingPrecision() {
        TriCoreException e = assertThrows(TriCoreException.class,
                () -> db.execute("INSERT INTO " + TABLE + " (id, f) VALUES (?, ?)",
                        List.of(40, new BigDecimal("0.1")), "main"));
        assertFalse(e.getMessage().isBlank());
        assertEquals("0", one("SELECT COUNT(*) FROM " + TABLE + " WHERE id = ?", 40));
    }

    @Test
    void bigIntegerStillBindsAsANumber() {
        db.execute("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", List.of(BigInteger.valueOf(41), "bi"), "main");
        assertEquals("bi", one("SELECT t FROM " + TABLE + " WHERE id = ?", 41));
    }

    @Test
    void withoutServerParamsTheArgumentOverloadsRefuseByName() {
        try (TriCore plain = server.connect(TriCore.FEATURES & ~TriCore.FEATURE_SERVER_PARAMS)) {
            assertFalse(plain.serverParamsGranted());
            TriCoreException exec = assertThrows(TriCoreException.class,
                    () -> plain.execute("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", List.of(99, "x"), "main"));
            assertTrue(exec.getMessage().contains("SERVER_PARAMS"), exec.getMessage());
            assertTrue(exec.getMessage().contains("will not silently render"), exec.getMessage());
            TriCoreException query = assertThrows(TriCoreException.class,
                    () -> plain.query("SELECT t FROM " + TABLE + " WHERE t = ?", List.of("x"), "main"));
            assertTrue(query.getMessage().contains("SERVER_PARAMS"), query.getMessage());
            TriCoreException txn = assertThrows(TriCoreException.class,
                    () -> plain.transaction(List.of(SqlStatement.of("INSERT INTO " + TABLE + " (id) VALUES (?)", 98))));
            assertTrue(txn.getMessage().contains("SERVER_PARAMS"), txn.getMessage());
            assertTrue(plain.query("SELECT COUNT(*) FROM " + TABLE).rows().size() == 1);
        }
        assertEquals("0", one("SELECT COUNT(*) FROM " + TABLE + " WHERE id = ? OR id = ?", 99, 98));
    }

    @Test
    void transactionBindsItsArgumentsServerSide() {
        String inject = "'; DROP TABLE " + USERS + "; --";
        TransactionResult res = db.transaction(List.of(
                SqlStatement.of("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", 10, inject),
                SqlStatement.of("INSERT INTO " + TABLE + " (id, t) VALUES (?, ?)", 11, "b\\c")));
        assertEquals("committed", res.outcome());
        assertEquals(2, res.committedWrites());
        assertEquals(inject, one("SELECT t FROM " + TABLE + " WHERE id = ?", 10));
        assertEquals("b\\c", one("SELECT t FROM " + TABLE + " WHERE id = ?", 11));
    }
}
