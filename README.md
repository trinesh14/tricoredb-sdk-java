# TriCoreDB Java SDK

A Java client for [TriCoreDB](https://hub.docker.com/r/trinesh14/tricoredb). It
talks to the server over TriCoreDB's native `tricore` wire protocol and covers
SQL, documents, vectors, graphs, the cache, LLM context export and admin calls.
It uses nothing beyond the JDK: no runtime dependencies. Requires Java 17 or
newer.

## Install

Maven:

```xml
<dependency>
  <groupId>io.github.trinesh14</groupId>
  <artifactId>tricoredb</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("io.github.trinesh14:tricoredb:0.1.0")
```

Gradle (Groovy):

```groovy
implementation 'io.github.trinesh14:tricoredb:0.1.0'
```

Everything is in the package `io.github.trinesh14.tricoredb`, and the JPMS
automatic module name is the same.

## Running a server

The quickest way is the official Docker image,
[`trinesh14/tricoredb`](https://hub.docker.com/r/trinesh14/tricoredb).

**Local development** (no TLS, no encryption — this machine only). Set
`TRICORE_ADMIN_PASSWORD` in your shell first, then create the admin and start
the server:

```bash
docker run --rm -v tricoredb-dev:/var/lib/tricoredb -e TRICORE_ADMIN_PASSWORD --entrypoint /usr/local/bin/tricore trinesh14/tricoredb:0.1.0-rc.1-r2 auth init-admin --user admin --password-env TRICORE_ADMIN_PASSWORD --data-dir /var/lib/tricoredb/data
docker run -d --name tricoredb-dev -p 127.0.0.1:8427:8427 -e TRICORE_TLS=off -e TRICORE_ENCRYPTION=off -e TRICORE_MODULES=all -v tricoredb-dev:/var/lib/tricoredb trinesh14/tricoredb:0.1.0-rc.1-r2
```

**Anything else:** the image's default is **TLS on** and an **encrypted data
volume**. Follow the quick start on the
[Docker Hub page](https://hub.docker.com/r/trinesh14/tricoredb) to create the
certificate and key, then connect with [TLS](#tls).

`TRICORE_MODULES=all` enables every data model. The image's default is `sql`,
`document` and `cache`; a call to a disabled model throws a `TriCoreException`
whose `errorCode()` is `engine.disabled`.

## Connect

A `TriCore` is one authenticated connection and is `AutoCloseable`.
TriCoreDB's default port is `8427`.

```java
import io.github.trinesh14.tricoredb.TriCore;

try (TriCore db = TriCore.connect("127.0.0.1", 8427, "admin", "secret")) {
    db.ping();
}
```

A single connection runs one request at a time. For concurrent use, create a
`Pool` (see below).

## SQL with parameters

`query` runs only `SELECT`; `execute` runs everything else. The server enforces
this split: a write sent through `query` is refused.

```java
import java.util.List;

db.execute("CREATE TABLE users (id INT PRIMARY KEY, name TEXT, balance DECIMAL)");
db.execute("INSERT INTO users VALUES (?, ?, ?)", List.of(1, "O'Hara", new java.math.BigDecimal("10.50")), "main");
Rows rows = db.query("SELECT name, balance FROM users WHERE id = ?", List.of(1), "main");
rows.columns();   // [name, balance]
rows.rows();      // [[O'Hara, 10.50]]
rows.asMaps();    // [{name=O'Hara, balance=10.50}]
```

Parameters are bound on the server. The values travel next to the statement,
not pasted into the SQL text, so a value can never be read as SQL syntax.
How Java values are sent:

| Java value | sent as |
| --- | --- |
| `String`, `CharSequence`, `Character` | text |
| `Integer`, `Long`, `Short`, `Byte`, `BigInteger` | JSON number |
| `Double`, `Float` | JSON number (NaN and infinity are refused) |
| `BigDecimal` | **text**, via `toPlainString()` |
| `byte[]` | `0x`-prefixed hex, for a BLOB column |
| `Boolean` | boolean |
| `UUID`, `Instant`, `LocalDateTime` | text |
| `null` | SQL NULL |

`BigDecimal` is sent as text because a JSON number passes through a double on
the server, which would lose every digit beyond double precision. A DECIMAL
column parses the text exactly. The trade-off: **binding a `BigDecimal` into a
DOUBLE column fails with an error** instead of quietly rounding. Use a `Double`
for DOUBLE columns.

Bound parameters need the `SERVER_PARAMS` feature, which is negotiated when the
connection opens (check `db.serverParamsGranted()`). If the server did not
grant it, every call that takes arguments throws a `TriCoreException` naming
`SERVER_PARAMS`. The driver never falls back to escaping values into the SQL
text. If you want client-side rendering, call `SqlParams.bind(sql, args...)`
yourself. Neither approach can parameterise a table or column name.

## Transactions

`transaction(...)` sends a whole `BEGIN ... COMMIT` script as a single request.
Its arguments are bound on the server too:

```java
db.transaction(List.of(
        SqlStatement.of("UPDATE accounts SET balance = balance - ? WHERE id = ?", 10, 1),
        SqlStatement.of("UPDATE accounts SET balance = balance + ? WHERE id = ?", 10, 2)));
```

Session transactions keep a rollback boundary open across several requests on
the same connection:

```java
db.withTransaction(tx -> {                       // commits when the lambda returns
    tx.execute("INSERT INTO users VALUES (2, 'ada', 0)");
    tx.execute("INSERT INTO users VALUES (3, 'grace', 0)");
});                                              // on any exception: rolls back, then rethrows

try (Transaction tx = db.transactionBlock()) {   // close() rolls back unless you committed
    db.execute("DELETE FROM users WHERE id = 3");
    tx.commit();
}
```

`begin()`, `commit()` and `rollback()` are also available. They need the
`SESSION_TXN` feature (`db.sessionTxnGranted()`). Without it, `begin()` throws
by name **before sending anything**; it never falls back to autocommit. The
transaction belongs to this connection's socket, so another connection cannot
commit it.

## Pool

```java
try (Pool pool = new Pool("127.0.0.1", 8427, "admin", "secret", 8)) {
    pool.use(conn -> conn.execute("INSERT INTO users VALUES (4, 'linus', 0)"));
    long n = pool.use(5_000L, conn -> conn.query("SELECT id FROM users").size());
    pool.stats();                                // created / idle / inUse
}
```

Connections are opened lazily, and the pool never grows past its size. If no
connection frees up within the timeout, `use` throws `PoolTimeoutException`.
A connection is never returned to the pool with a transaction still open: the
pool rolls the transaction back and throws.

## One example per data model

The runnable versions are in [`examples/`](examples/).

**Documents**

```java
db.documentCreateCollection("people");
db.documentInsert("people", "u1", Map.of("name", "asha", "city", "Pune", "age", 31));
db.documentFind("people", DocumentFilter.and(DocumentFilter.eq("city", "Pune"), DocumentFilter.gt("age", 30)));
db.documentUpdateOne("people", "u1", DocumentUpdate.inc("age", 1), false);
db.documentAggregate("people", List.of(
        AggregateStage.group(AggregateStage.byField("city"), AggregateStage.countInto("n")),
        AggregateStage.sort(AggregateStage.desc("n"))));
```

**Vectors**

```java
db.vectorCreateCollection("docs", 3, VectorMetric.COSINE);
db.vectorUpsert("docs", "alpha", new float[] {1f, 0f, 0f}, Map.of("tier", "gold"));
List<VectorMatch> hits = db.vectorSearch("docs", new float[] {0.9f, 0.1f, 0f}, 5, Map.of("tier", "gold"));
```

For every metric, a higher score means closer. For `L2` the score is the
negated squared distance, so it is `<= 0`.

**Graph**

```java
db.graphCreate("social");
db.graphAddNode("social", "a", List.of("Person"), Map.of("name", "Asha"));
db.graphAddNode("social", "b", List.of("Person"), Map.of("name", "Ravi"));
db.graphAddEdge("social", "e1", "a", "b", "KNOWS", Map.of("weight", 1));
GraphPath path = db.graphShortestPath("social", "a", "b", GraphDirection.OUTGOING);
db.graphQuery("social", "MATCH (n:Person) RETURN n.name ORDER BY n.name");
```

**Cache** (values are bytes)

```java
db.cacheSet("sessions", "u1", "token".getBytes(StandardCharsets.UTF_8), 60_000L);
db.cacheGet("sessions", "u1");                   // Optional<byte[]>
db.cacheIncr("counters", "hits", 1);
db.cacheXAdd("events", "s1", List.of(CachePair.ofText("kind", "login")), null);
```

**LLM context export**

```java
String ctx = db.llmContext(List.of(LlmSource.sql("SELECT id, name FROM users")));
String schema = db.llmSchema();
```

## Errors and leader redirects

Every server refusal and transport failure is an unchecked `TriCoreException`.
The subclasses are `AuthException`, `ProtocolException`,
`TriCoreTimeoutException` (a read deadline; the connection is then unusable)
and `PoolTimeoutException`. A lookup that finds nothing is not an error:
`documentGet`, `vectorGet`, `graphGetNode` and `graphGetEdge` return
`Optional.empty()`.

Branch on `errorCode()`, not on the message text:

```java
try {
    db.execute("INSERT INTO users VALUES (?, ?, ?)", List.of(5, "x", 0), "main");
} catch (TriCoreException e) {
    if (e.isNotLeader()) {                        // errorCode() == "not_leader"
        String leader = e.leaderHint();           // "host:port", or null if the leader is unknown
        // Your application decides whether and where to retry.
    } else {
        System.err.println(e.errorCode() + ": " + e.getMessage());
    }
}
```

A write that reaches a Raft follower fails with `not_leader`. `leaderHint()`
gives the leader's native-protocol address when the cluster knows it, and is
`null` during an election or when there is no address. **The driver never
follows a redirect by itself**: resending a write elsewhere is a policy
decision that belongs to your application.

## TLS

TLS is off unless you pass `TlsOptions`. When it is on, the server certificate
is verified and the hostname is checked. Without `caFile` the trust store is
empty; the driver does not fall back to the JDK's `cacerts`.

```java
TlsOptions tls = TlsOptions.builder()
        .caFile("/etc/tricore/ca.pem")
        .serverName("db.internal")
        .clientCertFile("/etc/tricore/client.pem")   // optional, for mTLS
        .clientKeyFile("/etc/tricore/client.key")    // unencrypted PKCS#8
        .build();

try (TriCore db = TriCore.connect("db.internal", 8427, "admin", "secret", tls)) {
    db.ping();
}
try (Pool pool = new Pool("db.internal", 8427, "admin", "secret", 8, tls)) { /* ... */ }
```

Without TLS, the secret crosses the network in cleartext.

## Building from source

Maven is not needed; the wrapper downloads it.

```
./mvnw -B verify                 # compile, run all tests, build the jars
scripts/build.sh | build.ps1     # build without tests
scripts/test.sh  | test.ps1      # run the tests
scripts/conformance.sh | .ps1    # run the cross-SDK conformance matrix
scripts/release-dry-run.sh | .ps1
```

Live tests start their own `tricore-server` on an ephemeral port, with a
throwaway data directory. The server binary is located from
`-Dtricore.server.bin=...`, then `TRICORE_SERVER_BIN`, then
`../../tricore/tricore-db/target/{release,debug}`. If no binary is found, the
live tests are **skipped**, not passed. The TLS test also needs `openssl`.

Signing is opt-in: `./mvnw -Prelease -Dgpg.keyname=<KEYID> verify`.

## License

Apache-2.0. See [LICENSE](LICENSE).
