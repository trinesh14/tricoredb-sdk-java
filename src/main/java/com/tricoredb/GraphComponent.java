package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * One group of nodes: a connected component or a community.
 *
 * @param nodes     node ids in the group, possibly truncated
 * @param size      how many nodes the group holds
 * @param truncated whether the node list was cut short
 */
public record GraphComponent(List<String> nodes, long size, boolean truncated) {

    static GraphComponent from(Map<String, Object> m) {
        return new GraphComponent(Wire.strings(m, "nodes"), Wire.num(m, "size"), Wire.bool(m, "truncated"));
    }
}
