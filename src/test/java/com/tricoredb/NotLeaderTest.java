package com.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The leader redirect as a typed error. A single node is never a follower, so a
 * scripted peer plays the refusal in the exact RESPONSE shape the server sends.
 */
class NotLeaderTest {

    private static TriCoreException refusal(String responseJson) throws Exception {
        try (ScriptedPeer peer = ScriptedPeer.start(s -> {
            ScriptedPeer.expectFrame(s);
            ScriptedPeer.writeFrame(s, 8, "{\"ok\":true,\"server_version\":{\"major\":1,\"minor\":0},"
                    + "\"message\":\"ok\",\"features\":7}");
            ScriptedPeer.expectFrame(s);
            ScriptedPeer.writeFrame(s, 3, responseJson);
            ScriptedPeer.expectFrame(s);
        })) {
            try (TriCore db = peer.connect()) {
                return assertThrows(TriCoreException.class,
                        () -> db.execute("INSERT INTO t VALUES (?)", List.of(1), "main"));
            }
        }
    }

    @Test
    void notLeaderWithAHintCarriesCodeAndHostPort() throws Exception {
        TriCoreException e = refusal("{\"request_id\":\"r1\",\"status\":\"error\","
                + "\"data\":{\"Message\":\"not the raft leader - send writes to `n2`\"},"
                + "\"diagnostics\":{\"error_code\":\"not_leader\",\"leader_hint\":\"10.9.9.7:8427\"}}");
        assertEquals(TriCoreException.ERROR_CODE_NOT_LEADER, e.errorCode());
        assertEquals("not_leader", e.errorCode());
        assertTrue(e.isNotLeader());
        assertEquals("10.9.9.7:8427", e.leaderHint());
    }

    @Test
    void notLeaderMidElectionHasNoHint() throws Exception {
        TriCoreException e = refusal("{\"request_id\":\"r1\",\"status\":\"error\","
                + "\"data\":{\"Message\":\"not the raft leader\"},"
                + "\"diagnostics\":{\"error_code\":\"not_leader\"}}");
        assertTrue(e.isNotLeader());
        assertNull(e.leaderHint());
    }

    @Test
    void anOrdinaryFailureIsNotARedirect() throws Exception {
        TriCoreException e = refusal("{\"request_id\":\"r1\",\"status\":\"error\","
                + "\"data\":{\"Message\":\"syntax error\"},"
                + "\"diagnostics\":{\"error_code\":\"request.invalid\"}}");
        assertFalse(e.isNotLeader());
        assertEquals("request.invalid", e.errorCode());
        assertNull(e.leaderHint());
    }

    @Test
    void theDriverDoesNotFollowTheRedirect() throws Exception {
        int[] connections = {0};
        try (ScriptedPeer peer = ScriptedPeer.start(s -> {
            connections[0]++;
            ScriptedPeer.expectFrame(s);
            ScriptedPeer.writeFrame(s, 8, "{\"ok\":true,\"features\":7}");
            ScriptedPeer.expectFrame(s);
            ScriptedPeer.writeFrame(s, 3, "{\"request_id\":\"r1\",\"status\":\"error\","
                    + "\"data\":{\"Message\":\"not the raft leader\"},"
                    + "\"diagnostics\":{\"error_code\":\"not_leader\",\"leader_hint\":\"127.0.0.1:1\"}}");
            ScriptedPeer.expectFrame(s);
        })) {
            try (TriCore db = peer.connect()) {
                TriCoreException e = assertThrows(TriCoreException.class, () -> db.execute("INSERT INTO t VALUES (1)"));
                assertTrue(e.isNotLeader());
                assertEquals("127.0.0.1:1", e.leaderHint());
                assertFalse(db.isPoisoned(), "a refusal is an answer, not a broken connection");
            }
        }
        assertEquals(1, connections[0]);
    }
}
