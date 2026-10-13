package com.tricoredb;

/** What a graph constraint's label names. */
public enum GraphConstraintTarget {

    /** Node labels. The default. */
    NODE("node"),
    /** Relationship (edge) labels. */
    EDGE("edge");

    private final String wire;

    GraphConstraintTarget(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
