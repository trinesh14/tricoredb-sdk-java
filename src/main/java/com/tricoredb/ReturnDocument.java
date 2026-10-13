package com.tricoredb;

/** Whether a by-id document write hands back the document it touched, and which version of it. */
public enum ReturnDocument {

    /** Return no document. The default. */
    NONE("None"),
    /** The document as it was before the write. Absent when there was none. */
    BEFORE("Before"),
    /** The document as it is after the write. */
    AFTER("After");

    private final String wire;

    ReturnDocument(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
