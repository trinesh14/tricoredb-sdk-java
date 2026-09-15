package io.github.trinesh14.tricoredb;

/** The peer did not speak the protocol we expect (wrong tag, malformed frame, unreachable shape). */
public class ProtocolException extends TriCoreException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
