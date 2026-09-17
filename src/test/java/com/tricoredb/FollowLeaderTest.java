package com.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

/**
 * Following the leader: what the driver does with {@code not_leader} by default.
 * Scripted peers stand in for a follower and a leader; a real follower's refusal
 * was applied nowhere, which is what makes sending the request again safe.
 */
class FollowLeaderTest {

    /** A peer serving any number of connections; {@code reply(requestId, n)} answers the n-th request. */
    static final class Peer implements AutoCloseable {
        final ServerSocket listener;
        final BiFunction<String, Integer, String> reply;
        final List<String> users = new ArrayList<>();
        final List<String> requestIds = new ArrayList<>();
        int connections;

        Peer(BiFunction<String, Integer, String> reply) throws IOException {
            this.reply = reply;
            this.listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::accept, "follow-peer");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        String address() {
            return "127.0.0.1:" + port();
        }

        synchronized int requests() {
            return requestIds.size();
        }

        synchronized int connections() {
            return connections;
        }

        private void accept() {
            while (true) {
                Socket s;
                try {
                    s = listener.accept();
                } catch (IOException e) {
                    return;
                }
                synchronized (this) {
                    connections++;
                }
                Thread t = new Thread(() -> serve(s), "follow-peer-conn");
                t.setDaemon(true);
                t.start();
            }
        }

        private void serve(Socket s) {
            try (s) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                while (true) {
                    byte[] header = new byte[6];
                    in.readFully(header);
                    int length = ((header[2] & 0xff) << 24) | ((header[3] & 0xff) << 16)
                            | ((header[4] & 0xff) << 8) | (header[5] & 0xff);
                    byte[] body = new byte[length];
                    in.readFully(body);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> json = length > 0
                            ? (Map<String, Object>) Json.decode(new String(body, StandardCharsets.UTF_8))
                            : Map.of();
                    switch (header[1]) {
                        case 0 -> write(out, 8, "{\"ok\":true,\"features\":7}");
                        case 1 -> {
                            synchronized (this) {
                                users.add(String.valueOf(json.get("username")));
                            }
                            write(out, 9, "{\"ok\":true,\"session_id\":\"s-1\"}");
                        }
                        case 7 -> {
                            write(out, 10, "{}");
                            return;
                        }
                        case 2 -> {
                            String rid = String.valueOf(json.get("request_id"));
                            int n;
                            synchronized (this) {
                                requestIds.add(rid);
                                n = requestIds.size();
                            }
                            write(out, 3, reply.apply(rid, n));
                        }
                        default -> { }
                    }
                }
            } catch (IOException ignored) {
                // the driver hanging up is the normal end
            }
        }

        private static void write(OutputStream out, int tag, String json) throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[6 + body.length];
            frame[0] = 1;
            frame[1] = (byte) tag;
            frame[2] = (byte) (body.length >>> 24);
            frame[3] = (byte) (body.length >>> 16);
            frame[4] = (byte) (body.length >>> 8);
            frame[5] = (byte) body.length;
            System.arraycopy(body, 0, frame, 6, body.length);
            out.write(frame);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    static String ok(String rid) {
        return "{\"request_id\":\"" + rid + "\",\"status\":\"ok\","
                + "\"data\":{\"Json\":{\"transaction\":\"began\",\"statements\":0,\"committed_writes\":0,\"discarded_writes\":0}},\"diagnostics\":{}}";
    }

    static String refusal(String rid, String code, String leader) {
        String hint = leader == null ? "" : ",\"leader_hint\":\"" + leader + "\"";
        return "{\"request_id\":\"" + rid + "\",\"status\":\"error\","
                + "\"data\":{\"Message\":\"not the raft leader\"},"
                + "\"diagnostics\":{\"error_code\":\"" + code + "\"" + hint + "}}";
    }

    static TriCore connect(Peer peer, String user) {
        TriCore db = TriCore.connect("127.0.0.1", peer.port(), user, user == null ? null : "s");
        db.setLeaderRetryDelayMs(1);
        return db;
    }

    @Test
    void aRefusedRequestGoesToTheLeaderAndStaysThere() throws Exception {
        try (Peer leader = new Peer((rid, n) -> ok(rid));
             Peer follower = new Peer((rid, n) -> refusal(rid, "not_leader", leader.address()));
             TriCore db = connect(follower, "u")) {
            db.execute("INSERT INTO t VALUES (1)");
            assertEquals(leader.address(), db.address());
            db.execute("INSERT INTO t VALUES (2)");
            assertEquals(1, follower.requests());
            assertEquals(2, leader.requests());
            assertEquals(1, leader.connections());
            assertEquals(List.of("u"), leader.users);
            assertEquals(follower.requestIds.get(0), leader.requestIds.get(0));
        }
    }

    @Test
    void withNoLeaderNamedTheDriverWaitsAndAsksAgain() throws Exception {
        try (Peer peer = new Peer((rid, n) -> n < 3 ? refusal(rid, "not_leader", null) : ok(rid));
             TriCore db = connect(peer, null)) {
            db.execute("INSERT INTO t VALUES (1)");
            assertEquals(3, peer.requests());
            assertEquals(1, peer.connections());
        }
    }

    @Test
    void retriesAreBoundedAndReported() throws Exception {
        try (Peer peer = new Peer((rid, n) -> refusal(rid, "not_leader", null));
             TriCore db = connect(peer, null)) {
            db.setLeaderRetries(2);
            TriCoreException e = assertThrows(TriCoreException.class, () -> db.execute("INSERT INTO t VALUES (1)"));
            assertTrue(e.isNotLeader());
            assertTrue(e.getMessage().contains("sent again 2 times"), e.getMessage());
            assertEquals(3, peer.requests());
        }
    }

    @Test
    void anUnreachableLeaderLeavesTheConnectionWhereItWas() throws Exception {
        String nowhere;
        try (ServerSocket dead = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            nowhere = "127.0.0.1:" + dead.getLocalPort();
        }
        String target = nowhere;
        try (Peer peer = new Peer((rid, n) -> n == 1 ? refusal(rid, "not_leader", target) : ok(rid));
             TriCore db = connect(peer, null)) {
            db.execute("INSERT INTO t VALUES (1)");
            assertEquals(peer.address(), db.address());
            assertEquals(2, peer.requests());
        }
    }

    @Test
    void nothingIsSentAgainInsideASessionTransaction() throws Exception {
        try (Peer peer = new Peer((rid, n) -> n == 1 ? ok(rid) : refusal(rid, "not_leader", null));
             TriCore db = connect(peer, null)) {
            db.begin();
            TriCoreException e = assertThrows(TriCoreException.class, () -> db.execute("INSERT INTO t VALUES (1)"));
            assertTrue(e.isNotLeader());
            assertEquals(2, peer.requests());
        }
    }

    @Test
    void outcomeUnknownIsNeverSentAgain() throws Exception {
        try (Peer peer = new Peer((rid, n) -> refusal(rid, TriCoreException.ERROR_CODE_OUTCOME_UNKNOWN, null));
             TriCore db = connect(peer, null)) {
            TriCoreException e = assertThrows(TriCoreException.class, () -> db.execute("INSERT INTO t VALUES (1)"));
            assertEquals("outcome_unknown", e.errorCode());
            assertFalse(e.isNotLeader());
            assertEquals(1, peer.requests());
        }
    }

    @Test
    void pooledConnectionsFollowTheLeader() throws Exception {
        try (Peer leader = new Peer((rid, n) -> ok(rid));
             Peer follower = new Peer((rid, n) -> refusal(rid, "not_leader", leader.address()))) {
            Pool pool = new Pool("127.0.0.1", follower.port(), null, null, 2);
            pool.setLeaderRetryDelayMs(1);
            try {
                for (int i = 0; i < 2; i++) {
                    int n = i;
                    pool.use(db -> db.execute("INSERT INTO t VALUES (" + n + ")"));
                }
            } finally {
                pool.close();
            }
            assertEquals(1, follower.requests());
            assertEquals(2, leader.requests());
        }
    }
}
