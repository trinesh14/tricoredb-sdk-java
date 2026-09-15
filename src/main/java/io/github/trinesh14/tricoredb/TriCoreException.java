package io.github.trinesh14.tricoredb;

/**
 * Any failure from the server or the transport.
 *
 * Unchecked: a driver call sits behind SQL text or a socket, both of which
 * can fail in ways a caller cannot always plan for at each call site, so
 * this follows the same shape as {@code java.lang.reflect} / NIO rather
 * than forcing a checked-exception ripple through every method signature.
 */
public class TriCoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The server's {@code diagnostics.error_code} for a write or linearizable
     * read that reached a Raft follower.
     */
    public static final String ERROR_CODE_NOT_LEADER = "not_leader";

    private final String errorCode;
    private final String leaderHint;

    public TriCoreException(String message) {
        this(message, null, null);
    }

    public TriCoreException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.leaderHint = null;
    }

    /**
     * A failure carrying the server's machine-readable code and, for a leader
     * redirect, the node it named.
     */
    public TriCoreException(String message, String errorCode, String leaderHint) {
        super(message);
        this.errorCode = errorCode;
        this.leaderHint = leaderHint;
    }

    /**
     * The server's machine-readable reason ({@code diagnostics.error_code}), or
     * {@code null} when it sent none — a transport failure, or a refusal this
     * driver made before sending.
     *
     * <p>This is the field to branch on. The message is prose and stays free to
     * be reworded.
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * A {@code host:port} this request should have gone to, set only alongside
     * an {@link #errorCode()} of {@link #ERROR_CODE_NOT_LEADER}.
     *
     * <p>It is an <b>address a client can dial</b>, not a node id: the server
     * resolves the leader's id through {@code [[raft.peers]].address}, which
     * names that node's native protocol listener — the same endpoint this
     * driver already speaks, not a separate consensus port.
     *
     * <p>{@code null} means "no address was named", which is <b>not</b> the same
     * as "this is not a redirect". Three situations produce a code with no hint:
     * a refusal raised mid-election, a node running without Raft, and a leader
     * whose id has no {@code [[raft.peers]]} entry — where the server
     * deliberately sends nothing rather than a bare id, because a label in an
     * address field is a connection attempt to a host that does not exist. All
     * three mean the same thing to a caller: wait and retry.
     *
     * <p>The refusal's message still names the leader's <b>node id</b>, on
     * purpose: prose is for a human reading logs and the hint is for a machine
     * dialling. Never scrape the id out of the message and use it as a
     * destination — that is exactly what this field replaces.
     *
     * <p>Test {@link #isNotLeader()} for the redirect and treat a null hint as
     * an unknown destination.
     */
    public String leaderHint() {
        return leaderHint;
    }

    /**
     * Whether this refusal is a leader redirect: the request was valid, and this
     * node is not the one that may serve it.
     *
     * <p>This driver deliberately does not follow the redirect itself:
     * re-sending a write to another address is a policy decision (which
     * endpoints are reachable, which credentials apply there, whether the
     * operation is safe to repeat) that belongs to the caller, not to a
     * connection object.
     */
    public boolean isNotLeader() {
        return ERROR_CODE_NOT_LEADER.equals(errorCode);
    }
}
