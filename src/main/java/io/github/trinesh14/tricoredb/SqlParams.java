package io.github.trinesh14.tricoredb;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Client-side parameter binding for SQL statements.
 *
 * <h2>TriCoreDB V1 has no server-side prepared statements</h2>
 *
 * The wire carries one {@code sql} string and nothing else. Parameters are
 * therefore rendered into that string here, in the driver, before it is sent.
 * That is a real and useful feature — it removes the hand-rolled concatenation
 * where injection bugs actually come from — but it is not the same guarantee a
 * server-side bind gives you, and this class is where that distinction is
 * documented rather than quietly blurred.
 *
 * <p>What it does guarantee:
 * <ul>
 *   <li>Strings are escaped by doubling every {@code '}, which is the only
 *       escape the server's tokenizer recognises. There is no backslash escape
 *       to smuggle a quote past.</li>
 *   <li>Numbers render with {@link Locale#ROOT}, so a comma decimal separator in
 *       the ambient locale cannot turn {@code 1.5} into {@code 1,5} and thereby
 *       into two values.</li>
 *   <li>Only a closed set of types is accepted. An unsupported type throws
 *       rather than falling back to {@code toString()}, which is how an object's
 *       debug rendering ends up inside a WHERE clause.</li>
 *   <li>Placeholder count must equal argument count, so a dropped argument fails
 *       loudly instead of shifting every later value by one.</li>
 *   <li>A {@code ?} inside a string literal is data, not a placeholder.</li>
 * </ul>
 *
 * <p>What it does not do: it cannot protect an <b>identifier</b>. Table and
 * column names are not values and are not bindable; build those from a
 * whitelist you control.
 *
 * <pre>{@code
 * db.query(SqlParams.bind("SELECT * FROM users WHERE city = ? AND age > ?", "Pune", 30));
 * }</pre>
 */
public final class SqlParams {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private SqlParams() {
    }

    /** Render {@code sql} with each {@code ?} replaced by the matching argument. */
    public static String bind(String sql, Object... args) {
        return bind(sql, args == null ? List.of() : java.util.Arrays.asList(args));
    }

    /** Render {@code sql} with each {@code ?} replaced by the matching argument. */
    public static String bind(String sql, List<Object> args) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        List<Object> values = args == null ? List.of() : args;

        StringBuilder out = new StringBuilder(sql.length() + values.size() * 8);
        int next = 0;
        boolean inString = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // `''` inside a literal is an escaped quote, not the end of one.
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
                out.append(c);
                continue;
            }
            if (c == '?' && !inString) {
                if (next >= values.size()) {
                    throw new IllegalArgumentException(
                            "SQL has more `?` placeholders than the " + values.size()
                                    + " argument(s) supplied");
                }
                out.append(literal(values.get(next++)));
                continue;
            }
            out.append(c);
        }

        if (inString) {
            throw new IllegalArgumentException("SQL ends inside an unterminated string literal");
        }
        if (next != values.size()) {
            throw new IllegalArgumentException("SQL has " + next + " `?` placeholder(s) but "
                    + values.size() + " argument(s) were supplied");
        }
        return out.toString();
    }

    /**
     * One value as a <b>server-side</b> parameter: the JSON scalar that travels
     * beside the statement for the server to bind.
     *
     * <h4>Why this is not {@link #literal}</h4>
     *
     * {@code literal} has to reproduce the server's literal syntax exactly, for
     * every type, and any mismatch is a wrong value or a parse error. A bound
     * parameter is substituted at a value position the grammar has already
     * fixed, so what a value contains — a quote, a backslash, a whole SQL
     * statement — can never change what the statement means.
     *
     * <p>The wire carries plain JSON because five SDKs build this payload; the
     * server maps each scalar to a typed SQL value and refuses, by name and by
     * index, anything it cannot represent. So this method's job is only the
     * types {@link Json} would otherwise get wrong or refuse:
     *
     * <ul>
     *   <li>{@code byte[]} becomes {@code 0x}-prefixed hex, which is what a
     *       BLOB column parses. {@link Json} would otherwise write the array of
     *       ints the protocol uses for cache values and AUTH secrets, and the
     *       server refuses a JSON array as a parameter. (Client-side rendering
     *       had no {@code byte[]} case at all — it threw.)</li>
     *   <li>{@code Instant} / {@code LocalDateTime} become the same sortable
     *       UTC text this driver has always sent for a TIMESTAMP column.</li>
     *   <li>{@code UUID} becomes its canonical text, which a UUID column
     *       parses.</li>
     *   <li>{@code Character} becomes a one-character String; JSON has no char.</li>
     *   <li>NaN and infinities are refused here rather than reaching
     *       {@link Json}, which would write the literal text {@code NaN} and
     *       produce a frame that is not JSON at all.</li>
     * </ul>
     *
     * <p>Everything else passes through: an integral type is exact in JSON at
     * every width Java has, and a String is a TEXT parameter whatever it holds.
     * A {@code BigDecimal} is sent as <b>text</b>. A JSON number is read through
     * a {@code double} on the way in, so every digit past a double's precision
     * was silently lost — which is the one thing a {@code BigDecimal} is chosen
     * to prevent. Text is the only wire spelling that carries them all, and a
     * DECIMAL column parses it exactly.
     *
     * <p>{@code toPlainString} rather than {@code toString}: the latter keeps
     * exponent notation for values like {@code 1.5E+3}, and the server's
     * tokenizer reads any exponent-bearing literal as a DOUBLE — back to the
     * binary float this exists to avoid.
     *
     * <p>The cost, stated plainly: binding a {@code BigDecimal} into a
     * <b>DOUBLE</b> column now fails by name, because the server accepts text
     * for DECIMAL but not for DOUBLE. Pass a {@code Double} for a DOUBLE column.
     * A named failure is the intended trade against silent precision loss.
     */
    public static Object param(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
            sb.append("0x");
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
        if (value instanceof Float f) {
            return requireFinite(f.doubleValue());
        }
        if (value instanceof Double d) {
            return requireFinite(d);
        }
        // Before the `Number` catch-all below, which would write it as a JSON
        // number and lose every digit past a double.
        if (value instanceof java.math.BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof Instant i) {
            return LocalDateTime.ofInstant(i, ZoneOffset.UTC).format(TIMESTAMP);
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(TIMESTAMP);
        }
        if (value instanceof java.util.UUID u) {
            return u.toString();
        }
        if (value instanceof Character c) {
            return c.toString();
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof CharSequence) {
            // CharSequence rather than String: a StringBuilder argument is text
            // too, and Json only knows how to write a String.
            return value instanceof CharSequence cs && !(value instanceof String)
                    ? cs.toString()
                    : value;
        }
        throw new IllegalArgumentException("no SQL parameter form for "
                + value.getClass().getName() + ". Convert it explicitly — passing it through "
                + "would put a JSON object or array where the server expects a scalar.");
    }

    /** Every value in {@code args} as a server-side parameter, in order. */
    public static List<Object> params(List<Object> args) {
        List<Object> out = new java.util.ArrayList<>(args == null ? 0 : args.size());
        if (args != null) {
            for (Object a : args) {
                out.add(param(a));
            }
        }
        return out;
    }

    private static Object requireFinite(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("`" + d + "` has no SQL parameter form");
        }
        return d;
    }

    /** Render one value as a SQL literal. */
    public static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Float f) {
            return real(f.doubleValue());
        }
        if (value instanceof Double d) {
            return real(d);
        }
        if (value instanceof java.math.BigDecimal bd) {
            // Plain, never exponent form: `new BigDecimal("1.5E+3").toString()`
            // is `1.5E+3`, and this tokenizer reads an exponent as a DOUBLE.
            return bd.toPlainString();
        }
        if (value instanceof java.math.BigInteger) {
            return value.toString();
        }
        if (value instanceof CharSequence s) {
            return quote(s.toString());
        }
        if (value instanceof Character c) {
            return quote(c.toString());
        }
        // The server has no date type, so a timestamp is stored as text and must
        // round-trip in a sortable form.
        if (value instanceof Instant i) {
            return quote(LocalDateTime.ofInstant(i, ZoneOffset.UTC).format(TIMESTAMP));
        }
        if (value instanceof LocalDateTime ldt) {
            return quote(ldt.format(TIMESTAMP));
        }
        throw new IllegalArgumentException("no SQL literal form for "
                + value.getClass().getName() + ". Convert it explicitly — falling back to "
                + "toString() would put an object's debug rendering into the statement.");
    }

    /**
     * Escape and quote a string: double every {@code '}, which is the only escape
     * the server's tokenizer recognises.
     */
    public static String quote(String s) {
        if (s == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return "'" + s.replace("'", "''") + "'";
    }

    private static String real(double d) {
        // The parser has no literal for these, so emitting one produces a
        // statement the server rejects with a syntax error far from the cause.
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("`" + d + "` has no SQL literal form");
        }
        return String.format(Locale.ROOT, "%s", d);
    }
}
