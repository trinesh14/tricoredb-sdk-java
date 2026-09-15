package io.github.trinesh14.tricoredb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decoding helpers shared by the document/vector/graph methods.
 *
 * The server's {@code ResponseData} is an externally-tagged enum, so a payload
 * arrives as {@code {"Json": ...}}, {@code {"Documents": [...]}}, and so on.
 * Every accessor here refuses the wrong tag with a {@link ProtocolException}
 * rather than returning null or an empty collection: a caller that cannot tell
 * "the server said no" from "there was nothing there" will eventually act on
 * the difference.
 */
final class Wire {

    private Wire() {
    }

    /** The {@code Json} payload as an object. Throws if absent or not an object. */
    static Map<String, Object> json(Response resp) {
        Object v = jsonOrNull(resp);
        if (v instanceof Map<?, ?>) {
            return cast(v);
        }
        throw new ProtocolException("expected a JSON object payload, got " + describe(v));
    }

    /** The {@code Json} payload, which the server sets to null for a miss. */
    static Object jsonOrNull(Response resp) {
        Object data = resp.data();
        if (data instanceof Map<?, ?> m && m.containsKey("Json")) {
            return m.get("Json");
        }
        throw new ProtocolException("expected Json payload, got " + describe(data));
    }

    /**
     * The {@code CacheValue} payload as bytes, or empty on a miss.
     *
     * A miss is a legitimate answer (an absent key, an empty list pop), so the
     * check is for the <b>presence</b> of the arm rather than a non-null value —
     * conflating the two would turn "no such key" into a protocol error.
     */
    static Optional<byte[]> cacheValue(Response resp) {
        Object data = resp.data();
        if (data instanceof Map<?, ?> m && m.containsKey("CacheValue")) {
            Object v = m.get("CacheValue");
            return v == null ? Optional.empty() : Optional.of(bytes(v, "cache value"));
        }
        throw new ProtocolException("expected CacheValue, got " + describe(data));
    }

    /** A wire byte array (a JSON array of small integers) back to bytes. */
    static byte[] bytes(Object v, String what) {
        if (!(v instanceof List<?> list)) {
            throw new ProtocolException("expected " + what + " to be a byte array, got " + describe(v));
        }
        byte[] out = new byte[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Number) list.get(i)).intValue();
        }
        return out;
    }

    /** A list of wire byte arrays, e.g. {@code LRange}'s values. */
    static List<byte[]> byteLists(Map<String, Object> m, String key) {
        List<Object> raw = list(m, key);
        List<byte[]> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            out.add(bytes(o, key + " element"));
        }
        return out;
    }

    /** The {@code Documents} payload. Throws if the tag is anything else. */
    static List<Map<String, Object>> documents(Response resp) {
        Object data = resp.data();
        if (data instanceof Map<?, ?> m && m.containsKey("Documents")) {
            Object v = m.get("Documents");
            if (v == null) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (o instanceof Map<?, ?>) {
                    out.add(cast(o));
                } else {
                    throw new ProtocolException("document is not a JSON object: " + describe(o));
                }
            }
            return out;
        }
        throw new ProtocolException("expected Documents payload, got " + describe(data));
    }

    // -- field accessors -------------------------------------------------------

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new ProtocolException("expected numeric `" + key + "`, got " + describe(v));
    }

    static double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new ProtocolException("expected numeric `" + key + "`, got " + describe(v));
    }

    static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    /** A list field, or an empty list when the server omitted it. */
    static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return cast(l);
        }
        throw new ProtocolException("expected array `" + key + "`, got " + describe(v));
    }

    static List<String> strings(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /** A nested object field, or an empty map when absent/null. */
    static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?>) {
            return cast(v);
        }
        throw new ProtocolException("expected object `" + key + "`, got " + describe(v));
    }

    /** An element of a decoded array, required to be an object. */
    static Map<String, Object> asObject(Object o, String what) {
        if (o instanceof Map<?, ?>) {
            return cast(o);
        }
        throw new ProtocolException("expected " + what + " object, got " + describe(o));
    }

    static float[] floats(Map<String, Object> m, String key) {
        List<Object> raw = list(m, key);
        float[] out = new float[raw.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = raw.get(i);
            if (!(v instanceof Number n)) {
                throw new ProtocolException("vector element " + i + " is not a number: " + describe(v));
            }
            out[i] = n.floatValue();
        }
        return out;
    }

    // -- encoding helpers ------------------------------------------------------

    /** Wrap a core op under its family tag, e.g. {@code {"Vector": {"Get": ...}}}. */
    static Map<String, Object> op(String family, String variant, Object body) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put(variant, body);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put(family, inner);
        return outer;
    }

    /** A fieldless variant such as {@code ListCollections}, encoded as a bare string. */
    static Map<String, Object> unitOp(String family, String variant) {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put(family, variant);
        return outer;
    }

    static List<Object> vectorOf(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        List<Object> out = new ArrayList<>(vector.length);
        for (float f : vector) {
            out.add(f);
        }
        return out;
    }

    static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            return "object with key `" + m.keySet().iterator().next() + "`";
        }
        return v.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
