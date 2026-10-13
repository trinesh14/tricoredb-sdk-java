package com.tricoredb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.Function;

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

    /**
     * The {@code CacheValues} payload: one entry per requested key, in the order
     * asked, empty where the key was absent or expired.
     */
    static List<Optional<byte[]>> cacheValues(Response resp) {
        Object data = resp.data();
        if (data instanceof Map<?, ?> m && m.get("CacheValues") instanceof List<?> values) {
            List<Optional<byte[]>> out = new ArrayList<>(values.size());
            for (Object v : values) {
                out.add(v == null ? Optional.empty() : Optional.of(bytes(v, "cache value")));
            }
            return out;
        }
        throw new ProtocolException("expected CacheValues, got " + describe(data));
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

    /** A number the server sends as null, or leaves out, when there is none. */
    static OptionalLong optLong(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
    }

    static OptionalDouble optDbl(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty();
    }

    /** A nested object field, or {@code null} when it is absent or not an object. */
    static Map<String, Object> optObj(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> ? cast(m.get(key)) : null;
    }

    /** A list field of objects, each decoded by {@code from}. */
    static <T> List<T> records(Map<String, Object> m, String key, Function<Map<String, Object>, T> from) {
        List<Object> raw = list(m, key);
        List<T> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            out.add(from.apply(asObject(o, key + " element")));
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

    /**
     * A list argument on the wire. A {@code null} list is sent empty, which is
     * the server's own default for a list field.
     */
    static <T> List<Object> wireList(List<T> values, Function<T, Object> wire, String name) {
        List<Object> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (T v : values) {
            if (v == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            out.add(wire.apply(v));
        }
        return out;
    }

    static Map<String, Object> vectorMap(Map<String, float[]> vectors) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            out.put(e.getKey(), vectorOf(e.getValue()));
        }
        return out;
    }

    /** An {@code Option<(a, b)>} field: both ends or neither. */
    static List<Object> pair(Long first, Long second, String name) {
        if (first == null && second == null) {
            return null;
        }
        if (first == null || second == null) {
            throw new IllegalArgumentException(name + " needs both of its values, or neither");
        }
        return List.of(first, second);
    }

    static Object nodeInput(GraphNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("labels", n.labels() == null ? List.of() : n.labels());
        m.put("properties", n.properties() == null ? Map.of() : n.properties());
        return m;
    }

    static Object edgeInput(GraphEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("from", e.from());
        m.put("to", e.to());
        m.put("label", e.label());
        m.put("properties", e.properties() == null ? Map.of() : e.properties());
        return m;
    }

    static Object itemInput(VectorItem v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("vector", vectorOf(v.vector()));
        m.put("metadata", v.metadata() == null ? Map.of() : v.metadata());
        return m;
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
