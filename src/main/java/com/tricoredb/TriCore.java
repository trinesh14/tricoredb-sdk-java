package com.tricoredb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TriCoreDB driver — speaks the native {@code tricore} wire protocol directly
 * (no PostgreSQL/MongoDB wire emulation), with no dependency beyond the JDK.
 *
 * <pre>{@code
 * try (TriCore db = TriCore.connect("127.0.0.1", 18427, "admin", "pw")) {
 *     db.cacheSet("n", "hello", "world".getBytes());
 *     db.cacheGet("n", "hello");                       // Optional[world]
 *     db.execute("INSERT INTO t VALUES (1, 'ada')");
 *     db.query("SELECT * FROM t").rows();              // [[1, ada]]
 * }
 * }</pre>
 *
 * See {@code docs/protocol/WIRE_REFERENCE.md}.
 *
 * Not thread-safe: a connection is one request/response stream, so sharing it
 * across threads interleaves frames. Use one connection per thread.
 */
public final class TriCore implements AutoCloseable {

    private static final String PROTOCOL = "tricore";
    private static final int VERSION = 1;
    public static final int DEFAULT_PORT = 8427;

    /**
     * This SDK's own version, so a consumer can pin one (V2 P2).
     *
     * <p>Deliberately separate from {@link #VERSION}, which is the <em>protocol</em>
     * major. The two move for different reasons, and collapsing them would make
     * one a silent proxy for the other.
     */
    public static final String SDK_VERSION = "0.1.0";

    /**
     * This SDK understands a request-scoped correlation id, which the server
     * joins to its access log, audit trail and cancel registry.
     */
    public static final long FEATURE_CORRELATION_ID = 1L;

    /**
     * The server binds {@code ?} placeholders to a typed parameter array carried
     * beside the statement, instead of this driver rendering the values into the
     * SQL text before sending it.
     *
     * <p>This is the difference between escaping and binding. Client-side
     * rendering has to reproduce the server's literal syntax exactly for every
     * type, and any mismatch is either a wrong value or a parse error; a bound
     * parameter is substituted at a value position the grammar has already
     * fixed, so a value can never become syntax however it is spelled.
     *
     * <p>{@link #execute(String, List, String)} and {@link #query(String, List, String)}
     * require it and refuse by name when it was not granted, rather than falling
     * back to the rendering they used to do — a silent downgrade from binding to
     * escaping is exactly the failure this bit exists to make visible.
     */
    public static final long FEATURE_SERVER_PARAMS = 1L << 1;

    /**
     * Session-scoped transactions: {@code BEGIN}, the statements, and
     * {@code COMMIT}/{@code ROLLBACK} sent as separate requests on one
     * connection, with a real rollback boundary between them.
     *
     * <p>Granted per connection: a sharded or forwarding node may withhold it,
     * and a server too old to negotiate never grants it. {@link #begin} refuses
     * by name when it was not granted rather than sending a {@code BEGIN} the
     * server would treat as a one-statement autocommit script.
     */
    public static final long FEATURE_SESSION_TXN = 1L << 2;

    /**
     * Optional protocol capabilities this build understands, sent in
     * {@code HELLO} as a bitmap (V2 P2). The server replies with the subset it
     * granted; a server too old to negotiate omits the field entirely, which
     * reads as {@code 0}.
     */
    public static final long FEATURES =
            FEATURE_CORRELATION_ID | FEATURE_SERVER_PARAMS | FEATURE_SESSION_TXN;

    /** What {@code HELLO} negotiated for this connection; 0 before a handshake. */
    private long grantedFeatures = 0L;

    /**
     * The optional capabilities this connection negotiated (V2 P2).
     *
     * <p>Zero against a server too old to negotiate, so a caller that needs a
     * capability tests for the bit rather than for a version number.
     */
    public long grantedFeatures() {
        return grantedFeatures;
    }

    /**
     * Whether the server granted {@link #FEATURE_SESSION_TXN} on this
     * connection — the bit {@link #begin} requires.
     */
    public boolean sessionTxnGranted() {
        return (grantedFeatures & FEATURE_SESSION_TXN) != 0L;
    }

    /**
     * Whether the server granted {@link #FEATURE_SERVER_PARAMS} on this
     * connection — the bit the argument-taking {@code execute}/{@code query}
     * overloads require.
     *
     * <p>False against a server too old to negotiate. Those overloads refuse by
     * name in that case rather than rendering the values into the statement
     * text, so a caller never binds server-side on one connection and
     * client-side on the next without being told.
     */
    public boolean serverParamsGranted() {
        return (grantedFeatures & FEATURE_SERVER_PARAMS) != 0L;
    }

    /**
     * True while a {@link #begin} block is open on this connection.
     *
     * <p>A closed or poisoned connection has no transaction: the server rolls
     * one back the moment the socket goes.
     */
    public boolean inTransaction() {
        return txnOpen && socket != null;
    }

    // Wire tags.
    private static final int HELLO = 0;
    private static final int AUTH = 1;
    private static final int REQUEST = 2;
    private static final int RESPONSE = 3;
    private static final int PING = 4;
    private static final int PONG = 5;
    private static final int ERROR = 6;
    private static final int CLOSE = 7;
    private static final int HELLO_OK = 8;
    private static final int AUTH_OK = 9;
    private static final int BYE = 10;
    private static final int CANCEL = 11;
    private static final int CANCEL_OK = 12;

    private static final int HEADER_SIZE = 1 + 1 + 4; // version, tag, u32 payload_len

    /**
     * The protocol's payload ceilings, mirroring
     * {@code crates/tricore_protocol/src/constants/mod.rs}.
     *
     * <p>A driver that trusts a declared length is six header bytes away from an
     * unbounded allocation: {@code payload_len} is a {@code u32}, so any peer —
     * hostile, broken, or simply the wrong port — could commit this JVM to a
     * 4 GiB {@code new byte[n]} and a read that never ends. The server refuses
     * these lengths on its side; the client must refuse them on its own, because
     * the server is not the only thing this socket can be connected to.
     *
     * <p>Control frames take the far tighter ceiling (C-40): every frame that is
     * not {@code REQUEST}/{@code RESPONSE} carries a small JSON document or
     * nothing at all, and the two an <i>unauthenticated</i> peer may send are
     * both in that set.
     */
    public static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;

    /** @see #MAX_FRAME_SIZE */
    public static final int MAX_CONTROL_FRAME_SIZE = 64 * 1024;

    /**
     * The highest frame-header format version this driver can read.
     *
     * <p>The header's first byte is a format version. It used to be skipped
     * entirely here, so a peer speaking a future frame layout was parsed as
     * though it spoke this one. The server refuses an unknown version by name;
     * so does this.
     */
    public static final int MAX_SUPPORTED_FRAME_VERSION = 1;

    /** How long {@link #close} waits for a {@code BYE} before dropping the socket. */
    private static final int CLOSE_TIMEOUT_MS = 2_000;

    /**
     * The payload ceiling for one tag. An <b>unknown</b> tag takes the tighter
     * ceiling deliberately: a tag this build cannot name is a tag whose payload
     * size it cannot vouch for, and the safe direction to be wrong in is
     * "too small".
     */
    private static long maxPayloadFor(int tag) {
        return (tag == REQUEST || tag == RESPONSE) ? MAX_FRAME_SIZE : MAX_CONTROL_FRAME_SIZE;
    }

    /**
     * A {@code request_id} prefix unique to one connection.
     *
     * <p><b>Why a bare counter is a correctness bug, not a cosmetic one.</b> Ids
     * used to be {@code java-1}, {@code java-2}, … restarting at zero <b>per
     * connection</b>. The server's cancel registry is keyed by
     * {@code request_id} scoped to the <i>principal</i>, and
     * {@code ExecutionRegistry::cancel}
     * ({@code crates/tricore_core/src/execution/registry.rs}) stops <b>every</b>
     * entry that matches — so a {@link Pool}, whose connections all authenticate
     * as one principal, issued concurrent {@code java-1}s and one
     * {@code cancel("java-1")} stopped all of them. The Rust client and the Node
     * and Python drivers carried the same defect and were fixed the same way.
     *
     * <p>Uniqueness is the requirement, not unguessability: the registry treats
     * the id as guessable by construction and scopes every lookup to the
     * principal, so secrecy buys nothing. A pid, a process-start stamp and a
     * monotonic counter give uniqueness across connections in a JVM and across
     * JVMs on a host.
     */
    private static final String PROCESS_STAMP =
            Long.toHexString(System.nanoTime() ^ (System.currentTimeMillis() << 20));

    private static final AtomicLong CONNECTION_SEQ = new AtomicLong();

    private static String nextConnectionPrefix() {
        return Long.toHexString(ProcessHandle.current().pid())
                + PROCESS_STAMP
                + Long.toHexString(CONNECTION_SEQ.incrementAndGet());
    }

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private long requestCounter = 0;
    private final String ridPrefix = nextConnectionPrefix();
    private String sessionId;
    private Long requestTimeoutMs;
    private String lastRequestId;

    // True from a successful begin() until the block ends. See inTransaction().
    private boolean txnOpen;

    /**
     * Set once this connection can no longer be trusted to be frame-aligned.
     *
     * <p>Throwing alone is not enough after a refused frame: the bytes the header
     * declared are still queued behind it, and a length-prefixed stream has no
     * resynchronisation point once the length is untrustworthy. So the socket is
     * dropped and every later use fails with the same error.
     */
    private TriCoreException fatal;

    // Held between sending a frame and reading its reply. See `exchange`.
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private TriCore(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    // -- lifecycle -------------------------------------------------------------

    public static TriCore connect(String host, int port, String user, String secret) {
        return connect(host, port, user, secret, "tricoredb-java", 10_000, null);
    }

    /** Connect over TLS. See {@link TlsOptions}. */
    public static TriCore connect(String host, int port, String user, String secret, TlsOptions tls) {
        return connect(host, port, user, secret, "tricoredb-java", 10_000, tls);
    }

    public static TriCore connect(String host, int port, String user, String secret,
                                   String clientName, int timeoutMs) {
        return connect(host, port, user, secret, clientName, timeoutMs, null);
    }

    public static TriCore connect(String host, int port, String user, String secret,
                                   String clientName, int timeoutMs, TlsOptions tls) {
        return connect(host, port, user, secret, clientName, timeoutMs, tls, 0);
    }

    public static TriCore connect(String host, int port, String user, String secret,
                                   String clientName, int timeoutMs, TlsOptions tls,
                                   int readTimeoutMs) {
        return connect(host, port, user, secret, clientName, timeoutMs, tls, readTimeoutMs, FEATURES);
    }

    /**
     * Connect, handshake, and (when {@code user} is non-null) authenticate.
     *
     * <h4>Two different deadlines, and they used to be one by accident</h4>
     *
     * <p>{@code timeoutMs} was set with {@code Socket.setSoTimeout} and left
     * there, so a value named and documented as a <em>connect</em> timeout was in
     * fact a deadline on every read for the life of the connection: any statement
     * over ten seconds died with a bare {@code SocketTimeoutException} —
     * outside this driver's error hierarchy — leaving the connection
     * desynchronised. The server's {@code statement_timeout_ms} defaults to
     * <em>unlimited</em>, so ten seconds was a number neither end had agreed to.
     *
     * <p>{@code timeoutMs} now bounds the connect phase only — TCP, TLS,
     * {@code HELLO} and {@code AUTH}, which is the phase the server itself bounds
     * with a pre-auth deadline — and is cleared afterwards.
     * {@code readTimeoutMs} is the separate, opt-in, steady-state one.
     *
     * @param timeoutMs     bounds the connect phase in milliseconds; {@code 0} disables it
     * @param tls           {@code null} for plain TCP — on which {@code secret} crosses the
     *                      wire in the clear — or {@link TlsOptions} to negotiate TLS first
     * @param readTimeoutMs the steady-state read deadline armed once the handshake
     *                      completes; {@code 0} (the default) means none, and exceeding it
     *                      raises {@link TriCoreTimeoutException} and poisons the connection
     * @param features      the capability bitmap announced in {@code HELLO}; defaults to
     *                      {@link #FEATURES}, everything this build understands. Mask a bit
     *                      out to opt out of one — the server grants only what was asked for,
     *                      so {@code FEATURES & ~FEATURE_SESSION_TXN} produces a connection
     *                      on which {@link #begin} refuses by name.
     */
    public static TriCore connect(String host, int port, String user, String secret,
                                   String clientName, int timeoutMs, TlsOptions tls,
                                   int readTimeoutMs, long features) {
        if (readTimeoutMs < 0) {
            throw new IllegalArgumentException("readTimeoutMs must be >= 0 (0 means none)");
        }
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            // Handshake before any frame is written: HELLO and the AUTH secret
            // below must travel inside the TLS session, not ahead of it.
            if (tls != null) {
                socket = tls.wrap(socket);
                socket.setSoTimeout(timeoutMs);
            }
            TriCore db = new TriCore(socket);
            db.hello(clientName, features);
            if (user != null) {
                db.auth(user, secret == null ? "" : secret);
            }
            // Armed only now, so the connect budget never leaks onto a running
            // statement.
            socket.setSoTimeout(readTimeoutMs);
            return db;
        } catch (SocketTimeoutException e) {
            throw new TriCoreTimeoutException(
                    "connecting to " + host + ":" + port + " did not complete within "
                            + timeoutMs + "ms (TCP, TLS, HELLO and AUTH share this budget)", e);
        } catch (IOException e) {
            throw new TriCoreException("connect failed: " + e.getMessage(), e);
        }
    }

    /**
     * Change the steady-state read deadline on an open connection. {@code 0}
     * clears it. See {@link #connect(String, int, String, String, String, int,
     * TlsOptions, int)}.
     */
    public void setReadTimeoutMs(int readTimeoutMs) {
        if (readTimeoutMs < 0) {
            throw new IllegalArgumentException("readTimeoutMs must be >= 0 (0 means none)");
        }
        if (socket == null) {
            throw new TriCoreException("connection is closed");
        }
        try {
            socket.setSoTimeout(readTimeoutMs);
        } catch (IOException e) {
            throw new TriCoreException("could not set the read timeout: " + e.getMessage(), e);
        }
    }

    /**
     * True once a frame this driver refused, a read deadline, or a transport
     * failure left the stream un-resynchronisable. A poisoned connection is
     * closed and is never handed back to a {@link Pool}.
     */
    public boolean isPoisoned() {
        return fatal != null;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * Stamp a server-side deadline on every subsequent request, or {@code null}
     * to clear it.
     *
     * <p>This is not the socket timeout set at connect time. That one abandons
     * the read on this side while the server keeps working; this one makes the
     * server stop. Set both when you want the work to actually end.
     */
    public void setRequestTimeoutMs(Long timeoutMs) {
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive, or null to clear");
        }
        this.requestTimeoutMs = timeoutMs;
    }

    /**
     * The {@code request_id} most recently sent. Pass it to {@link #cancel} from
     * a <b>second</b> connection to stop a running statement.
     */
    public String lastRequestId() {
        return lastRequestId;
    }

    /**
     * Ask the server to stop one of <b>this principal's</b> running statements,
     * named by the {@code request_id} it was sent with.
     *
     * <p>This must be sent on a <b>second connection</b>. The connection running
     * the statement is blocked reading its reply and is not reading anything
     * else, so a cancel can never reach it there.
     *
     * <p>Scoped by the authenticated principal: you cannot stop — or learn about
     * — somebody else's statement. An unknown id returns 0 rather than failing.
     *
     * @return how many of the caller's executions were asked to stop
     */
    public int cancel(String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", require(requestId, "request_id"));
        try {
            Frame f = exchange(CANCEL, payload);
            if (f.tag == ERROR) {
                throw new TriCoreException(errorText(f.body));
            }
            if (f.tag != CANCEL_OK) {
                throw new ProtocolException("expected CANCEL_OK, got tag " + f.tag);
            }
            Object n = asMap(f.body).get("cancelled");
            return n instanceof Number num ? num.intValue() : 0;
        } catch (IOException e) {
            throw new TriCoreException("cancel failed: " + e.getMessage(), e);
        }
    }

    /**
     * End the session politely, then release the socket.
     *
     * A failure to say goodbye is not worth raising over during teardown — the
     * socket closes either way — but the socket is always released.
     */
    @Override
    public void close() {
        if (socket == null) {
            return;
        }
        try {
            // Bounded: the goodbye is optional, the teardown is not. With a
            // steady-state read timeout of 0 (the default) an unbounded goodbye
            // hangs for ever on a peer that accepts CLOSE and answers nothing —
            // and close() is what a try-with-resources block and the pool's
            // retire path both call, so the hang lands in the application's
            // cleanup.
            if (!isPoisoned()) {
                socket.setSoTimeout(CLOSE_TIMEOUT_MS);
                send(CLOSE, null);
                recv();
            }
        } catch (IOException | TriCoreException ignored) {
            // Teardown best-effort: the peer may already be gone.
        } finally {
            // Read the field into a local first: the goodbye above can end in a
            // read deadline, which poisons the connection and has ALREADY dropped
            // the socket — `socket.close()` then threw a NullPointerException out
            // of a close() the caller was relying on to be safe. Found by
            // HardeningTest's bounded-close case, and the same crash the Python
            // driver carried.
            Socket s = socket;
            socket = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Nothing meaningful to do with a failure to close.
                }
            }
        }
    }

    // -- operations --------------------------------------------------------------

    /**
     * A {@code PING}/{@code PONG} liveness round trip.
     *
     * <p>Routed through {@code exchange} like every other frame pair: it used to
     * call {@code send}/{@code recv} directly, so a ping from a second thread
     * bypassed the in-flight guard this driver enforces everywhere else and
     * consumed the first thread's reply.
     */
    public void ping() {
        try {
            Frame f = exchange(PING, null);
            if (f.tag != PONG) {
                throw new ProtocolException("expected PONG, got tag " + f.tag);
            }
        } catch (IOException e) {
            throw new TriCoreException("ping failed: " + e.getMessage(), e);
        }
    }

    private void auth(String user, String secret) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", user);
        payload.put("secret", secret.getBytes(StandardCharsets.UTF_8));
        Frame f = exchange(AUTH, payload);
        if (f.tag == ERROR) {
            throw new AuthException(errorText(f.body));
        }
        if (f.tag != AUTH_OK) {
            throw new ProtocolException("expected AUTH_OK, got tag " + f.tag);
        }
        Map<String, Object> body = asMap(f.body);
        // An AUTH_OK frame carrying ok=false is still a refusal.
        if (!Boolean.TRUE.equals(body.get("ok"))) {
            throw new AuthException(String.valueOf(body.getOrDefault("message", "authentication refused")));
        }
        Object sid = body.get("session_id");
        this.sessionId = sid == null ? null : String.valueOf(sid);
    }

    /** Send a raw operation. The typed helpers below all funnel through this. */
    public Response request(Map<String, Object> op, String database) {
        requestCounter++;
        // See nextConnectionPrefix: the prefix is what keeps a CANCEL naming one
        // statement rather than every connection's Nth statement.
        String requestId = "java-" + ridPrefix + "-" + requestCounter;
        lastRequestId = requestId;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", requestId);
        payload.put("database", database);
        payload.put("op", op);
        // A server-side deadline, distinct from the socket timeout: this one
        // makes the *server* stop, rather than abandoning the read on this side
        // while the work carries on.
        if (requestTimeoutMs != null) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("timeout_ms", requestTimeoutMs);
            payload.put("options", options);
        }
        try {
            Frame f = exchange(REQUEST, payload);
            if (f.tag == ERROR) {
                throw new TriCoreException(errorText(f.body));
            }
            if (f.tag != RESPONSE) {
                throw new ProtocolException("expected RESPONSE, got tag " + f.tag);
            }
            Response resp = new Response(asMap(f.body));
            // Anything that is not `ok` means the server did not complete the
            // operation, and must not reach a caller wearing a success's clothes.
            //
            // Refusing only `error` used to be the whole check, and the kernel
            // has three statuses: `ok`, `error`, and `not_implemented` — "a
            // recognized hook that was NOT run"
            // (crates/tricore_core/src/response/mod.rs). It is live on a default
            // node: Admin::RebalanceStatus without [sharding], Admin::RaftMessage
            // without [raft]. Every typed helper funnels through here and several
            // of them unwrap the `Message` payload such a response carries, so the
            // refusal text was returned as the result.
            //
            // Compared against `ok` rather than a list of bad statuses, so a
            // status added to the kernel later fails closed.
            if (!"ok".equals(resp.status())) {
                String msg = messageOf(resp.data());
                // The code and the leader hint ride the exception so an
                // application branches on them instead of substring-matching the
                // prose. A leader redirect is the case that makes this load
                // bearing: `not_leader` says the request was valid and went to
                // the wrong node, and it is not deducible from the message.
                throw new TriCoreException("server returned status `" + resp.status() + "`: "
                        + (msg != null ? msg : "request failed"),
                        resp.errorCode(), resp.leaderHint());
            }
            return resp;
        } catch (IOException e) {
            throw new TriCoreException("request failed: " + e.getMessage(), e);
        }
    }

    public Response request(Map<String, Object> op) {
        return request(op, "main");
    }

    /** Run a write ({@code CREATE}/{@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code BEGIN…COMMIT}). */
    public Response execute(String sql, String database) {
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("sql", sql);
        Map<String, Object> sqlOp = new LinkedHashMap<>();
        sqlOp.put("Exec", exec);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("Sql", sqlOp);
        return request(op, database);
    }

    public Response execute(String sql) {
        return execute(sql, "main");
    }

    /** Run a read and return its rows. A write sent through here is refused by the server. */
    public Rows query(String sql, String database) {
        Map<String, Object> queryOp = new LinkedHashMap<>();
        queryOp.put("sql", sql);
        Map<String, Object> sqlOp = new LinkedHashMap<>();
        sqlOp.put("Query", queryOp);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("Sql", sqlOp);
        return rowsOf(request(op, database));
    }

    /**
     * Decode a {@code Rows} payload. Shared by both {@code query} overloads so
     * the two cannot drift in what they accept.
     */
    private static Rows rowsOf(Response resp) {
        Object data = resp.data();
        if (data instanceof Map<?, ?> m && m.containsKey("Rows")) {
            Map<?, ?> r = (Map<?, ?>) m.get("Rows");
            Object colsObj = r.get("columns");
            Object rowsObj = r.get("rows");
            List<String> columns = colsObj == null ? List.of()
                    : ((List<?>) colsObj).stream().map(String::valueOf).toList();
            List<List<String>> rows = rowsObj == null ? List.of()
                    : ((List<?>) rowsObj).stream()
                        .map(row -> ((List<?>) row).stream().map(String::valueOf).toList())
                        .toList();
            return new Rows(columns, rows);
        }
        throw new ProtocolException("expected Rows, got " + kindOf(data));
    }

    public Rows query(String sql) {
        return query(sql, "main");
    }

    // -- cache -------------------------------------------------------------------
    //
    // Values are byte[] throughout, never String. The server stores opaque bytes,
    // and a driver that only spoke strings would silently corrupt any value that
    // is not valid UTF-8. The *Text helpers exist for the common case and are
    // explicit about the encoding they impose.
    //
    // A key holds exactly one type at a time: operating on the wrong type is an
    // error, never a coercion. A collection mutation never resets the key's TTL,
    // and a collection that becomes empty deletes its key.

    /**
     * Liveness check routed through the cache core.
     *
     * <p>Distinct from {@link #ping()}, which exchanges a PING frame and never
     * reaches a module. This one proves auth, routing and dispatch are working.
     */
    public void cachePing(String database) {
        request(Wire.unitOp("Cache", "Ping"), database);
    }

    public void cachePing() {
        cachePing("main");
    }

    public void cacheSet(String namespace, String key, byte[] value, Long ttlMs, String database) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("value", requireNotNull(value, "value"));
        body.put("ttl_ms", ttlMs);
        request(Wire.op("Cache", "Set", body), database);
    }

    public void cacheSet(String namespace, String key, byte[] value, Long ttlMs) {
        cacheSet(namespace, key, value, ttlMs, "main");
    }

    public void cacheSet(String namespace, String key, byte[] value) {
        cacheSet(namespace, key, value, null, "main");
    }

    /** Set a key from a string, encoded UTF-8. */
    public void cacheSetText(String namespace, String key, String value, Long ttlMs) {
        cacheSet(namespace, key, requireNotNull(value, "value").getBytes(UTF_8), ttlMs, "main");
    }

    /** The value, or empty on a miss — distinct from a hit on a zero-length value. */
    public Optional<byte[]> cacheGet(String namespace, String key, String database) {
        return Wire.cacheValue(request(Wire.op("Cache", "Get", keyBody(namespace, key)), database));
    }

    public Optional<byte[]> cacheGet(String namespace, String key) {
        return cacheGet(namespace, key, "main");
    }

    /** The value decoded as UTF-8, or empty on a miss. */
    public Optional<String> cacheGetText(String namespace, String key) {
        return cacheGet(namespace, key, "main").map(b -> new String(b, UTF_8));
    }

    /** Whether the key existed. Deleting a missing key is not an error. */
    public boolean cacheDelete(String namespace, String key, String database) {
        return Wire.bool(
                Wire.json(request(Wire.op("Cache", "Delete", keyBody(namespace, key)), database)), "deleted");
    }

    public boolean cacheDelete(String namespace, String key) {
        return cacheDelete(namespace, key, "main");
    }

    public boolean cacheExists(String namespace, String key, String database) {
        return Wire.bool(
                Wire.json(request(Wire.op("Cache", "Exists", keyBody(namespace, key)), database)), "exists");
    }

    public boolean cacheExists(String namespace, String key) {
        return cacheExists(namespace, key, "main");
    }

    /**
     * Remaining time to live in milliseconds.
     *
     * <p>Empty when the key is missing <b>or</b> has no expiry — use
     * {@link #cacheExists} to tell those two apart.
     */
    public OptionalLong cacheTtl(String namespace, String key, String database) {
        Object v = Wire.json(request(Wire.op("Cache", "Ttl", keyBody(namespace, key)), database)).get("ttl_ms");
        return v instanceof Number n ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
    }

    public OptionalLong cacheTtl(String namespace, String key) {
        return cacheTtl(namespace, key, "main");
    }

    /** Delete every key in a namespace; returns how many were removed. */
    public long cacheClearNamespace(String namespace, String database) {
        return Wire.num(Wire.json(request(
                Wire.op("Cache", "ClearNamespace", named("namespace", namespace)), database)), "cleared");
    }

    public long cacheClearNamespace(String namespace) {
        return cacheClearNamespace(namespace, "main");
    }

    /**
     * Add {@code by} to a counter and return the new value.
     *
     * <p>A missing key starts at 0; a key holding a non-numeric value is an error,
     * never a coercion.
     */
    public long cacheIncr(String namespace, String key, long by, String database) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("by", by);
        return Wire.num(Wire.json(request(Wire.op("Cache", "Incr", body), database)), "value");
    }

    public long cacheIncr(String namespace, String key, long by) {
        return cacheIncr(namespace, key, by, "main");
    }

    /** Set or replace a TTL. False when the key does not exist. */
    public boolean cacheExpire(String namespace, String key, long ttlMs, String database) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("ttl_ms", ttlMs);
        return Wire.bool(Wire.json(request(Wire.op("Cache", "Expire", body), database)), "updated");
    }

    public boolean cacheExpire(String namespace, String key, long ttlMs) {
        return cacheExpire(namespace, key, ttlMs, "main");
    }

    /** Drop a TTL, making the key permanent. False when it had none. */
    public boolean cachePersist(String namespace, String key, String database) {
        return Wire.bool(
                Wire.json(request(Wire.op("Cache", "Persist", keyBody(namespace, key)), database)), "persisted");
    }

    public boolean cachePersist(String namespace, String key) {
        return cachePersist(namespace, key, "main");
    }

    /** Set only if absent. The primitive behind a distributed lock. */
    public boolean cacheSetNx(String namespace, String key, byte[] value, Long ttlMs, String database) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("value", requireNotNull(value, "value"));
        body.put("ttl_ms", ttlMs);
        return Wire.bool(Wire.json(request(Wire.op("Cache", "SetNx", body), database)), "set");
    }

    public boolean cacheSetNx(String namespace, String key, byte[] value, Long ttlMs) {
        return cacheSetNx(namespace, key, value, ttlMs, "main");
    }

    /**
     * Live keys in a namespace, each with its remaining TTL and size.
     *
     * @param pattern a simple glob where {@code *} matches any run of characters
     *                ({@code user:*}, {@code *:sess}, {@code *tmp*}), or
     *                {@code null} to list everything
     * @param limit   {@code null} to leave the cap to the server
     */
    public List<CacheKeyInfo> cacheKeys(String namespace, String pattern, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("pattern", pattern);
        body.put("limit", limit);
        List<CacheKeyInfo> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Cache", "Keys", body), database)), "keys")) {
            out.add(CacheKeyInfo.from(Wire.asObject(o, "cache key")));
        }
        return out;
    }

    public List<CacheKeyInfo> cacheKeys(String namespace) {
        return cacheKeys(namespace, null, null, "main");
    }

    // -- cache: lists ------------------------------------------------------------

    /** Prepend elements; returns the new length. */
    public long cacheLPush(String namespace, String key, List<byte[]> values, String database) {
        return push("LPush", namespace, key, values, database);
    }

    public long cacheLPush(String namespace, String key, List<byte[]> values) {
        return push("LPush", namespace, key, values, "main");
    }

    /** Append elements; returns the new length. */
    public long cacheRPush(String namespace, String key, List<byte[]> values, String database) {
        return push("RPush", namespace, key, values, database);
    }

    public long cacheRPush(String namespace, String key, List<byte[]> values) {
        return push("RPush", namespace, key, values, "main");
    }

    private long push(String variant, String namespace, String key, List<byte[]> values, String database) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("values", requireNonEmpty(values, "values"));
        return Wire.num(Wire.json(request(Wire.op("Cache", variant, body), database)), "length");
    }

    /** Remove and return the first element, or empty on an empty/missing key. */
    public Optional<byte[]> cacheLPop(String namespace, String key) {
        return Wire.cacheValue(request(Wire.op("Cache", "LPop", keyBody(namespace, key)), "main"));
    }

    /** Remove and return the last element, or empty on an empty/missing key. */
    public Optional<byte[]> cacheRPop(String namespace, String key) {
        return Wire.cacheValue(request(Wire.op("Cache", "RPop", keyBody(namespace, key)), "main"));
    }

    /**
     * An inclusive index range. Negative indices count from the end ({@code -1} is
     * the last element) and out-of-range bounds clamp.
     */
    public List<byte[]> cacheLRange(String namespace, String key, long start, long stop, String database) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("start", start);
        body.put("stop", stop);
        return Wire.byteLists(Wire.json(request(Wire.op("Cache", "LRange", body), database)), "values");
    }

    public List<byte[]> cacheLRange(String namespace, String key, long start, long stop) {
        return cacheLRange(namespace, key, start, stop, "main");
    }

    /** Number of elements; 0 when the key is missing. */
    public long cacheLLen(String namespace, String key) {
        return Wire.num(Wire.json(request(Wire.op("Cache", "LLen", keyBody(namespace, key)), "main")), "length");
    }

    /** One element by index; negative counts from the end. Empty when out of range. */
    public Optional<byte[]> cacheLIndex(String namespace, String key, long index) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("index", index);
        return Wire.cacheValue(request(Wire.op("Cache", "LIndex", body), "main"));
    }

    // -- cache: sets -------------------------------------------------------------

    /** Add members; returns how many were newly added. */
    public long cacheSAdd(String namespace, String key, List<byte[]> members) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("members", requireNonEmpty(members, "members"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "SAdd", body), "main")), "added");
    }

    /** Remove members; returns how many were present. */
    public long cacheSRem(String namespace, String key, List<byte[]> members) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("members", requireNonEmpty(members, "members"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "SRem", body), "main")), "removed");
    }

    public boolean cacheSIsMember(String namespace, String key, byte[] member) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("member", requireNotNull(member, "member"));
        return Wire.bool(Wire.json(request(Wire.op("Cache", "SIsMember", body), "main")), "is_member");
    }

    /** Number of members; 0 when the key is missing. */
    public long cacheSCard(String namespace, String key) {
        return Wire.num(
                Wire.json(request(Wire.op("Cache", "SCard", keyBody(namespace, key)), "main")), "cardinality");
    }

    /** Every member, in ascending byte order. */
    public List<byte[]> cacheSMembers(String namespace, String key) {
        return Wire.byteLists(
                Wire.json(request(Wire.op("Cache", "SMembers", keyBody(namespace, key)), "main")), "members");
    }

    // -- cache: hashes -----------------------------------------------------------

    /**
     * Set fields; returns how many were newly created (as opposed to overwritten).
     *
     * <p>{@code entries} is a list of pairs rather than a map: hash fields are
     * arbitrary bytes and need not be valid UTF-8, so they cannot all be map keys.
     * Use {@link #cacheHSetText} when they are text.
     */
    public long cacheHSet(String namespace, String key, List<CachePair> entries) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("entries", pairsWire(entries, "entries"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "HSet", body), "main")), "created");
    }

    /** Set string fields, encoded UTF-8. */
    public long cacheHSetText(String namespace, String key, Map<String, String> entries) {
        return cacheHSet(namespace, key, CachePair.ofText(entries));
    }

    /** One field's value, or empty when the field or key is absent. */
    public Optional<byte[]> cacheHGet(String namespace, String key, byte[] field) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("field", requireNotNull(field, "field"));
        return Wire.cacheValue(request(Wire.op("Cache", "HGet", body), "main"));
    }

    /** Delete fields; returns how many were present. */
    public long cacheHDel(String namespace, String key, List<byte[]> fields) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("fields", requireNonEmpty(fields, "fields"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "HDel", body), "main")), "deleted");
    }

    /** Every field/value pair, in ascending field order. */
    public List<CachePair> cacheHGetAll(String namespace, String key) {
        return CachePair.decode(
                Wire.list(Wire.json(request(Wire.op("Cache", "HGetAll", keyBody(namespace, key)), "main")), "entries"),
                "hash entry");
    }

    public boolean cacheHExists(String namespace, String key, byte[] field) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("field", requireNotNull(field, "field"));
        return Wire.bool(Wire.json(request(Wire.op("Cache", "HExists", body), "main")), "exists");
    }

    /** Number of fields; 0 when the key is missing. */
    public long cacheHLen(String namespace, String key) {
        return Wire.num(Wire.json(request(Wire.op("Cache", "HLen", keyBody(namespace, key)), "main")), "length");
    }

    // -- cache: streams ----------------------------------------------------------
    //
    // Append-only logs. Each entry carries a strictly increasing `<ms>-<seq>` id;
    // that ordering is the point, so XAdd refuses a caller id that is not greater
    // than the last. Consumer groups and blocking reads are out of scope in V1 and
    // are refused by name.

    /**
     * Append an entry, returning the assigned id.
     *
     * @param id {@code null} or {@code "*"} to auto-generate, {@code "<ms>"} or
     *           {@code "<ms>-*"} to fix the millisecond, or {@code "<ms>-<seq>"}
     *           for an exact id. A non-increasing id is an error.
     */
    public String cacheXAdd(String namespace, String key, List<CachePair> fields, String id) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("id", id);
        body.put("fields", pairsWire(fields, "fields"));
        return Wire.str(Wire.json(request(Wire.op("Cache", "XAdd", body), "main")), "id");
    }

    /** Append an entry whose fields are strings, encoded UTF-8. */
    public String cacheXAddText(String namespace, String key, Map<String, String> fields, String id) {
        return cacheXAdd(namespace, key, CachePair.ofText(fields), id);
    }

    /** Number of entries; 0 when the key is missing. */
    public long cacheXLen(String namespace, String key) {
        return Wire.num(Wire.json(request(Wire.op("Cache", "XLen", keyBody(namespace, key)), "main")), "length");
    }

    /**
     * Entries whose id falls in the inclusive range. {@code -} and {@code +} are
     * the min/max ids; a bare {@code <ms>} spans that whole millisecond.
     */
    public List<StreamEntry> cacheXRange(String namespace, String key, String start, String end, Integer count) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("start", require(start, "start"));
        body.put("end", require(end, "end"));
        body.put("count", count);
        return StreamEntry.decode(Wire.json(request(Wire.op("Cache", "XRange", body), "main")));
    }

    public List<StreamEntry> cacheXRange(String namespace, String key) {
        return cacheXRange(namespace, key, "-", "+", null);
    }

    /**
     * Entries strictly newer than {@code after} — the non-blocking poll primitive.
     * Pass {@code "$"} for "only new entries". This read never blocks.
     */
    public List<StreamEntry> cacheXRead(String namespace, String key, String after, Integer count) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("after", require(after, "after"));
        body.put("count", count);
        return StreamEntry.decode(Wire.json(request(Wire.op("Cache", "XRead", body), "main")));
    }

    /** Delete entries by exact id; returns how many were present. */
    public long cacheXDel(String namespace, String key, List<String> ids) {
        Map<String, Object> body = keyBody(namespace, key);
        body.put("ids", requireNonEmpty(ids, "ids"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "XDel", body), "main")), "deleted");
    }

    /** Cap the stream by evicting the oldest; returns how many were evicted. */
    public long cacheXTrim(String namespace, String key, int maxLen) {
        if (maxLen < 0) {
            throw new IllegalArgumentException("maxLen must not be negative");
        }
        Map<String, Object> body = keyBody(namespace, key);
        body.put("max_len", maxLen);
        return Wire.num(Wire.json(request(Wire.op("Cache", "XTrim", body), "main")), "trimmed");
    }

    private static Map<String, Object> keyBody(String namespace, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        return body;
    }

    private static <T> List<T> requireNonEmpty(List<T> values, String name) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
        for (T v : values) {
            if (v == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
        }
        return values;
    }

    private static List<Object> pairsWire(List<CachePair> entries, String name) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
        List<Object> out = new ArrayList<>(entries.size());
        for (CachePair p : entries) {
            if (p == null) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            out.add(List.of(p.field(), p.value()));
        }
        return out;
    }

    // -- document ----------------------------------------------------------------

    public void documentCreateCollection(String collection, String database) {
        request(Wire.op("Document", "CreateCollection", named("collection", collection)), database);
    }

    public void documentCreateCollection(String collection) {
        documentCreateCollection(collection, "main");
    }

    /**
     * Insert a document and return its id.
     *
     * @param id {@code null} to let the server assign a unique id — the
     *           returned value is the id actually stored either way
     */
    public String documentInsert(String collection, String id, Map<String, Object> document,
                                  String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("id", id);
        body.put("document", requireNotNull(document, "document"));
        Response resp = request(Wire.op("Document", "Insert", body), database);
        return Wire.str(Wire.json(resp), "id");
    }

    public String documentInsert(String collection, String id, Map<String, Object> document) {
        return documentInsert(collection, id, document, "main");
    }

    /** Insert with a server-assigned id. */
    public String documentInsert(String collection, Map<String, Object> document) {
        return documentInsert(collection, null, document, "main");
    }

    /** The document, or empty when no document has that id. */
    public Optional<Map<String, Object>> documentGet(String collection, String id, String database) {
        Response resp = request(Wire.op("Document", "Get", collectionId(collection, id)), database);
        List<Map<String, Object>> docs = Wire.documents(resp);
        return docs.isEmpty() ? Optional.empty() : Optional.of(docs.get(0));
    }

    public Optional<Map<String, Object>> documentGet(String collection, String id) {
        return documentGet(collection, id, "main");
    }

    /**
     * Find documents matching {@code filter}.
     *
     * @param limit {@code null} for no client-imposed limit
     */
    public List<Map<String, Object>> documentFind(String collection, DocumentFilter filter,
                                                   Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", requireNotNull(filter, "filter").wire());
        body.put("limit", limit);
        return Wire.documents(request(Wire.op("Document", "Find", body), database));
    }

    public List<Map<String, Object>> documentFind(String collection, DocumentFilter filter, Integer limit) {
        return documentFind(collection, filter, limit, "main");
    }

    public List<Map<String, Object>> documentFind(String collection, DocumentFilter filter) {
        return documentFind(collection, filter, null, "main");
    }

    /**
     * Set fields on an existing document. Dot paths create or overwrite nested
     * keys. <b>Not</b> an upsert: a missing id is an error.
     */
    public void documentUpdate(String collection, String id, Map<String, Object> set, String database) {
        Map<String, Object> body = collectionId(collection, id);
        body.put("set", requireNotNull(set, "set"));
        request(Wire.op("Document", "Update", body), database);
    }

    public void documentUpdate(String collection, String id, Map<String, Object> set) {
        documentUpdate(collection, id, set, "main");
    }

    /**
     * Apply an update to one document by id.
     *
     * @param upsert create the document from the update when the id is missing;
     *               off by default, which matches {@link #documentUpdate}
     * @return whether the document was created rather than modified
     */
    public boolean documentUpdateOne(String collection, String id, DocumentUpdate update,
                                      boolean upsert, String database) {
        Map<String, Object> body = collectionId(collection, id);
        body.put("update", requireNotNull(update, "update").wire());
        body.put("upsert", upsert);
        Response resp = request(Wire.op("Document", "UpdateOne", body), database);
        return Wire.bool(Wire.json(resp), "inserted");
    }

    public boolean documentUpdateOne(String collection, String id, DocumentUpdate update, boolean upsert) {
        return documentUpdateOne(collection, id, update, upsert, "main");
    }

    /**
     * Apply an update to every document matching {@code filter}. Never an
     * upsert: a filter matching nothing modifies nothing, and is not an error.
     */
    public UpdateManyResult documentUpdateMany(String collection, DocumentFilter filter,
                                                DocumentUpdate update, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", requireNotNull(filter, "filter").wire());
        body.put("update", requireNotNull(update, "update").wire());
        Response resp = request(Wire.op("Document", "UpdateMany", body), database);
        return UpdateManyResult.from(Wire.json(resp));
    }

    public UpdateManyResult documentUpdateMany(String collection, DocumentFilter filter, DocumentUpdate update) {
        return documentUpdateMany(collection, filter, update, "main");
    }

    public void documentDelete(String collection, String id, String database) {
        request(Wire.op("Document", "Delete", collectionId(collection, id)), database);
    }

    public void documentDelete(String collection, String id) {
        documentDelete(collection, id, "main");
    }

    public List<String> documentListCollections(String database) {
        Response resp = request(Wire.unitOp("Document", "ListCollections"), database);
        return Wire.strings(Wire.json(resp), "collections");
    }

    public List<String> documentListCollections() {
        return documentListCollections("main");
    }

    public void documentDropCollection(String collection, String database) {
        request(Wire.op("Document", "DropCollection", named("collection", collection)), database);
    }

    public void documentDropCollection(String collection) {
        documentDropCollection(collection, "main");
    }

    /** Create a top-level-field index on a collection. */
    public void documentCreateIndex(String collection, String indexName, String field,
                                     boolean unique, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("index_name", require(indexName, "index_name"));
        body.put("field", require(field, "field"));
        body.put("unique", unique);
        request(Wire.op("Document", "CreateIndex", body), database);
    }

    public void documentCreateIndex(String collection, String indexName, String field, boolean unique) {
        documentCreateIndex(collection, indexName, field, unique, "main");
    }

    public void documentDropIndex(String collection, String indexName, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("index_name", require(indexName, "index_name"));
        request(Wire.op("Document", "DropIndex", body), database);
    }

    public void documentDropIndex(String collection, String indexName) {
        documentDropIndex(collection, indexName, "main");
    }

    public List<DocumentIndex> documentListIndexes(String collection, String database) {
        Response resp = request(Wire.op("Document", "ListIndexes", named("collection", collection)), database);
        List<DocumentIndex> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(resp), "indexes")) {
            out.add(DocumentIndex.from(Wire.asObject(o, "index")));
        }
        return out;
    }

    public List<DocumentIndex> documentListIndexes(String collection) {
        return documentListIndexes(collection, "main");
    }

    /**
     * Run an aggregation pipeline. Stages apply strictly in the order given —
     * that order is semantics, not style.
     */
    public List<Map<String, Object>> documentAggregate(String collection,
                                                        List<AggregateStage> pipeline, String database) {
        List<Object> stages = new ArrayList<>();
        for (AggregateStage s : requireNotNull(pipeline, "pipeline")) {
            stages.add(requireNotNull(s, "pipeline stage").wire());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("pipeline", stages);
        return Wire.documents(request(Wire.op("Document", "Aggregate", body), database));
    }

    public List<Map<String, Object>> documentAggregate(String collection, List<AggregateStage> pipeline) {
        return documentAggregate(collection, pipeline, "main");
    }

    /** Collect approximate statistics for a collection. */
    public DocumentStats documentAnalyze(String collection, String database) {
        Response resp = request(Wire.op("Document", "Analyze", named("collection", collection)), database);
        return DocumentStats.from(Wire.json(resp));
    }

    public DocumentStats documentAnalyze(String collection) {
        return documentAnalyze(collection, "main");
    }

    // -- vector ------------------------------------------------------------------

    public void vectorCreateCollection(String collection, int dimension, VectorMetric metric,
                                        VectorQuantization quantization, String database) {
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be >= 1");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("dimension", dimension);
        body.put("metric", requireNotNull(metric, "metric").wire());
        body.put("quantization",
                (quantization == null ? VectorQuantization.NONE : quantization).wire());
        request(Wire.op("Vector", "CreateCollection", body), database);
    }

    public void vectorCreateCollection(String collection, int dimension, VectorMetric metric,
                                        VectorQuantization quantization) {
        vectorCreateCollection(collection, dimension, metric, quantization, "main");
    }

    public void vectorCreateCollection(String collection, int dimension, VectorMetric metric) {
        vectorCreateCollection(collection, dimension, metric, VectorQuantization.NONE, "main");
    }

    /** Insert or replace a vector. Its length must match the collection's dimension. */
    public void vectorUpsert(String collection, String id, float[] vector,
                              Map<String, Object> metadata, String database) {
        Map<String, Object> body = collectionId(collection, id);
        body.put("vector", Wire.vectorOf(vector));
        body.put("metadata", metadata == null ? Map.of() : metadata);
        request(Wire.op("Vector", "Upsert", body), database);
    }

    public void vectorUpsert(String collection, String id, float[] vector, Map<String, Object> metadata) {
        vectorUpsert(collection, id, vector, metadata, "main");
    }

    public void vectorUpsert(String collection, String id, float[] vector) {
        vectorUpsert(collection, id, vector, null, "main");
    }

    /** The stored vector, or empty when no vector has that id. */
    public Optional<VectorItem> vectorGet(String collection, String id, String database) {
        Response resp = request(Wire.op("Vector", "Get", collectionId(collection, id)), database);
        Object v = Wire.jsonOrNull(resp);
        return v == null ? Optional.empty() : Optional.of(VectorItem.from(Wire.asObject(v, "vector")));
    }

    public Optional<VectorItem> vectorGet(String collection, String id) {
        return vectorGet(collection, id, "main");
    }

    public void vectorDelete(String collection, String id, String database) {
        request(Wire.op("Vector", "Delete", collectionId(collection, id)), database);
    }

    public void vectorDelete(String collection, String id) {
        vectorDelete(collection, id, "main");
    }

    /**
     * Nearest neighbours of {@code vector}, best first.
     *
     * @param filter exact-equality metadata filter (top-level field → required
     *               value), or {@code null} for none. No ranges, no nesting.
     */
    public List<VectorMatch> vectorSearch(String collection, float[] vector, int topK,
                                           Map<String, Object> filter, String database) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("vector", Wire.vectorOf(vector));
        body.put("top_k", topK);
        body.put("filter", filter);
        Response resp = request(Wire.op("Vector", "Search", body), database);
        List<VectorMatch> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(resp), "results")) {
            out.add(VectorMatch.from(Wire.asObject(o, "search result")));
        }
        return out;
    }

    public List<VectorMatch> vectorSearch(String collection, float[] vector, int topK,
                                           Map<String, Object> filter) {
        return vectorSearch(collection, vector, topK, filter, "main");
    }

    public List<VectorMatch> vectorSearch(String collection, float[] vector, int topK) {
        return vectorSearch(collection, vector, topK, null, "main");
    }

    public List<String> vectorListCollections(String database) {
        Response resp = request(Wire.unitOp("Vector", "ListCollections"), database);
        return Wire.strings(Wire.json(resp), "collections");
    }

    public List<String> vectorListCollections() {
        return vectorListCollections("main");
    }

    public VectorCollectionInfo vectorDescribeCollection(String collection, String database) {
        Response resp = request(
                Wire.op("Vector", "DescribeCollection", named("collection", collection)), database);
        return VectorCollectionInfo.from(Wire.json(resp));
    }

    public VectorCollectionInfo vectorDescribeCollection(String collection) {
        return vectorDescribeCollection(collection, "main");
    }

    /**
     * One page of a collection's vectors, ordered by id. The server clamps
     * {@code limit}; check {@link VectorPage#truncated()} before treating the
     * page as the whole collection.
     */
    public VectorPage vectorListVectors(String collection, Integer limit, Integer offset, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("limit", limit);
        body.put("offset", offset);
        Response resp = request(Wire.op("Vector", "ListVectors", body), database);
        Map<String, Object> m = Wire.json(resp);
        List<VectorItem> items = new ArrayList<>();
        for (Object o : Wire.list(m, "vectors")) {
            items.add(VectorItem.from(Wire.asObject(o, "vector")));
        }
        return new VectorPage(Wire.str(m, "collection"), items, Wire.bool(m, "truncated"), Wire.num(m, "total"));
    }

    public VectorPage vectorListVectors(String collection, Integer limit, Integer offset) {
        return vectorListVectors(collection, limit, offset, "main");
    }

    public VectorPage vectorListVectors(String collection) {
        return vectorListVectors(collection, null, null, "main");
    }

    public void vectorDropCollection(String collection, String database) {
        request(Wire.op("Vector", "DropCollection", named("collection", collection)), database);
    }

    public void vectorDropCollection(String collection) {
        vectorDropCollection(collection, "main");
    }

    // -- graph -------------------------------------------------------------------

    public void graphCreate(String graph, String database) {
        request(Wire.op("Graph", "CreateGraph", named("graph", graph)), database);
    }

    public void graphCreate(String graph) {
        graphCreate(graph, "main");
    }

    public void graphAddNode(String graph, String id, List<String> labels,
                              Map<String, Object> properties, String database) {
        Map<String, Object> body = graphId(graph, id);
        // `labels` is a Vec<String> on the server, so an absent list must be an
        // empty array — a JSON null fails to deserialize rather than defaulting.
        body.put("labels", labels == null ? List.of() : new ArrayList<>(labels));
        body.put("properties", properties == null ? Map.of() : properties);
        request(Wire.op("Graph", "AddNode", body), database);
    }

    public void graphAddNode(String graph, String id, List<String> labels, Map<String, Object> properties) {
        graphAddNode(graph, id, labels, properties, "main");
    }

    public void graphAddNode(String graph, String id) {
        graphAddNode(graph, id, null, null, "main");
    }

    /** The node, or empty when no node has that id. */
    public Optional<GraphNode> graphGetNode(String graph, String id, String database) {
        Response resp = request(Wire.op("Graph", "GetNode", graphId(graph, id)), database);
        Object v = Wire.jsonOrNull(resp);
        return v == null ? Optional.empty() : Optional.of(GraphNode.from(Wire.asObject(v, "node")));
    }

    public Optional<GraphNode> graphGetNode(String graph, String id) {
        return graphGetNode(graph, id, "main");
    }

    /** Add an edge. Both endpoints must already exist. */
    public void graphAddEdge(String graph, String id, String from, String to, String label,
                              Map<String, Object> properties, String database) {
        Map<String, Object> body = graphId(graph, id);
        body.put("from", require(from, "from"));
        body.put("to", require(to, "to"));
        body.put("label", requireNotNull(label, "label"));
        body.put("properties", properties == null ? Map.of() : properties);
        request(Wire.op("Graph", "AddEdge", body), database);
    }

    public void graphAddEdge(String graph, String id, String from, String to, String label,
                              Map<String, Object> properties) {
        graphAddEdge(graph, id, from, to, label, properties, "main");
    }

    public void graphAddEdge(String graph, String id, String from, String to, String label) {
        graphAddEdge(graph, id, from, to, label, null, "main");
    }

    /** The edge, or empty when no edge has that id. */
    public Optional<GraphEdge> graphGetEdge(String graph, String id, String database) {
        Response resp = request(Wire.op("Graph", "GetEdge", graphId(graph, id)), database);
        Object v = Wire.jsonOrNull(resp);
        return v == null ? Optional.empty() : Optional.of(GraphEdge.from(Wire.asObject(v, "edge")));
    }

    public Optional<GraphEdge> graphGetEdge(String graph, String id) {
        return graphGetEdge(graph, id, "main");
    }

    /**
     * Neighbours of a node.
     *
     * @param label {@code null} to follow edges of every label
     * @param limit {@code null} for the server's cap
     */
    public List<GraphNeighbor> graphNeighbors(String graph, String nodeId, GraphDirection direction,
                                               String label, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("node_id", require(nodeId, "node_id"));
        body.put("direction", direction(direction));
        body.put("label", label);
        body.put("limit", limit);
        Response resp = request(Wire.op("Graph", "Neighbors", body), database);
        List<GraphNeighbor> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(resp), "neighbors")) {
            out.add(GraphNeighbor.from(Wire.asObject(o, "neighbor")));
        }
        return out;
    }

    public List<GraphNeighbor> graphNeighbors(String graph, String nodeId, GraphDirection direction,
                                               String label, Integer limit) {
        return graphNeighbors(graph, nodeId, direction, label, limit, "main");
    }

    public List<GraphNeighbor> graphNeighbors(String graph, String nodeId, GraphDirection direction) {
        return graphNeighbors(graph, nodeId, direction, null, null, "main");
    }

    public void graphDeleteNode(String graph, String id, String database) {
        request(Wire.op("Graph", "DeleteNode", graphId(graph, id)), database);
    }

    public void graphDeleteNode(String graph, String id) {
        graphDeleteNode(graph, id, "main");
    }

    public void graphDeleteEdge(String graph, String id, String database) {
        request(Wire.op("Graph", "DeleteEdge", graphId(graph, id)), database);
    }

    public void graphDeleteEdge(String graph, String id) {
        graphDeleteEdge(graph, id, "main");
    }

    public List<String> graphList(String database) {
        Response resp = request(Wire.unitOp("Graph", "ListGraphs"), database);
        return Wire.strings(Wire.json(resp), "graphs");
    }

    public List<String> graphList() {
        return graphList("main");
    }

    public void graphDrop(String graph, String database) {
        request(Wire.op("Graph", "DropGraph", named("graph", graph)), database);
    }

    public void graphDrop(String graph) {
        graphDrop(graph, "main");
    }

    /**
     * Bounded multi-hop BFS from {@code start}. The server clamps both
     * {@code maxDepth} and {@code limit}; the values it actually used come back
     * on the result.
     */
    public GraphTraversal graphTraverse(String graph, String start, GraphDirection direction,
                                         String label, Integer maxDepth, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("start", require(start, "start"));
        body.put("direction", direction(direction));
        body.put("label", label);
        body.put("max_depth", maxDepth);
        body.put("limit", limit);
        Response resp = request(Wire.op("Graph", "Traverse", body), database);
        Map<String, Object> m = Wire.json(resp);
        List<GraphTraversalNode> nodes = new ArrayList<>();
        for (Object o : Wire.list(m, "nodes")) {
            nodes.add(GraphTraversalNode.from(Wire.asObject(o, "traversal node")));
        }
        return new GraphTraversal(
                Wire.str(m, "start"),
                GraphDirection.fromWire(Wire.str(m, "direction")),
                Wire.num(m, "max_depth"),
                Wire.num(m, "limit"),
                Wire.bool(m, "truncated"),
                nodes);
    }

    public GraphTraversal graphTraverse(String graph, String start, GraphDirection direction,
                                         String label, Integer maxDepth, Integer limit) {
        return graphTraverse(graph, start, direction, label, maxDepth, limit, "main");
    }

    public GraphTraversal graphTraverse(String graph, String start, GraphDirection direction) {
        return graphTraverse(graph, start, direction, null, null, null, "main");
    }

    /**
     * Fewest-hop path from {@code from} to {@code to}. "No path" comes back as
     * a result with {@code found = false}, not an exception.
     */
    public GraphPath graphShortestPath(String graph, String from, String to, GraphDirection direction,
                                        String label, Integer maxDepth, String database) {
        Map<String, Object> body = fromTo(graph, from, to, direction, label);
        body.put("max_depth", maxDepth);
        return GraphPath.from(Wire.json(request(Wire.op("Graph", "ShortestPath", body), database)));
    }

    public GraphPath graphShortestPath(String graph, String from, String to, GraphDirection direction,
                                        String label, Integer maxDepth) {
        return graphShortestPath(graph, from, to, direction, label, maxDepth, "main");
    }

    public GraphPath graphShortestPath(String graph, String from, String to, GraphDirection direction) {
        return graphShortestPath(graph, from, to, direction, null, null, "main");
    }

    /**
     * Least-cost path by summed edge weight. Distinct from
     * {@link #graphShortestPath}, which minimises hops: with unequal weights the
     * two return different paths and neither substitutes for the other.
     *
     * @param weightProperty edge property holding the cost, or {@code null} for
     *                       the server's default ({@code "weight"}). An edge
     *                       missing it, or holding a non-number, weighs 1.0.
     */
    public GraphPath graphWeightedShortestPath(String graph, String from, String to,
                                                GraphDirection direction, String label,
                                                String weightProperty, String database) {
        Map<String, Object> body = fromTo(graph, from, to, direction, label);
        body.put("weight_property", weightProperty);
        return GraphPath.from(
                Wire.json(request(Wire.op("Graph", "WeightedShortestPath", body), database)));
    }

    public GraphPath graphWeightedShortestPath(String graph, String from, String to,
                                                GraphDirection direction, String label,
                                                String weightProperty) {
        return graphWeightedShortestPath(graph, from, to, direction, label, weightProperty, "main");
    }

    public GraphPath graphWeightedShortestPath(String graph, String from, String to,
                                                GraphDirection direction) {
        return graphWeightedShortestPath(graph, from, to, direction, null, null, "main");
    }

    /** Edges incident to a node. {@code BOTH} counts each edge once. */
    public long graphDegree(String graph, String nodeId, GraphDirection direction, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("node_id", require(nodeId, "node_id"));
        body.put("direction", direction(direction));
        return Wire.num(Wire.json(request(Wire.op("Graph", "Degree", body), database)), "degree");
    }

    public long graphDegree(String graph, String nodeId, GraphDirection direction) {
        return graphDegree(graph, nodeId, direction, "main");
    }

    public GraphNodePage graphListNodes(String graph, Integer limit, Integer offset, String database) {
        Map<String, Object> m = Wire.json(
                request(Wire.op("Graph", "ListNodes", page(graph, limit, offset)), database));
        List<GraphNode> nodes = new ArrayList<>();
        for (Object o : Wire.list(m, "nodes")) {
            nodes.add(GraphNode.from(Wire.asObject(o, "node")));
        }
        return new GraphNodePage(Wire.str(m, "graph"), nodes, Wire.bool(m, "truncated"), Wire.num(m, "total"));
    }

    public GraphNodePage graphListNodes(String graph, Integer limit, Integer offset) {
        return graphListNodes(graph, limit, offset, "main");
    }

    public GraphNodePage graphListNodes(String graph) {
        return graphListNodes(graph, null, null, "main");
    }

    public GraphEdgePage graphListEdges(String graph, Integer limit, Integer offset, String database) {
        Map<String, Object> m = Wire.json(
                request(Wire.op("Graph", "ListEdges", page(graph, limit, offset)), database));
        List<GraphEdge> edges = new ArrayList<>();
        for (Object o : Wire.list(m, "edges")) {
            edges.add(GraphEdge.from(Wire.asObject(o, "edge")));
        }
        return new GraphEdgePage(Wire.str(m, "graph"), edges, Wire.bool(m, "truncated"), Wire.num(m, "total"));
    }

    public GraphEdgePage graphListEdges(String graph, Integer limit, Integer offset) {
        return graphListEdges(graph, limit, offset, "main");
    }

    public GraphEdgePage graphListEdges(String graph) {
        return graphListEdges(graph, null, null, "main");
    }

    /**
     * Run a read-only Cypher-subset query.
     *
     * The server implements {@code MATCH} / {@code WHERE} / {@code RETURN} with
     * labels, property predicates, relationship direction and type, bounded
     * variable-length paths, {@code DISTINCT}, {@code ORDER BY} / {@code SKIP} /
     * {@code LIMIT} and global aggregates. Every write clause and everything
     * else is refused <b>by name</b> — a query that silently dropped a clause
     * would return a confidently wrong answer — so an unsupported query throws
     * {@link TriCoreException} rather than returning partial rows.
     */
    public GraphQueryResult graphQuery(String graph, String cypher, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("cypher", require(cypher, "cypher"));
        Map<String, Object> m = Wire.json(request(Wire.op("Graph", "Query", body), database));
        List<List<Object>> rows = new ArrayList<>();
        for (Object row : Wire.list(m, "rows")) {
            if (!(row instanceof List<?> l)) {
                throw new ProtocolException("query row is not an array: " + Wire.describe(row));
            }
            rows.add(new ArrayList<>(l));
        }
        return new GraphQueryResult(
                Wire.str(m, "graph"), Wire.strings(m, "columns"), rows, Wire.bool(m, "truncated"));
    }

    public GraphQueryResult graphQuery(String graph, String cypher) {
        return graphQuery(graph, cypher, "main");
    }

    // -- llm ---------------------------------------------------------------------
    //
    // Read-only context assembly. No LLM operation ever writes.

    /**
     * Assemble a context bundle from one or more read-only sources.
     *
     * <p>The caller needs the matching read permission for each source. Returns
     * the rendered bundle: text for TOON/Markdown, JSON otherwise.
     *
     * @param options {@code null} for the server's defaults (redact, no cap)
     */
    public String llmContext(List<LlmSource> sources, OutputFormat format, LlmOptions options,
                              String database) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("a context bundle needs at least one source");
        }
        List<Object> wire = new ArrayList<>(sources.size());
        for (LlmSource s : sources) {
            wire.add(requireNotNull(s, "source").wire());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sources", wire);
        body.put("format", requireNotNull(format, "format").wire());
        body.put("options", (options == null ? LlmOptions.defaults() : options).wire());
        return rendered(request(Wire.op("Llm", "Context", body), database));
    }

    public String llmContext(List<LlmSource> sources) {
        return llmContext(sources, OutputFormat.TOON, null, "main");
    }

    /** Export the schema catalog: SQL tables plus document collections. */
    public String llmSchema(OutputFormat format, LlmOptions options, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", requireNotNull(format, "format").wire());
        body.put("options", (options == null ? LlmOptions.defaults() : options).wire());
        return rendered(request(Wire.op("Llm", "Schema", body), database));
    }

    public String llmSchema() {
        return llmSchema(OutputFormat.TOON, null, "main");
    }

    /** Unwrap whichever payload the requested output format produced. */
    private static String rendered(Response resp) {
        Object data = resp.data();
        if (data instanceof Map<?, ?> m) {
            if (m.containsKey("Toon")) {
                return String.valueOf(m.get("Toon"));
            }
            if (m.containsKey("Json")) {
                return Json.encode(m.get("Json"));
            }
            if (m.containsKey("Message")) {
                return String.valueOf(m.get("Message"));
            }
        }
        throw new ProtocolException("expected a rendered export, got " + Wire.describe(data));
    }

    // -- admin -------------------------------------------------------------------
    //
    // Both require the Admin permission *and* the `cluster` module, so an
    // unprivileged or misconfigured caller gets an error rather than silently
    // empty data.

    /**
     * Round-trip a request through the full pipeline.
     *
     * <p>Distinct from {@link #ping()}, which never reaches a module. This one
     * proves auth, routing and dispatch are working — what a readiness check
     * actually wants to know.
     */
    public void adminPing(String database) {
        request(Wire.unitOp("Admin", "Ping"), database);
    }

    public void adminPing() {
        adminPing("main");
    }

    /**
     * Server status as reported by the cluster core.
     *
     * <p>Stub cores answer with a plain message rather than structured JSON,
     * which is returned under the {@code "message"} key rather than raised, so a
     * status call never hard-fails on a healthy single node.
     */
    public Map<String, Object> adminStatus(String database) {
        Response resp = request(Wire.unitOp("Admin", "Status"), database);
        Object data = resp.data();
        if (data instanceof Map<?, ?> m && m.containsKey("Message")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", String.valueOf(m.get("Message")));
            return out;
        }
        return Wire.json(resp);
    }

    public Map<String, Object> adminStatus() {
        return adminStatus("main");
    }

    // -- transactions ------------------------------------------------------------

    /**
     * Run a pre-declared script atomically, in <b>one request</b>.
     *
     * <p>The server runs the {@code BEGIN ... COMMIT} script against an MVCC
     * snapshot and flushes it as one atomic batch; {@code ROLLBACK}, or any error
     * mid-script, discards the buffer. This is the right tool when every
     * statement is known up front: one round trip, one replication event, and it
     * works on <em>every</em> node — including the sharded and forwarding ones
     * that withhold {@link #FEATURE_SESSION_TXN}.
     *
     * <pre>{@code
     * db.transaction(List.of(
     *         SqlStatement.of("INSERT INTO t VALUES (?, ?)", 1, "ada"),
     *         SqlStatement.of("INSERT INTO t VALUES (?, ?)", 2, "bob")));
     * }</pre>
     *
     * <p>When a later statement depends on what an earlier one read, use
     * {@link #begin}/{@link #commit}/{@link #rollback} or
     * {@link #withTransaction} instead.
     *
     * @return the server's summary: statement count, committed writes, outcome
     */
    public TransactionResult transaction(List<SqlStatement> statements, String database) {
        // The script keeps its `?` placeholders and the statements' arguments are
        // concatenated in statement order — which is exactly how the server binds
        // a multi-statement script, walking it left to right and consuming one
        // parameter per placeholder. So a transaction inherits the same guarantee
        // a single bound execute() has, instead of pasting values into the text.
        Response resp = execute(
                SqlStatement.parameterizedScript(statements),
                SqlStatement.scriptArgs(statements),
                database);
        return TransactionResult.from(Wire.json(resp));
    }

    public TransactionResult transaction(List<SqlStatement> statements) {
        return transaction(statements, "main");
    }

    /**
     * Open a session transaction on <b>this connection</b>.
     *
     * <p>Every statement sent on this connection until {@link #commit} or
     * {@link #rollback} runs inside it, at one snapshot, and is invisible to
     * other connections until committed. The block belongs to this connection's
     * socket: it cannot be committed from another connection, a pooled one
     * included, and a dropped socket rolls it back.
     *
     * <p>Requires the server to have granted {@link #FEATURE_SESSION_TXN} in the
     * handshake ({@link #sessionTxnGranted()}). If it did not — an older server,
     * or a sharded / forwarding node — this throws by name <b>before sending
     * anything</b>, rather than sending a {@code BEGIN} the server would run as a
     * one-statement autocommit script; {@link #transaction} works everywhere.
     *
     * <p>What the server enforces inside a block, in its own words when it
     * happens: a failed statement aborts the block and every statement after it
     * is refused until {@code ROLLBACK}; a second {@code begin()} is refused (no
     * savepoints); DDL and multi-statement scripts are refused; a block left idle
     * past the server's idle-in-transaction window (60 s by default) is rolled
     * back and the connection closed; and a block that buffers more than
     * {@code max_pending_writes_per_transaction} writes is aborted.
     *
     * @return the server's outcome, whose {@code outcome} is {@code "began"}
     */
    public TransactionResult begin(String database) {
        if (!sessionTxnGranted()) {
            throw new TriCoreException(
                    "this server did not grant session transactions (SESSION_TXN is not in the "
                            + "granted feature set), so begin()/commit()/rollback() cannot open a "
                            + "rollback boundary on this connection; use transaction(List.of(...)) "
                            + "to send the whole unit as one `BEGIN; <statements>; COMMIT` request");
        }
        return txnControl("BEGIN", database);
    }

    /** {@link #begin(String)} on the {@code main} database. */
    public TransactionResult begin() {
        return begin("main");
    }

    /**
     * Commit the block opened by {@link #begin}, durably and as one atomic batch.
     *
     * <p>A refusal is the server's own and ends the block either way: after a
     * failed statement the server has already rolled it back and says so; a
     * first-committer-wins conflict discards it.
     *
     * @return the server's outcome, whose {@code outcome} is {@code "committed"}
     */
    public TransactionResult commit(String database) {
        return txnControl("COMMIT", database);
    }

    /** {@link #commit(String)} on the {@code main} database. */
    public TransactionResult commit() {
        return commit("main");
    }

    /**
     * Discard the block opened by {@link #begin}.
     *
     * @return the server's outcome, whose {@code outcome} is {@code "rolled_back"}
     */
    public TransactionResult rollback(String database) {
        return txnControl("ROLLBACK", database);
    }

    /** {@link #rollback(String)} on the {@code main} database. */
    public TransactionResult rollback() {
        return rollback("main");
    }

    /**
     * {@link #begin}, run {@code body}, then {@link #commit} — or
     * {@link #rollback} and rethrow if {@code body} throws (the original
     * exception is what propagates, an {@link Error} included).
     *
     * <pre>{@code
     * db.withTransaction(tx -> {
     *     tx.execute("UPDATE accounts SET balance = balance - 10 WHERE id = 1");
     *     tx.execute("UPDATE accounts SET balance = balance + 10 WHERE id = 2");
     * });
     * }</pre>
     *
     * <p>{@code body} must issue its statements on the connection it is given —
     * this one — and only this one. A statement on any other connection, a pooled
     * one included, is outside the block: the server binds the transaction to
     * this socket and refuses {@code COMMIT} from any other by name.
     *
     * <p>Deliberately a {@link Consumer} rather than an overloaded pair of
     * void- and value-returning callbacks: javac cannot disambiguate two SAMs of
     * the same shape for a lambda whose body is a single void call, which is the
     * trap {@link Pool.Action} documents. A caller after a result captures it in
     * an effectively-final holder, or uses {@link #transactionBlock()}.
     */
    public void withTransaction(String database, Consumer<TriCore> body) {
        requireNotNull(body, "body");
        begin(database);
        try {
            body.accept(this);
        } catch (RuntimeException | Error e) {
            if (inTransaction()) {
                try {
                    rollback(database);
                } catch (RuntimeException ignored) {
                    // The caller's failure is the one to report. If the socket is
                    // already gone the server has rolled the block back on its own.
                }
            }
            throw e;
        }
        commit(database);
    }

    /** {@link #withTransaction(String, Consumer)} on the {@code main} database. */
    public void withTransaction(Consumer<TriCore> body) {
        withTransaction("main", body);
    }

    /**
     * A session transaction as a try-with-resources resource: {@code close()}
     * rolls the block back unless it was committed.
     *
     * <pre>{@code
     * try (Transaction tx = db.transactionBlock()) {
     *     db.execute("INSERT INTO t VALUES (1, 'ada')");
     *     tx.commit();
     * }   // an early return, a `break`, or a thrown exception rolls back here
     * }</pre>
     */
    public Transaction transactionBlock(String database) {
        begin(database);
        return new Transaction(this, database);
    }

    /** {@link #transactionBlock(String)} on the {@code main} database. */
    public Transaction transactionBlock() {
        return transactionBlock("main");
    }

    /**
     * Transaction control travels as {@code Exec}: the server authorizes it as a
     * write and refuses it on {@code Query}.
     *
     * <p>{@code txnOpen} follows what the server answered. Every
     * {@code COMMIT}/{@code ROLLBACK} reply — a refusal included — means the
     * block is over, because the server ends it either way; the one exception is
     * a request that never left this process, which is what
     * {@link RequestNotSentException} names.
     */
    private TransactionResult txnControl(String keyword, String database) {
        Response resp;
        try {
            resp = execute(keyword, database);
        } catch (RuntimeException e) {
            if (!"BEGIN".equals(keyword) && !(e instanceof RequestNotSentException)) {
                txnOpen = false;
            }
            throw e;
        }
        txnOpen = "BEGIN".equals(keyword);
        return TransactionResult.from(Wire.json(resp));
    }

    /**
     * Run a write, binding {@code ?} placeholders from {@code args}
     * <b>server-side</b>.
     *
     * <p>The values travel beside the SQL as a typed array and the server
     * substitutes them at value positions its grammar has already fixed. A value
     * therefore cannot become syntax however it is spelled: a quote, a
     * backslash, or an argument that is itself a complete SQL statement is
     * stored as the text it is.
     *
     * <p>This requires {@link #FEATURE_SERVER_PARAMS}, negotiated at
     * {@code HELLO}. When the server did not grant it — one too old to
     * negotiate — this throws by name <b>before sending anything</b>, rather
     * than falling back to rendering the values into the statement text. The
     * fallback is what this driver used to do unconditionally, and doing it
     * silently would mean the same call binds on one connection and escapes on
     * the next with nothing to tell them apart. A caller who genuinely wants the
     * old behaviour asks for it by name with {@link SqlParams#bind}.
     *
     * <p>Empty {@code args} needs no capability: with nothing to bind this is
     * exactly {@link #execute(String, String)}.
     */
    public Response execute(String sql, List<Object> args, String database) {
        return request(sqlOp("Exec", sql, args), database);
    }

    /** Run a read, binding {@code ?} placeholders server-side. See {@link #execute(String, List, String)}. */
    public Rows query(String sql, List<Object> args, String database) {
        return rowsOf(request(sqlOp("Query", sql, args), database));
    }

    /**
     * Build a {@code Query}/{@code Exec} op, binding {@code args} server-side.
     *
     * <p>Placeholder arity is deliberately left to the server: it counts
     * {@code ?} against the parameters it was given and refuses a mismatch by
     * name, and it also understands {@code $n} placeholders, which a
     * client-side scanner counting {@code ?} would mis-report. One authority on
     * what a statement's placeholders are is the point.
     */
    private Map<String, Object> sqlOp(String kind, String sql, List<Object> args) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        if (args != null && !args.isEmpty()) {
            if (!serverParamsGranted()) {
                throw new TriCoreException(SERVER_PARAMS_NOT_GRANTED);
            }
            body.put("params", SqlParams.params(args));
        }
        Map<String, Object> sqlOp = new LinkedHashMap<>();
        sqlOp.put(kind, body);
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("Sql", sqlOp);
        return op;
    }

    /**
     * The refusal for an ungranted {@link #FEATURE_SERVER_PARAMS}, shared by
     * every entry point that binds so one wording is searchable.
     */
    static final String SERVER_PARAMS_NOT_GRANTED =
            "this server did not grant server-side parameters (SERVER_PARAMS is not in the "
            + "granted feature set), so this driver will not bind `?` placeholders on this "
            + "connection; it will not silently render the values into the statement text "
            + "instead, because escaping and binding are not the same guarantee. Upgrade the "
            + "server, or call SqlParams.bind(sql, args) explicitly to accept client-side "
            + "rendering";

    // -- op-building helpers -----------------------------------------------------

    private static Map<String, Object> named(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, require(value, key));
        return m;
    }

    private static Map<String, Object> collectionId(String collection, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("collection", require(collection, "collection"));
        m.put("id", require(id, "id"));
        return m;
    }

    private static Map<String, Object> graphId(String graph, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("graph", require(graph, "graph"));
        m.put("id", require(id, "id"));
        return m;
    }

    private static Map<String, Object> fromTo(String graph, String from, String to,
                                               GraphDirection direction, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("graph", require(graph, "graph"));
        m.put("from", require(from, "from"));
        m.put("to", require(to, "to"));
        m.put("direction", direction(direction));
        m.put("label", label);
        return m;
    }

    private static Map<String, Object> page(String graph, Integer limit, Integer offset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("graph", require(graph, "graph"));
        m.put("limit", limit);
        m.put("offset", offset);
        return m;
    }

    private static String direction(GraphDirection direction) {
        return (direction == null ? GraphDirection.OUTGOING : direction).wire();
    }

    private static String require(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be null or empty");
        }
        return value;
    }

    private static <T> T requireNotNull(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        return value;
    }

    // -- transport ---------------------------------------------------------------

    private void hello(String clientName, long features) throws IOException {
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("major", VERSION);
        version.put("minor", 0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol", PROTOCOL);
        payload.put("version", version);
        payload.put("client", clientName);
        payload.put("features", features);
        Frame f = exchange(HELLO, payload);
        if (f.tag == ERROR) {
            throw new ProtocolException(errorText(f.body));
        }
        if (f.tag != HELLO_OK) {
            throw new ProtocolException("expected HELLO_OK, got tag " + f.tag);
        }
        Map<String, Object> body = asMap(f.body);
        if (!Boolean.TRUE.equals(body.get("ok"))) {
            throw new ProtocolException(String.valueOf(body.getOrDefault("message", "handshake refused")));
        }
        Object granted = body.get("features");
        grantedFeatures = (granted instanceof Number) ? ((Number) granted).longValue() : 0L;
    }

    /**
     * Send one frame and read its reply, holding the connection meanwhile.
     *
     * <p>A connection is one request/response stream, and {@code recv} reads a
     * 6-byte header followed by exactly that many body bytes. Two overlapping
     * exchanges — two threads sharing one {@code TriCore} — both read a header:
     * the first gets the header, the <b>second gets the first frame's body</b>
     * and mis-parses it. From there the stream is unrecoverable and the driver
     * blocks forever on bytes that never arrive.
     *
     * <p>Refusing the second caller turns that hang into an exception naming the
     * mistake and the fix. This is a guard, not a queue: silently serialising
     * would make an unsafe pattern appear to work while giving the caller no
     * concurrency at all — use {@link Pool} for that.
     */
    private Frame exchange(int tag, Object payload) throws IOException {
        if (!inFlight.compareAndSet(false, true)) {
            // RequestNotSentException, not a bare TriCoreException: nothing was
            // written, so a caller — `txnControl` above all — can tell "the
            // server never saw this" from "the server refused it".
            throw new RequestNotSentException(
                    "a request is already in flight on this connection. A TriCore connection is a "
                            + "single request/response stream: overlapping requests interleave frames "
                            + "and deadlock. Use one connection per thread, or a Pool.");
        }
        try {
            send(tag, payload);
            return recv();
        } finally {
            inFlight.set(false);
        }
    }

    private void send(int tag, Object payload) throws IOException {
        throwIfUnusable();
        byte[] body = payload == null ? new byte[0] : Json.encode(payload).getBytes(StandardCharsets.UTF_8);

        // Mirrors tricore_protocol::write_frame. A control frame over the ceiling
        // is refused locally and named; an oversized REQUEST is still written, so
        // the server's own `frame_too_large` stays the diagnosis for the case an
        // operator will actually meet.
        if (tag != REQUEST && body.length > MAX_CONTROL_FRAME_SIZE) {
            throw new ProtocolException("a " + body.length + "-byte payload for tag " + tag
                    + " exceeds the protocol's ceiling for control frames at "
                    + MAX_CONTROL_FRAME_SIZE + " bytes");
        }
        byte[] header = new byte[HEADER_SIZE];
        header[0] = (byte) VERSION;
        header[1] = (byte) tag;
        header[2] = (byte) (body.length >>> 24);
        header[3] = (byte) (body.length >>> 16);
        header[4] = (byte) (body.length >>> 8);
        header[5] = (byte) body.length;
        out.write(header);
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private Frame recv() throws IOException {
        byte[] header = readExactly(HEADER_SIZE);
        int version = header[0] & 0xFF;
        if (version > MAX_SUPPORTED_FRAME_VERSION) {
            throw poison(new ProtocolException("frame header version " + version
                    + " is newer than this driver can read (max " + MAX_SUPPORTED_FRAME_VERSION + ")"));
        }
        int tag = header[1] & 0xFF;
        long length = ((long) (header[2] & 0xFF) << 24)
                | ((long) (header[3] & 0xFF) << 16)
                | ((long) (header[4] & 0xFF) << 8)
                | (header[5] & 0xFF);
        // Checked BEFORE a payload byte is asked for, and before anything is
        // allocated for it. The old `length > Integer.MAX_VALUE` guard caught only
        // the top half of the u32 range: a declared 2 GiB was refused and a
        // declared 2 GiB minus one byte was buffered.
        long limit = maxPayloadFor(tag);
        if (length > limit) {
            throw poison(new ProtocolException("frame payload of " + length + " bytes for tag "
                    + tag + " exceeds the protocol ceiling of " + limit + " bytes"));
        }
        if (length == 0) {
            return new Frame(tag, null);
        }
        byte[] body = readExactly((int) length);
        Object decoded;
        try {
            decoded = Json.decode(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw poison(new ProtocolException("malformed frame payload for tag " + tag
                    + ": " + e.getMessage()));
        }
        return new Frame(tag, decoded);
    }

    /** Refuse any use of a connection that is closed or poisoned. */
    private void throwIfUnusable() {
        if (fatal != null) {
            throw new ProtocolException("this connection was closed after a protocol failure "
                    + "and cannot be reused: " + fatal.getMessage());
        }
        if (socket == null) {
            throw new TriCoreException("connection is closed");
        }
    }

    /**
     * Record {@code e} as fatal, drop the socket, and hand it back so a call site
     * can read {@code throw poison(new ...)}.
     */
    private <E extends TriCoreException> E poison(E e) {
        if (fatal == null) {
            fatal = e;
            Socket s = socket;
            socket = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Nothing meaningful to do with a failure to close.
                }
            }
        }
        return e;
    }

    /**
     * Read exactly {@code n} bytes.
     *
     * TCP is a stream, not a message queue: a single {@code read} may return a
     * partial frame (or several coalesced ones). {@link DataInputStream#readFully}
     * is what keeps this correct once payloads outgrow one TCP segment — a small
     * local test never fragments and would happily hide the bug, which is exactly
     * why the end-to-end test below pushes a 100 KB value through this path.
     */
    private byte[] readExactly(int n) throws IOException {
        throwIfUnusable();
        byte[] buf = new byte[n];
        try {
            in.readFully(buf);
        } catch (EOFException e) {
            throw poison(new ProtocolException("connection closed mid-frame by the server"));
        } catch (SocketTimeoutException e) {
            throw poison(new TriCoreTimeoutException(
                    "no reply within this connection's read timeout", e));
        } catch (IOException e) {
            // A failed read leaves the stream un-resynchronised: the reply may
            // still be in flight, and reusing the socket would read it as the
            // answer to the next request.
            poison(new ProtocolException("read failed: " + e.getMessage()));
            throw e;
        }
        return buf;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object body) {
        if (body instanceof Map) {
            return (Map<String, Object>) body;
        }
        return Map.of();
    }

    private static String kindOf(Object data) {
        if (data instanceof Map<?, ?> m && !m.isEmpty()) {
            return String.valueOf(m.keySet().iterator().next());
        }
        return String.valueOf(data);
    }

    private static String messageOf(Object data) {
        if (data instanceof Map<?, ?> m) {
            if (m.get("Message") != null) {
                return String.valueOf(m.get("Message"));
            }
            if (m.get("Json") != null) {
                return Json.encode(m.get("Json"));
            }
        }
        return null;
    }

    private static String errorText(Object body) {
        if (body instanceof Map<?, ?> m) {
            Object msg = m.get("message");
            if (msg == null) msg = m.get("error");
            if (msg != null) return String.valueOf(msg);
            return Json.encode(m);
        }
        return String.valueOf(body);
    }

    private record Frame(int tag, Object body) {
    }
}
