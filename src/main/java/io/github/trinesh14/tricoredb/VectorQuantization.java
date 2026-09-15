package io.github.trinesh14.tricoredb;

/**
 * How a collection's ANN index stores vectors in memory.
 *
 * An index-level choice only: the durable records always keep full {@code f32}
 * precision, so quantization never loses stored data and reads are identical
 * either way.
 */
public enum VectorQuantization {

    /** Full {@code f32} vectors in the graph. The default. */
    NONE("none"),
    /** Per-vector int8 quantization, with an exact re-rank against the stored vectors. */
    INT8("int8");

    private final String wire;

    VectorQuantization(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static VectorQuantization fromWire(String s) {
        for (VectorQuantization q : values()) {
            if (q.wire.equals(s)) {
                return q;
            }
        }
        throw new ProtocolException("unknown vector quantization `" + s + "`");
    }
}
