package com.tricoredb;

import java.util.Map;

/**
 * What the server did with a transaction script.
 *
 * @param statements      how many statements ran
 * @param committedWrites writes flushed as one atomic batch
 * @param discardedWrites writes thrown away by a rollback
 * @param outcome         {@code "committed"} or {@code "rolled_back"}
 */
public record TransactionResult(long statements, long committedWrites, long discardedWrites,
                                 String outcome) {

    static TransactionResult from(Map<String, Object> m) {
        return new TransactionResult(
                Wire.num(m, "statements"),
                Wire.num(m, "committed_writes"),
                Wire.num(m, "discarded_writes"),
                Wire.str(m, "transaction"));
    }

    /** Whether the script committed rather than rolling back. */
    public boolean committed() {
        return "committed".equals(outcome);
    }
}
