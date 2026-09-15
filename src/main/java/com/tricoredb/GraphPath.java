package com.tricoredb;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The result of a path search.
 *
 * "No path" is a successful response with {@link #found()} false, not an error,
 * so callers must check it. {@link #message()} says <i>why</i> nothing was
 * found: a search stopped at the server's visited-node cap is inconclusive, not
 * proof that no path exists.
 *
 * @param totalCost present only for a weighted search
 */
public record GraphPath(
        boolean found,
        String from,
        String to,
        GraphDirection direction,
        long hops,
        List<String> nodePath,
        List<String> edgePath,
        OptionalDouble totalCost,
        String message) {

    static GraphPath from(Map<String, Object> m) {
        Object cost = m.get("total_cost");
        return new GraphPath(
                Wire.bool(m, "found"),
                Wire.str(m, "from"),
                Wire.str(m, "to"),
                GraphDirection.fromWire(Wire.str(m, "direction")),
                Wire.num(m, "hops"),
                Wire.strings(m, "node_path"),
                Wire.strings(m, "edge_path"),
                cost instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty(),
                Wire.str(m, "message"));
    }
}
