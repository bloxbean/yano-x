package org.yanoproject.app.e2e;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Starts one devnet node from the packaged Yano X JVM distribution for a {@link PackagedDevnet} class and points
 * {@link BaseE2ETest} at it through {@code yano.e2e.baseUrl}. A base URL supplied from outside wins: the class then
 * runs against that node instead.
 */
final class PackagedDevnetNode implements BeforeAllCallback, AfterAllCallback {
    private static final String BASE_URL = "yano.e2e.baseUrl";
    private static final Pattern OBSERVER_CHAIN = Pattern.compile("(yano\\.app-chain\\.chains\\[\\d+]\\.)observers\\..+");
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);

    private Process node;
    private Path log;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (System.getProperty(BASE_URL) != null) return;
        PackagedDevnet spec = context.getRequiredTestClass().getAnnotation(PackagedDevnet.class);
        String zip = System.getProperty("yano.e2e.distributionZip");
        if (zip == null) {
            throw new IllegalStateException("Run through ./gradlew :fixtures:eutxo-e2e:e2eTest, which supplies "
                    + "yano.e2e.distributionZip, or set " + BASE_URL + " to a running devnet node");
        }
        // Read before the base URL is set: the devnet profile returns no overrides once one exists.
        QuarkusTestProfile profile = spec.profile().getDeclaredConstructor().newInstance();
        Map<String, String> config = new TreeMap<>(profile.getConfigOverrides());

        Path work = Path.of(System.getProperty("yano.e2e.workDir", "build/e2e"));
        Path distribution = extract(Path.of(zip), work.resolve("distribution"));
        Path run = Files.createDirectories(work.resolve("runs")).resolve(context.getRequiredTestClass().getSimpleName());
        deleteTree(run);
        Path plugins = Files.createDirectories(run.resolve("plugins"));
        try (var bundles = Files.list(distribution.resolve("plugins"))) {
            for (Path bundle : bundles.toList()) {
                if (bundle.toString().endsWith(".jar") && !matches(bundle, spec.removeBundles())) {
                    Files.copy(bundle, plugins.resolve(bundle.getFileName()));
                }
            }
        }
        try (var optional = Files.list(distribution.resolve("optional-plugins"))) {
            for (Path bundle : optional.toList()) {
                if (matches(bundle, spec.addOptionalBundles())) Files.copy(bundle, plugins.resolve(bundle.getFileName()));
            }
        }
        Path genesis = run.resolve("shelley-genesis.json");
        Files.copy(Path.of(config.get("yano.genesis.shelley-genesis-file")), genesis);
        int http = freePort();
        int server = freePort();
        config.put("yano.genesis.shelley-genesis-file", genesis.toString());
        config.put("yano.storage.path", Files.createDirectories(run.resolve("chainstate")).toString());
        config.put("yano.history.dir", Files.createDirectories(run.resolve("history")).toString());
        config.put("yano.app-chain.storage.path", Files.createDirectories(run.resolve("appchain")).toString());
        config.put("yano.plugins.directory", plugins.toString());
        config.put("yano.plugins.enabled", "true");
        config.put("yano.plugins.startup-diagnostics", "full");
        config.put("quarkus.http.port", Integer.toString(http));
        config.put("yano.server.port", Integer.toString(server));
        // L1 observers need the identity of the exact genesis the node loads (ADR-036).
        String genesisId = sha256(genesis);
        for (String key : new TreeSet<>(config.keySet())) {
            Matcher observer = OBSERVER_CHAIN.matcher(key);
            if (observer.matches()) config.putIfAbsent(observer.group(1) + "observation.l1-network-genesis-id", genesisId);
        }

        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx2g",
                "-Dquarkus.profile=devnet"));
        // Both forms: a profiled default in the packaged configuration would otherwise win over a plain override.
        config.forEach((key, value) -> {
            command.add("-D" + key + "=" + value);
            command.add("-D%devnet." + key + "=" + value);
        });
        command.addAll(List.of("-jar", distribution.resolve("yano.jar").toString()));
        log = run.resolve("node.log");
        node = new ProcessBuilder(command).directory(distribution.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        awaitReady(http);
        System.setProperty(BASE_URL, "http://127.0.0.1:" + http + "/api/v1/");
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (node == null) return;
        System.clearProperty(BASE_URL);
        node.destroy();
        if (!node.waitFor(30, TimeUnit.SECONDS)) {
            node.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        node = null;
    }

    private void awaitReady(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest ready = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/q/health/ready"))
                .timeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            // A failed start can leave the process up with health DOWN; the log says so at once.
            if (!node.isAlive() || Files.readString(log).contains("YANO_STARTUP_FAILURE")) break;
            try {
                if (client.send(ready, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
            } catch (IOException notYet) {
                // The node is still starting.
            }
            Thread.sleep(1000);
        }
        List<String> tail = Files.readAllLines(log);
        node.destroyForcibly();
        throw new IllegalStateException("Packaged devnet node did not become ready; log " + log + ":\n"
                + String.join("\n", tail.subList(Math.max(0, tail.size() - 40), tail.size())));
    }

    private static Path extract(Path zip, Path target) throws IOException {
        Path marker = target.resolve(".extracted-from");
        String identity = zip.toAbsolutePath() + "\n" + Files.size(zip) + "\n" + Files.getLastModifiedTime(zip);
        if (Files.isRegularFile(marker) && Files.readString(marker).equals(identity)) return root(target);
        deleteTree(target);
        Files.createDirectories(target);
        try (InputStream file = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(file)) {
            for (ZipEntry entry; (entry = entries.getNextEntry()) != null; ) {
                Path path = target.resolve(entry.getName()).normalize();
                if (!path.startsWith(target)) throw new IOException("unsafe distribution entry " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.copy(entries, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(marker, identity);
        return root(target);
    }

    /** The single top-level directory of the extracted release. */
    private static Path root(Path target) throws IOException {
        try (var contents = Files.list(target)) {
            List<Path> directories = contents.filter(Files::isDirectory).toList();
            if (directories.size() != 1) throw new IOException("expected one release directory in " + target);
            return directories.getFirst();
        }
    }

    private static boolean matches(Path bundle, String[] prefixes) {
        String name = bundle.getFileName().toString();
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
