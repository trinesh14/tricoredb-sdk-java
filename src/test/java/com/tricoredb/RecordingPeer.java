package com.tricoredb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A peer that completes the handshake, records every request the driver sends,
 * and answers each one the same way. What a typed method puts on the wire is
 * then read back from the real frame bytes rather than from the method's own
 * idea of them.
 */
final class RecordingPeer implements AutoCloseable {

    private static final int REQUEST = 2;
    private static final int RESPONSE = 3;
    private static final int CLOSE = 7;
    private static final int HELLO_OK = 8;
    private static final int BYE = 10;

    private final ScriptedPeer peer;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    private RecordingPeer(String response) throws IOException {
        this.peer = ScriptedPeer.start(s -> {
            ScriptedPeer.expectFrame(s);
            ScriptedPeer.writeFrame(s, HELLO_OK, "{\"ok\":true,\"server_version\":{\"major\":1,\"minor\":0},"
                    + "\"message\":\"ok\",\"features\":7}");
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (true) {
                byte[] header = new byte[6];
                try {
                    in.readFully(header);
                } catch (EOFException e) {
                    return;
                }
                int length = ((header[2] & 0xFF) << 24) | ((header[3] & 0xFF) << 16)
                        | ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                byte[] body = new byte[length];
                in.readFully(body);
                if (header[1] == CLOSE) {
                    ScriptedPeer.writeFrame(s, BYE, "{}");
                    return;
                }
                if (header[1] == REQUEST) {
                    requests.add(new String(body, StandardCharsets.UTF_8));
                    ScriptedPeer.writeFrame(s, RESPONSE, response);
                }
            }
        });
    }

    /** Answer every request {@code ok} with {@code dataJson} as its payload. */
    static RecordingPeer answering(String dataJson) throws IOException {
        return new RecordingPeer("{\"request_id\":\"r\",\"status\":\"ok\",\"data\":" + dataJson + "}");
    }

    /** Refuse every request, as a server without the operation's prerequisite does. */
    static RecordingPeer refusing() throws IOException {
        return new RecordingPeer("{\"request_id\":\"r\",\"status\":\"error\","
                + "\"data\":{\"Message\":\"invalid request: refused\"}}");
    }

    TriCore connect() {
        return peer.connect();
    }

    /** The last request's {@code op} is exactly {@code expectedOp}, and it named no transaction. */
    void assertSent(String expectedOp) {
        assertSent(expectedOp, null);
    }

    /**
     * The last request's {@code op} is exactly {@code expectedOp}, and it named
     * {@code txn} in its options. Field order is not part of the contract;
     * everything else is, including whether a number is an integer.
     */
    @SuppressWarnings("unchecked")
    void assertSent(String expectedOp, String txn) {
        Map<String, Object> sent = (Map<String, Object>) Json.decode(requests.get(requests.size() - 1));
        assertEquals(Json.decode(expectedOp), sent.get("op"));
        Object options = sent.get("options");
        Object sentTxn = options instanceof Map<?, ?> m ? m.get("txn") : null;
        assertEquals(txn, sentTxn);
    }

    @Override
    public void close() throws IOException {
        peer.close();
    }
}
