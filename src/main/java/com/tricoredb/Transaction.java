package com.tricoredb;

/**
 * An open session transaction, scoped to a try-with-resources block.
 *
 * <pre>{@code
 * try (Transaction tx = db.transactionBlock()) {
 *     db.execute("INSERT INTO t VALUES (1, 'ada')");
 *     tx.commit();
 * }
 * }</pre>
 *
 * <p>{@link #close()} rolls the block back unless {@link #commit()} or
 * {@link #rollback()} already ended it — so an early return, a {@code break} or
 * a thrown exception can never leave a block open on the connection. That is the
 * whole point of the type: the leak it prevents is a connection returned to a
 * pool mid-block, which is how one request's uncommitted writes become another's.
 *
 * <p>Statements go through the <b>connection</b>, not through this handle: the
 * server binds the block to the socket, so {@link #connection()} — the very
 * connection {@code transactionBlock()} was called on — is the only place they
 * belong.
 */
public final class Transaction implements AutoCloseable {

    private final TriCore conn;
    private final String database;
    private boolean ended;

    Transaction(TriCore conn, String database) {
        this.conn = conn;
        this.database = database;
    }

    /** The connection this block is bound to. Every statement in it goes here. */
    public TriCore connection() {
        return conn;
    }

    /** Whether the block is still open. */
    public boolean open() {
        return !ended && conn.inTransaction();
    }

    /** Commit the block. Further use of this handle is a no-op on close. */
    public TransactionResult commit() {
        TransactionResult r = conn.commit(database);
        ended = true;
        return r;
    }

    /** Discard the block. Further use of this handle is a no-op on close. */
    public TransactionResult rollback() {
        TransactionResult r = conn.rollback(database);
        ended = true;
        return r;
    }

    /**
     * Roll back unless the block already ended.
     *
     * <p>Best-effort by necessity: this runs while an exception may already be
     * propagating, and a rollback failure must not replace the caller's cause. A
     * connection whose rollback fails is one whose socket is going away anyway,
     * and the server rolls an abandoned block back on its own.
     */
    @Override
    public void close() {
        if (ended) {
            return;
        }
        ended = true;
        if (conn.inTransaction()) {
            try {
                conn.rollback(database);
            } catch (RuntimeException ignored) {
                // see above
            }
        }
    }
}
