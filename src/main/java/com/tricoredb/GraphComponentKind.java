package com.tricoredb;

/** Which connectivity connected components group nodes by. */
public enum GraphComponentKind {

    /** Connected ignoring edge direction. The default. */
    WEAK("weak"),
    /** Mutually reachable following edge direction. */
    STRONG("strong");

    private final String wire;

    GraphComponentKind(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
