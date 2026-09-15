package io.github.trinesh14.tricoredb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * TLS settings for {@link TriCore}. Mirrors the Rust client's {@code TlsOptions}
 * ({@code crates/tricore_client/src/tls.rs}).
 *
 * <p>Passing a {@code TlsOptions} at all is what turns TLS on. Once on, the server
 * certificate is verified <b>and</b> its name is checked, unless the caller sets
 * {@link Builder#dangerAcceptInvalidCerts(boolean)}.
 *
 * <pre>{@code
 * TlsOptions tls = TlsOptions.builder()
 *         .caFile("/etc/tricore/ca.pem")
 *         .serverName("db.internal")
 *         .build();
 * try (TriCore db = TriCore.connect("db.internal", 8427, "admin", "pw", tls)) { ... }
 * }</pre>
 *
 * <h2>Two JDK defaults are corrected here</h2>
 *
 * <p><b>Hostname verification is off by default on a raw {@link SSLSocket}.</b> Unlike
 * {@code HttpsURLConnection}, a plain {@code SSLSocket} validates the certificate chain
 * but never checks that the certificate actually belongs to the host you dialled — so a
 * valid certificate for <i>any</i> host, issued by a trusted CA, would be accepted. This
 * class sets {@code SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")} to turn
 * that check on. Removing that line leaves a driver that looks secure and is not.
 *
 * <p><b>The trust store starts empty</b> rather than defaulting to the JDK {@code cacerts}
 * bundle, matching the Rust client, where an absent {@code ca_file} means an empty
 * {@code RootCertStore}. A typo'd {@code caFile} path therefore fails closed instead of
 * quietly succeeding against some unrelated public CA.
 *
 * <h2>Secret hygiene</h2>
 *
 * <p>Private key <b>contents</b> are never logged or placed in an exception message. On
 * failure the <i>path</i> is reported so an operator can find the file, never the bytes.
 */
public final class TlsOptions {

    private final String caFile;
    private final String serverName;
    private final boolean dangerAcceptInvalidCerts;
    private final String clientCertFile;
    private final String clientKeyFile;

    private TlsOptions(Builder b) {
        this.caFile = b.caFile;
        this.serverName = b.serverName;
        this.dangerAcceptInvalidCerts = b.dangerAcceptInvalidCerts;
        this.clientCertFile = b.clientCertFile;
        this.clientKeyFile = b.clientKeyFile;
        if ((clientCertFile == null) != (clientKeyFile == null)) {
            String missing = clientKeyFile == null ? "clientKeyFile" : "clientCertFile";
            throw new TriCoreException(
                    "tls " + missing + " is required alongside the other (both are needed for mTLS)");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder; every field is optional except that mTLS needs both cert and key. */
    public static final class Builder {
        private String caFile;
        private String serverName = "localhost";
        private boolean dangerAcceptInvalidCerts = false;
        private String clientCertFile;
        private String clientKeyFile;

        /** PEM CA bundle used to verify the server. Omit and the trust store stays empty. */
        public Builder caFile(String path) {
            this.caFile = path;
            return this;
        }

        /** Expected server name: used for SNI and for the certificate name check. */
        public Builder serverName(String name) {
            this.serverName = name;
            return this;
        }

        /**
         * <b>Development only.</b> Accept any server certificate without verifying it, and
         * skip the hostname check. A connection with this set looks encrypted but
         * authenticates nothing, so an active attacker can sit in the middle undetected —
         * strictly worse than visibly using plain TCP. Never enable it against a real server.
         */
        public Builder dangerAcceptInvalidCerts(boolean danger) {
            this.dangerAcceptInvalidCerts = danger;
            return this;
        }

        /** PEM client certificate chain to present (mTLS). Requires {@link #clientKeyFile}. */
        public Builder clientCertFile(String path) {
            this.clientCertFile = path;
            return this;
        }

        /** PEM PKCS#8 private key for {@link #clientCertFile} (mTLS). */
        public Builder clientKeyFile(String path) {
            this.clientKeyFile = path;
            return this;
        }

        public TlsOptions build() {
            return new TlsOptions(this);
        }
    }

    String serverName() {
        return serverName;
    }

    /**
     * Layer TLS over an already-connected socket and complete the handshake.
     *
     * <p>The handshake is forced eagerly via {@link SSLSocket#startHandshake()} rather than
     * left to happen lazily on first write. Otherwise a certificate rejection would surface
     * later, in the middle of the HELLO exchange, and look like a protocol error instead of
     * the trust failure it is.
     */
    SSLSocket wrap(Socket plain) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers(), trustManagers(), null);

            SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(plain, serverName, plain.getPort(), true);
            ssl.setUseClientMode(true);

            SSLParameters params = ssl.getSSLParameters();
            if (!dangerAcceptInvalidCerts) {
                // THE line that makes this driver actually secure — see the class
                // javadoc. Without it the chain is validated but the name is not.
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            ssl.setSSLParameters(params);

            ssl.startHandshake();
            return ssl;
        } catch (IOException | GeneralSecurityException e) {
            closeQuietly(plain);
            throw new TriCoreException("tls handshake with `" + serverName + "` failed: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Already failing; a close error adds nothing.
        }
    }

    /** Trust managers: either the "verify nothing" one, or a store built from caFile. */
    private TrustManager[] trustManagers() throws GeneralSecurityException, IOException {
        if (dangerAcceptInvalidCerts) {
            return new TrustManager[] { INSECURE_TRUST_MANAGER };
        }
        // An empty store (no caFile) trusts nothing, which is the intended
        // fail-closed behaviour rather than a fallback to the JDK cacerts bundle.
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        if (caFile != null) {
            List<X509Certificate> cas = readCertificates(caFile, "caFile");
            int i = 0;
            for (X509Certificate c : cas) {
                ks.setCertificateEntry("ca-" + (i++), c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    /** Key managers for mTLS, or null when no client identity was configured. */
    private KeyManager[] keyManagers() throws GeneralSecurityException, IOException {
        if (clientCertFile == null) {
            return null;
        }
        List<X509Certificate> chain = readCertificates(clientCertFile, "clientCertFile");
        if (chain.isEmpty()) {
            throw new TriCoreException("tls clientCertFile `" + clientCertFile + "` has no certificates");
        }
        PrivateKey key = readPrivateKey(clientKeyFile);

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        // An in-memory keystore still demands a password; it never leaves this
        // method, so an empty one is not a secret worth managing.
        char[] noPassword = new char[0];
        ks.setKeyEntry("client", key, noPassword, chain.toArray(new Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, noPassword);
        return kmf.getKeyManagers();
    }

    private static List<X509Certificate> readCertificates(String path, String label) {
        byte[] pem;
        try {
            pem = Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new TriCoreException("tls " + label + " `" + path + "`: " + e.getMessage(), e);
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(pem));
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : certs) {
                out.add((X509Certificate) c);
            }
            if (out.isEmpty()) {
                throw new TriCoreException("tls " + label + " `" + path + "` has no PEM certificates");
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new TriCoreException("tls " + label + " `" + path + "` parse failed: " + e.getMessage(), e);
        }
    }

    /**
     * Read an unencrypted PKCS#8 PEM private key.
     *
     * <p>The JDK has no PEM parser before Java 25, and this driver takes no third-party
     * dependency, so the PEM envelope is stripped by hand. Note what is deliberately absent
     * from every exception below: the key material. A parse failure reports the path and the
     * algorithm, never a fragment of the file, because exception messages end up in logs.
     */
    private PrivateKey readPrivateKey(String path) {
        String pem;
        try {
            pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TriCoreException("tls clientKeyFile `" + path + "`: " + e.getMessage(), e);
        }

        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < 0) {
            throw new TriCoreException("tls clientKeyFile `" + path
                    + "` is not an unencrypted PKCS#8 PEM key (expected a "
                    + "`BEGIN PRIVATE KEY` block; convert with "
                    + "`openssl pkcs8 -topk8 -nocrypt`)");
        }
        String base64 = pem.substring(from + begin.length(), to).replaceAll("\\s", "");

        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new TriCoreException("tls clientKeyFile `" + path + "` is not valid base64");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        // The key type is not stated in a PKCS#8 PEM header, so try the algorithms
        // the server may issue certificates for.
        for (String algorithm : new String[] { "RSA", "EC", "Ed25519" }) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException ignored) {
                // Wrong algorithm for this key; try the next.
            }
        }
        throw new TriCoreException("tls clientKeyFile `" + path
                + "` could not be parsed as an RSA, EC, or Ed25519 PKCS#8 key");
    }

    /**
     * A trust manager that accepts every certificate. Reachable only via
     * {@link Builder#dangerAcceptInvalidCerts(boolean)} — see the warning there.
     */
    private static final X509TrustManager INSECURE_TRUST_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Development-only: deliberately verifies nothing.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Development-only: deliberately verifies nothing.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
