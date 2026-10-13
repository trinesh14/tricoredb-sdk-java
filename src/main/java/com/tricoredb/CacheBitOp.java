package com.tricoredb;

/** The bitwise operation a cache BitOp applies across its sources. */
public enum CacheBitOp {

    /** Bitwise AND of every source. */
    AND("And"),
    /** Bitwise OR of every source. */
    OR("Or"),
    /** Bitwise XOR of every source. */
    XOR("Xor"),
    /** Bitwise NOT. Takes exactly one source. */
    NOT("Not");

    private final String wire;

    CacheBitOp(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
