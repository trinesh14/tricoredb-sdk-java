package com.tricoredb;

import java.util.Map;
import java.util.OptionalLong;

/**
 * What the server did with a transaction script.
 *
 * @param statements      how many statements ran
 * @param committedWrites writes flushed as one atomic batch
 * @param discardedWrites writes thrown away by a rollback
 * @param outcome         {@code "committed"} or {@code "rolled_back"};
 *                        {@code null} for a statement that opened or ended a
 *                        nested block
 * @param nestedDepth     how many nested blocks are open after this statement,
 *                        when it opened or ended one; empty when it began or
 *                        ended the transaction itself. A server from 0.1.0-rc.3
 *                        opens a nested block, backed by a savepoint, for a
 *                        {@code BEGIN} inside an open transaction: its
 *                        {@code COMMIT} releases it and its {@code ROLLBACK}
 *                        undoes only that block
 */
public record TransactionResult(long statements, long committedWrites, long discardedWrites,
                                 String outcome, OptionalLong nestedDepth) {

    public TransactionResult(long statements, long committedWrites, long discardedWrites, String outcome) {
        this(statements, committedWrites, discardedWrites, outcome, OptionalLong.empty());
    }

    static TransactionResult from(Map<String, Object> m) {
        return new TransactionResult(
                count(m, "statements"),
                count(m, "committed_writes"),
                count(m, "discarded_writes"),
                Wire.str(m, "transaction"),
                Wire.optLong(m, "nested_depth"));
    }

    // The answer to a nested BEGIN, COMMIT or ROLLBACK carries none of the counts.
    private static long count(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    /** Whether the script committed rather than rolling back. */
    public boolean committed() {
        return "committed".equals(outcome);
    }
}
