package com.tricoredb;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A thread-safe pool of {@link TriCore} connections.
 *
 * <p><b>Why this exists.</b> A {@code TriCore} connection is a single request/response
 * stream: two threads sharing one would interleave frames and read each other's replies.
 * So concurrency requires one connection per concurrent caller, and hand-rolling that per
 * request means paying a TCP connect + HELLO + AUTH round trip every time.
 *
 * <p>The pool keeps authenticated connections alive and hands out <b>exclusive</b>
 * ownership for the duration of a callback:
 *
 * <pre>{@code
 * try (Pool pool = new Pool("127.0.0.1", 18427, "admin", "pw", 8)) {
 *     pool.use(db -> db.execute("INSERT INTO t VALUES (1, 'ada')"));
 * }
 * }</pre>
 *
 * <p>A connection is never handed to two callers at once — that is the whole point, and
 * it is why {@code use} takes a callback rather than returning a connection a caller
 * could retain and share across threads.
 */
public final class Pool implements AutoCloseable {

    private final String host;
    private final int port;
    private final String user;
    private final String secret;
    private final String clientName;
    private final int connectTimeoutMs;
    private final int size;
    // Carried so every pooled connection gets the same TLS treatment; without
    // it a pool against a TLS server would fail to connect after the first
    // handout, or worse, quietly negotiate plaintext.
    private final TlsOptions tls;

    private final Object lock = new Object();
    // Connections are created lazily: a pool sized for peak load should not pay for
    // peak load at startup.
    private final Deque<TriCore> idle = new ArrayDeque<>();
    private int created = 0;
    private boolean closed = false;

    public Pool(String host, int port, String user, String secret, int size) {
        this(host, port, user, secret, size, "tricoredb-java-pool", 10_000, null);
    }

    /** A pool whose connections all speak TLS. See {@link TlsOptions}. */
    public Pool(String host, int port, String user, String secret, int size, TlsOptions tls) {
        this(host, port, user, secret, size, "tricoredb-java-pool", 10_000, tls);
    }

    public Pool(String host, int port, String user, String secret, int size,
                String clientName, int connectTimeoutMs) {
        this(host, port, user, secret, size, clientName, connectTimeoutMs, null);
    }

    public Pool(String host, int port, String user, String secret, int size,
                String clientName, int connectTimeoutMs, TlsOptions tls) {
        if (size < 1) {
            throw new IllegalArgumentException("pool size must be >= 1, got " + size);
        }
        this.tls = tls;
        this.host = host;
        this.port = port;
        this.user = user;
        this.secret = secret;
        this.size = size;
        this.clientName = clientName;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int size() {
        return size;
    }

    /** {@code (size, created, idle, inUse)} — for tests and diagnostics. */
    public record Stats(int size, int created, int idle, int inUse) {
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(size, created, idle.size(), created - idle.size());
        }
    }

    /**
     * A unit of work run with exclusive ownership of a pooled connection.
     *
     * <p>Deliberately void rather than a generic {@code Action<R>}: overloading {@code use}
     * on a void-returning and a value-returning functional interface of the same shape is
     * ambiguous to javac for any lambda whose body is a single assignment or void call (it
     * cannot tell which SAM you meant), so there is exactly one callback shape here — a
     * caller after a result captures it in an effectively-final holder, same as {@code Go}'s
     * {@code func(*Client) error}.
     */
    @FunctionalInterface
    public interface Action {
        void run(TriCore conn);
    }

    public void use(Action action) {
        use(10_000L, action);
    }

    /**
     * Borrow a connection for the duration of {@code action}.
     *
     * <p>Blocks up to {@code timeoutMs} if every connection is in use, then throws
     * {@link PoolTimeoutException} rather than silently growing past {@code size} — an
     * unbounded pool under load just relocates the failure to the server.
     */
    public void use(long timeoutMs, Action action) {
        TriCore conn = acquire(timeoutMs);
        boolean broken = false;
        boolean leftOpen = false;
        try {
            action.run(conn);
            if (conn.inTransaction()) {
                // Rolled back here rather than returned to the pool mid-block: the next
                // borrower would otherwise be writing into somebody else's transaction.
                // Reported after the connection is released, so the slot comes back.
                leftOpen = true;
                broken = !abandonBlock(conn);
            }
        } catch (RuntimeException e) {
            // A transport/protocol failure may leave the stream mid-frame; handing it to
            // the next caller would give them someone else's bytes. A server-side error
            // (bad SQL, say) is a perfectly healthy response and must NOT retire the
            // connection, or the pool churns on ordinary application errors.
            broken = isBroken(e) || conn.isPoisoned();
            if (!broken && conn.inTransaction()) {
                // The caller's exception is the one to propagate; the block still has to
                // go, and the connection is retired if it will not.
                broken = !abandonBlock(conn);
            }
            throw e;
        } finally {
            // Runs even if `action` threw, or a released slot would never come back and
            // the pool would eventually deadlock every caller.
            release(conn, broken);
        }
        if (leftOpen) {
            throw new TriCoreException(
                    "the callback returned with a session transaction still open on the pooled "
                            + "connection; it has been rolled back rather than returned to the pool "
                            + "mid-block. commit() or rollback() inside the callback, or use "
                            + "db.withTransaction(...) / db.transactionBlock().");
        }
    }

    /**
     * Roll back a block a pooled callback left open. False when the connection must be
     * retired instead — a rollback this driver could not complete leaves a session whose
     * state no next borrower can assume anything about.
     */
    private static boolean abandonBlock(TriCore conn) {
        try {
            conn.rollback();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TriCore acquire(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        synchronized (lock) {
            while (true) {
                if (closed) {
                    throw new TriCoreException("pool is closed");
                }
                if (!idle.isEmpty()) {
                    return idle.pop();
                }
                if (created < size) {
                    // Reserve the slot before releasing the lock so concurrent callers
                    // cannot both decide to create the last connection.
                    created++;
                    break;
                }
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    throw new PoolTimeoutException(
                            "no pooled connection available within " + timeoutMs + "ms "
                                    + "(size=" + size + "); raise size or shorten your work");
                }
                try {
                    lock.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TriCoreException("interrupted while waiting for a pooled connection", e);
                }
            }
        }
        try {
            return TriCore.connect(host, port, user, secret, clientName, connectTimeoutMs, tls);
        } catch (RuntimeException e) {
            // Give the reserved slot back, or the pool permanently shrinks.
            synchronized (lock) {
                created--;
                lock.notify();
            }
            throw e;
        }
    }

    private void release(TriCore conn, boolean broken) {
        // Never idle a connection mid-block, whatever path led here.
        if (conn.inTransaction()) {
            broken = true;
        }
        synchronized (lock) {
            if (broken || closed) {
                created--;
                lock.notify();
                try {
                    conn.close();
                } catch (RuntimeException ignored) {
                    // Nothing meaningful to do with a failure to close a connection
                    // that's already being discarded.
                }
                return;
            }
            idle.push(conn);
            lock.notify();
        }
    }

    /**
     * Whether {@code e} means the stream can no longer be trusted to be frame-aligned.
     * {@link ProtocolException} is a direct framing failure; a {@link TriCoreException}
     * whose cause is an {@link IOException} is a transport failure caught and wrapped by
     * {@code TriCore}. Anything else — including a plain {@code TriCoreException} from a
     * server-reported application error — is a healthy connection.
     *
     * <p>The connection's own {@link TriCore#isPoisoned()} is consulted alongside
     * this at the call site, which is the part that cannot fall out of date: a
     * driver that refused a frame, timed out, or failed a read knows it is
     * un-resynchronisable whether or not the exception type happens to be listed
     * here.
     */
    private static boolean isBroken(RuntimeException e) {
        if (e instanceof ProtocolException || e instanceof TriCoreTimeoutException) {
            return true;
        }
        return e.getCause() instanceof IOException;
    }

    /** Closes every idle connection. In-use ones are closed as their {@code use} returns. */
    @Override
    public void close() {
        List<TriCore> toClose;
        synchronized (lock) {
            closed = true;
            toClose = new ArrayList<>(idle);
            created -= idle.size();
            idle.clear();
            lock.notifyAll();
        }
        for (TriCore c : toClose) {
            try {
                c.close();
            } catch (RuntimeException ignored) {
                // Best-effort teardown.
            }
        }
    }
}
