package com.tricoredb;

import java.util.List;
import java.util.Map;

/** A server response: typed data plus how it was produced. */
public final class Response {

    private final String requestId;
    private final String status;
    private final Object data;
    private final Map<String, Object> diagnostics;

    @SuppressWarnings("unchecked")
    Response(Map<String, Object> raw) {
        this.requestId = String.valueOf(raw.getOrDefault("request_id", ""));
        Object st = raw.get("status");
        this.status = st == null ? "error" : String.valueOf(st);
        this.data = raw.get("data");
        Object diag = raw.get("diagnostics");
        this.diagnostics = diag instanceof Map ? (Map<String, Object>) diag : Map.of();
    }

    public String requestId() {
        return requestId;
    }

    public String status() {
        return status;
    }

    public Object data() {
        return data;
    }

    public Map<String, Object> diagnostics() {
        return diagnostics;
    }

    /**
     * Non-fatal warnings.
     *
     * Not decorative: a broadcast DDL that could not reach every shard reports
     * it here <b>while still returning {@code ok}</b>, so a caller that ignores
     * this can miss a divergent cluster.
     */
    @SuppressWarnings("unchecked")
    public List<String> warnings() {
        Object w = diagnostics.get("warnings");
        return w instanceof List ? (List<String>) w : List.of();
    }

    public String route() {
        Object r = diagnostics.get("route");
        return r == null ? "" : String.valueOf(r);
    }

    public long elapsedMs() {
        Object e = diagnostics.get("elapsed_ms");
        return e instanceof Number ? ((Number) e).longValue() : 0L;
    }

    /**
     * The server's machine-readable reason a request failed
     * ({@code diagnostics.error_code}), or {@code null} when it did not fail or
     * the server sent none.
     *
     * <p>This is the field to branch on. The message is prose and stays free to
     * be reworded; substring-matching it is how an authorization denial once
     * reached clients as something else entirely, which is the failure the code
     * was added to end.
     */
    public String errorCode() {
        Object c = diagnostics.get("error_code");
        return c == null ? null : String.valueOf(c);
    }

    /**
     * A {@code host:port} this request should have gone to, carried only with an
     * {@link #errorCode()} of {@link TriCoreException#ERROR_CODE_NOT_LEADER}.
     *
     * <p>An address a client can dial, resolved by the server from
     * {@code [[raft.peers]].address}, not a node id. {@code null} means "no
     * address was named", which is <b>not</b> the same as "this is not a
     * redirect": a refusal raised mid-election, on a node without Raft, or for a
     * leader with no {@code [[raft.peers]]} entry all carry the code and no
     * hint. See {@link TriCoreException#leaderHint()}.
     */
    public String leaderHint() {
        Object h = diagnostics.get("leader_hint");
        return h == null ? null : String.valueOf(h);
    }

    @Override
    public String toString() {
        return "Response(status=" + status + ", route=" + route() + ")";
    }
}
