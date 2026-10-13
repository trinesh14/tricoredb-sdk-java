package com.tricoredb;

/** How node similarity scores two neighbour sets. */
public enum GraphSimilarityMetric {

    /** Shared neighbours over all neighbours of either node. The default. */
    JACCARD("jaccard"),
    /** Shared neighbours over the smaller neighbour set. */
    OVERLAP("overlap");

    private final String wire;

    GraphSimilarityMetric(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
