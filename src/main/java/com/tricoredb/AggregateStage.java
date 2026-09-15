package com.tricoredb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stage of a document aggregation pipeline.
 *
 * Stages are applied <b>strictly in the order given</b>; the order is
 * semantics, not style. {@code match} before {@code group} filters documents,
 * after it filters groups.
 *
 * <pre>{@code
 * List<Map<String, Object>> out = db.documentAggregate("sales", List.of(
 *         AggregateStage.match(DocumentFilter.eq("tier", "gold")),
 *         AggregateStage.group(
 *                 AggregateStage.byField("city"),
 *                 AggregateStage.sum("total", "amount")),
 *         AggregateStage.sort(AggregateStage.desc("total")),
 *         AggregateStage.limit(10)));
 * }</pre>
 *
 * {@code $lookup}, {@code $unwind}, {@code $facet}, {@code $out} and
 * {@code $addFields} are absent here because the server refuses them by name.
 */
public final class AggregateStage {

    private final Object wire;

    private AggregateStage(Object wire) {
        this.wire = wire;
    }

    /** Filter with the same matcher {@code documentFind} uses. */
    public static AggregateStage match(DocumentFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter must not be null");
        }
        return tagged("Match", filter.wire());
    }

    public static AggregateStage group(GroupKey by, Accumulator... accumulators) {
        return group(by, Arrays.asList(accumulators));
    }

    public static AggregateStage group(GroupKey by, List<Accumulator> accumulators) {
        if (by == null) {
            throw new IllegalArgumentException("group key must not be null");
        }
        List<Object> accs = new ArrayList<>();
        for (Accumulator a : accumulators) {
            accs.add(a.wire());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("by", by.wire());
        body.put("accumulators", accs);
        return tagged("Group", body);
    }

    public static AggregateStage sort(SortKey... keys) {
        return sort(Arrays.asList(keys));
    }

    public static AggregateStage sort(List<SortKey> keys) {
        List<Object> out = new ArrayList<>();
        for (SortKey k : keys) {
            out.add(k.wire());
        }
        return tagged("Sort", out);
    }

    public static AggregateStage skip(int n) {
        return tagged("Skip", requireNonNegative(n, "skip"));
    }

    public static AggregateStage limit(int n) {
        return tagged("Limit", requireNonNegative(n, "limit"));
    }

    /** Keep ({@code include = true}) or drop the named top-level fields. */
    public static AggregateStage project(List<String> fields, boolean include) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", new ArrayList<String>(fields));
        body.put("include", include);
        return tagged("Project", body);
    }

    /** Replace the stream with a single document holding the input count. */
    public static AggregateStage count(String field) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("field", requireName(field, "count field"));
        return tagged("Count", body);
    }

    // -- group keys ------------------------------------------------------------

    /**
     * Group by the value at a dot-notation path. A document missing that path
     * groups under {@code null} rather than being dropped.
     */
    public static GroupKey byField(String field) {
        return new GroupKey(single("Field", requireName(field, "group field")));
    }

    /** One group for the whole collection, keyed by this constant. */
    public static GroupKey byConstant(Object value) {
        return new GroupKey(single("Constant", value));
    }

    // -- accumulators ----------------------------------------------------------

    public static Accumulator sum(String output, String field) {
        return accumulator(output, single("Sum", requireName(field, "sum field")));
    }

    public static Accumulator avg(String output, String field) {
        return accumulator(output, single("Avg", requireName(field, "avg field")));
    }

    public static Accumulator min(String output, String field) {
        return accumulator(output, single("Min", requireName(field, "min field")));
    }

    public static Accumulator max(String output, String field) {
        return accumulator(output, single("Max", requireName(field, "max field")));
    }

    /**
     * Counts documents, so it reads no field.
     *
     * Named {@code countInto} rather than {@code count} because {@link #count}
     * is already the {@code $count} <i>stage</i>, which is a different thing:
     * the stage replaces the whole stream with one document, this accumulator
     * adds a per-group total.
     */
    public static Accumulator countInto(String output) {
        return accumulator(output, "Count");
    }

    // -- sort keys -------------------------------------------------------------

    public static SortKey asc(String field) {
        return new SortKey(field, false);
    }

    public static SortKey desc(String field) {
        return new SortKey(field, true);
    }

    /** How {@code group} derives a group key from a document. */
    public static final class GroupKey {

        private final Object wire;

        private GroupKey(Object wire) {
            this.wire = wire;
        }

        Object wire() {
            return wire;
        }
    }

    /**
     * A reduction plus the field name it writes into the grouped document.
     *
     * {@code output} must not be {@code _id} (that holds the group key) and
     * must not repeat within one {@code group}.
     */
    public static final class Accumulator {

        private final String output;
        private final Object op;

        private Accumulator(String output, Object op) {
            this.output = output;
            this.op = op;
        }

        Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("output", output);
            m.put("op", op);
            return m;
        }
    }

    /**
     * One sort key. After a {@code group} the addressable fields are
     * {@code _id} and the accumulator outputs, not the original document's.
     */
    public static final class SortKey {

        private final String field;
        private final boolean descending;

        private SortKey(String field, boolean descending) {
            this.field = requireName(field, "sort field");
            this.descending = descending;
        }

        Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("descending", descending);
            return m;
        }
    }

    private static Accumulator accumulator(String output, Object op) {
        if ("_id".equals(output)) {
            throw new IllegalArgumentException("accumulator output must not be `_id` — that holds the group key");
        }
        return new Accumulator(requireName(output, "accumulator output"), op);
    }

    private static Map<String, Object> single(String tag, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(tag, body);
        return m;
    }

    private static AggregateStage tagged(String tag, Object body) {
        return new AggregateStage(single(tag, body));
    }

    private static String requireName(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be null or empty");
        }
        return value;
    }

    private static int requireNonNegative(int n, String what) {
        if (n < 0) {
            throw new IllegalArgumentException(what + " must not be negative");
        }
        return n;
    }

    Object wire() {
        return wire;
    }

    @Override
    public String toString() {
        return "AggregateStage" + Json.encode(wire);
    }
}
