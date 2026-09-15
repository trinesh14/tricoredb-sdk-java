package com.tricoredb;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * A stored vector: its id, the full {@code f32} vector, and its metadata.
 *
 * @param metadata the stored metadata, or an empty map when none was given
 */
public record VectorItem(String id, float[] vector, Map<String, Object> metadata) {

    /** The vector's dimension. */
    public int dimension() {
        return vector.length;
    }

    static VectorItem from(Map<String, Object> m) {
        return new VectorItem(Wire.str(m, "id"), Wire.floats(m, "vector"), Wire.obj(m, "metadata"));
    }

    /**
     * Value equality, comparing the vector element by element.
     *
     * A record's generated {@code equals} compares arrays by <i>identity</i>,
     * so two items read back from the same stored vector would compare unequal.
     * That is the kind of difference a caller only discovers via a test that
     * mysteriously fails, so it is overridden here rather than documented.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof VectorItem other
                && Objects.equals(id, other.id)
                && Arrays.equals(vector, other.vector)
                && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, Arrays.hashCode(vector), metadata);
    }

    @Override
    public String toString() {
        return "VectorItem(id=" + id + ", dimension=" + vector.length + ")";
    }
}
