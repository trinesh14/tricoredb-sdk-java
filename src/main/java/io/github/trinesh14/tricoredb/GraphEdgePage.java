package io.github.trinesh14.tricoredb;

import java.util.List;

/**
 * One page of an edge listing.
 *
 * @param truncated more edges remain past this page
 * @param total     edges in the graph, not in this page
 */
public record GraphEdgePage(String graph, List<GraphEdge> edges, boolean truncated, long total) {
}
