package io.github.trinesh14.tricoredb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Verified TLS against a live server with throwaway openssl certificates; skipped without openssl. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsLiveTest {

    private Path certs;
    private TestServer server;

    private static String openssl() {
        List<String> candidates = new ArrayList<>(List.of("openssl"));
        candidates.add("C:\\Program Files\\Git\\mingw64\\bin\\openssl.exe");
        candidates.add("C:\\Program Files\\Git\\usr\\bin\\openssl.exe");
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) {
                    return c;
                }
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed:\n" + out);
        }
    }

    private static String fwd(Path p) {
        return p.toString().replace('\\', '/');
    }

    @BeforeAll
    void start() throws Exception {
        Assumptions.assumeTrue(TestServer.binary() != null, "no tricore-server binary");
        String ssl = openssl();
        Assumptions.assumeTrue(ssl != null, "openssl not found; TLS live test skipped");
        certs = Files.createTempDirectory("tricore-java-tls-");
        run(certs, ssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.pem",
                "-days", "2", "-subj", "/CN=tricore-test-ca");
        run(certs, ssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "other-ca.key",
                "-out", "other-ca.pem", "-days", "2", "-subj", "/CN=tricore-other-ca");
        run(certs, ssl, "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key", "-out", "server.csr",
                "-subj", "/CN=localhost");
        Files.writeString(certs.resolve("ext.cnf"), "subjectAltName=DNS:localhost,IP:127.0.0.1\n");
        run(certs, ssl, "x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
                "-CAcreateserial", "-out", "server.pem", "-days", "2", "-extfile", "ext.cnf");
        server = TestServer.start("sdk-java-tls", "\n[tls]\nenabled = true\n"
                + "cert_file = \"" + fwd(certs.resolve("server.pem")) + "\"\n"
                + "key_file = \"" + fwd(certs.resolve("server.key")) + "\"\n"
                + "ca_file = \"" + fwd(certs.resolve("ca.pem")) + "\"\n"
                + "require_client_cert = false\n");
    }

    @AfterAll
    void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private TlsOptions good() {
        return TlsOptions.builder().caFile(certs.resolve("ca.pem").toString()).serverName("localhost").build();
    }

    private void refuses(TlsOptions tls) {
        assertThrows(TriCoreException.class, () -> {
            try (TriCore db = TriCore.connect(server.host, server.port, TestServer.USER, TestServer.SECRET,
                    "tricoredb-java-tls", 10_000, tls)) {
                db.ping();
            }
        });
    }

    @Test
    void verifiedTlsRoundTripsPastOneRecord() {
        try (TriCore db = TriCore.connect(server.host, server.port, TestServer.USER, TestServer.SECRET, good())) {
            db.cacheSet("javatls", "k", "hello-tls".getBytes(StandardCharsets.UTF_8));
            assertEquals("hello-tls", new String(db.cacheGet("javatls", "k").orElseThrow(), StandardCharsets.UTF_8));
            byte[] big = new byte[128 * 1024];
            Arrays.fill(big, (byte) 0xab);
            db.cacheSet("javatls", "big", big);
            assertArrayEquals(big, db.cacheGet("javatls", "big").orElseThrow());
        }
    }

    @Test
    void refusesAnUntrustedCaAnEmptyTrustStoreAndAWrongName() {
        refuses(TlsOptions.builder().caFile(certs.resolve("other-ca.pem").toString()).serverName("localhost").build());
        refuses(TlsOptions.builder().serverName("localhost").build());
        refuses(TlsOptions.builder().caFile(certs.resolve("ca.pem").toString()).serverName("wrong.example.com").build());
    }

    @Test
    void thePoolCarriesTls() {
        try (Pool pool = new Pool(server.host, server.port, TestServer.USER, TestServer.SECRET, 2, good())) {
            pool.use(db -> {
                db.cacheSet("javatls", "pooled", "via-pool".getBytes(StandardCharsets.UTF_8));
                assertEquals("via-pool",
                        new String(db.cacheGet("javatls", "pooled").orElseThrow(), StandardCharsets.UTF_8));
            });
        }
    }

    @Test
    void halfAClientIdentityIsRejected() {
        assertThrows(TriCoreException.class, () -> TlsOptions.builder()
                .caFile(Paths.get(certs.toString(), "ca.pem").toString())
                .clientCertFile(certs.resolve("server.pem").toString()).build());
    }
}
