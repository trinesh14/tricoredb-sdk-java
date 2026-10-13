package com.tricoredb;

import java.util.List;
import java.util.Map;

/**
 * A graph's communities.
 *
 * @param modularity  the partition's modularity
 * @param count       how many communities were found
 * @param converged   whether the optimisation converged
 * @param iterations  passes run
 * @param communities the communities, largest first
 */
public record GraphCommunities(
        double modularity,
        long count,
        boolean converged,
        long iterations,
        List<GraphComponent> communities) {

    static GraphCommunities from(Map<String, Object> m) {
        return new GraphCommunities(
                Wire.dbl(m, "modularity"),
                Wire.num(m, "count"),
                Wire.bool(m, "converged"),
                Wire.num(m, "iterations"),
                Wire.records(m, "communities", GraphComponent::from));
    }
}
