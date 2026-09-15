package com.tricoredb;

import java.util.List;

/**
 * The result of a bounded BFS traversal.
 *
 * @param maxDepth  the depth actually used — the server clamps the requested
 *                  value, so this can be lower than what was asked for
 * @param limit     the limit actually used, likewise clamped
 * @param truncated the walk stopped early at the limit or the visited-node cap,
 *                  so the node set is incomplete
 */
public record GraphTraversal(
        String start,
        GraphDirection direction,
        long maxDepth,
        long limit,
        boolean truncated,
        List<GraphTraversalNode> nodes) {
}
