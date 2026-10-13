package com.tricoredb;

/** When a conditional cache write is allowed to proceed. */
public enum CacheSetCondition {

    /** Write unconditionally. */
    ALWAYS("Always"),
    /** Write only if the key is absent. An expired key counts as absent. */
    IF_ABSENT("IfAbsent"),
    /** Write only if the key is already present and live. */
    IF_PRESENT("IfPresent");

    private final String wire;

    CacheSetCondition(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
