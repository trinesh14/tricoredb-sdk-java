package com.tricoredb;

/** Which centrality to compute. */
public enum GraphCentralityMeasure {

    /** The share of shortest paths between other nodes that pass through a node. */
    BETWEENNESS("betweenness"),
    /** How near a node is to the nodes it can reach. */
    CLOSENESS("closeness"),
    /** A node is central when central nodes point at it. */
    EIGENVECTOR("eigenvector");

    private final String wire;

    GraphCentralityMeasure(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
