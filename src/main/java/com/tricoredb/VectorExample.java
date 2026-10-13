package com.tricoredb;

/**
 * An example for {@code vectorSearchByExamples}: a stored vector named by id,
 * or a vector given inline.
 */
public final class VectorExample {

    private final Object wire;

    private VectorExample(Object wire) {
        this.wire = wire;
    }

    /** The stored vector with this id. */
    public static VectorExample id(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id must not be null or empty");
        }
        return new VectorExample(id);
    }

    /** This vector. */
    public static VectorExample vector(float[] vector) {
        return new VectorExample(Wire.vectorOf(vector));
    }

    Object wire() {
        return wire;
    }
}
