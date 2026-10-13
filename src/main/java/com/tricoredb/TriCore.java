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
import java.util.OptionalDouble;
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
    public static final String SDK_VERSION = "0.3.0";

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
    private String requestTxn;
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

    // Following the leader. See setFollowLeader.
    private static final long MAX_LEADER_RETRY_DELAY_MS = 2_000;
    private static final int FOLLOW_CONNECT_TIMEOUT_MS = 3_000;
    private boolean followLeader = true;
    private int leaderRetries = 5;
    private long leaderRetryDelayMs = 250;

    // What connect() was given, to open a connection to the leader the same way.
    private String address = "";
    private String authUser;
    private String authSecret;
    private String clientName;
    private int connectTimeoutMs;
    private TlsOptions tls;
    private long features;

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
            db.address = joinAddress(host, port);
            db.authUser = user;
            db.authSecret = secret;
            db.clientName = clientName;
            db.connectTimeoutMs = timeoutMs;
            db.tls = tls;
            db.features = features;
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
     * Send a request again when a cluster node refuses it with {@code not_leader}.
     * On by default.
     *
     * <p>The request goes to the leader the refusal names, over a new connection
     * that replaces this one (same user, secret and TLS settings, with the
     * leader's host as the certificate name), or, while no leader is known, to the
     * same node after a pause. That is safe because a {@code not_leader} refusal
     * was applied nowhere. It never happens inside a session transaction, and never
     * for {@link TriCoreException#ERROR_CODE_OUTCOME_UNKNOWN}.
     */
    public void setFollowLeader(boolean follow) {
        this.followLeader = follow;
    }

    public boolean followLeader() {
        return followLeader;
    }

    /** How many times a refused request is sent again. Default 5. */
    public void setLeaderRetries(int retries) {
        if (retries < 0) {
            throw new IllegalArgumentException("leaderRetries must be >= 0");
        }
        this.leaderRetries = retries;
    }

    /** The first pause before asking again; it doubles up to 2 s. Default 250 ms. */
    public void setLeaderRetryDelayMs(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("leaderRetryDelayMs must be >= 0");
        }
        this.leaderRetryDelayMs = delayMs;
    }

    /** The {@code host:port} this connection talks to. It changes when the driver follows the leader. */
    public String address() {
        return address;
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

    /**
     * Run every subsequent request inside a document or graph transaction, or
     * pass {@code null} to stop doing so.
     *
     * <p>Give it the id {@link #documentBeginTransaction()} or
     * {@link #graphBeginTransaction(String)} returned and each later request
     * carries it, so it reads and writes inside that transaction. Committing or
     * rolling that transaction back clears it.
     */
    public void setRequestTxn(String txn) {
        this.requestTxn = txn;
    }

    /** The transaction requests currently run inside, or {@code null}. */
    public String requestTxn() {
        return requestTxn;
    }

    /** Send a raw operation. The typed helpers below all funnel through this. */
    public Response request(Map<String, Object> op, String database) {
        return request(op, database, requestTxn);
    }

    /** Send an operation inside the transaction {@code txn}, whatever {@link #requestTxn()} says. */
    Response request(Map<String, Object> op, String database, String txn) {
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
        if (requestTimeoutMs != null || txn != null) {
            Map<String, Object> options = new LinkedHashMap<>();
            if (requestTimeoutMs != null) {
                options.put("timeout_ms", requestTimeoutMs);
            }
            if (txn != null) {
                options.put("txn", txn);
            }
            payload.put("options", options);
        }
        long pause = leaderRetryDelayMs;
        for (int retried = 0; ; retried++) {
            try {
                return sendRequest(payload);
            } catch (TriCoreException e) {
                if (!e.isNotLeader()) {
                    throw e;
                }
                // A `not_leader` refusal was applied nowhere, so sending it again
                // cannot apply it twice. A session transaction belongs to this
                // connection and cannot move.
                if (!followLeader || txnOpen || retried >= leaderRetries) {
                    if (retried == 0) {
                        throw e;
                    }
                    throw new TriCoreException(e.getMessage() + " (sent again " + retried
                            + " times, refused each time)", e.errorCode(), e.leaderHint());
                }
                String hint = e.leaderHint();
                if (hint != null && !hint.equals(address) && moveTo(hint)) {
                    continue;
                }
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                pause = Math.min(Math.max(pause * 2, 1), MAX_LEADER_RETRY_DELAY_MS);
            }
        }
    }

    /**
     * Replace this connection with one to {@code target}, opened the way this one
     * was. False, leaving this connection untouched, when that fails.
     */
    private boolean moveTo(String target) {
        String host;
        String portText;
        if (target.startsWith("[")) {
            int end = target.indexOf("]:");
            if (end < 0) {
                return false;
            }
            host = target.substring(1, end);
            portText = target.substring(end + 2);
        } else {
            int colon = target.lastIndexOf(':');
            if (colon <= 0) {
                return false;
            }
            host = target.substring(0, colon);
            portText = target.substring(colon + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return false;
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return false;
        }
        int budget = connectTimeoutMs > 0 && connectTimeoutMs < FOLLOW_CONNECT_TIMEOUT_MS
                ? connectTimeoutMs : FOLLOW_CONNECT_TIMEOUT_MS;
        int readTimeoutMs;
        TriCore fresh;
        try {
            readTimeoutMs = socket.getSoTimeout();
            // The leader's certificate is checked against the name the hint gives.
            TlsOptions leaderTls = tls == null ? null : tls.forServer(host);
            fresh = connect(host, port, authUser, authSecret, clientName, budget, leaderTls,
                    readTimeoutMs, features);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        Socket old = socket;
        socket = fresh.socket;
        in = fresh.in;
        out = fresh.out;
        sessionId = fresh.sessionId;
        grantedFeatures = fresh.grantedFeatures;
        address = target;
        fresh.socket = null;
        try {
            old.close();
        } catch (IOException ignored) {
            // teardown of a connection that is no longer used
        }
        return true;
    }

    private static String joinAddress(String host, int port) {
        return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
    }

    private Response sendRequest(Map<String, Object> payload) {
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

    // -- cache: added for server 0.1.0-rc.3 -----------------------------------
    //
    // An older server answers each of these with an error naming the unknown
    // operation.

    /**
     * Write a value that answers as fresh for freshMs milliseconds and as stale after
     * that, until its TTL (if any) ends. Returns the instant, in Unix milliseconds,
     * until which the value is fresh.
     *
     * <p>{@code ttlMs} may be {@code null}.
     */
    public long cacheSetFresh(String namespace, String key, byte[] value, long freshMs, Long ttlMs,
                              String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("value", requireNotNull(value, "value"));
        body.put("fresh_ms", freshMs);
        body.put("ttl_ms", ttlMs);
        return Wire.num(Wire.json(request(Wire.op("Cache", "SetFresh", body), database)), "freshUntilMs");
    }

    public long cacheSetFresh(String namespace, String key, byte[] value, long freshMs, Long ttlMs) {
        return cacheSetFresh(namespace, key, value, freshMs, ttlMs, "main");
    }

    public long cacheSetFresh(String namespace, String key, byte[] value, long freshMs) {
        return cacheSetFresh(namespace, key, value, freshMs, null, "main");
    }

    /**
     * Read with stale-while-revalidate and stampede protection. The state is fresh,
     * stale (with refresh set for the one caller holding the refresh lease), missing (a
     * cached absence) or miss (with lease set for the one caller that should compute
     * the value).
     */
    public CacheFreshRead cacheGetFresh(String namespace, String key, String owner, long leaseMs,
                                        String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("owner", require(owner, "owner"));
        body.put("lease_ms", leaseMs);
        return CacheFreshRead.from(Wire.json(request(Wire.op("Cache", "GetFresh", body), database)));
    }

    public CacheFreshRead cacheGetFresh(String namespace, String key, String owner, long leaseMs) {
        return cacheGetFresh(namespace, key, owner, leaseMs, "main");
    }

    /**
     * Negative caching: remember for ttlMs milliseconds that the source has no value
     * for the key.
     */
    public void cacheSetMissing(String namespace, String key, long ttlMs, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("ttl_ms", ttlMs);
        request(Wire.op("Cache", "SetMissing", body), database);
    }

    public void cacheSetMissing(String namespace, String key, long ttlMs) {
        cacheSetMissing(namespace, key, ttlMs, "main");
    }

    /**
     * Cache warming: write the entries that are not already cached, leaving live values
     * alone.
     *
     * <p>{@code ttlMs} may be {@code null}.
     */
    public CacheWarmResult cacheWarm(String namespace, List<CacheEntry> entries, Long ttlMs, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("entries", Wire.wireList(requireNotNull(entries, "entries"), CacheEntry::wire, "entries"));
        body.put("ttl_ms", ttlMs);
        return CacheWarmResult.from(Wire.json(request(Wire.op("Cache", "Warm", body), database)));
    }

    public CacheWarmResult cacheWarm(String namespace, List<CacheEntry> entries, Long ttlMs) {
        return cacheWarm(namespace, entries, ttlMs, "main");
    }

    public CacheWarmResult cacheWarm(String namespace, List<CacheEntry> entries) {
        return cacheWarm(namespace, entries, null, "main");
    }

    /**
     * Read many values in one request. Answers positionally, one result per requested
     * key, with an absent value where the key is missing or expired. One round trip,
     * not a snapshot.
     */
    public List<Optional<byte[]>> cacheMGet(String namespace, List<String> keys, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        return Wire.cacheValues(request(Wire.op("Cache", "MGet", body), database));
    }

    public List<Optional<byte[]>> cacheMGet(String namespace, List<String> keys) {
        return cacheMGet(namespace, keys, "main");
    }

    /**
     * Write many values in one request, all with the same optional TTL, as one batch. A
     * repeated key is refused. Returns how many values were written.
     *
     * <p>{@code ttlMs} may be {@code null}.
     */
    public long cacheMSet(String namespace, List<CacheEntry> entries, Long ttlMs, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("entries", Wire.wireList(requireNotNull(entries, "entries"), CacheEntry::wire, "entries"));
        body.put("ttl_ms", ttlMs);
        return Wire.num(Wire.json(request(Wire.op("Cache", "MSet", body), database)), "set");
    }

    public long cacheMSet(String namespace, List<CacheEntry> entries, Long ttlMs) {
        return cacheMSet(namespace, entries, ttlMs, "main");
    }

    public long cacheMSet(String namespace, List<CacheEntry> entries) {
        return cacheMSet(namespace, entries, null, "main");
    }

    /**
     * Write a value under a condition, with explicit control over the TTL: the general
     * form of Set and SetNx. Returns false when the condition was not met, which is not
     * a failure.
     */
    public boolean cacheSetIf(String namespace, String key, byte[] value, CacheSetCondition condition,
                              CacheExpiry expiry, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("value", requireNotNull(value, "value"));
        body.put("condition", requireNotNull(condition, "condition").wire());
        body.put("expiry", requireNotNull(expiry, "expiry").wire());
        return Wire.bool(Wire.json(request(Wire.op("Cache", "SetIf", body), database)), "set");
    }

    public boolean cacheSetIf(String namespace, String key, byte[] value, CacheSetCondition condition,
                              CacheExpiry expiry) {
        return cacheSetIf(namespace, key, value, condition, expiry, "main");
    }

    /**
     * Add scored members to a sorted set, or re-score ones already there. A NaN score
     * is refused.
     */
    public CacheZAddResult cacheZAdd(String namespace, String key, List<ScoredMember> members,
                                     String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("members", Wire.wireList(requireNotNull(members, "members"), ScoredMember::wire, "members"));
        return CacheZAddResult.from(Wire.json(request(Wire.op("Cache", "ZAdd", body), database)));
    }

    public CacheZAddResult cacheZAdd(String namespace, String key, List<ScoredMember> members) {
        return cacheZAdd(namespace, key, members, "main");
    }

    /**
     * Remove members from a sorted set. Returns how many were present.
     */
    public long cacheZRem(String namespace, String key, List<byte[]> members, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("members", requireNonEmpty(members, "members"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "ZRem", body), database)), "removed");
    }

    public long cacheZRem(String namespace, String key, List<byte[]> members) {
        return cacheZRem(namespace, key, members, "main");
    }

    /**
     * The score of one member, or absent when it is not in the set.
     */
    public OptionalDouble cacheZScore(String namespace, String key, byte[] member, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("member", requireNotNull(member, "member"));
        return Wire.optDbl(Wire.json(request(Wire.op("Cache", "ZScore", body), database)), "score");
    }

    public OptionalDouble cacheZScore(String namespace, String key, byte[] member) {
        return cacheZScore(namespace, key, member, "main");
    }

    /**
     * How many members the sorted set holds.
     */
    public long cacheZCard(String namespace, String key, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "ZCard", body), database)), "cardinality");
    }

    public long cacheZCard(String namespace, String key) {
        return cacheZCard(namespace, key, "main");
    }

    /**
     * A member's position in the ordering, or absent when it is not in the set. With
     * rev, counting starts from the high-scoring end.
     */
    public OptionalLong cacheZRank(String namespace, String key, byte[] member, boolean rev, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("member", requireNotNull(member, "member"));
        body.put("rev", rev);
        return Wire.optLong(Wire.json(request(Wire.op("Cache", "ZRank", body), database)), "rank");
    }

    public OptionalLong cacheZRank(String namespace, String key, byte[] member, boolean rev) {
        return cacheZRank(namespace, key, member, rev, "main");
    }

    /**
     * Members by rank, start to stop inclusive. Negative indices count from the end, so
     * 0 to -1 is the whole set. Each member carries its score only when withScores is
     * set.
     */
    public List<CacheZMember> cacheZRange(String namespace, String key, long start, long stop, boolean rev,
                                          boolean withScores, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("start", start);
        body.put("stop", stop);
        body.put("rev", rev);
        body.put("with_scores", withScores);
        return CacheZMember.decode(Wire.list(Wire.json(
                request(Wire.op("Cache", "ZRange", body), database)), "members"));
    }

    public List<CacheZMember> cacheZRange(String namespace, String key, long start, long stop, boolean rev,
                                          boolean withScores) {
        return cacheZRange(namespace, key, start, stop, rev, withScores, "main");
    }

    /**
     * Members by score, between two bounds. The limit offset and count are applied
     * after the range is selected; give both or neither.
     *
     * <p>Each of {@code limitOffset}, {@code limitCount} may be {@code null}.
     */
    public List<CacheZMember> cacheZRangeByScore(String namespace, String key, ScoreBound min,
                                                 ScoreBound max, boolean rev, boolean withScores,
                                                 Long limitOffset, Long limitCount, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("min", requireNotNull(min, "min").wire());
        body.put("max", requireNotNull(max, "max").wire());
        body.put("rev", rev);
        body.put("with_scores", withScores);
        body.put("limit", Wire.pair(limitOffset, limitCount, "limit"));
        return CacheZMember.decode(Wire.list(Wire.json(
                request(Wire.op("Cache", "ZRangeByScore", body), database)), "members"));
    }

    public List<CacheZMember> cacheZRangeByScore(String namespace, String key, ScoreBound min,
                                                 ScoreBound max, boolean rev, boolean withScores,
                                                 Long limitOffset, Long limitCount) {
        return cacheZRangeByScore(namespace, key, min, max, rev, withScores, limitOffset, limitCount, "main");
    }

    public List<CacheZMember> cacheZRangeByScore(String namespace, String key, ScoreBound min,
                                                 ScoreBound max, boolean rev, boolean withScores) {
        return cacheZRangeByScore(namespace, key, min, max, rev, withScores, null, null, "main");
    }

    /**
     * Add by to a member's score, creating the member at by when it is absent. Returns
     * the resulting score.
     */
    public double cacheZIncrBy(String namespace, String key, byte[] member, double by, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("member", requireNotNull(member, "member"));
        body.put("by", by);
        return Wire.dbl(Wire.json(request(Wire.op("Cache", "ZIncrBy", body), database)), "score");
    }

    public double cacheZIncrBy(String namespace, String key, byte[] member, double by) {
        return cacheZIncrBy(namespace, key, member, by, "main");
    }

    /**
     * Take a lease on a lock, or refresh one already held by owner. The fencing token
     * strictly increases with every acquisition and is never reused; acquiring a lock
     * you already hold returns the same token with a refreshed lease.
     */
    public CacheLock cacheLockAcquire(String namespace, String key, String owner, long ttlMs,
                                      String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("owner", require(owner, "owner"));
        body.put("ttl_ms", ttlMs);
        return CacheLock.from(Wire.json(request(Wire.op("Cache", "LockAcquire", body), database)));
    }

    public CacheLock cacheLockAcquire(String namespace, String key, String owner, long ttlMs) {
        return cacheLockAcquire(namespace, key, owner, ttlMs, "main");
    }

    /**
     * Extend a lease the caller still holds. Refused once the lease has lapsed, even if
     * nobody else has taken the lock.
     */
    public CacheLockRenewal cacheLockRenew(String namespace, String key, String owner, long ttlMs,
                                           String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("owner", require(owner, "owner"));
        body.put("ttl_ms", ttlMs);
        return CacheLockRenewal.from(Wire.json(request(Wire.op("Cache", "LockRenew", body), database)));
    }

    public CacheLockRenewal cacheLockRenew(String namespace, String key, String owner, long ttlMs) {
        return cacheLockRenew(namespace, key, owner, ttlMs, "main");
    }

    /**
     * Give up a lock. Only the current, live holder can release; returns whether this
     * call released it.
     */
    public boolean cacheLockRelease(String namespace, String key, String owner, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("owner", require(owner, "owner"));
        return Wire.bool(Wire.json(request(Wire.op("Cache", "LockRelease", body), database)), "released");
    }

    public boolean cacheLockRelease(String namespace, String key, String owner) {
        return cacheLockRelease(namespace, key, owner, "main");
    }

    /**
     * Set one bit of a string value, growing it with zero bytes if needed. Bits are
     * numbered from the most significant bit of byte 0. Returns the bit's previous
     * value.
     */
    public boolean cacheSetBit(String namespace, String key, long offset, boolean value, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("offset", offset);
        body.put("value", value);
        return Wire.num(Wire.json(request(Wire.op("Cache", "SetBit", body), database)), "previous") != 0;
    }

    public boolean cacheSetBit(String namespace, String key, long offset, boolean value) {
        return cacheSetBit(namespace, key, offset, value, "main");
    }

    /**
     * Read one bit. Past the end of the value, and on a missing key, every bit is 0.
     */
    public boolean cacheGetBit(String namespace, String key, long offset, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("offset", offset);
        return Wire.num(Wire.json(request(Wire.op("Cache", "GetBit", body), database)), "bit") != 0;
    }

    public boolean cacheGetBit(String namespace, String key, long offset) {
        return cacheGetBit(namespace, key, offset, "main");
    }

    /**
     * Count the set bits, optionally within a byte range (not a bit range) with signed
     * indices; give both ends or neither.
     *
     * <p>Each of {@code rangeStart}, {@code rangeEnd} may be {@code null}.
     */
    public long cacheBitCount(String namespace, String key, Long rangeStart, Long rangeEnd, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("range", Wire.pair(rangeStart, rangeEnd, "range"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "BitCount", body), database)), "count");
    }

    public long cacheBitCount(String namespace, String key, Long rangeStart, Long rangeEnd) {
        return cacheBitCount(namespace, key, rangeStart, rangeEnd, "main");
    }

    public long cacheBitCount(String namespace, String key) {
        return cacheBitCount(namespace, key, null, null, "main");
    }

    /**
     * The position of the first bit equal to bit, optionally within a byte range, or -1
     * when there is none.
     *
     * <p>Each of {@code rangeStart}, {@code rangeEnd} may be {@code null}.
     */
    public long cacheBitPos(String namespace, String key, boolean bit, Long rangeStart, Long rangeEnd,
                            String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("bit", bit);
        body.put("range", Wire.pair(rangeStart, rangeEnd, "range"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "BitPos", body), database)), "position");
    }

    public long cacheBitPos(String namespace, String key, boolean bit, Long rangeStart, Long rangeEnd) {
        return cacheBitPos(namespace, key, bit, rangeStart, rangeEnd, "main");
    }

    public long cacheBitPos(String namespace, String key, boolean bit) {
        return cacheBitPos(namespace, key, bit, null, null, "main");
    }

    /**
     * Combine several values bitwise and store the result at dest. Shorter sources are
     * zero-padded to the longest. Returns the result's size in bytes.
     */
    public long cacheBitOp(String namespace, CacheBitOp op, String dest, List<String> keys, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("op", requireNotNull(op, "op").wire());
        body.put("dest", require(dest, "dest"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "BitOp", body), database)), "length");
    }

    public long cacheBitOp(String namespace, CacheBitOp op, String dest, List<String> keys) {
        return cacheBitOp(namespace, op, dest, keys, "main");
    }

    /**
     * Add elements to a HyperLogLog, creating it when absent. Returns whether the
     * estimate may have moved.
     */
    public boolean cachePfAdd(String namespace, String key, List<byte[]> elements, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("elements", requireNonEmpty(elements, "elements"));
        return Wire.bool(Wire.json(request(Wire.op("Cache", "PfAdd", body), database)), "changed");
    }

    public boolean cachePfAdd(String namespace, String key, List<byte[]> elements) {
        return cachePfAdd(namespace, key, elements, "main");
    }

    /**
     * Estimated number of distinct elements. With several keys, the estimate is of
     * their union, computed without modifying any of them.
     */
    public long cachePfCount(String namespace, List<String> keys, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "PfCount", body), database)), "count");
    }

    public long cachePfCount(String namespace, List<String> keys) {
        return cachePfCount(namespace, keys, "main");
    }

    /**
     * Merge several HyperLogLogs into dest, which is included as a source when it
     * already exists.
     */
    public void cachePfMerge(String namespace, String dest, List<String> sources, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("dest", require(dest, "dest"));
        body.put("sources", Wire.wireList(requireNotNull(sources, "sources"), x -> x, "sources"));
        request(Wire.op("Cache", "PfMerge", body), database);
    }

    public void cachePfMerge(String namespace, String dest, List<String> sources) {
        cachePfMerge(namespace, dest, sources, "main");
    }

    /**
     * Atomically pop an element from one end of source and push it onto one end of
     * destination. Returns the element, or absent when source is empty. The two keys
     * may be the same, which rotates the list.
     */
    public Optional<byte[]> cacheLMove(String namespace, String source, String destination,
                                       CacheListEnd from, CacheListEnd to, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("source", require(source, "source"));
        body.put("destination", require(destination, "destination"));
        body.put("from", requireNotNull(from, "from").wire());
        body.put("to", requireNotNull(to, "to").wire());
        return Wire.cacheValue(request(Wire.op("Cache", "LMove", body), database));
    }

    public Optional<byte[]> cacheLMove(String namespace, String source, String destination,
                                       CacheListEnd from, CacheListEnd to) {
        return cacheLMove(namespace, source, destination, from, to, "main");
    }

    /**
     * Set the absolute instant an existing live key dies, in Unix epoch milliseconds. A
     * deadline already passed deletes the key; a missing or expired key is not created.
     */
    public CacheExpireAtResult cacheExpireAt(String namespace, String key, long atMs, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("at_ms", atMs);
        return CacheExpireAtResult.from(Wire.json(request(Wire.op("Cache", "ExpireAt", body), database)));
    }

    public CacheExpireAtResult cacheExpireAt(String namespace, String key, long atMs) {
        return cacheExpireAt(namespace, key, atMs, "main");
    }

    /**
     * What this node's cache is doing: lookup hits and misses, the memory budget and
     * the eviction policy. Node-wide, not per database. Returned as the server's own
     * JSON object.
     */
    public Map<String, Object> cacheStats(String database) {
        return Wire.json(request(Wire.unitOp("Cache", "Stats"), database));
    }

    public Map<String, Object> cacheStats() {
        return cacheStats("main");
    }

    /**
     * Delete every cache key in this database, across every namespace. Returns how many
     * keys were removed.
     */
    public long cacheFlushDatabase(String database) {
        return Wire.num(Wire.json(request(Wire.unitOp("Cache", "FlushDatabase"), database)), "cleared");
    }

    public long cacheFlushDatabase() {
        return cacheFlushDatabase("main");
    }

    /**
     * Concatenate bytes onto what a key already holds and return the new length. A
     * missing key is created; an existing key keeps its expiry.
     */
    public long cacheAppend(String namespace, String key, byte[] value, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("value", requireNotNull(value, "value"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "Append", body), database)), "length");
    }

    public long cacheAppend(String namespace, String key, byte[] value) {
        return cacheAppend(namespace, key, value, "main");
    }

    /**
     * Write only if the key still holds what the caller last read. An absent expected
     * requires the key to be absent, and an absent value deletes it. On a mismatch
     * nothing is written and the result carries the value actually present when it is
     * valid UTF-8.
     *
     * <p>Each of {@code expected}, {@code value}, {@code ttlMs} may be {@code null}.
     */
    public CacheSwapResult cacheCompareAndSwap(String namespace, String key, byte[] expected, byte[] value,
                                               Long ttlMs, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("expected", expected);
        body.put("value", value);
        body.put("ttl_ms", ttlMs);
        return CacheSwapResult.from(Wire.json(request(Wire.op("Cache", "CompareAndSwap", body), database)));
    }

    public CacheSwapResult cacheCompareAndSwap(String namespace, String key, byte[] expected, byte[] value,
                                               Long ttlMs) {
        return cacheCompareAndSwap(namespace, key, expected, value, ttlMs, "main");
    }

    public CacheSwapResult cacheCompareAndSwap(String namespace, String key, byte[] expected, byte[] value) {
        return cacheCompareAndSwap(namespace, key, expected, value, null, "main");
    }

    /**
     * Read a key and delete it in one atomic step. Returns the value that was removed,
     * or absent.
     */
    public Optional<byte[]> cacheGetDel(String namespace, String key, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        return Wire.cacheValue(request(Wire.op("Cache", "GetDel", body), database));
    }

    public Optional<byte[]> cacheGetDel(String namespace, String key) {
        return cacheGetDel(namespace, key, "main");
    }

    /**
     * Write a value and return the one it replaced, atomically. Any TTL on the old
     * value is dropped; pass ttlMs to set one.
     *
     * <p>{@code ttlMs} may be {@code null}.
     */
    public Optional<byte[]> cacheGetSet(String namespace, String key, byte[] value, Long ttlMs,
                                        String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("value", requireNotNull(value, "value"));
        body.put("ttl_ms", ttlMs);
        return Wire.cacheValue(request(Wire.op("Cache", "GetSet", body), database));
    }

    public Optional<byte[]> cacheGetSet(String namespace, String key, byte[] value, Long ttlMs) {
        return cacheGetSet(namespace, key, value, ttlMs, "main");
    }

    public Optional<byte[]> cacheGetSet(String namespace, String key, byte[] value) {
        return cacheGetSet(namespace, key, value, null, "main");
    }

    /**
     * Read a key and change its expiry in the same atomic step. The value itself is
     * never modified.
     */
    public Optional<byte[]> cacheGetEx(String namespace, String key, CacheExpiry expiry, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("expiry", requireNotNull(expiry, "expiry").wire());
        return Wire.cacheValue(request(Wire.op("Cache", "GetEx", body), database));
    }

    public Optional<byte[]> cacheGetEx(String namespace, String key, CacheExpiry expiry) {
        return cacheGetEx(namespace, key, expiry, "main");
    }

    /**
     * Keep only the inclusive index range of a list, dropping everything outside it.
     * Negative indices count from the end; a range selecting nothing deletes the key.
     */
    public CacheTrimResult cacheLTrim(String namespace, String key, long start, long stop, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("start", start);
        body.put("stop", stop);
        return CacheTrimResult.from(Wire.json(request(Wire.op("Cache", "LTrim", body), database)));
    }

    public CacheTrimResult cacheLTrim(String namespace, String key, long start, long stop) {
        return cacheLTrim(namespace, key, start, stop, "main");
    }

    /**
     * Every member of every named set, without duplicates. A missing key is the empty
     * set.
     */
    public List<byte[]> cacheSUnion(String namespace, List<String> keys, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        return Wire.byteLists(Wire.json(request(Wire.op("Cache", "SUnion", body), database)), "members");
    }

    public List<byte[]> cacheSUnion(String namespace, List<String> keys) {
        return cacheSUnion(namespace, keys, "main");
    }

    /**
     * The members present in every named set. A missing key is the empty set, so the
     * intersection is then empty.
     */
    public List<byte[]> cacheSInter(String namespace, List<String> keys, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        return Wire.byteLists(Wire.json(request(Wire.op("Cache", "SInter", body), database)), "members");
    }

    public List<byte[]> cacheSInter(String namespace, List<String> keys) {
        return cacheSInter(namespace, keys, "main");
    }

    /**
     * The members of the first set that are in none of the rest.
     */
    public List<byte[]> cacheSDiff(String namespace, List<String> keys, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        return Wire.byteLists(Wire.json(request(Wire.op("Cache", "SDiff", body), database)), "members");
    }

    public List<byte[]> cacheSDiff(String namespace, List<String> keys) {
        return cacheSDiff(namespace, keys, "main");
    }

    /**
     * Random members of a set, without removing them. An absent count gives at most one
     * member, a positive count up to that many distinct members, and a negative count
     * exactly that many with repeats allowed.
     *
     * <p>{@code count} may be {@code null}.
     */
    public List<byte[]> cacheSRandMember(String namespace, String key, Long count, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("count", count);
        return Wire.byteLists(Wire.json(request(Wire.op("Cache", "SRandMember", body), database)), "members");
    }

    public List<byte[]> cacheSRandMember(String namespace, String key, Long count) {
        return cacheSRandMember(namespace, key, count, "main");
    }

    public List<byte[]> cacheSRandMember(String namespace, String key) {
        return cacheSRandMember(namespace, key, null, "main");
    }

    /**
     * Add by to one hash field, atomically, and return the new value. A missing field
     * starts at zero.
     */
    public long cacheHIncrBy(String namespace, String key, byte[] field, long by, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("key", require(key, "key"));
        body.put("field", requireNotNull(field, "field"));
        body.put("by", by);
        return Wire.num(Wire.json(request(Wire.op("Cache", "HIncrBy", body), database)), "value");
    }

    public long cacheHIncrBy(String namespace, String key, byte[] field, long by) {
        return cacheHIncrBy(namespace, key, field, by, "main");
    }

    /**
     * Run commands in order in one round trip, each exactly as if it had been sent
     * alone. A failing command does not stop the rest; each has its own result.
     */
    public List<CacheOpResult> cachePipeline(List<CacheCommand> ops, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ops", Wire.wireList(requireNotNull(ops, "ops"), CacheCommand::wire, "ops"));
        return CacheOpResult.decode(Wire.list(Wire.json(
                request(Wire.op("Cache", "Pipeline", body), database)), "results"));
    }

    public List<CacheOpResult> cachePipeline(List<CacheCommand> ops) {
        return cachePipeline(ops, "main");
    }

    /**
     * Run commands in order holding the cache write lock throughout, so no other writer
     * interleaves. Every command is checked before any runs; after that there is no
     * rollback, and a command that fails is reported while the rest still run.
     */
    public List<CacheOpResult> cacheTransaction(List<CacheCommand> ops, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ops", Wire.wireList(requireNotNull(ops, "ops"), CacheCommand::wire, "ops"));
        return CacheOpResult.decode(Wire.list(Wire.json(
                request(Wire.op("Cache", "Transaction", body), database)), "results"));
    }

    public List<CacheOpResult> cacheTransaction(List<CacheCommand> ops) {
        return cacheTransaction(ops, "main");
    }

    /**
     * Store a script: commands run as one transaction, in which any string or value
     * written $1, $2 and so on is replaced by that argument at run time. Loading the
     * same name again replaces it. Returns how many commands the script holds.
     */
    public long cacheScriptLoad(String name, List<CacheCommand> ops, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", require(name, "name"));
        body.put("ops", Wire.wireList(requireNotNull(ops, "ops"), CacheCommand::wire, "ops"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "ScriptLoad", body), database)), "ops");
    }

    public long cacheScriptLoad(String name, List<CacheCommand> ops) {
        return cacheScriptLoad(name, ops, "main");
    }

    /**
     * Run a stored script with the given arguments as one transaction.
     */
    public List<CacheOpResult> cacheScriptRun(String name, List<String> args, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", require(name, "name"));
        body.put("args", Wire.wireList(args, x -> x, "args"));
        return CacheOpResult.decode(Wire.list(Wire.json(
                request(Wire.op("Cache", "ScriptRun", body), database)), "results"));
    }

    public List<CacheOpResult> cacheScriptRun(String name, List<String> args) {
        return cacheScriptRun(name, args, "main");
    }

    public List<CacheOpResult> cacheScriptRun(String name) {
        return cacheScriptRun(name, List.of(), "main");
    }

    /**
     * The most frequently addressed keys on this node since it started, with estimated
     * counts. The server refuses this unless its cache hot_keys setting is above zero.
     * Returned as the server's own JSON, undecoded.
     *
     * <p>{@code limit} may be {@code null}.
     */
    public Object cacheHotKeys(Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", limit);
        return Wire.jsonOrNull(request(Wire.op("Cache", "HotKeys", body), database));
    }

    public Object cacheHotKeys(Integer limit) {
        return cacheHotKeys(limit, "main");
    }

    public Object cacheHotKeys() {
        return cacheHotKeys(null, "main");
    }

    /**
     * Delete every expired cache key in this database now, rather than leaving it to
     * compaction. Returns how many were purged.
     */
    public long cachePurgeExpired(String database) {
        return Wire.num(Wire.json(request(Wire.unitOp("Cache", "PurgeExpired"), database)), "purged");
    }

    public long cachePurgeExpired() {
        return cachePurgeExpired("main");
    }

    /**
     * Pop the head of the first non-empty list among keys, waiting up to timeoutMs
     * milliseconds for one to receive an element. Absent when the wait ran out. A
     * connection read timeout must be longer than the wait.
     */
    public Optional<CachePopped> cacheBLPop(String namespace, List<String> keys, long timeoutMs,
                                            String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        body.put("timeout_ms", timeoutMs);
        return CachePopped.decode(Wire.cacheValues(request(Wire.op("Cache", "BLPop", body), database)));
    }

    public Optional<CachePopped> cacheBLPop(String namespace, List<String> keys, long timeoutMs) {
        return cacheBLPop(namespace, keys, timeoutMs, "main");
    }

    /**
     * Pop the tail of the first non-empty list among keys, waiting up to timeoutMs
     * milliseconds for one to receive an element. Absent when the wait ran out. A
     * connection read timeout must be longer than the wait.
     */
    public Optional<CachePopped> cacheBRPop(String namespace, List<String> keys, long timeoutMs,
                                            String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("keys", Wire.wireList(requireNotNull(keys, "keys"), x -> x, "keys"));
        body.put("timeout_ms", timeoutMs);
        return CachePopped.decode(Wire.cacheValues(request(Wire.op("Cache", "BRPop", body), database)));
    }

    public Optional<CachePopped> cacheBRPop(String namespace, List<String> keys, long timeoutMs) {
        return cacheBRPop(namespace, keys, timeoutMs, "main");
    }

    /**
     * Cap a namespace at maxKeys live keys and maxBytes of values. A write that would
     * pass either is refused. Both absent removes the limit.
     *
     * <p>Each of {@code maxKeys}, {@code maxBytes} may be {@code null}.
     */
    public CacheNamespaceLimit cacheSetNamespaceLimit(String namespace, Long maxKeys, Long maxBytes,
                                                      String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        body.put("max_keys", maxKeys);
        body.put("max_bytes", maxBytes);
        return CacheNamespaceLimit.from(Wire.json(
                request(Wire.op("Cache", "SetNamespaceLimit", body), database)));
    }

    public CacheNamespaceLimit cacheSetNamespaceLimit(String namespace, Long maxKeys, Long maxBytes) {
        return cacheSetNamespaceLimit(namespace, maxKeys, maxBytes, "main");
    }

    public CacheNamespaceLimit cacheSetNamespaceLimit(String namespace) {
        return cacheSetNamespaceLimit(namespace, null, null, "main");
    }

    /**
     * A namespace's limit and what it holds now.
     */
    public CacheNamespaceLimit cacheGetNamespaceLimit(String namespace, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", require(namespace, "namespace"));
        return CacheNamespaceLimit.from(Wire.json(
                request(Wire.op("Cache", "GetNamespaceLimit", body), database)));
    }

    public CacheNamespaceLimit cacheGetNamespaceLimit(String namespace) {
        return cacheGetNamespaceLimit(namespace, "main");
    }

    /**
     * Append a message to a channel (Pub/Sub). Returns the id the message was given.
     */
    public long cachePublish(String channel, String message, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", require(channel, "channel"));
        body.put("message", require(message, "message"));
        return Wire.num(Wire.json(request(Wire.op("Cache", "Publish", body), database)), "id");
    }

    public long cachePublish(String channel, String message) {
        return cachePublish(channel, message, "main");
    }

    /**
     * The messages after id after on the given channels. A trailing * in a channel name
     * is a pattern; the __keyspace__ and __keyevent__ prefixes turn keyspace
     * notifications on while polled.
     */
    public CachePollResult cachePoll(List<String> channels, long after, int limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channels", Wire.wireList(requireNotNull(channels, "channels"), x -> x, "channels"));
        body.put("after", after);
        body.put("limit", limit);
        return CachePollResult.from(Wire.json(request(Wire.op("Cache", "Poll", body), database)));
    }

    public CachePollResult cachePoll(List<String> channels, long after, int limit) {
        return cachePoll(channels, after, limit, "main");
    }

    public CachePollResult cachePoll(List<String> channels) {
        return cachePoll(channels, 0L, 100, "main");
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

    // -- document: added for server 0.1.0-rc.3 --------------------------------
    //
    // An older server answers each of these with an error naming the unknown
    // operation.

    /**
     * Swap a document's whole body for a new one: after a replace the stored body is
     * exactly the document given, plus its id. With upsert, a missing document is
     * created rather than refused. expectedVersion applies the write only if the
     * document's version is still that (a versioned collection only).
     *
     * <p>{@code expectedVersion} may be {@code null}.
     */
    public DocumentReplaceResult documentReplace(String collection, String id,
                                                 Map<String, Object> document, boolean upsert,
                                                 ReturnDocument returnDocument, Long expectedVersion,
                                                 String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("id", require(id, "id"));
        body.put("document", requireNotNull(document, "document"));
        body.put("upsert", upsert);
        body.put("return", (returnDocument == null ? ReturnDocument.NONE : returnDocument).wire());
        body.put("expected_version", expectedVersion);
        return DocumentReplaceResult.from(Wire.json(request(Wire.op("Document", "Replace", body), database)));
    }

    public DocumentReplaceResult documentReplace(String collection, String id,
                                                 Map<String, Object> document, boolean upsert,
                                                 ReturnDocument returnDocument, Long expectedVersion) {
        return documentReplace(collection, id, document, upsert, returnDocument, expectedVersion, "main");
    }

    public DocumentReplaceResult documentReplace(String collection, String id, Map<String, Object> document) {
        return documentReplace(collection, id, document, false, ReturnDocument.NONE, null, "main");
    }

    /**
     * Insert several documents as one atomic batch: either every document lands or none
     * does. Each document's id is its own _id when it carries one and is generated
     * otherwise. Returns the ids, in order.
     */
    public List<String> documentInsertMany(String collection, List<Map<String, Object>> documents,
                                           String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("documents", Wire.wireList(requireNotNull(documents, "documents"), x -> x, "documents"));
        return Wire.strings(Wire.json(request(Wire.op("Document", "InsertMany", body), database)), "ids");
    }

    public List<String> documentInsertMany(String collection, List<Map<String, Object>> documents) {
        return documentInsertMany(collection, documents, "main");
    }

    /**
     * Delete every document matching the filter, in one atomic batch. Returns how many
     * were deleted.
     */
    public long documentDeleteMany(String collection, DocumentFilter filter, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", requireNotNull(filter, "filter").wire());
        return Wire.num(Wire.json(request(Wire.op("Document", "DeleteMany", body), database)), "deleted");
    }

    public long documentDeleteMany(String collection, DocumentFilter filter) {
        return documentDeleteMany(collection, filter, "main");
    }

    /**
     * Move a collection, with its documents and indexes, to a new name in one atomic
     * batch. Refused when the new name already exists.
     */
    public void documentRenameCollection(String collection, String to, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("to", require(to, "to"));
        request(Wire.op("Document", "RenameCollection", body), database);
    }

    public void documentRenameCollection(String collection, String to) {
        documentRenameCollection(collection, to, "main");
    }

    /**
     * Delete the documents a TTL index says have expired, now.
     */
    public DocumentPurgeResult documentPurgeExpired(String collection, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        return DocumentPurgeResult.from(Wire.json(
                request(Wire.op("Document", "PurgeExpired", body), database)));
    }

    public DocumentPurgeResult documentPurgeExpired(String collection) {
        return documentPurgeExpired(collection, "main");
    }

    /**
     * Run a find and describe how it ran instead of returning its documents: the plan,
     * the index, why it scanned, the estimate, records examined and returned, and the
     * time taken. Returned as the server's own JSON object.
     *
     * <p>Each of {@code limit}, {@code skip} may be {@code null}.
     */
    public Map<String, Object> documentExplain(String collection, DocumentFilter filter, Integer limit,
                                               List<AggregateStage.SortKey> sort, Integer skip,
                                               String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", requireNotNull(filter, "filter").wire());
        body.put("limit", limit);
        body.put("sort", Wire.wireList(sort, AggregateStage.SortKey::wire, "sort"));
        body.put("skip", skip);
        return Wire.json(request(Wire.op("Document", "Explain", body), database));
    }

    public Map<String, Object> documentExplain(String collection, DocumentFilter filter, Integer limit,
                                               List<AggregateStage.SortKey> sort, Integer skip) {
        return documentExplain(collection, filter, limit, sort, skip, "main");
    }

    public Map<String, Object> documentExplain(String collection, DocumentFilter filter) {
        return documentExplain(collection, filter, null, List.of(), null, "main");
    }

    /**
     * Open a transaction spanning requests and return its id. Each later request that
     * carries the id reads and writes inside it, seeing its own writes and nobody
     * else's since it began. Snapshot isolation, or serializable with its reads
     * validated at commit.
     */
    public String documentBeginTransaction(boolean serializable, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serializable", serializable);
        return Wire.str(Wire.json(request(Wire.op("Document", "BeginTransaction", body), database)), "txn");
    }

    public String documentBeginTransaction(boolean serializable) {
        return documentBeginTransaction(serializable, "main");
    }

    public String documentBeginTransaction() {
        return documentBeginTransaction(false, "main");
    }

    /**
     * Commit a transaction: every write lands as one atomic batch, or a conflict is
     * returned and nothing lands. Returns how many writes were applied.
     */
    public long documentCommitTransaction(String txn, String database) {
        try {
            return Wire.num(Wire.json(request(Wire.unitOp("Document", "CommitTransaction"), database, require(txn, "txn"))), "writes");
        } finally {
            if (txn != null && txn.equals(requestTxn)) {
                requestTxn = null;
            }
        }
    }

    public long documentCommitTransaction(String txn) {
        return documentCommitTransaction(txn, "main");
    }

    /**
     * Discard a transaction and everything written inside it.
     */
    public void documentRollbackTransaction(String txn, String database) {
        try {
            request(Wire.unitOp("Document", "RollbackTransaction"), database, require(txn, "txn"));
        } finally {
            if (txn != null && txn.equals(requestTxn)) {
                requestTxn = null;
            }
        }
    }

    public void documentRollbackTransaction(String txn) {
        documentRollbackTransaction(txn, "main");
    }

    /**
     * Rebuild one index from the stored documents; its entries are replaced in one
     * batch.
     */
    public DocumentIndexRebuild documentRebuildIndex(String collection, String indexName, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("index_name", require(indexName, "index_name"));
        return DocumentIndexRebuild.from(Wire.json(
                request(Wire.op("Document", "RebuildIndex", body), database)));
    }

    public DocumentIndexRebuild documentRebuildIndex(String collection, String indexName) {
        return documentRebuildIndex(collection, indexName, "main");
    }

    /**
     * Set a collection's options, replacing any it had. Refused when an existing
     * document would break the new schema. Returns how many stored documents were
     * checked.
     */
    public long documentSetCollectionOptions(String collection, CollectionOptions options, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("options", requireNotNull(options, "options").wire());
        return Wire.num(Wire.json(
                request(Wire.op("Document", "SetCollectionOptions", body), database)), "documents_checked");
    }

    public long documentSetCollectionOptions(String collection, CollectionOptions options) {
        return documentSetCollectionOptions(collection, options, "main");
    }

    /**
     * A collection's options: its schema, defaults and whether it keeps versions.
     */
    public CollectionOptions documentGetCollectionOptions(String collection, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        return CollectionOptions.from(Wire.obj(Wire.json(
                request(Wire.op("Document", "GetCollectionOptions", body), database)), "options"));
    }

    public CollectionOptions documentGetCollectionOptions(String collection) {
        return documentGetCollectionOptions(collection, "main");
    }

    /**
     * Many inserts, updates, replaces and deletes on one collection, applied in order
     * as one atomic batch: every operation lands or none does. A later operation sees
     * the effect of an earlier one on the same id.
     */
    public BulkWriteResult documentBulkWrite(String collection, List<BulkOp> ops, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("ops", Wire.wireList(requireNotNull(ops, "ops"), BulkOp::wire, "ops"));
        return BulkWriteResult.from(Wire.json(request(Wire.op("Document", "BulkWrite", body), database)));
    }

    public BulkWriteResult documentBulkWrite(String collection, List<BulkOp> ops) {
        return documentBulkWrite(collection, ops, "main");
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

    // -- vector: added for server 0.1.0-rc.3 ----------------------------------
    //
    // An older server answers each of these with an error naming the unknown
    // operation.

    /**
     * Embed texts with the server's built-in embedder (deterministic feature hashing:
     * lexical, no model) and return the vectors, one per text. Nothing is stored.
     */
    public List<float[]> vectorEmbed(List<String> texts, int dimension, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("texts", Wire.wireList(requireNotNull(texts, "texts"), x -> x, "texts"));
        body.put("dimension", dimension);
        List<float[]> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Vector", "Embed", body), database)), "vectors")) {
            out.add(Wire.floats(Map.of("vector", o), "vector"));
        }
        return out;
    }

    public List<float[]> vectorEmbed(List<String> texts, int dimension) {
        return vectorEmbed(texts, dimension, "main");
    }

    /**
     * Embed each text at the collection's dimension and upsert the vectors as one
     * atomic batch. The text is kept in the metadata under text. Returns how many were
     * written.
     */
    public long vectorUpsertText(String collection, List<VectorText> items, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("items", Wire.wireList(requireNotNull(items, "items"), VectorText::wire, "items"));
        return Wire.num(Wire.json(request(Wire.op("Vector", "UpsertText", body), database)), "upserted");
    }

    public long vectorUpsertText(String collection, List<VectorText> items) {
        return vectorUpsertText(collection, items, "main");
    }

    /**
     * Point an alias at a collection; an absent collection removes the alias. Every
     * operation naming the alias acts on the collection it points at.
     *
     * <p>{@code collection} may be {@code null}.
     */
    public void vectorSetAlias(String alias, String collection, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("alias", require(alias, "alias"));
        body.put("collection", collection);
        request(Wire.op("Vector", "SetAlias", body), database);
    }

    public void vectorSetAlias(String alias, String collection) {
        vectorSetAlias(alias, collection, "main");
    }

    /**
     * Every alias and the collection it names.
     */
    public List<VectorAlias> vectorListAliases(String database) {
        List<VectorAlias> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.unitOp("Vector", "ListAliases"), database)), "aliases")) {
            out.add(VectorAlias.from(Wire.asObject(o, "aliases")));
        }
        return out;
    }

    public List<VectorAlias> vectorListAliases() {
        return vectorListAliases("main");
    }

    /**
     * Declare a named vector on a collection: a second vector per item, with its own
     * dimension and metric.
     */
    public void vectorAddNamedVector(String collection, String name, int dimension, VectorMetric metric,
                                     String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("name", require(name, "name"));
        body.put("dimension", dimension);
        body.put("metric", requireNotNull(metric, "metric").wire());
        request(Wire.op("Vector", "AddNamedVector", body), database);
    }

    public void vectorAddNamedVector(String collection, String name, int dimension, VectorMetric metric) {
        vectorAddNamedVector(collection, name, dimension, metric, "main");
    }

    /**
     * Write an item's vectors by name (default is the collection's own), each with the
     * same metadata.
     *
     * <p>{@code metadata} may be {@code null}.
     */
    public void vectorUpsertNamed(String collection, String id, Map<String, float[]> vectors,
                                  Map<String, Object> metadata, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("id", require(id, "id"));
        body.put("vectors", Wire.vectorMap(requireNotNull(vectors, "vectors")));
        if (metadata != null) {
            body.put("metadata", metadata);
        }
        request(Wire.op("Vector", "UpsertNamed", body), database);
    }

    public void vectorUpsertNamed(String collection, String id, Map<String, float[]> vectors,
                                  Map<String, Object> metadata) {
        vectorUpsertNamed(collection, id, vectors, metadata, "main");
    }

    public void vectorUpsertNamed(String collection, String id, Map<String, float[]> vectors) {
        vectorUpsertNamed(collection, id, vectors, null, "main");
    }

    /**
     * Write many vectors in one request, as one atomic batch. Every item is validated
     * before any is written, and a repeated id is refused. Returns how many were
     * written.
     */
    public long vectorUpsertMany(String collection, List<VectorItem> items, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("items", Wire.wireList(requireNotNull(items, "items"), Wire::itemInput, "items"));
        return Wire.num(Wire.json(request(Wire.op("Vector", "UpsertMany", body), database)), "upserted");
    }

    public long vectorUpsertMany(String collection, List<VectorItem> items) {
        return vectorUpsertMany(collection, items, "main");
    }

    /**
     * Delete many vectors by id, as one atomic batch. Returns how many of the ids were
     * present.
     */
    public long vectorDeleteMany(String collection, List<String> ids, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("ids", Wire.wireList(requireNotNull(ids, "ids"), x -> x, "ids"));
        return Wire.num(Wire.json(request(Wire.op("Vector", "DeleteMany", body), database)), "deleted");
    }

    public long vectorDeleteMany(String collection, List<String> ids) {
        return vectorDeleteMany(collection, ids, "main");
    }

    /**
     * Run several query vectors against one collection in one request. Answers
     * positionally: one result list per query, in the order asked, each what a single
     * search with the same arguments returns.
     *
     * <p>{@code options} may be {@code null}.
     */
    public List<List<VectorMatch>> vectorSearchBatch(String collection, List<float[]> vectors, int topK,
                                                     VectorSearchOptions options, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("vectors", Wire.wireList(requireNotNull(vectors, "vectors"), Wire::vectorOf, "vectors"));
        body.put("top_k", topK);
        if (options != null) {
            options.addTo(body);
        }
        List<List<VectorMatch>> out = new ArrayList<>();
        for (Object batch : Wire.list(Wire.json(request(Wire.op("Vector", "SearchBatch", body), database)), "results")) {
            if (!(batch instanceof List<?> hits)) {
                throw new ProtocolException("batch result is not an array: " + Wire.describe(batch));
            }
            List<VectorMatch> matches = new ArrayList<>(hits.size());
            for (Object o : hits) {
                matches.add(VectorMatch.from(Wire.asObject(o, "search result")));
            }
            out.add(matches);
        }
        return out;
    }

    public List<List<VectorMatch>> vectorSearchBatch(String collection, List<float[]> vectors, int topK,
                                                     VectorSearchOptions options) {
        return vectorSearchBatch(collection, vectors, topK, options, "main");
    }

    public List<List<VectorMatch>> vectorSearchBatch(String collection, List<float[]> vectors, int topK) {
        return vectorSearchBatch(collection, vectors, topK, null, "main");
    }

    /**
     * Delete every vector whose metadata matches the filter, as one atomic batch. A
     * filter is required. Returns how many were deleted.
     */
    public long vectorDeleteByFilter(String collection, Map<String, Object> filter, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", requireNotNull(filter, "filter"));
        return Wire.num(Wire.json(request(Wire.op("Vector", "DeleteByFilter", body), database)), "deleted");
    }

    public long vectorDeleteByFilter(String collection, Map<String, Object> filter) {
        return vectorDeleteByFilter(collection, filter, "main");
    }

    /**
     * Merge top-level metadata fields into every vector matching the filter, as one
     * atomic batch; vectors are never touched. Returns how many were updated.
     */
    public long vectorUpdateByFilter(String collection, Map<String, Object> filter,
                                     Map<String, Object> set, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", requireNotNull(filter, "filter"));
        body.put("set", requireNotNull(set, "set"));
        return Wire.num(Wire.json(request(Wire.op("Vector", "UpdateByFilter", body), database)), "updated");
    }

    public long vectorUpdateByFilter(String collection, Map<String, Object> filter, Map<String, Object> set) {
        return vectorUpdateByFilter(collection, filter, set, "main");
    }

    /**
     * How many vectors a collection holds or, with a filter, how many match it. An
     * exact count, not an estimate.
     *
     * <p>{@code filter} may be {@code null}.
     */
    public long vectorCount(String collection, Map<String, Object> filter, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("filter", filter);
        return Wire.num(Wire.json(request(Wire.op("Vector", "Count", body), database)), "count");
    }

    public long vectorCount(String collection, Map<String, Object> filter) {
        return vectorCount(collection, filter, "main");
    }

    public long vectorCount(String collection) {
        return vectorCount(collection, null, "main");
    }

    /**
     * The partitions of a collection: each distinct value of the metadata field
     * (_namespace or _tenant) with its vector count.
     */
    public VectorPartitions vectorListPartitions(String collection, String field, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("field", field == null ? "_namespace" : field);
        return VectorPartitions.from(Wire.json(request(Wire.op("Vector", "ListPartitions", body), database)));
    }

    public VectorPartitions vectorListPartitions(String collection, String field) {
        return vectorListPartitions(collection, field, "main");
    }

    public VectorPartitions vectorListPartitions(String collection) {
        return vectorListPartitions(collection, "_namespace", "main");
    }

    /**
     * Rename a collection, moving its catalog record and every vector with it in one
     * atomic batch. Refused when the new name already exists.
     */
    public void vectorRenameCollection(String collection, String to, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("to", require(to, "to"));
        request(Wire.op("Vector", "RenameCollection", body), database);
    }

    public void vectorRenameCollection(String collection, String to) {
        vectorRenameCollection(collection, to, "main");
    }

    /**
     * Rebuild the collection's ANN index from its stored vectors and persist it,
     * clearing every tombstone. Returned as the server's own JSON object.
     */
    public Map<String, Object> vectorRebuildIndex(String collection, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        return Wire.json(request(Wire.op("Vector", "RebuildIndex", body), database));
    }

    public Map<String, Object> vectorRebuildIndex(String collection) {
        return vectorRebuildIndex(collection, "main");
    }

    /**
     * The shape of the collection's ANN index: layers, degrees, tombstones,
     * reachability and memory. Returned as the server's own JSON object, whose fields
     * depend on the kind of index.
     */
    public Map<String, Object> vectorIndexStats(String collection, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        return Wire.json(request(Wire.op("Vector", "IndexStats", body), database));
    }

    public Map<String, Object> vectorIndexStats(String collection) {
        return vectorIndexStats(collection, "main");
    }

    /**
     * Measure the ANN index's recall: sampled stored vectors are each searched through
     * the index and by an exact scan, and the overlap is reported. Returned as the
     * server's own JSON object.
     *
     * <p>Each of {@code sample}, {@code topK}, {@code efSearch} may be {@code null}.
     */
    public Map<String, Object> vectorMeasureRecall(String collection, Integer sample, Integer topK,
                                                   Integer efSearch, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("sample", sample);
        body.put("top_k", topK);
        body.put("ef_search", efSearch);
        return Wire.json(request(Wire.op("Vector", "MeasureRecall", body), database));
    }

    public Map<String, Object> vectorMeasureRecall(String collection, Integer sample, Integer topK,
                                                   Integer efSearch) {
        return vectorMeasureRecall(collection, sample, topK, efSearch, "main");
    }

    public Map<String, Object> vectorMeasureRecall(String collection) {
        return vectorMeasureRecall(collection, null, null, null, "main");
    }

    /**
     * Search with several query vectors and return one fused ranking.
     *
     * <p>{@code filter} may be {@code null}.
     */
    public List<VectorMatch> vectorSearchMulti(String collection, List<float[]> vectors, int topK,
                                               VectorFusion fusion, Map<String, Object> filter,
                                               String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("vectors", Wire.wireList(requireNotNull(vectors, "vectors"), Wire::vectorOf, "vectors"));
        body.put("top_k", topK);
        body.put("fusion", (fusion == null ? VectorFusion.RRF : fusion).wire());
        body.put("filter", filter);
        List<VectorMatch> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Vector", "SearchMulti", body), database)), "results")) {
            out.add(VectorMatch.from(Wire.asObject(o, "search result")));
        }
        return out;
    }

    public List<VectorMatch> vectorSearchMulti(String collection, List<float[]> vectors, int topK,
                                               VectorFusion fusion, Map<String, Object> filter) {
        return vectorSearchMulti(collection, vectors, topK, fusion, filter, "main");
    }

    public List<VectorMatch> vectorSearchMulti(String collection, List<float[]> vectors, int topK) {
        return vectorSearchMulti(collection, vectors, topK, VectorFusion.RRF, null, "main");
    }

    /**
     * Change a collection's configuration; give at least one setting. normalize can
     * only be set on an empty collection; m and efConstruction take effect at the next
     * index build, which this forces. Returns the configuration now in force as the
     * server's own JSON object.
     *
     * <p>Each of {@code normalize}, {@code m}, {@code efConstruction}, {@code
     * multiTenant} may be {@code null}.
     */
    public Map<String, Object> vectorSetCollectionConfig(String collection, Boolean normalize, Integer m,
                                                         Integer efConstruction, Boolean multiTenant,
                                                         String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("normalize", normalize);
        body.put("m", m);
        body.put("ef_construction", efConstruction);
        body.put("multi_tenant", multiTenant);
        return Wire.json(request(Wire.op("Vector", "SetCollectionConfig", body), database));
    }

    public Map<String, Object> vectorSetCollectionConfig(String collection, Boolean normalize, Integer m,
                                                         Integer efConstruction, Boolean multiTenant) {
        return vectorSetCollectionConfig(collection, normalize, m, efConstruction, multiTenant, "main");
    }

    public Map<String, Object> vectorSetCollectionConfig(String collection) {
        return vectorSetCollectionConfig(collection, null, null, null, null, "main");
    }

    /**
     * Copy a collection, with its configuration and every vector, to a new name.
     * Refused when the new name exists. Returns how many vectors were copied.
     */
    public long vectorCopyCollection(String collection, String to, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("to", require(to, "to"));
        return Wire.num(Wire.json(request(Wire.op("Vector", "CopyCollection", body), database)), "copied");
    }

    public long vectorCopyCollection(String collection, String to) {
        return vectorCopyCollection(collection, to, "main");
    }

    /**
     * Search with a query built from examples: the sum of the positive examples minus
     * the sum of the negative ones. Examples named by id are left out of the results
     * unless excludeExamples is false.
     *
     * <p>{@code options} may be {@code null}.
     */
    public List<VectorMatch> vectorSearchByExamples(String collection, List<VectorExample> positive,
                                                    int topK, List<VectorExample> negative,
                                                    boolean excludeExamples, VectorSearchOptions options,
                                                    String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("collection", require(collection, "collection"));
        body.put("positive", Wire.wireList(requireNotNull(positive, "positive"), VectorExample::wire, "positive"));
        body.put("negative", Wire.wireList(negative, VectorExample::wire, "negative"));
        body.put("top_k", topK);
        body.put("exclude_examples", excludeExamples);
        if (options != null) {
            options.addTo(body);
        }
        List<VectorMatch> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Vector", "SearchByExamples", body), database)), "results")) {
            out.add(VectorMatch.from(Wire.asObject(o, "search result")));
        }
        return out;
    }

    public List<VectorMatch> vectorSearchByExamples(String collection, List<VectorExample> positive,
                                                    int topK, List<VectorExample> negative,
                                                    boolean excludeExamples, VectorSearchOptions options) {
        return vectorSearchByExamples(collection, positive, topK, negative, excludeExamples, options, "main");
    }

    public List<VectorMatch> vectorSearchByExamples(String collection, List<VectorExample> positive,
                                                    int topK) {
        return vectorSearchByExamples(collection, positive, topK, List.of(), true, null, "main");
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

    // -- graph: added for server 0.1.0-rc.3 -----------------------------------
    //
    // An older server answers each of these with an error naming the unknown
    // operation.

    /**
     * Write many nodes and edges as one unit: every record lands or none does. Nodes
     * are written before edges, so an edge may join nodes created in the same batch.
     * Each record replaces any existing one with its id.
     */
    public GraphBatchResult graphAddMany(String graph, List<GraphNode> nodes, List<GraphEdge> edges,
                                         String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("nodes", Wire.wireList(requireNotNull(nodes, "nodes"), Wire::nodeInput, "nodes"));
        body.put("edges", Wire.wireList(edges, Wire::edgeInput, "edges"));
        return GraphBatchResult.from(Wire.json(request(Wire.op("Graph", "AddMany", body), database)));
    }

    public GraphBatchResult graphAddMany(String graph, List<GraphNode> nodes, List<GraphEdge> edges) {
        return graphAddMany(graph, nodes, edges, "main");
    }

    public GraphBatchResult graphAddMany(String graph, List<GraphNode> nodes) {
        return graphAddMany(graph, nodes, List.of(), "main");
    }

    /**
     * Delete many nodes and edges as one unit. An id that is not there is skipped. With
     * detach, every edge touching a removed node goes too.
     */
    public GraphRemoveResult graphRemoveMany(String graph, List<String> nodes, List<String> edges,
                                             boolean detach, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("nodes", Wire.wireList(requireNotNull(nodes, "nodes"), x -> x, "nodes"));
        body.put("edges", Wire.wireList(edges, x -> x, "edges"));
        body.put("detach", detach);
        return GraphRemoveResult.from(Wire.json(request(Wire.op("Graph", "RemoveMany", body), database)));
    }

    public GraphRemoveResult graphRemoveMany(String graph, List<String> nodes, List<String> edges,
                                             boolean detach) {
        return graphRemoveMany(graph, nodes, edges, detach, "main");
    }

    public GraphRemoveResult graphRemoveMany(String graph, List<String> nodes) {
        return graphRemoveMany(graph, nodes, List.of(), false, "main");
    }

    /**
     * Change an existing node in place and return it. properties is a JSON merge patch:
     * a key set to a value is set, a key set to null is removed, and a key left out is
     * left alone. Labels are removed before they are added.
     *
     * <p>{@code properties} may be {@code null}.
     */
    public GraphNode graphUpdateNode(String graph, String id, Map<String, Object> properties,
                                     List<String> addLabels, List<String> removeLabels, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("id", require(id, "id"));
        if (properties != null) {
            body.put("properties", properties);
        }
        body.put("add_labels", Wire.wireList(addLabels, x -> x, "add_labels"));
        body.put("remove_labels", Wire.wireList(removeLabels, x -> x, "remove_labels"));
        return GraphNode.from(Wire.json(request(Wire.op("Graph", "UpdateNode", body), database)));
    }

    public GraphNode graphUpdateNode(String graph, String id, Map<String, Object> properties,
                                     List<String> addLabels, List<String> removeLabels) {
        return graphUpdateNode(graph, id, properties, addLabels, removeLabels, "main");
    }

    public GraphNode graphUpdateNode(String graph, String id) {
        return graphUpdateNode(graph, id, null, List.of(), List.of(), "main");
    }

    /**
     * Change an existing edge in place and return it: properties merges as for a node,
     * and label, when given, changes the edge's type. The endpoints cannot change.
     *
     * <p>Each of {@code properties}, {@code label} may be {@code null}.
     */
    public GraphEdge graphUpdateEdge(String graph, String id, Map<String, Object> properties, String label,
                                     String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("id", require(id, "id"));
        if (properties != null) {
            body.put("properties", properties);
        }
        body.put("label", label);
        return GraphEdge.from(Wire.json(request(Wire.op("Graph", "UpdateEdge", body), database)));
    }

    public GraphEdge graphUpdateEdge(String graph, String id, Map<String, Object> properties, String label) {
        return graphUpdateEdge(graph, id, properties, label, "main");
    }

    public GraphEdge graphUpdateEdge(String graph, String id) {
        return graphUpdateEdge(graph, id, null, null, "main");
    }

    /**
     * Copy a graph, with its nodes, edges and schema, to a new name. Refused when the
     * new name exists.
     */
    public GraphBatchResult graphCopy(String graph, String to, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("to", require(to, "to"));
        return GraphBatchResult.from(Wire.json(request(Wire.op("Graph", "CopyGraph", body), database)));
    }

    public GraphBatchResult graphCopy(String graph, String to) {
        return graphCopy(graph, to, "main");
    }

    /**
     * Bounded depth-first walk from start: the nodes within maxDepth hops, in depth-
     * first preorder, each with the shortest depth found. The server clamps maxDepth
     * and limit.
     *
     * <p>Each of {@code label}, {@code maxDepth}, {@code limit} may be {@code null}.
     */
    public GraphTraversal graphDfs(String graph, String start, GraphDirection direction, String label,
                                   Integer maxDepth, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("start", require(start, "start"));
        body.put("direction", (direction == null ? GraphDirection.OUTGOING : direction).wire());
        body.put("label", label);
        body.put("max_depth", maxDepth);
        body.put("limit", limit);
        return traversalOf(Wire.json(request(Wire.op("Graph", "Dfs", body), database)));
    }

    public GraphTraversal graphDfs(String graph, String start, GraphDirection direction, String label,
                                   Integer maxDepth, Integer limit) {
        return graphDfs(graph, start, direction, label, maxDepth, limit, "main");
    }

    public GraphTraversal graphDfs(String graph, String start) {
        return graphDfs(graph, start, GraphDirection.OUTGOING, null, null, null, "main");
    }

    /**
     * Partition the graph's nodes into connected components, largest first. Weak
     * ignores edge direction; strong groups nodes that can each reach the other. limit
     * bounds how many are listed, never how many are counted.
     *
     * <p>Each of {@code label}, {@code limit} may be {@code null}.
     */
    public GraphComponents graphConnectedComponents(String graph, GraphComponentKind kind, String label,
                                                    Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("kind", (kind == null ? GraphComponentKind.WEAK : kind).wire());
        body.put("label", label);
        body.put("limit", limit);
        return GraphComponents.from(Wire.json(
                request(Wire.op("Graph", "ConnectedComponents", body), database)));
    }

    public GraphComponents graphConnectedComponents(String graph, GraphComponentKind kind, String label,
                                                    Integer limit) {
        return graphConnectedComponents(graph, kind, label, limit, "main");
    }

    public GraphComponents graphConnectedComponents(String graph) {
        return graphConnectedComponents(graph, GraphComponentKind.WEAK, null, null, "main");
    }

    /**
     * Whether the graph contains a cycle, and one cycle as evidence. directed follows
     * edge direction; undirected treats every edge as two-way.
     *
     * <p>{@code label} may be {@code null}.
     */
    public GraphCycle graphDetectCycle(String graph, boolean directed, String label, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("directed", directed);
        body.put("label", label);
        return GraphCycle.from(Wire.json(request(Wire.op("Graph", "DetectCycle", body), database)));
    }

    public GraphCycle graphDetectCycle(String graph, boolean directed, String label) {
        return graphDetectCycle(graph, directed, label, "main");
    }

    public GraphCycle graphDetectCycle(String graph) {
        return graphDetectCycle(graph, true, null, "main");
    }

    /**
     * Counts describing a graph: nodes and edges, per node label and per edge type,
     * self-loops, isolated nodes, degree extremes and dangling edges. Returned as the
     * server's own JSON object.
     */
    public Map<String, Object> graphStats(String graph, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        return Wire.json(request(Wire.op("Graph", "GraphStats", body), database));
    }

    public Map<String, Object> graphStats(String graph) {
        return graphStats(graph, "main");
    }

    /**
     * The part of the graph within maxDepth hops of the seeds: the nodes reached and
     * every edge joining two of them.
     *
     * <p>Each of {@code label}, {@code maxDepth}, {@code limit} may be {@code null}.
     */
    public GraphSubgraph graphSubgraph(String graph, List<String> seeds, GraphDirection direction,
                                       String label, Integer maxDepth, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("seeds", Wire.wireList(requireNotNull(seeds, "seeds"), x -> x, "seeds"));
        body.put("direction", (direction == null ? GraphDirection.BOTH : direction).wire());
        body.put("label", label);
        body.put("max_depth", maxDepth);
        body.put("limit", limit);
        return GraphSubgraph.from(Wire.json(request(Wire.op("Graph", "Subgraph", body), database)));
    }

    public GraphSubgraph graphSubgraph(String graph, List<String> seeds, GraphDirection direction,
                                       String label, Integer maxDepth, Integer limit) {
        return graphSubgraph(graph, seeds, direction, label, maxDepth, limit, "main");
    }

    public GraphSubgraph graphSubgraph(String graph, List<String> seeds) {
        return graphSubgraph(graph, seeds, GraphDirection.BOTH, null, null, null, "main");
    }

    /**
     * What the graph's data looks like: its node labels and edge types, each with a
     * count and the property keys seen on it. Returned as the server's own JSON object.
     */
    public Map<String, Object> graphSchema(String graph, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        return Wire.json(request(Wire.op("Graph", "GraphSchema", body), database));
    }

    public Map<String, Object> graphSchema(String graph) {
        return graphSchema(graph, "main");
    }

    /**
     * The k cheapest loopless paths between two nodes, cheapest first. Without
     * weightProperty every edge costs 1; with it, an edge's cost is that numeric
     * property.
     *
     * <p>Each of {@code k}, {@code label}, {@code weightProperty} may be {@code null}.
     */
    public List<GraphRankedPath> graphKShortestPaths(String graph, String from, String to, Integer k,
                                                     GraphDirection direction, String label,
                                                     String weightProperty, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("from", require(from, "from"));
        body.put("to", require(to, "to"));
        body.put("k", k);
        body.put("direction", (direction == null ? GraphDirection.OUTGOING : direction).wire());
        body.put("label", label);
        body.put("weight_property", weightProperty);
        List<GraphRankedPath> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Graph", "KShortestPaths", body), database)), "paths")) {
            out.add(GraphRankedPath.from(Wire.asObject(o, "paths")));
        }
        return out;
    }

    public List<GraphRankedPath> graphKShortestPaths(String graph, String from, String to, Integer k,
                                                     GraphDirection direction, String label,
                                                     String weightProperty) {
        return graphKShortestPaths(graph, from, to, k, direction, label, weightProperty, "main");
    }

    public List<GraphRankedPath> graphKShortestPaths(String graph, String from, String to) {
        return graphKShortestPaths(graph, from, to, null, GraphDirection.OUTGOING, null, null, "main");
    }

    /**
     * Every simple path between two nodes of at most maxDepth edges, up to limit paths.
     * A work budget bounds the search, and the result says whether the enumeration
     * finished.
     *
     * <p>Each of {@code label}, {@code maxDepth}, {@code limit} may be {@code null}.
     */
    public GraphPaths graphAllPaths(String graph, String from, String to, GraphDirection direction,
                                    String label, Integer maxDepth, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("from", require(from, "from"));
        body.put("to", require(to, "to"));
        body.put("direction", (direction == null ? GraphDirection.OUTGOING : direction).wire());
        body.put("label", label);
        body.put("max_depth", maxDepth);
        body.put("limit", limit);
        return GraphPaths.from(Wire.json(request(Wire.op("Graph", "AllPaths", body), database)));
    }

    public GraphPaths graphAllPaths(String graph, String from, String to, GraphDirection direction,
                                    String label, Integer maxDepth, Integer limit) {
        return graphAllPaths(graph, from, to, direction, label, maxDepth, limit, "main");
    }

    public GraphPaths graphAllPaths(String graph, String from, String to) {
        return graphAllPaths(graph, from, to, GraphDirection.OUTGOING, null, null, null, "main");
    }

    /**
     * Betweenness, closeness or eigenvector centrality, top limit nodes, best first.
     * iterations and tolerance tune eigenvector only.
     *
     * <p>Each of {@code label}, {@code limit}, {@code iterations}, {@code tolerance}
     * may be {@code null}.
     */
    public List<GraphScore> graphCentrality(String graph, GraphCentralityMeasure measure, boolean directed,
                                            String label, Integer limit, Integer iterations,
                                            Double tolerance, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("measure", requireNotNull(measure, "measure").wire());
        body.put("directed", directed);
        body.put("label", label);
        body.put("limit", limit);
        body.put("iterations", iterations);
        body.put("tolerance", tolerance);
        List<GraphScore> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Graph", "Centrality", body), database)), "results")) {
            out.add(GraphScore.from(Wire.asObject(o, "results")));
        }
        return out;
    }

    public List<GraphScore> graphCentrality(String graph, GraphCentralityMeasure measure, boolean directed,
                                            String label, Integer limit, Integer iterations,
                                            Double tolerance) {
        return graphCentrality(graph, measure, directed, label, limit, iterations, tolerance, "main");
    }

    public List<GraphScore> graphCentrality(String graph, GraphCentralityMeasure measure) {
        return graphCentrality(graph, measure, true, null, null, null, null, "main");
    }

    /**
     * Communities by Louvain modularity optimisation, direction ignored, largest first,
     * with the partition's modularity.
     *
     * <p>Each of {@code label}, {@code iterations}, {@code limit} may be {@code null}.
     */
    public GraphCommunities graphCommunities(String graph, String label, Integer iterations, Integer limit,
                                             String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("label", label);
        body.put("iterations", iterations);
        body.put("limit", limit);
        return GraphCommunities.from(Wire.json(request(Wire.op("Graph", "Communities", body), database)));
    }

    public GraphCommunities graphCommunities(String graph, String label, Integer iterations, Integer limit) {
        return graphCommunities(graph, label, iterations, limit, "main");
    }

    public GraphCommunities graphCommunities(String graph) {
        return graphCommunities(graph, null, null, null, "main");
    }

    /**
     * Triangles and clustering coefficients, direction ignored.
     *
     * <p>Each of {@code label}, {@code limit} may be {@code null}.
     */
    public GraphTriangles graphTriangleCount(String graph, String label, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("label", label);
        body.put("limit", limit);
        return GraphTriangles.from(Wire.json(request(Wire.op("Graph", "TriangleCount", body), database)));
    }

    public GraphTriangles graphTriangleCount(String graph, String label, Integer limit) {
        return graphTriangleCount(graph, label, limit, "main");
    }

    public GraphTriangles graphTriangleCount(String graph) {
        return graphTriangleCount(graph, null, null, "main");
    }

    /**
     * The nodes whose neighbour sets most resemble the given node's, best first.
     *
     * <p>Each of {@code label}, {@code limit} may be {@code null}.
     */
    public List<GraphScore> graphNodeSimilarity(String graph, String node, GraphDirection direction,
                                                String label, GraphSimilarityMetric metric, Integer limit,
                                                String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("node", require(node, "node"));
        body.put("direction", (direction == null ? GraphDirection.BOTH : direction).wire());
        body.put("label", label);
        body.put("metric", (metric == null ? GraphSimilarityMetric.JACCARD : metric).wire());
        body.put("limit", limit);
        List<GraphScore> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Graph", "NodeSimilarity", body), database)), "results")) {
            out.add(GraphScore.from(Wire.asObject(o, "results")));
        }
        return out;
    }

    public List<GraphScore> graphNodeSimilarity(String graph, String node, GraphDirection direction,
                                                String label, GraphSimilarityMetric metric, Integer limit) {
        return graphNodeSimilarity(graph, node, direction, label, metric, limit, "main");
    }

    public List<GraphScore> graphNodeSimilarity(String graph, String node) {
        return graphNodeSimilarity(
                graph, node, GraphDirection.BOTH, null, GraphSimilarityMetric.JACCARD, null, "main");
    }

    /**
     * Check a graph's stored records against each other and against the resident
     * mirror: dangling edges, undecodable records, records stored under another id, and
     * any disagreement between mirror and storage. Returned as the server's own JSON
     * object.
     */
    public Map<String, Object> graphCheckConsistency(String graph, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        return Wire.json(request(Wire.op("Graph", "CheckConsistency", body), database));
    }

    public Map<String, Object> graphCheckConsistency(String graph) {
        return graphCheckConsistency(graph, "main");
    }

    /**
     * Index node properties for finding nodes by value. With one property it is a
     * property index, with several a composite one; label limits it to nodes carrying
     * that label.
     *
     * <p>{@code label} may be {@code null}.
     */
    public void graphCreateNodeIndex(String graph, String name, List<String> properties, String label,
                                     String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("name", require(name, "name"));
        body.put("label", label);
        body.put("properties", Wire.wireList(requireNotNull(properties, "properties"), x -> x, "properties"));
        request(Wire.op("Graph", "CreateNodeIndex", body), database);
    }

    public void graphCreateNodeIndex(String graph, String name, List<String> properties, String label) {
        graphCreateNodeIndex(graph, name, properties, label, "main");
    }

    public void graphCreateNodeIndex(String graph, String name, List<String> properties) {
        graphCreateNodeIndex(graph, name, properties, null, "main");
    }

    /**
     * Enforce a rule on a property of every node, or with the edge target every
     * relationship, carrying a label. Refused when a stored record already breaks it;
     * afterwards every write that would break it is refused.
     */
    public void graphCreateConstraint(String graph, String name, String label, String property,
                                      GraphConstraintKind kind, GraphConstraintTarget target,
                                      String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("name", require(name, "name"));
        body.put("label", require(label, "label"));
        body.put("property", require(property, "property"));
        body.put("kind", requireNotNull(kind, "kind").wire());
        body.put("target", (target == null ? GraphConstraintTarget.NODE : target).wire());
        request(Wire.op("Graph", "CreateConstraint", body), database);
    }

    public void graphCreateConstraint(String graph, String name, String label, String property,
                                      GraphConstraintKind kind, GraphConstraintTarget target) {
        graphCreateConstraint(graph, name, label, property, kind, target, "main");
    }

    public void graphCreateConstraint(String graph, String name, String label, String property,
                                      GraphConstraintKind kind) {
        graphCreateConstraint(graph, name, label, property, kind, GraphConstraintTarget.NODE, "main");
    }

    /**
     * Drop an index or a constraint by name.
     */
    public void graphDropSchemaObject(String graph, String name, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("name", require(name, "name"));
        request(Wire.op("Graph", "DropSchemaObject", body), database);
    }

    public void graphDropSchemaObject(String graph, String name) {
        graphDropSchemaObject(graph, name, "main");
    }

    /**
     * A graph's indexes and constraints.
     */
    public GraphSchemaObjects graphListSchemaObjects(String graph, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        return GraphSchemaObjects.from(Wire.json(
                request(Wire.op("Graph", "ListSchemaObjects", body), database)));
    }

    public GraphSchemaObjects graphListSchemaObjects(String graph) {
        return graphListSchemaObjects(graph, "main");
    }

    /**
     * Nodes with the label (when given) whose properties equal the given values, in id
     * order, answered from an index that covers the request, the label index, or a
     * scan; the result says which.
     *
     * <p>Each of {@code label}, {@code properties}, {@code limit} may be {@code null}.
     */
    public GraphFindResult graphFindNodes(String graph, String label, Map<String, Object> properties,
                                          Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("label", label);
        if (properties != null) {
            body.put("properties", properties);
        }
        body.put("limit", limit);
        return GraphFindResult.from(Wire.json(request(Wire.op("Graph", "FindNodes", body), database)));
    }

    public GraphFindResult graphFindNodes(String graph, String label, Map<String, Object> properties,
                                          Integer limit) {
        return graphFindNodes(graph, label, properties, limit, "main");
    }

    public GraphFindResult graphFindNodes(String graph) {
        return graphFindNodes(graph, null, null, null, "main");
    }

    /**
     * Import nodes and edges from CSV text, as one atomic batch, so constraints apply.
     *
     * <p>Each of {@code nodes}, {@code edges} may be {@code null}.
     */
    public GraphBatchResult graphImportCsv(String graph, String nodes, String edges, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("nodes", nodes);
        body.put("edges", edges);
        return GraphBatchResult.from(Wire.json(request(Wire.op("Graph", "ImportCsv", body), database)));
    }

    public GraphBatchResult graphImportCsv(String graph, String nodes, String edges) {
        return graphImportCsv(graph, nodes, edges, "main");
    }

    public GraphBatchResult graphImportCsv(String graph) {
        return graphImportCsv(graph, null, null, "main");
    }

    /**
     * PageRank over edge direction: the top limit nodes by rank, best first. damping
     * must lie in [0, 1).
     *
     * <p>Each of {@code damping}, {@code iterations}, {@code tolerance}, {@code label},
     * {@code limit} may be {@code null}.
     */
    public List<GraphScore> graphPageRank(String graph, Double damping, Integer iterations,
                                          Double tolerance, String label, Integer limit, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("damping", damping);
        body.put("iterations", iterations);
        body.put("tolerance", tolerance);
        body.put("label", label);
        body.put("limit", limit);
        List<GraphScore> out = new ArrayList<>();
        for (Object o : Wire.list(Wire.json(request(Wire.op("Graph", "PageRank", body), database)), "ranks")) {
            out.add(GraphScore.from(Wire.asObject(o, "ranks")));
        }
        return out;
    }

    public List<GraphScore> graphPageRank(String graph, Double damping, Integer iterations,
                                          Double tolerance, String label, Integer limit) {
        return graphPageRank(graph, damping, iterations, tolerance, label, limit, "main");
    }

    public List<GraphScore> graphPageRank(String graph) {
        return graphPageRank(graph, null, null, null, null, null, "main");
    }

    /**
     * Run a Cypher write: CREATE, MERGE, SET, REMOVE or DELETE, optionally after MATCH
     * and WHERE. Inside a graph transaction the statement is queued rather than
     * applied. Returned as the server's own JSON object: counters when applied, the
     * queue length when queued.
     */
    public Map<String, Object> graphExecute(String graph, String cypher, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        body.put("cypher", require(cypher, "cypher"));
        return Wire.json(request(Wire.op("Graph", "Execute", body), database));
    }

    public Map<String, Object> graphExecute(String graph, String cypher) {
        return graphExecute(graph, cypher, "main");
    }

    /**
     * Open a transaction on a graph and return its id. An Execute that carries the id
     * is queued rather than applied.
     */
    public String graphBeginTransaction(String graph, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        return Wire.str(Wire.json(
                request(Wire.op("Graph", "BeginTransaction", body), database)), "transaction");
    }

    public String graphBeginTransaction(String graph) {
        return graphBeginTransaction(graph, "main");
    }

    /**
     * Apply every queued statement of a transaction as one unit: all of them or none.
     * Returned as the server's own JSON object of counters.
     */
    public Map<String, Object> graphCommitTransaction(String graph, String txn, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        try {
            return Wire.json(request(Wire.op("Graph", "CommitTransaction", body), database, require(txn, "txn")));
        } finally {
            if (txn != null && txn.equals(requestTxn)) {
                requestTxn = null;
            }
        }
    }

    public Map<String, Object> graphCommitTransaction(String graph, String txn) {
        return graphCommitTransaction(graph, txn, "main");
    }

    /**
     * Discard a transaction and its queued statements.
     */
    public void graphRollbackTransaction(String graph, String txn, String database) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", require(graph, "graph"));
        try {
            request(Wire.op("Graph", "RollbackTransaction", body), database, require(txn, "txn"));
        } finally {
            if (txn != null && txn.equals(requestTxn)) {
                requestTxn = null;
            }
        }
    }

    public void graphRollbackTransaction(String graph, String txn) {
        graphRollbackTransaction(graph, txn, "main");
    }

    private static GraphTraversal traversalOf(Map<String, Object> m) {
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
     * <p>{@code txnOpen} follows what the server answered. A
     * {@code COMMIT}/{@code ROLLBACK} reply means the block is over — a refusal
     * included, because the server ends it either way — unless the reply
     * carries {@code nested_depth}, which says only a nested block ended. The
     * one exception is a request that never left this process, which is what
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
        TransactionResult result = TransactionResult.from(Wire.json(resp));
        // A BEGIN inside an open block opens a nested one, and the COMMIT or
        // ROLLBACK that answers it ends only that: the server says so with
        // `nested_depth`, and the outer block is still open either way.
        txnOpen = result.nestedDepth().isPresent() || "BEGIN".equals(keyword);
        return result;
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
