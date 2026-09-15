import com.tricoredb.AggregateStage;
import com.tricoredb.CacheKeyInfo;
import com.tricoredb.CachePair;
import com.tricoredb.DocumentFilter;
import com.tricoredb.DocumentIndex;
import com.tricoredb.DocumentStats;
import com.tricoredb.DocumentUpdate;
import com.tricoredb.GraphDirection;
import com.tricoredb.GraphEdge;
import com.tricoredb.GraphEdgePage;
import com.tricoredb.GraphNeighbor;
import com.tricoredb.GraphNode;
import com.tricoredb.GraphNodePage;
import com.tricoredb.GraphPath;
import com.tricoredb.GraphQueryResult;
import com.tricoredb.GraphTraversal;
import com.tricoredb.GraphTraversalNode;
import com.tricoredb.LlmSource;
import com.tricoredb.OutputFormat;
import com.tricoredb.Response;
import com.tricoredb.Rows;
import com.tricoredb.StreamEntry;
import com.tricoredb.TriCore;
import com.tricoredb.UpdateManyResult;
import com.tricoredb.VectorCollectionInfo;
import com.tricoredb.VectorItem;
import com.tricoredb.VectorMatch;
import com.tricoredb.VectorMetric;
import com.tricoredb.VectorPage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Conformance runner for this SDK, built against the packaged jar.
 *
 * <pre>
 *   java -jar conformance/out/runner.jar &lt;host&gt; &lt;port&gt; &lt;user&gt; &lt;secret&gt; &lt; scenario.json
 * </pre>
 *
 * Makes no assertions and computes no values: canonical action to SDK call,
 * typed result to canonical JSON, one line per step on stdout.
 */
public final class Runner {

    private Runner() {
    }

    static final class JsonParser {
        private final String s;
        private int i;

        JsonParser(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            JsonParser p = new JsonParser(text);
            p.ws();
            Object v = p.value();
            p.ws();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++;
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String text = s.substring(start, i);
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        }
    }

    static String encode(Object v) {
        StringBuilder sb = new StringBuilder();
        encodeInto(v, sb);
        return sb.toString();
    }

    private static void encodeInto(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            encodeString(str, sb);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isFinite(d) ? trimDouble(d) : "\"" + d + "\"");
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                encodeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                encodeInto(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                encodeInto(o, sb);
            }
            sb.append(']');
        } else if (v instanceof float[] a) {
            sb.append('[');
            for (int k = 0; k < a.length; k++) {
                if (k > 0) {
                    sb.append(',');
                }
                sb.append(trimDouble(a[k]));
            }
            sb.append(']');
        } else {
            encodeString(String.valueOf(v), sb);
        }
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static void encodeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof String s ? s : null;
    }

    private static int intOf(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolOf(Map<String, Object> a, String k) {
        return a.get(k) instanceof Boolean b && b;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    private static List<String> strings(Map<String, Object> a, String k) {
        List<String> out = new ArrayList<>();
        for (Object o : list(a, k)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static float[] floats(Map<String, Object> a, String k) {
        List<Object> raw = list(a, k);
        float[] out = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            out[i] = ((Number) raw.get(i)).floatValue();
        }
        return out;
    }

    private static GraphDirection direction(Map<String, Object> a) {
        String d = str(a, "direction");
        if ("incoming".equals(d)) {
            return GraphDirection.INCOMING;
        }
        if ("both".equals(d)) {
            return GraphDirection.BOTH;
        }
        return GraphDirection.OUTGOING;
    }

    private static DocumentUpdate documentUpdate(Map<String, Object> a) {
        DocumentUpdate.Builder b = DocumentUpdate.builder();
        for (Map.Entry<String, Object> e : obj(a, "set").entrySet()) {
            b.set(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Object> e : obj(a, "inc").entrySet()) {
            b.inc(e.getKey(), (Number) e.getValue());
        }
        return b.build();
    }

    private static DocumentFilter filter(Map<String, Object> spec) {
        String op = String.valueOf(spec.get("op"));
        switch (op) {
            case "all": return DocumentFilter.all();
            case "eq": return DocumentFilter.eq(str(spec, "field"), spec.get("value"));
            case "gt": return DocumentFilter.gt(str(spec, "field"), spec.get("value"));
            case "contains": return DocumentFilter.contains(str(spec, "field"), spec.get("value"));
            case "and": {
                List<DocumentFilter> subs = new ArrayList<>();
                for (Object o : list(spec, "filters")) {
                    subs.add(filter(castMap(o)));
                }
                return DocumentFilter.and(subs);
            }
            default: throw new IllegalArgumentException("unsupported filter op in the scenario: " + op);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static AggregateStage stage(Map<String, Object> spec) {
        String kind = String.valueOf(spec.get("stage"));
        switch (kind) {
            case "match":
                return AggregateStage.match(filter(obj(spec, "filter")));
            case "group": {
                List<AggregateStage.Accumulator> accs = new ArrayList<>();
                for (Object o : list(spec, "accumulators")) {
                    Map<String, Object> a = castMap(o);
                    String op = String.valueOf(a.get("op"));
                    if ("sum".equals(op)) {
                        accs.add(AggregateStage.sum(str(a, "output"), str(a, "field")));
                    } else if ("count".equals(op)) {
                        accs.add(AggregateStage.countInto(str(a, "output")));
                    } else {
                        throw new IllegalArgumentException("unsupported accumulator: " + op);
                    }
                }
                return AggregateStage.group(AggregateStage.byField(str(obj(spec, "by"), "field")), accs);
            }
            case "sort": {
                List<AggregateStage.SortKey> keys = new ArrayList<>();
                for (Object o : list(spec, "keys")) {
                    Map<String, Object> k = castMap(o);
                    keys.add(boolOf(k, "descending")
                            ? AggregateStage.desc(str(k, "field"))
                            : AggregateStage.asc(str(k, "field")));
                }
                return AggregateStage.sort(keys);
            }
            case "count":
                return AggregateStage.count(str(spec, "field"));
            default:
                throw new IllegalArgumentException("unsupported aggregate stage: " + kind);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Object dispatch(TriCore db, String action, Map<String, Object> a,
                                   Map<String, Object> results) {
        switch (action) {
            case "doc.createCollection":
                db.documentCreateCollection(str(a, "collection"));
                return map();
            case "doc.dropCollection":
                db.documentDropCollection(str(a, "collection"));
                return map();
            case "doc.listCollections":
                return map("names", db.documentListCollections());
            case "doc.insert":
                return map("id", db.documentInsert(str(a, "collection"), str(a, "id"), obj(a, "document")));
            case "doc.get": {
                Optional<Map<String, Object>> doc = db.documentGet(str(a, "collection"), str(a, "id"));
                return doc.isPresent() ? map("found", true, "doc", doc.get()) : map("found", false);
            }
            case "doc.find": {
                Integer limit = a.containsKey("limit") ? Integer.valueOf(intOf(a, "limit")) : null;
                return map("docs", db.documentFind(str(a, "collection"), filter(obj(a, "filter")), limit));
            }
            case "doc.update":
                db.documentUpdate(str(a, "collection"), str(a, "id"), obj(a, "set"));
                return map();
            case "doc.updateOne":
                db.documentUpdateOne(str(a, "collection"), str(a, "id"), documentUpdate(a), boolOf(a, "upsert"));
                return map();
            case "doc.updateMany": {
                UpdateManyResult r = db.documentUpdateMany(
                        str(a, "collection"), filter(obj(a, "filter")), documentUpdate(a));
                return map("matched", r.matched(), "modified", r.modified());
            }
            case "doc.delete":
                db.documentDelete(str(a, "collection"), str(a, "id"));
                return map();
            case "doc.createIndex":
                db.documentCreateIndex(str(a, "collection"), str(a, "indexName"), str(a, "field"), boolOf(a, "unique"));
                return map();
            case "doc.dropIndex":
                db.documentDropIndex(str(a, "collection"), str(a, "indexName"));
                return map();
            case "doc.listIndexes": {
                List<Object> out = new ArrayList<>();
                for (DocumentIndex i : db.documentListIndexes(str(a, "collection"))) {
                    out.add(map("name", i.indexName(), "field", i.field()));
                }
                return map("indexes", out);
            }
            case "doc.analyze": {
                DocumentStats s = db.documentAnalyze(str(a, "collection"));
                return map("document_count", s.documentCount());
            }
            case "doc.aggregate": {
                List<AggregateStage> pipeline = new ArrayList<>();
                for (Object o : list(a, "pipeline")) {
                    pipeline.add(stage(castMap(o)));
                }
                return map("docs", db.documentAggregate(str(a, "collection"), pipeline));
            }

            case "vec.createCollection":
                db.vectorCreateCollection(str(a, "collection"), intOf(a, "dimension"),
                        VectorMetric.valueOf(str(a, "metric").toUpperCase(Locale.ROOT)));
                return map();
            case "vec.dropCollection":
                db.vectorDropCollection(str(a, "collection"));
                return map();
            case "vec.listCollections":
                return map("names", db.vectorListCollections());
            case "vec.upsert":
                db.vectorUpsert(str(a, "collection"), str(a, "id"), floats(a, "vector"), obj(a, "metadata"));
                return map();
            case "vec.get": {
                Optional<VectorItem> v = db.vectorGet(str(a, "collection"), str(a, "id"));
                if (v.isEmpty()) {
                    return map("found", false);
                }
                return map("found", true, "id", v.get().id(), "vector", v.get().vector(),
                        "metadata", v.get().metadata());
            }
            case "vec.delete":
                db.vectorDelete(str(a, "collection"), str(a, "id"));
                return map();
            case "vec.search": {
                Map<String, Object> f = a.containsKey("filter") ? obj(a, "filter") : null;
                List<VectorMatch> hits = db.vectorSearch(str(a, "collection"), floats(a, "vector"), intOf(a, "topK"), f);
                List<Object> vids = new ArrayList<>();
                List<Object> scores = new ArrayList<>();
                for (VectorMatch m : hits) {
                    vids.add(m.id());
                    scores.add(m.score());
                }
                return map("ids", vids, "scores", scores);
            }
            case "vec.describeCollection": {
                VectorCollectionInfo info = db.vectorDescribeCollection(str(a, "collection"));
                return map("dimension", info.dimension(), "metric", info.metric().name().toLowerCase(Locale.ROOT),
                        "count", info.count());
            }
            case "vec.listVectors": {
                VectorPage page = db.vectorListVectors(str(a, "collection"));
                List<Object> vids = new ArrayList<>();
                for (VectorItem v : page.vectors()) {
                    vids.add(v.id());
                }
                return map("ids", vids, "total", page.total());
            }

            case "graph.create":
                db.graphCreate(str(a, "graph"));
                return map();
            case "graph.drop":
                db.graphDrop(str(a, "graph"));
                return map();
            case "graph.listGraphs":
                return map("names", db.graphList());
            case "graph.addNode":
                db.graphAddNode(str(a, "graph"), str(a, "id"), strings(a, "labels"), obj(a, "properties"));
                return map();
            case "graph.getNode": {
                Optional<GraphNode> n = db.graphGetNode(str(a, "graph"), str(a, "id"));
                if (n.isEmpty()) {
                    return map("found", false);
                }
                return map("found", true, "id", n.get().id(), "labels", n.get().labels(),
                        "properties", n.get().properties());
            }
            case "graph.deleteNode":
                db.graphDeleteNode(str(a, "graph"), str(a, "id"));
                return map();
            case "graph.addEdge":
                db.graphAddEdge(str(a, "graph"), str(a, "id"), str(a, "from"), str(a, "to"),
                        str(a, "label"), obj(a, "properties"));
                return map();
            case "graph.getEdge": {
                Optional<GraphEdge> e = db.graphGetEdge(str(a, "graph"), str(a, "id"));
                if (e.isEmpty()) {
                    return map("found", false);
                }
                return map("found", true, "id", e.get().id(), "from", e.get().from(), "to", e.get().to(),
                        "label", e.get().label(), "properties", e.get().properties());
            }
            case "graph.deleteEdge":
                db.graphDeleteEdge(str(a, "graph"), str(a, "id"));
                return map();
            case "graph.neighbors": {
                List<GraphNeighbor> ns = db.graphNeighbors(str(a, "graph"), str(a, "nodeId"), direction(a),
                        str(a, "label"), null);
                List<Object> nodeIds = new ArrayList<>();
                List<Object> edgeIds = new ArrayList<>();
                for (GraphNeighbor n : ns) {
                    nodeIds.add(n.nodeId());
                    edgeIds.add(n.edgeId());
                }
                return map("nodeIds", nodeIds, "edgeIds", edgeIds);
            }
            case "graph.degree":
                return map("degree", db.graphDegree(str(a, "graph"), str(a, "nodeId"), direction(a)));
            case "graph.traverse": {
                Integer maxDepth = a.containsKey("maxDepth") ? Integer.valueOf(intOf(a, "maxDepth")) : null;
                GraphTraversal t = db.graphTraverse(str(a, "graph"), str(a, "start"), direction(a),
                        null, maxDepth, null);
                List<Object> nids = new ArrayList<>();
                Map<String, Object> depths = new LinkedHashMap<>();
                for (GraphTraversalNode n : t.nodes()) {
                    nids.add(n.id());
                    depths.put(n.id(), n.depth());
                }
                return map("ids", nids, "depths", depths);
            }
            case "graph.shortestPath": {
                GraphPath p = db.graphShortestPath(str(a, "graph"), str(a, "from"), str(a, "to"),
                        GraphDirection.OUTGOING);
                return map("found", p.found(), "hops", p.hops(), "nodePath", p.nodePath(), "edgePath", p.edgePath());
            }
            case "graph.weightedShortestPath": {
                GraphPath p = db.graphWeightedShortestPath(str(a, "graph"), str(a, "from"), str(a, "to"),
                        GraphDirection.OUTGOING, null, str(a, "weightProperty"));
                return map("found", p.found(), "totalCost",
                        p.totalCost().isPresent() ? Double.valueOf(p.totalCost().getAsDouble()) : null,
                        "nodePath", p.nodePath(), "edgePath", p.edgePath());
            }
            case "graph.listNodes": {
                GraphNodePage page = db.graphListNodes(str(a, "graph"));
                List<Object> nids = new ArrayList<>();
                Map<String, Object> labels = new LinkedHashMap<>();
                for (GraphNode n : page.nodes()) {
                    nids.add(n.id());
                    labels.put(n.id(), n.labels());
                }
                return map("ids", nids, "labels", labels, "total", page.total());
            }
            case "graph.listEdges": {
                GraphEdgePage page = db.graphListEdges(str(a, "graph"));
                List<Object> eids = new ArrayList<>();
                Map<String, Object> labels = new LinkedHashMap<>();
                for (GraphEdge e : page.edges()) {
                    eids.add(e.id());
                    labels.put(e.id(), e.label());
                }
                return map("ids", eids, "labels", labels, "total", page.total());
            }
            case "graph.query": {
                GraphQueryResult q = db.graphQuery(str(a, "graph"), str(a, "cypher"));
                return map("columns", q.columns(), "rows", q.rows());
            }

            case "sql.execute": {
                Response resp = db.execute(str(a, "sql"));
                Object affected = null;
                if (resp.data() instanceof Map<?, ?> m && m.get("Json") instanceof Map<?, ?> j
                        && j.get("rows_affected") instanceof Number num) {
                    affected = num.longValue();
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("rowsAffected", affected);
                return out;
            }
            case "sql.query": {
                Rows rows = db.query(str(a, "sql"));
                return map("columns", rows.columns(), "rows", rows.rows());
            }

            case "cache.ping":
                db.cachePing();
                return map();
            case "cache.set":
                db.cacheSet(str(a, "namespace"), str(a, "key"), bytesOf(str(a, "value")), longOrNull(a, "ttlMs"));
                return map();
            case "cache.get":
                return found(db.cacheGet(str(a, "namespace"), str(a, "key")));
            case "cache.delete":
                return map("deleted", db.cacheDelete(str(a, "namespace"), str(a, "key")));
            case "cache.exists":
                return map("exists", db.cacheExists(str(a, "namespace"), str(a, "key")));
            case "cache.ttl": {
                OptionalLong ttl = db.cacheTtl(str(a, "namespace"), str(a, "key"));
                return ttl.isPresent() ? map("hasTtl", true, "ttlMs", ttl.getAsLong()) : map("hasTtl", false);
            }
            case "cache.clearNamespace":
                return map("cleared", db.cacheClearNamespace(str(a, "namespace")));
            case "cache.incr":
                return map("value", db.cacheIncr(str(a, "namespace"), str(a, "key"), intOf(a, "by")));
            case "cache.expire":
                return map("updated", db.cacheExpire(str(a, "namespace"), str(a, "key"), intOf(a, "ttlMs")));
            case "cache.persist":
                return map("persisted", db.cachePersist(str(a, "namespace"), str(a, "key")));
            case "cache.setNx":
                return map("set", db.cacheSetNx(str(a, "namespace"), str(a, "key"),
                        bytesOf(str(a, "value")), longOrNull(a, "ttlMs")));
            case "cache.keys": {
                List<Object> names = new ArrayList<>();
                for (CacheKeyInfo k : db.cacheKeys(str(a, "namespace"), str(a, "pattern"), null, "main")) {
                    names.add(k.key());
                }
                return map("keys", names);
            }

            case "cache.lPush":
                return map("length", db.cacheLPush(str(a, "namespace"), str(a, "key"), byteList(a, "values")));
            case "cache.rPush":
                return map("length", db.cacheRPush(str(a, "namespace"), str(a, "key"), byteList(a, "values")));
            case "cache.lPop":
                return found(db.cacheLPop(str(a, "namespace"), str(a, "key")));
            case "cache.rPop":
                return found(db.cacheRPop(str(a, "namespace"), str(a, "key")));
            case "cache.lRange":
                return map("values", texts(db.cacheLRange(str(a, "namespace"), str(a, "key"),
                        intOf(a, "start"), intOf(a, "stop"))));
            case "cache.lLen":
                return map("length", db.cacheLLen(str(a, "namespace"), str(a, "key")));
            case "cache.lIndex":
                return found(db.cacheLIndex(str(a, "namespace"), str(a, "key"), intOf(a, "index")));

            case "cache.sAdd":
                return map("added", db.cacheSAdd(str(a, "namespace"), str(a, "key"), byteList(a, "members")));
            case "cache.sRem":
                return map("removed", db.cacheSRem(str(a, "namespace"), str(a, "key"), byteList(a, "members")));
            case "cache.sIsMember":
                return map("isMember", db.cacheSIsMember(str(a, "namespace"), str(a, "key"),
                        bytesOf(str(a, "member"))));
            case "cache.sCard":
                return map("cardinality", db.cacheSCard(str(a, "namespace"), str(a, "key")));
            case "cache.sMembers":
                return map("members", texts(db.cacheSMembers(str(a, "namespace"), str(a, "key"))));

            case "cache.hSet":
                return map("created", db.cacheHSet(str(a, "namespace"), str(a, "key"), pairList(a, "entries")));
            case "cache.hGet":
                return found(db.cacheHGet(str(a, "namespace"), str(a, "key"), bytesOf(str(a, "field"))));
            case "cache.hDel":
                return map("deleted", db.cacheHDel(str(a, "namespace"), str(a, "key"), byteList(a, "fields")));
            case "cache.hGetAll": {
                List<Object> entries = new ArrayList<>();
                for (CachePair p : db.cacheHGetAll(str(a, "namespace"), str(a, "key"))) {
                    entries.add(List.of(p.fieldText(), p.valueText()));
                }
                return map("entries", entries);
            }
            case "cache.hExists":
                return map("exists", db.cacheHExists(str(a, "namespace"), str(a, "key"), bytesOf(str(a, "field"))));
            case "cache.hLen":
                return map("length", db.cacheHLen(str(a, "namespace"), str(a, "key")));

            case "cache.xAdd":
                return map("id", db.cacheXAdd(str(a, "namespace"), str(a, "key"), pairList(a, "fields"), null));
            case "cache.xLen":
                return map("length", db.cacheXLen(str(a, "namespace"), str(a, "key")));
            case "cache.xRange":
                return map("entries", streamEntries(db.cacheXRange(str(a, "namespace"), str(a, "key"),
                        str(a, "start"), str(a, "end"), null)));
            case "cache.xRead":
                return map("entries", streamEntries(db.cacheXRead(str(a, "namespace"), str(a, "key"),
                        idFromStep(results, str(a, "afterStep")), null)));
            case "cache.xDel": {
                List<String> ids = new ArrayList<>();
                for (Object step : list(a, "idsFromSteps")) {
                    ids.add(idFromStep(results, String.valueOf(step)));
                }
                return map("deleted", db.cacheXDel(str(a, "namespace"), str(a, "key"), ids));
            }
            case "cache.xTrim":
                return map("trimmed", db.cacheXTrim(str(a, "namespace"), str(a, "key"), intOf(a, "maxLen")));
            case "cache.xGroup": {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("namespace", str(a, "namespace"));
                body.put("key", str(a, "key"));
                body.put("command", str(a, "command"));
                db.request(Map.of("Cache", Map.of("XGroup", body)));
                return map();
            }

            case "llm.schema":
                return map("rendered", db.llmSchema(outputFormat(str(a, "format")), null, "main"));
            case "llm.context": {
                List<LlmSource> sources = new ArrayList<>();
                for (Object o : list(a, "sources")) {
                    Map<String, Object> spec = castMap(o);
                    if (spec.get("sql") instanceof String q) {
                        sources.add(LlmSource.sql(q));
                    } else {
                        sources.add(LlmSource.documentFind(str(spec, "collection")));
                    }
                }
                return map("rendered", db.llmContext(sources, outputFormat(str(a, "format")), null, "main"));
            }

            case "admin.ping":
                db.adminPing();
                return map();
            case "admin.status":
                return map("status", db.adminStatus());

            default:
                return null;
        }
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length < 4) {
            System.err.println("usage: Runner <host> <port> <user> <secret> < scenario.json");
            System.exit(2);
        }
        String text;
        try (InputStream in = System.in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            text = buf.toString(StandardCharsets.UTF_8);
        }
        Map<String, Object> scenario = castMap(JsonParser.parse(text));

        Map<String, Object> results = new LinkedHashMap<>();
        try (TriCore db = TriCore.connect(args[0], Integer.parseInt(args[1]), args[2], args[3])) {
            for (Object o : list(scenario, "steps")) {
                Map<String, Object> step = castMap(o);
                String id = str(step, "id");
                String action = str(step, "action");
                try {
                    Object value = dispatch(db, action, obj(step, "args"), results);
                    if (value == null) {
                        out.println(encode(map("id", id, "status", "unsupported",
                                "error", "no Java SDK method for action " + action)));
                    } else {
                        results.put(id, value);
                        out.println(encode(map("id", id, "status", "ok", "value", value)));
                    }
                } catch (RuntimeException e) {
                    out.println(encode(map("id", id, "status", "error",
                            "error", e.getClass().getSimpleName() + ": " + e.getMessage())));
                }
            }
        }
    }

    private static byte[] bytesOf(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<byte[]> byteList(Map<String, Object> a, String k) {
        List<byte[]> out = new ArrayList<>();
        for (Object o : list(a, k)) {
            out.add(bytesOf(String.valueOf(o)));
        }
        return out;
    }

    private static List<CachePair> pairList(Map<String, Object> a, String k) {
        List<CachePair> out = new ArrayList<>();
        for (Object o : list(a, k)) {
            List<?> pair = (List<?>) o;
            out.add(CachePair.ofText(String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
        }
        return out;
    }

    private static List<Object> texts(List<byte[]> values) {
        List<Object> out = new ArrayList<>();
        for (byte[] v : values) {
            out.add(new String(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static List<Object> streamEntries(List<StreamEntry> entries) {
        List<Object> out = new ArrayList<>();
        for (StreamEntry e : entries) {
            List<Object> fields = new ArrayList<>();
            for (CachePair p : e.fields()) {
                fields.add(List.of(p.fieldText(), p.valueText()));
            }
            out.add(map("id", e.id(), "fields", fields));
        }
        return out;
    }

    private static Map<String, Object> found(Optional<byte[]> v) {
        return v.isPresent()
                ? map("found", true, "value", new String(v.get(), StandardCharsets.UTF_8))
                : map("found", false);
    }

    private static String idFromStep(Map<String, Object> results, String step) {
        return results.get(step) instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : null;
    }

    private static Long longOrNull(Map<String, Object> a, String k) {
        return a.get(k) instanceof Number n ? n.longValue() : null;
    }

    private static OutputFormat outputFormat(String name) {
        return switch (name) {
            case "native" -> OutputFormat.NATIVE;
            case "json" -> OutputFormat.JSON;
            case "toon" -> OutputFormat.TOON;
            case "markdown" -> OutputFormat.MARKDOWN;
            default -> throw new IllegalArgumentException("unsupported output format: " + name);
        };
    }
}
