import static com.tricoredb.GraphDirection.OUTGOING;

import com.tricoredb.CachePair;
import com.tricoredb.DocumentFilter;
import com.tricoredb.DocumentUpdate;
import com.tricoredb.GraphEdge;
import com.tricoredb.GraphNode;
import com.tricoredb.GraphPath;
import com.tricoredb.GraphTraversal;
import com.tricoredb.LlmSource;
import com.tricoredb.Pool;
import com.tricoredb.SqlStatement;
import com.tricoredb.TransactionResult;
import com.tricoredb.TriCore;
import com.tricoredb.TriCoreException;
import com.tricoredb.VectorItem;
import com.tricoredb.VectorMatch;
import com.tricoredb.VectorPage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runnable samples for every TriCoreDB module.
 *
 * <pre>{@code
 * javac -cp ../target/tricoredb-0.1.0.jar -d out *.java
 * java -cp "out;../target/tricoredb-0.1.0.jar" Examples            # list the samples
 * java -cp "out;../target/tricoredb-0.1.0.jar" Examples sql        # run just one
 * java -cp "out;../target/tricoredb-0.1.0.jar" Examples all        # run every one
 * }</pre>
 *
 * <p>One entry point rather than nine {@code main} classes: on the JVM that is
 * the form that actually gets run, and each sample is still independent — no
 * sample depends on another having run first.
 */
public final class Examples {

    public static void main(String[] args) {
        String which = args.length > 0 ? args[0] : "";
        switch (which) {
            case "basic" -> basic();
            case "sql" -> sql();
            case "nosql" -> nosql();
            case "vector" -> vector();
            case "graph" -> graph();
            case "cache" -> cache();
            case "errors" -> errors();
            case "concurrency" -> concurrency();
            case "all" -> {
                basic();
                sql();
                nosql();
                vector();
                graph();
                cache();
                errors();
                concurrency();
            }
            default -> {
                System.out.println("usage: java -cp \"out" + java.io.File.pathSeparator + "../target/tricoredb-0.1.0.jar\" Examples <sample>");
                System.out.println("  basic  sql  nosql  vector  graph  cache  errors  concurrency  all");
                System.exit(args.length > 0 ? 2 : 0);
            }
        }
    }

    // -- basic ---------------------------------------------------------------

    static void basic() {
        Ex.run("Basic connection", db -> {
            Ex.show("session id", db.sessionId());

            // Two different liveness checks, and the difference matters.
            db.ping();
            Ex.show("ping (transport)", "PONG - the socket and framing are alive");
            db.cachePing();
            Ex.show("cache ping (module)", "ok - auth, routing and dispatch all work");

            long t0 = System.nanoTime();
            Ex.show("SELECT 1", db.query("SELECT 1").rows());
            Ex.show("round trip", "%.2f ms".formatted((System.nanoTime() - t0) / 1e6));

            // A second connection is independent: its own session, its own ids.
            try (TriCore other = Ex.connect("tricoredb-example-second")) {
                Ex.show("second session id", other.sessionId());
                Ex.show("sessions differ", !other.sessionId().equals(db.sessionId()));
            }
            Ex.show("second connection", "closed by try-with-resources");
        });
    }

    // -- sql -----------------------------------------------------------------

    static void sql() {
        final String t = "ex_sql_users";
        Ex.run("SQL", db -> {
            Ex.ignoreMissing(() -> db.execute("DROP TABLE " + t));

            Ex.section("schema");
            db.execute("CREATE TABLE " + t + " (id INT PRIMARY KEY, name TEXT, city TEXT, age INT)");
            Ex.show("created", t);
            db.execute("CREATE INDEX " + t + "_city ON " + t + " (city)");
            Ex.show("index", t + "_city on city");

            Ex.section("insert, with bound parameters");
            // `?` is bound server-side: the quote in O'Brien is data, never syntax;
            // building this string by concatenation is where injection bugs
            // come from.
            Object[][] rows = {
                {1, "ada", "Pune", 36},
                {2, "O'Brien", "Cork", 41},
                {3, "grace", "Pune", 45},
            };
            for (Object[] r : rows) {
                db.execute("INSERT INTO " + t + " VALUES (?, ?, ?, ?)", List.of(r), "main");
                Ex.show("insert " + r[0], "ok");
            }

            Ex.section("select");
            Ex.show("all rows", db.query("SELECT * FROM " + t).rows());
            Ex.show("by city", db.query("SELECT name FROM " + t + " WHERE city = ?", List.of("Pune"), "main").rows());
            Ex.show("quote round-trip", db.query("SELECT name FROM " + t + " WHERE id = ?", List.of(2), "main").rows());
            Ex.show("aggregate", db.query("SELECT city, COUNT(id) FROM " + t + " GROUP BY city").rows());
            Ex.show("as maps", db.query("SELECT id, name FROM " + t + " WHERE age > ?", List.of(40), "main").asMaps());

            Ex.section("update and delete");
            db.execute("UPDATE " + t + " SET city = ? WHERE id = ?", List.of("Mumbai", 1), "main");
            Ex.show("after update", db.query("SELECT city FROM " + t + " WHERE id = ?", List.of(1), "main").rows());
            db.execute("DELETE FROM " + t + " WHERE id = ?", List.of(3), "main");
            Ex.show("remaining", db.query("SELECT id FROM " + t).rows().size());

            Ex.section("transactions");
            // transaction() sends one request carrying a whole BEGIN..COMMIT
            // script. For a boundary across requests use begin()/commit()
            // or withTransaction(...).
            TransactionResult tr = db.transaction(List.of(
                    SqlStatement.of("INSERT INTO " + t + " VALUES (?, ?, ?, ?)", 10, "tx-a", "Delhi", 30),
                    SqlStatement.of("INSERT INTO " + t + " VALUES (?, ?, ?, ?)", 11, "tx-b", "Delhi", 31)));
            Ex.show("committed", tr);
            Ex.show("rows now", db.query("SELECT id FROM " + t).rows().size());

            try {
                db.transaction(List.of(
                        SqlStatement.of("INSERT INTO " + t + " VALUES (?, ?, ?, ?)", 20, "ghost", "Nowhere", 1),
                        SqlStatement.of("INSERT INTO no_such_table VALUES (1)")));
            } catch (TriCoreException e) {
                Ex.show("aborted script", Ex.truncate(e.getMessage(), 60));
            }
            boolean gone = db.query("SELECT id FROM " + t + " WHERE id = ?", List.of(20), "main").rows().isEmpty();
            Ex.show("ghost row present?", gone ? "no - the whole script was discarded" : "YES (bug!)");

            Ex.section("plans");
            Ex.show("EXPLAIN (indexed)", db.execute("EXPLAIN SELECT * FROM " + t + " WHERE id = 1").data());
            Ex.show("EXPLAIN (scan)", db.execute("EXPLAIN SELECT * FROM " + t + " WHERE age > 30").data());

            Ex.section("cleanup");
            db.execute("DROP TABLE " + t);
            Ex.show("dropped", t);
        });
    }

    // -- nosql ---------------------------------------------------------------

    static void nosql() {
        final String c = "ex_people";
        Ex.run("NoSQL (document)", db -> {
            Ex.ignoreMissing(() -> db.documentDropCollection(c));

            Ex.section("collection");
            db.documentCreateCollection(c);
            Ex.show("created", c);

            Ex.section("insert");
            Ex.show("server-assigned id",
                    db.documentInsert(c, doc("name", "ada", "city", "Pune", "age", 36)));
            db.documentInsert(c, "grace", doc("name", "grace", "city", "Pune", "age", 45));
            db.documentInsert(c, "linus", doc("name", "linus", "city", "Helsinki", "age", 54));
            Ex.show("chosen ids", List.of("grace", "linus"));

            Ex.section("read");
            Ex.show("get by id", db.documentGet(c, "grace").map(d -> d.get("name")).orElse("(missing)"));
            Ex.show("get a miss", db.documentGet(c, "nobody").isEmpty()
                    ? "empty - a miss is not an error" : "found?!");
            Ex.show("find all", db.documentFind(c, DocumentFilter.all()).size());
            Ex.show("city = Pune", names(db.documentFind(c, DocumentFilter.eq("city", "Pune"))));
            Ex.show("Pune AND age > 40", names(db.documentFind(c, DocumentFilter.and(
                    DocumentFilter.eq("city", "Pune"), DocumentFilter.gt("age", 40)))));
            Ex.show("with a limit", db.documentFind(c, DocumentFilter.all(), 1).size());

            Ex.section("update");
            db.documentUpdate(c, "grace", Map.of("city", "Arlington"));
            Ex.show("set a field", db.documentGet(c, "grace").get().get("city"));
            db.documentUpdateOne(c, "grace", DocumentUpdate.inc("age", 1), false);
            Ex.show("increment", db.documentGet(c, "grace").get().get("age"));
            Ex.show("upsert created?",
                    db.documentUpdateOne(c, "newcomer", DocumentUpdate.set("name", "newcomer"), true));
            Ex.show("update many", db.documentUpdateMany(c, DocumentFilter.eq("city", "Pune"),
                    DocumentUpdate.set("region", "west")));

            Ex.section("indexes");
            db.documentCreateIndex(c, "ex_by_city", "city", false);
            Ex.show("indexes", db.documentListIndexes(c).size());
            Ex.show("analyze", db.documentAnalyze(c));

            Ex.section("delete");
            db.documentDelete(c, "newcomer");
            Ex.show("after delete", db.documentFind(c, DocumentFilter.all()).size());
            db.documentDropIndex(c, "ex_by_city");
            db.documentDropCollection(c);
            Ex.show("dropped", c);
        });
    }

    // -- vector --------------------------------------------------------------

    static void vector() {
        final String c = "ex_embeddings";
        Ex.run("Vector", db -> {
            Ex.ignoreMissing(() -> db.vectorDropCollection(c));

            Ex.section("collection");
            db.vectorCreateCollection(c, 4, com.tricoredb.VectorMetric.COSINE);
            Ex.show("created", c + " (dimension 4, cosine)");
            Ex.show("describe", db.vectorDescribeCollection(c));

            Ex.section("insert");
            db.vectorUpsert(c, "v1", new float[] {1, 0, 0, 0}, Map.of("tag", "alpha", "lang", "en"));
            db.vectorUpsert(c, "v2", new float[] {0, 1, 0, 0}, Map.of("tag", "beta", "lang", "en"));
            db.vectorUpsert(c, "v3", new float[] {0.9f, 0.1f, 0, 0}, Map.of("tag", "alpha", "lang", "fr"));
            Ex.show("upserted", 3);

            Ex.section("search");
            // `score` is always "higher is closer", whatever the metric - for
            // l2 the server negates the distance so callers never branch on it.
            List<VectorMatch> hits = db.vectorSearch(c, new float[] {1, 0, 0, 0}, 3);
            List<String> labels = new ArrayList<>();
            for (VectorMatch h : hits) {
                labels.add("%s=%.4f".formatted(h.id(), h.score()));
            }
            Ex.show("nearest to [1,0,0,0]", labels);
            Ex.show("filtered tag=alpha", ids(db.vectorSearch(c, new float[] {1, 0, 0, 0}, 5,
                    Map.of("tag", "alpha"))));
            Ex.show("topK bounds it", db.vectorSearch(c, new float[] {1, 0, 0, 0}, 1).size());

            Ex.section("read and page");
            Optional<VectorItem> got = db.vectorGet(c, "v1");
            Ex.show("get v1", got.map(VectorItem::dimension).orElse(-1) + " dimensions");
            Ex.show("get a miss", db.vectorGet(c, "nope").isEmpty()
                    ? "empty - a miss is not an error" : "found?!");
            VectorPage page = db.vectorListVectors(c);
            Ex.show("list", "%d of %d, truncated=%s".formatted(
                    page.vectors().size(), page.total(), page.truncated()));

            Ex.section("overwrite");
            // Upsert replaces wholesale: metadata is not merged.
            db.vectorUpsert(c, "v1", new float[] {0, 0, 1, 0}, Map.of("tag", "gamma"));
            VectorItem after = db.vectorGet(c, "v1").orElseThrow();
            Ex.show("lang key gone?", after.metadata().containsKey("lang")
                    ? "NO (bug!)" : "yes - metadata is replaced");

            Ex.section("cleanup");
            db.vectorDelete(c, "v2");
            Ex.show("after delete", db.vectorListVectors(c).total());
            db.vectorDropCollection(c);
            Ex.show("dropped", c);
        });
    }

    // -- graph ---------------------------------------------------------------

    static void graph() {
        final String g = "ex_social";
        Ex.run("Graph", db -> {
            Ex.ignoreMissing(() -> db.graphDrop(g));

            Ex.section("graph");
            db.graphCreate(g);
            Ex.show("created", g);

            Ex.section("nodes");
            db.graphAddNode(g, "ada", List.of("Person"), Map.of("name", "ada", "city", "Pune"));
            db.graphAddNode(g, "grace", List.of("Person"), Map.of("name", "grace", "city", "Pune"));
            db.graphAddNode(g, "linus", List.of("Person"), Map.of("name", "linus", "city", "Helsinki"));
            db.graphAddNode(g, "acme", List.of("Company"), Map.of("name", "ACME"));
            Ex.show("nodes", 4);
            Optional<GraphNode> ada = db.graphGetNode(g, "ada");
            Ex.show("get ada", ada.map(GraphNode::labels).orElse(List.of()));
            Ex.show("get a miss", db.graphGetNode(g, "nobody").isEmpty()
                    ? "empty - a miss is not an error" : "found?!");

            Ex.section("edges");
            // Weights are lopsided on purpose so fewest-hops and least-cost disagree.
            db.graphAddEdge(g, "e1", "ada", "grace", "KNOWS", Map.of("weight", 1));
            db.graphAddEdge(g, "e2", "grace", "linus", "KNOWS", Map.of("weight", 1));
            db.graphAddEdge(g, "e3", "ada", "linus", "KNOWS", Map.of("weight", 10));
            Ex.show("edges", 3);
            Optional<GraphEdge> e1 = db.graphGetEdge(g, "e1");
            Ex.show("get e1", e1.map(e -> e.from() + " -> " + e.to()).orElse("(missing)"));

            Ex.section("traversal");
            Ex.show("neighbours of ada", db.graphNeighbors(g, "ada", OUTGOING).size());
            Ex.show("degree of ada", db.graphDegree(g, "ada", OUTGOING));
            GraphTraversal walk = db.graphTraverse(g, "ada", OUTGOING, null, 2, null);
            Ex.show("traverse depth 2", walk.nodes().size() + " nodes");
            Ex.show("clamped to", "max_depth=%d limit=%d truncated=%s".formatted(
                    walk.maxDepth(), walk.limit(), walk.truncated()));

            Ex.section("paths");
            GraphPath hops = db.graphShortestPath(g, "ada", "linus", OUTGOING);
            Ex.show("fewest hops", String.join(" -> ", hops.nodePath()) + " (" + hops.hops() + " hop)");
            GraphPath cost = db.graphWeightedShortestPath(g, "ada", "linus", OUTGOING, null, "weight");
            Ex.show("least cost", String.join(" -> ", cost.nodePath()) + " (cost " + cost.totalCost() + ")");
            // Two different algorithms; on this graph they genuinely disagree.
            Ex.show("same path?", hops.nodePath().equals(cost.nodePath()) ? "yes" : "no - as expected");
            Ex.show("unreachable", "found=" + db.graphShortestPath(g, "ada", "acme", OUTGOING).found()
                    + " - not an error, just no path");

            Ex.section("cypher (read-only subset)");
            Ex.show("MATCH label", db.graphQuery(g, "MATCH (n:Person) RETURN n.name ORDER BY n.name").rows());
            try {
                db.graphQuery(g, "MATCH (n) DETACH DELETE n");
            } catch (TriCoreException e) {
                // Write clauses are refused by name rather than ignored: a query
                // that silently dropped one would return a confidently wrong answer.
                Ex.show("write clause", "refused - " + Ex.truncate(e.getMessage(), 56));
            }

            Ex.section("cleanup");
            Ex.show("list nodes", db.graphListNodes(g).total());
            Ex.show("list edges", db.graphListEdges(g).total());
            db.graphDrop(g);
            Ex.show("dropped", g);
        });
    }

    // -- cache ---------------------------------------------------------------

    static void cache() {
        final String ns = "ex_cache";
        Ex.run("Cache", db -> {
            db.cacheClearNamespace(ns);

            Ex.section("strings and TTL");
            db.cacheSet(ns, "greeting", Ex.b("hello"));
            Ex.show("get", db.cacheGetText(ns, "greeting").orElse("(miss)"));
            Ex.show("exists", db.cacheExists(ns, "greeting"));
            Ex.show("get a miss", db.cacheGet(ns, "nope").isEmpty()
                    ? "empty - distinct from a stored empty value" : "found?!");
            db.cacheSet(ns, "session", Ex.b("abc123"), 60_000L);
            Ex.show("ttl", db.cacheTtl(ns, "session"));
            Ex.show("ttl of a permanent key", db.cacheTtl(ns, "greeting") + " - empty means 'no expiry'");
            Ex.show("persist", db.cachePersist(ns, "session"));
            Ex.show("expire", db.cacheExpire(ns, "session", 30_000L));
            Ex.show("expire a missing key", db.cacheExpire(ns, "nope", 1000L));

            Ex.section("counters and locks");
            Ex.show("incr from absent", db.cacheIncr(ns, "hits", 1));
            Ex.show("incr again", db.cacheIncr(ns, "hits", 4));
            Ex.show("setnx takes it", db.cacheSetNx(ns, "lock", Ex.b("owner-a"), 30_000L));
            Ex.show("setnx loses", db.cacheSetNx(ns, "lock", Ex.b("owner-b"), 30_000L));
            Ex.show("holder", db.cacheGetText(ns, "lock").orElse("(miss)"));

            Ex.section("key browsing");
            Ex.show("keys", db.cacheKeys(ns).size());

            Ex.section("lists");
            Ex.show("rpush", db.cacheRPush(ns, "queue", List.of(Ex.b("a"), Ex.b("b"))));
            Ex.show("lpush", db.cacheLPush(ns, "queue", List.of(Ex.b("z"))));
            Ex.show("lrange 0..-1", texts(db.cacheLRange(ns, "queue", 0, -1)));
            Ex.show("llen", db.cacheLLen(ns, "queue"));
            Ex.show("lindex -1", db.cacheLIndex(ns, "queue", -1).map(Ex::s).orElse("(miss)"));
            Ex.show("lpop / rpop", db.cacheLPop(ns, "queue").map(Ex::s).orElse("-")
                    + " / " + db.cacheRPop(ns, "queue").map(Ex::s).orElse("-"));

            Ex.section("sets");
            Ex.show("sadd", db.cacheSAdd(ns, "tags", List.of(Ex.b("x"), Ex.b("y"), Ex.b("z"))));
            Ex.show("sadd a duplicate", db.cacheSAdd(ns, "tags", List.of(Ex.b("x"))) + " - already present");
            Ex.show("sismember", db.cacheSIsMember(ns, "tags", Ex.b("x")));
            Ex.show("scard", db.cacheSCard(ns, "tags"));
            Ex.show("smembers", texts(db.cacheSMembers(ns, "tags")));
            Ex.show("srem", db.cacheSRem(ns, "tags", List.of(Ex.b("x"))));

            Ex.section("hashes");
            Ex.show("hset", db.cacheHSetText(ns, "profile", Map.of("name", "ada", "city", "Pune")));
            Ex.show("hset overwrite",
                    db.cacheHSetText(ns, "profile", Map.of("city", "Mumbai")) + " - 0 newly created");
            Ex.show("hget", db.cacheHGet(ns, "profile", Ex.b("city")).map(Ex::s).orElse("(miss)"));
            Ex.show("hgetall", CachePair.toText(db.cacheHGetAll(ns, "profile")));
            Ex.show("hexists", db.cacheHExists(ns, "profile", Ex.b("name")));
            Ex.show("hlen", db.cacheHLen(ns, "profile"));
            Ex.show("hdel", db.cacheHDel(ns, "profile", List.of(Ex.b("city"))));

            Ex.section("streams");
            String id1 = db.cacheXAddText(ns, "events", Map.of("type", "signup"), null);
            String id2 = db.cacheXAddText(ns, "events", Map.of("type", "login"), null);
            Ex.show("xadd", id1 + ", " + id2);
            Ex.show("ids increase", id2.compareTo(id1) > 0 ? "yes - strictly monotonic" : "NO (bug!)");
            Ex.show("xlen", db.cacheXLen(ns, "events"));
            Ex.show("xrange", db.cacheXRange(ns, "events").size() + " entries");
            // The non-blocking poll primitive: everything newer than what I saw.
            Ex.show("xread after id1", db.cacheXRead(ns, "events", id1, null).size());
            Ex.show("xread after id2",
                    db.cacheXRead(ns, "events", id2, null).size() + " - caught up, and it does not block");
            Ex.show("xdel", db.cacheXDel(ns, "events", List.of(id2)));
            Ex.show("xtrim to 0", db.cacheXTrim(ns, "events", 0));

            Ex.section("type safety");
            try {
                db.cacheLPush(ns, "greeting", List.of(Ex.b("nope")));
            } catch (TriCoreException e) {
                // A key holds exactly one type at a time. Operating on the wrong
                // one is an error, never a coercion that discards the value.
                Ex.show("list op on a string", "refused - " + Ex.truncate(e.getMessage(), 56));
            }

            Ex.section("cleanup");
            Ex.show("cleared", db.cacheClearNamespace(ns));
        });
    }

    // -- errors --------------------------------------------------------------

    static void errors() {
        Ex.run("Error model", db -> {
            Ex.section("server refusals");
            expect("unknown table", () -> db.query("SELECT * FROM no_such_table_xyz"));
            expect("syntax error", () -> db.query("SELCT 1"));
            expect("unknown collection", () -> db.documentFind("no_such_collection_xyz", DocumentFilter.all()));
            expect("unknown graph", () -> db.graphListNodes("no_such_graph_xyz"));
            expect("write sent as a read", () -> db.query("INSERT INTO t VALUES (1)"));

            Ex.section("client-side validation");
            expect("empty member list", () -> db.cacheSAdd("ns", "k", List.of()));
            expect("placeholder mismatch", () -> db.query("SELECT ?", List.of(), "main"));
            expect("nested BEGIN", () -> db.transaction(List.of(SqlStatement.of("BEGIN"))));
            expect("empty cancel id", () -> db.cancel(""));

            Ex.section("connection failures");
            expect("server unavailable",
                    () -> TriCore.connect("127.0.0.1", 1, "x", "y", "probe", 2000).close());

            Ex.section("using a closed connection");
            TriCore doomed = Ex.connect("tricoredb-example-doomed");
            doomed.close();
            expect("request after close", () -> doomed.query("SELECT 1"));

            Ex.section("authentication");
            // Under `dev_auth = true` any non-empty secret is accepted by design,
            // so this is reported rather than asserted: claiming a pass here
            // would be claiming the server rejected a credential it accepts.
            try (TriCore bad = TriCore.connect(Ex.HOST, Ex.PORT, Ex.USER, "definitely-not-the-password",
                    "tricoredb-example-badauth", 10_000)) {
                Ex.show("wrong password", "accepted - this server runs dev auth (any non-empty secret)");
            } catch (TriCoreException e) {
                Ex.show("wrong password", e.getClass().getSimpleName() + ": " + Ex.truncate(e.getMessage(), 60));
            }
        });
    }

    private static void expect(String label, ThrowingRunnable r) {
        try {
            r.run();
            Ex.show(label, "NO ERROR RAISED - this example is wrong, or the server changed");
            System.exit(1);
        } catch (Exception e) {
            Ex.show(label, e.getClass().getSimpleName() + ": " + Ex.truncate(String.valueOf(e.getMessage()), 66));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // -- concurrency ---------------------------------------------------------

    static void concurrency() {
        final String ns = "ex_conc";
        Ex.run("Concurrency", db -> {
            db.cacheClearNamespace(ns);

            Ex.section("one connection is a queue");
            long t0 = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                db.cacheSet(ns, "serial-" + i, Ex.b(String.valueOf(i)));
            }
            Ex.show("50 sets, serial", "%.1f ms".formatted((System.nanoTime() - t0) / 1e6));

            Ex.section("a pool gives real concurrency");
            for (int size : new int[] {1, 10, 50}) {
                try (Pool pool = new Pool(Ex.HOST, Ex.PORT, Ex.USER, Ex.PASSWORD, size)) {
                    long start = System.nanoTime();
                    ExecutorService ex = Executors.newFixedThreadPool(size);
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int i = 0; i < 200; i++) {
                        final int n = i;
                        tasks.add(ex.submit(() -> pool.use(conn ->
                                conn.cacheSet(ns, "pool-" + n, Ex.b(String.valueOf(n))))));
                    }
                    for (Future<?> f : tasks) {
                        f.get();
                    }
                    ex.shutdown();
                    Ex.show("200 sets, pool of " + size, "%.1f ms".formatted((System.nanoTime() - start) / 1e6));
                }
            }

            Ex.section("results stay matched to their callers");
            // The real hazard is not slowness, it is a reply landing on the
            // wrong caller. Each task writes a value only it knows and reads it
            // back; a crossed wire shows as a mismatch, not a wrong number.
            try (Pool pool = new Pool(Ex.HOST, Ex.PORT, Ex.USER, Ex.PASSWORD, 20)) {
                ExecutorService ex = Executors.newFixedThreadPool(20);
                AtomicInteger matched = new AtomicInteger();
                AtomicInteger crossed = new AtomicInteger();
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    final int n = i;
                    tasks.add(ex.submit(() -> pool.use(conn -> {
                        String key = "match-" + n;
                        String want = "value-" + n;
                        conn.cacheSet(ns, key, Ex.b(want));
                        if (want.equals(conn.cacheGetText(ns, key).orElse(null))) {
                            matched.incrementAndGet();
                        } else {
                            crossed.incrementAndGet();
                        }
                    })));
                }
                for (Future<?> f : tasks) {
                    f.get();
                }
                Ex.show("500 write+read pairs", matched.get() + " matched, " + crossed.get() + " crossed");

                Ex.section("concurrent counter increments");
                // Every increment must land: the server serialises them, so the
                // final value is the arithmetic total.
                List<Future<?>> bumps = new ArrayList<>();
                for (int i = 0; i < 300; i++) {
                    bumps.add(ex.submit(() -> pool.use(conn -> conn.cacheIncr(ns, "counter", 1))));
                }
                for (Future<?> f : bumps) {
                    f.get();
                }
                ex.shutdown();
                ex.awaitTermination(30, TimeUnit.SECONDS);
                Ex.show("300 concurrent incr", "counter = " + db.cacheGetText(ns, "counter").orElse("?"));
            }

            Ex.section("the mistake the pool prevents");
            // Four threads on ONE connection. Without the driver's in-flight
            // guard this deadlocks: the second reader consumes the first reply's
            // body as a header and waits forever for bytes that never arrive.
            ExecutorService ex = Executors.newFixedThreadPool(4);
            AtomicInteger refusals = new AtomicInteger();
            AtomicReference<String> firstRefusal = new AtomicReference<>();
            List<Future<?>> hammering = new ArrayList<>();
            for (int g = 0; g < 4; g++) {
                hammering.add(ex.submit(() -> {
                    try {
                        for (int i = 0; i < 20; i++) {
                            db.query("SELECT 1");
                        }
                    } catch (TriCoreException e) {
                        refusals.incrementAndGet();
                        firstRefusal.compareAndSet(null, e.getMessage());
                    }
                    return null;
                }));
            }
            ex.shutdown();
            boolean finished = ex.awaitTermination(30, TimeUnit.SECONDS);
            Ex.show("4 threads, 1 connection", finished ? "all finished (no deadlock)" : "DEADLOCKED");
            Ex.show("refusals", refusals.get());
            if (firstRefusal.get() != null) {
                Ex.show("the refusal says", Ex.truncate(firstRefusal.get(), 60));
            }
            // And the connection is still perfectly usable.
            Ex.show("connection still works", db.query("SELECT 1").rows());

            db.cacheClearNamespace(ns);
            Ex.show("cleaned up", ns);
        });
    }

    // -- helpers -------------------------------------------------------------

    private static Map<String, Object> doc(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static List<Object> names(List<Map<String, Object>> docs) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            out.add(d.get("name"));
        }
        return out;
    }

    private static List<String> ids(List<VectorMatch> hits) {
        List<String> out = new ArrayList<>();
        for (VectorMatch h : hits) {
            out.add(h.id());
        }
        return out;
    }

    private static List<String> texts(List<byte[]> values) {
        List<String> out = new ArrayList<>();
        for (byte[] v : values) {
            out.add(Ex.s(v));
        }
        return out;
    }

    private Examples() {
    }
}
