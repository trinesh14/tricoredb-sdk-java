package com.tricoredb;

/** Which end of a list an element leaves or joins. */
public enum CacheListEnd {

    /** The head of the list. */
    LEFT("Left"),
    /** The tail of the list. */
    RIGHT("Right");

    private final String wire;

    CacheListEnd(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
