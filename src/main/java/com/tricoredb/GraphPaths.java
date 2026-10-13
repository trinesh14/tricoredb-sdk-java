package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * The simple paths between two nodes.
 *
 * @param paths     the paths found
 * @param complete  whether the enumeration finished
 * @param stoppedBy what stopped it early, when it did not finish; {@code null}
 *                  otherwise
 */
public record GraphPaths(List<GraphRankedPath> paths, boolean complete, String stoppedBy) {

    static GraphPaths from(Map<String, Object> m) {
        return new GraphPaths(
                Wire.records(m, "paths", GraphRankedPath::from),
                Wire.bool(m, "complete"),
                Wire.str(m, "stopped_by"));
    }
}
