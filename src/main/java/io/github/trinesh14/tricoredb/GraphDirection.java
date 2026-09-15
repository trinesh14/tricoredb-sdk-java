package io.github.trinesh14.tricoredb;

/** Direction to traverse edges from a node. */
public enum GraphDirection {

    /** Edges where the node is the {@code from} endpoint. */
    OUTGOING("outgoing"),
    /** Edges where the node is the {@code to} endpoint. */
    INCOMING("incoming"),
    /** Both directions. */
    BOTH("both");

    private final String wire;

    GraphDirection(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static GraphDirection fromWire(String s) {
        for (GraphDirection d : values()) {
            if (d.wire.equals(s)) {
                return d;
            }
        }
        throw new ProtocolException("unknown graph direction `" + s + "`");
    }
}
