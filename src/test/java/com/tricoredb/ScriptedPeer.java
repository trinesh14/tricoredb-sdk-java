package com.tricoredb;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A raw socket peer that speaks real frame bytes and can misbehave on purpose. */
final class ScriptedPeer implements AutoCloseable {

    interface Script {
        void run(Socket s) throws Exception;
    }

    private final ServerSocket listener;

    private ScriptedPeer(ServerSocket listener, Script script) {
        this.listener = listener;
        Thread t = new Thread(() -> {
            try (Socket s = listener.accept()) {
                s.setTcpNoDelay(true);
                script.run(s);
            } catch (Exception ignored) {
                // the driver hanging up mid-script ends a case normally
            }
        }, "scripted-peer");
        t.setDaemon(true);
        t.start();
    }

    static ScriptedPeer start(Script script) throws IOException {
        return new ScriptedPeer(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()), script);
    }

    int port() {
        return listener.getLocalPort();
    }

    TriCore connect() {
        return connect(null, null, 10_000, 0);
    }

    TriCore connect(String user, String secret, int timeoutMs, int readTimeoutMs) {
        return TriCore.connect("127.0.0.1", port(), user, secret, "scripted", timeoutMs, null, readTimeoutMs);
    }

    static void writeHeader(Socket s, int version, int tag, long length) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(new byte[] {
                (byte) version, (byte) tag,
                (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length,
        });
        out.flush();
    }

    static void writeFrame(Socket s, int tag, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        writeHeader(s, 1, tag, body.length);
        s.getOutputStream().write(body);
        s.getOutputStream().flush();
    }

    static void expectFrame(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        byte[] header = new byte[6];
        in.readFully(header);
        long length = ((long) (header[2] & 0xFF) << 24) | ((long) (header[3] & 0xFF) << 16)
                | ((long) (header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        if (length > 0) {
            in.readFully(new byte[(int) length]);
        }
    }

    static void sleepForever() throws InterruptedException {
        Thread.sleep(5 * 60_000L);
    }

    @Override
    public void close() throws IOException {
        listener.close();
    }
}
