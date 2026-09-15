package io.github.trinesh14.tricoredb;

/**
 * A reply did not arrive within this connection's read timeout, or the connect
 * phase did not finish within its budget.
 *
 * <p>Fatal to the connection, not a retryable hiccup: the reply this driver
 * stopped waiting for may still be in flight, and reusing the socket would read
 * it as the answer to the <b>next</b> request. The connection is poisoned when
 * this is thrown, and a {@link Pool} retires it.
 *
 * <p>A named subclass of {@link TriCoreException} rather than the JDK's
 * {@code SocketTimeoutException}, so that an existing
 * {@code catch (TriCoreException)} sees it. A timeout that escaped the driver's
 * own error hierarchy — surfacing as a bare checked {@code IOException} with no
 * type a caller could branch on — is exactly the defect the Python driver
 * carried, and the wrapped {@code SocketTimeoutException} is kept as the cause.
 *
 * <p>The steady-state read timeout has <b>no default</b>. A read legitimately
 * blocks for exactly as long as the statement runs, the server's own
 * {@code statement_timeout_ms} defaults to unlimited, and any number this driver
 * picked would be a guess at a guarantee the server does not make. A caller who
 * wants the <i>server</i> to stop rather than merely stopping the wait should
 * use {@link TriCore#setRequestTimeoutMs}; that is almost always the one you
 * want.
 */
public class TriCoreTimeoutException extends TriCoreException {

    private static final long serialVersionUID = 1L;

    public TriCoreTimeoutException(String message) {
        super(message);
    }

    public TriCoreTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
