package io.github.trinesh14.tricoredb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A document query filter.
 *
 * Every {@code field} accepts dot notation ({@code "a.b.c"}) resolving into
 * nested JSON objects; a missing path never matches, including for
 * {@link #ne}. Comparisons across mismatched types never match.
 *
 * <pre>{@code
 * DocumentFilter f = DocumentFilter.and(
 *         DocumentFilter.gt("age", 30),
 *         DocumentFilter.eq("city", "Pune"));
 * db.documentFind("users", f, 10);
 * }</pre>
 *
 * This is deliberately <b>not</b> a MongoDB query language: the server has no
 * {@code Or}, no {@code Not} and no regex, so neither does this class. A
 * builder that accepted them would have to translate into something the server
 * cannot evaluate.
 */
public final class DocumentFilter {

    private final Object wire;

    private DocumentFilter(Object wire) {
        this.wire = wire;
    }

    /** Match every document. */
    public static DocumentFilter all() {
        return new DocumentFilter("All");
    }

    /** Match documents whose {@code field} equals {@code value}. */
    public static DocumentFilter eq(String field, Object value) {
        return comparison("Eq", field, value);
    }

    /** Match documents whose {@code field} exists and does not equal {@code value}. */
    public static DocumentFilter ne(String field, Object value) {
        return comparison("Ne", field, value);
    }

    public static DocumentFilter gt(String field, Object value) {
        return comparison("Gt", field, value);
    }

    public static DocumentFilter gte(String field, Object value) {
        return comparison("Gte", field, value);
    }

    public static DocumentFilter lt(String field, Object value) {
        return comparison("Lt", field, value);
    }

    public static DocumentFilter lte(String field, Object value) {
        return comparison("Lte", field, value);
    }

    /**
     * Match documents whose {@code field} equals any of {@code values}.
     *
     * Named {@code inValues} because {@code in} is a Java keyword.
     */
    public static DocumentFilter inValues(String field, List<?> values) {
        requireField(field);
        if (values == null) {
            throw new IllegalArgumentException("values must not be null");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("field", field);
        body.put("values", new ArrayList<Object>(values));
        return tagged("In", body);
    }

    /**
     * Match documents whose string {@code field} contains {@code value} as a
     * substring, or whose array {@code field} has an element equal to it.
     */
    public static DocumentFilter contains(String field, Object value) {
        return comparison("Contains", field, value);
    }

    /** Match documents satisfying every sub-filter. An empty list matches everything. */
    public static DocumentFilter and(DocumentFilter... filters) {
        return and(Arrays.asList(filters));
    }

    public static DocumentFilter and(List<DocumentFilter> filters) {
        if (filters == null) {
            throw new IllegalArgumentException("filters must not be null");
        }
        List<Object> parts = new ArrayList<>(filters.size());
        for (DocumentFilter f : filters) {
            if (f == null) {
                throw new IllegalArgumentException("filter list must not contain null");
            }
            parts.add(f.wire);
        }
        return tagged("And", parts);
    }

    private static DocumentFilter comparison(String tag, String field, Object value) {
        requireField(field);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("field", field);
        body.put("value", value);
        return tagged(tag, body);
    }

    private static DocumentFilter tagged(String tag, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(tag, body);
        return new DocumentFilter(m);
    }

    private static void requireField(String field) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("field must not be null or empty");
        }
    }

    /** The wire representation the server's externally-tagged enum expects. */
    Object wire() {
        return wire;
    }

    @Override
    public String toString() {
        return "DocumentFilter" + Json.encode(wire);
    }
}
