package io.github.trinesh14.tricoredb;

/** Distance/similarity metric for a vector collection. */
public enum VectorMetric {

    /** Cosine similarity (higher = closer). */
    COSINE("cosine"),
    /** Dot product (higher = closer). */
    DOT("dot"),
    /** Squared Euclidean distance. The server negates it so higher is always closer. */
    L2("l2");

    private final String wire;

    VectorMetric(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    /** Parse a wire spelling. Throws rather than defaulting on an unknown name. */
    static VectorMetric fromWire(String s) {
        for (VectorMetric m : values()) {
            if (m.wire.equals(s)) {
                return m;
            }
        }
        throw new ProtocolException("unknown vector metric `" + s + "`");
    }
}
