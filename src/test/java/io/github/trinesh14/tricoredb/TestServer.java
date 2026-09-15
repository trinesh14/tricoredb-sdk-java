package io.github.trinesh14.tricoredb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;

/**
 * A private tricore-server for one test class: ephemeral port, own data dir,
 * every module on, dev_auth. Stopped through its own Process handle only.
 *
 * <p>Binary lookup: {@code -Dtricore.server.bin}, then {@code TRICORE_SERVER_BIN},
 * then the sibling checkout's {@code target/release} and {@code target/debug}.
 * With no binary the live tests are skipped, never passed.
 */
final class TestServer implements AutoCloseable {

    static final String USER = "admin";
    static final String SECRET = "pw";

    private static final Pattern LISTENING = Pattern.compile("listening on\\s+([0-9.]+):(\\d+)");

    final Process process;
    final String host;
    final int port;
    private final Path dir;

    private TestServer(Process process, String host, int port, Path dir) {
        this.process = process;
        this.host = host;
        this.port = port;
        this.dir = dir;
    }

    static Path binary() {
        List<String> candidates = new ArrayList<>();
        String prop = System.getProperty("tricore.server.bin");
        if (prop != null && !prop.isBlank()) {
            candidates.add(prop);
        }
        String env = System.getenv("TRICORE_SERVER_BIN");
        if (env != null && !env.isBlank()) {
            candidates.add(env);
        }
        String exe = System.getProperty("os.name").toLowerCase().contains("win")
                ? "tricore-server.exe" : "tricore-server";
        for (String profile : List.of("release", "debug")) {
            candidates.add(Paths.get("..", "..", "tricore", "tricore-db", "target", profile, exe).toString());
        }
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    static TestServer start(String nodeId) throws Exception {
        return start(nodeId, "");
    }

    /** {@code extraToml} is appended verbatim, e.g. a {@code [tls]} block. */
    static TestServer start(String nodeId, String extraToml) throws Exception {
        Path bin = binary();
        Assumptions.assumeTrue(bin != null,
                "no tricore-server binary: set -Dtricore.server.bin or TRICORE_SERVER_BIN");
        Path dir = Files.createTempDirectory("tricore-java-sdk-" + nodeId + "-");
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        String hasTls = extraToml.contains("[tls]") ? "" : "\n[tls]\nenabled = false\n";
        String cfg = "[server]\n"
                + "host = \"127.0.0.1\"\n"
                + "port = 0\n"
                + "node_id = \"" + nodeId + "\"\n"
                + "region_id = \"sdk-java-test\"\n"
                + "shutdown_grace_secs = 1\n\n"
                + "[modules]\n"
                + "sql = true\ndocument = true\ncache = true\nvector = true\ngraph = true\nllm = true\ncluster = true\n\n"
                + "[storage]\n"
                + "data_dir = \"" + data.toString().replace('\\', '/') + "\"\n"
                + "fsync = false\n\n"
                + "[security]\n"
                + "auth_mode = \"password\"\n"
                + "dev_auth = true\n\n"
                + "[security.login_rate_limit]\n"
                + "enabled = false\n\n"
                + "[observability]\n"
                + "port = 0\n"
                + hasTls
                + extraToml;
        Path cfgFile = dir.resolve("tricore.test.toml");
        Files.writeString(cfgFile, cfg, StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(bin.toString(), "--config", cfgFile.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder seen = new StringBuilder();
        String[] found = new String[2];
        CountDownLatch ready = new CountDownLatch(1);
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (ready.getCount() > 0) {
                        seen.append(line).append('\n');
                        Matcher m = LISTENING.matcher(line);
                        if (m.find()) {
                            found[0] = m.group(1);
                            found[1] = m.group(2);
                            ready.countDown();
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process ended
            }
        }, "tricore-server-drain-" + nodeId);
        drain.setDaemon(true);
        drain.start();

        if (!ready.await(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException("tricore-server (pid " + proc.pid()
                    + ") did not report a port within 60s:\n" + seen);
        }
        return new TestServer(proc, found[0], Integer.parseInt(found[1]), dir);
    }

    TriCore connect() {
        return TriCore.connect(host, port, USER, SECRET);
    }

    TriCore connect(long features) {
        return TriCore.connect(host, port, USER, SECRET, "tricoredb-java-test", 10_000, null, 0, features);
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // a lingering handle on a temp dir is not a test failure
                }
            });
        }
    }
}
