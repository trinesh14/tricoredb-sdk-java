package com.tricoredb;

/** What a graph constraint enforces. */
public enum GraphConstraintKind {

    /** No two records share a value of the property. */
    UNIQUE("unique"),
    /** Every record has the property, and not as null. */
    REQUIRED("required");

    private final String wire;

    GraphConstraintKind(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
