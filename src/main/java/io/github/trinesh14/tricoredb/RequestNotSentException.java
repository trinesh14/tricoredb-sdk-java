package io.github.trinesh14.tricoredb;

/**
 * A request this driver refused <b>before writing a byte</b>, so the server
 * never saw it and the session's state is exactly what it was.
 *
 * <p>The distinction is not cosmetic. A failed {@code COMMIT} normally means the
 * block is over — the server ends it either way, and says so. A {@code COMMIT}
 * that was never sent means the block is still open, and treating the two alike
 * would let a connection go back to a {@link Pool} carrying somebody else's
 * uncommitted writes.
 *
 * <p>A {@link TriCoreException} subclass, so every existing
 * {@code catch (TriCoreException)} still sees it.
 */
public class RequestNotSentException extends TriCoreException {

    public RequestNotSentException(String message) {
        super(message);
    }
}
