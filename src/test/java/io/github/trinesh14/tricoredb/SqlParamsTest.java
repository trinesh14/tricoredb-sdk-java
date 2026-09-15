package io.github.trinesh14.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Offline: what each Java value becomes on the two SQL value paths. */
class SqlParamsTest {

    @Test
    void bigDecimalIsSentAsPlainTextOnTheServerParameterPath() {
        Object p = SqlParams.param(new BigDecimal("12345678901234567890.123456789012345678"));
        assertInstanceOf(String.class, p);
        assertEquals("12345678901234567890.123456789012345678", p);
    }

    @Test
    void bigDecimalInExponentFormIsFlattenedNotSentAsAnExponent() {
        assertEquals("1500", SqlParams.param(new BigDecimal("1.5E+3")));
        assertEquals("0.00000012", SqlParams.param(new BigDecimal("1.2E-7")));
        assertEquals("1.50", SqlParams.param(new BigDecimal("1.50")));
    }

    @Test
    void bigDecimalIsPlainOnTheClientSideLiteralPath() {
        assertEquals("1500", SqlParams.literal(new BigDecimal("1.5E+3")));
        assertEquals("-0.000000000000000001", SqlParams.literal(new BigDecimal("-1E-18")));
        assertEquals("SELECT 1500, 'a''b'", SqlParams.bind("SELECT ?, ?", new BigDecimal("1.5E+3"), "a'b"));
    }

    @Test
    void bigIntegerIsUnchanged() {
        BigInteger big = new BigInteger("123456789012345678901234567890");
        assertSame(big, SqlParams.param(big));
        assertEquals("123456789012345678901234567890", SqlParams.literal(big));
    }

    @Test
    void byteArrayGoesOutAsZeroXHex() {
        byte[] blob = {0x00, 0x01, (byte) 0xff, (byte) 0xfe, '\'', '\\', 'h', 'i', 0x00};
        assertEquals("0x0001fffe275c686900", SqlParams.param(blob));
        assertEquals("0x", SqlParams.param(new byte[0]));
    }

    @Test
    void otherScalarsKeepTheirJsonForm() {
        assertNull(SqlParams.param(null));
        assertEquals(Boolean.TRUE, SqlParams.param(true));
        assertEquals(42, SqlParams.param(42));
        assertEquals(-0.125d, SqlParams.param(-0.125d));
        assertEquals("x", SqlParams.param('x'));
        assertEquals("sb", SqlParams.param(new StringBuilder("sb")));
        UUID u = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals("123e4567-e89b-12d3-a456-426614174000", SqlParams.param(u));
        assertEquals(Arrays.asList("0x0a", null, 7L), SqlParams.params(Arrays.asList(new byte[] {10}, null, 7L)));
    }

    @Test
    void nonFiniteAndUnknownTypesAreRefusedByName() {
        IllegalArgumentException nan = assertThrows(IllegalArgumentException.class,
                () -> SqlParams.param(Double.NaN));
        assertTrue(nan.getMessage().contains("NaN"));
        IllegalArgumentException obj = assertThrows(IllegalArgumentException.class,
                () -> SqlParams.param(List.of(1)));
        assertTrue(obj.getMessage().contains("no SQL parameter form"));
    }

    @Test
    void clientSideBindCountsPlaceholdersAndIgnoresQuotedOnes() {
        assertEquals("SELECT '?', 1", SqlParams.bind("SELECT '?', ?", 1));
        assertThrows(IllegalArgumentException.class, () -> SqlParams.bind("SELECT ?, ?", 1));
        assertThrows(IllegalArgumentException.class, () -> SqlParams.bind("SELECT ?", 1, 2));
    }
}
