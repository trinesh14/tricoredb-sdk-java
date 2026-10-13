package com.tricoredb;

/** How a multi-vector search combines its queries' rankings. */
public enum VectorFusion {

    /** Reciprocal rank fusion. The default. */
    RRF("rrf"),
    /** The best score any query gave the vector. */
    MAX("max"),
    /** The mean score over all queries. */
    MEAN("mean");

    private final String wire;

    VectorFusion(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
