package io.github.trinesh14.tricoredb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code (field, value)} entry for a cache hash or stream.
 *
 * Fields are arbitrary bytes and need not be UTF-8, which is why this exists at
 * all rather than the API taking a {@code Map<String, byte[]>}: a map keyed by
 * String cannot represent a field the server accepts.
 */
public record CachePair(byte[] field, byte[] value) {

    public CachePair {
        if (field == null || value == null) {
            throw new IllegalArgumentException("a cache pair needs both a field and a value");
        }
    }

    /** A pair from two strings, both encoded UTF-8. */
    public static CachePair ofText(String field, String value) {
        return new CachePair(field.getBytes(UTF_8), value.getBytes(UTF_8));
    }

    /** Pairs from a string map, preserving iteration order. */
    public static List<CachePair> ofText(Map<String, String> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        List<CachePair> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> e : entries.entrySet()) {
            out.add(ofText(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** The field decoded as UTF-8. Wrong for binary fields; {@link #field()} stays authoritative. */
    public String fieldText() {
        return new String(field, UTF_8);
    }

    /** The value decoded as UTF-8. Wrong for binary values; {@link #value()} stays authoritative. */
    public String valueText() {
        return new String(value, UTF_8);
    }

    /** Decode a wire list of {@code [field, value]} arrays. */
    static List<CachePair> decode(List<Object> raw, String what) {
        List<CachePair> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (!(o instanceof List<?> pair) || pair.size() != 2) {
                throw new ProtocolException("expected each " + what + " to be a [field, value] pair");
            }
            out.add(new CachePair(Wire.bytes(pair.get(0), what + " field"),
                    Wire.bytes(pair.get(1), what + " value")));
        }
        return out;
    }

    /** The pairs as a map of decoded text. Convenient, and wrong for binary data. */
    public static Map<String, String> toText(List<CachePair> pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (CachePair p : pairs) {
            out.put(p.fieldText(), p.valueText());
        }
        return out;
    }

    // Arrays need value equality here: the record default compares references,
    // so two pairs holding identical bytes would not be equal.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof CachePair other
                && Arrays.equals(field, other.field)
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(field) + Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "CachePair(" + fieldText() + ")";
    }
}
