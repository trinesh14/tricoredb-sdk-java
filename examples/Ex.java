import io.github.trinesh14.tricoredb.TriCore;
import io.github.trinesh14.tricoredb.TriCoreException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Connection settings and printing helpers the examples share.
 *
 * <p>Settings come from the environment. Copy {@code .env.example} to
 * {@code .env} and export it, or just set the variables:
 *
 * <pre>{@code
 * TRICOREDB_HOST=127.0.0.1 TRICOREDB_PORT=9423 java -cp "out;../target/tricoredb-0.1.0.jar" Examples sql
 * }</pre>
 *
 * <p>There is no .env parser here on purpose: this driver has no dependency
 * beyond the JDK, and an example that needed one would stop being a standalone
 * example.
 */
final class Ex {

    static final String HOST = envOr("TRICOREDB_HOST", "127.0.0.1");
    static final int PORT = Integer.parseInt(envOr("TRICOREDB_PORT", "9423"));
    static final String USER = envOr("TRICOREDB_USER", "admin");
    static final String PASSWORD = envOr("TRICOREDB_PASSWORD", "admin");

    private Ex() {
    }

    static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    /** Connect with the ambient settings. */
    static TriCore connect(String clientName) {
        return TriCore.connect(HOST, PORT, USER, PASSWORD, clientName, 10_000);
    }

    /**
     * Run an example's body, print a banner, and always close the connection.
     *
     * <p>Exits non-zero on failure with the stack trace intact: an example that
     * swallows the reason it failed is worse than no example.
     */
    static void run(String title, Body body) {
        System.out.printf("%n=== %s ===%n", title);
        System.out.printf("connecting to %s:%d as %s%n%n", HOST, PORT, USER);
        try (TriCore db = connect("tricoredb-example")) {
            body.accept(db);
            System.out.printf("%n%s: OK%n", title);
        } catch (Exception e) {
            System.out.printf("%n%s: FAILED%n", title);
            e.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /** A label/value line, aligned so a run reads as a table. */
    static void show(String label, Object value) {
        System.out.printf("  %-26s %s%n", label, format(value));
    }

    /** A section divider. */
    static void section(String name) {
        System.out.printf("%n  -- %s %s%n", name, "-".repeat(Math.max(0, 58 - name.length())));
    }

    /** Drop a fixture, ignoring "it was not there" — examples must re-run. */
    static void ignoreMissing(Runnable r) {
        try {
            r.run();
        } catch (TriCoreException ignored) {
            // A fresh store has no such fixture; that is the point.
        }
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String format(Object v) {
        if (v instanceof byte[] bytes) {
            return s(bytes);
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(format(list.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?> map) {
            return map.toString();
        }
        return String.valueOf(v);
    }

    /** An example body: takes a live connection, may throw. */
    interface Body {
        void accept(TriCore db) throws Exception;
    }
}
