package io.github.trinesh14.tricoredb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field mutations to apply to a document: {@code set} overwrites the value at
 * a dot-notation path, {@code inc} adds a number to it. Every {@code set} is
 * applied before every {@code inc}.
 *
 * <pre>{@code
 * DocumentUpdate u = DocumentUpdate.builder()
 *         .set("city", "Pune")
 *         .inc("visits", 1)
 *         .build();
 * }</pre>
 *
 * {@code inc} never coerces: incrementing a field holding a string, bool, null,
 * array or object is a server-side error rather than a silent conversion, and
 * a missing field increments from {@code 0}. There is no {@code dec} — a
 * negative {@code inc} is exactly that operation.
 */
public final class DocumentUpdate {

    private final Map<String, Object> set;
    private final Map<String, Object> inc;

    private DocumentUpdate(Map<String, Object> set, Map<String, Object> inc) {
        this.set = set;
        this.inc = inc;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Shorthand for a single {@code set}. */
    public static DocumentUpdate set(String path, Object value) {
        return builder().set(path, value).build();
    }

    /** Shorthand for a single {@code inc}. */
    public static DocumentUpdate inc(String path, Number delta) {
        return builder().inc(path, delta).build();
    }

    public static final class Builder {

        private final Map<String, Object> set = new LinkedHashMap<>();
        private final Map<String, Object> inc = new LinkedHashMap<>();

        public Builder set(String path, Object value) {
            requirePath(path);
            set.put(path, value);
            return this;
        }

        public Builder inc(String path, Number delta) {
            requirePath(path);
            if (delta == null) {
                throw new IllegalArgumentException("inc delta must not be null");
            }
            inc.put(path, delta);
            return this;
        }

        public DocumentUpdate build() {
            if (set.isEmpty() && inc.isEmpty()) {
                throw new IllegalArgumentException(
                        "a DocumentUpdate needs at least one set or inc — an empty update would "
                                + "issue a write that changes nothing");
            }
            return new DocumentUpdate(new LinkedHashMap<>(set), new LinkedHashMap<>(inc));
        }

        private static void requirePath(String path) {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("path must not be null or empty");
            }
        }
    }

    Map<String, Object> wire() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("set", set);
        m.put("inc", inc);
        return m;
    }

    @Override
    public String toString() {
        return "DocumentUpdate" + Json.encode(wire());
    }
}
