package io.github.trinesh14.tricoredb;

import java.util.List;

/**
 * One page of a node listing.
 *
 * @param truncated more nodes remain past this page
 * @param total     nodes in the graph, not in this page
 */
public record GraphNodePage(String graph, List<GraphNode> nodes, boolean truncated, long total) {
}
