package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.yanoproject.x.appchain.formats.Ed25519;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One operator's node home ({@code --node-dir}, default {@code ./yano-node}): the member key, the advertised
 * addresses, the joined applications, generated configuration and node data. Keys never leave it.
 */
final class NodeDir {
    static final String DEFAULT = "yano-node";

    /** {@code ./yano-node}, or the current directory when the operator runs commands from inside their node. */
    static Path defaultDir() {
        return !Files.isDirectory(Path.of(DEFAULT)) && Files.isRegularFile(Path.of("node.json")) ? Path.of(".")
                : Path.of(DEFAULT);
    }
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /** Advertised addresses and local ports, fixed at the first {@code request}. */
    record Identity(String name, String publicKey, String node, String http, int listenPort, int httpPort) {
    }

    /**
     * This node's local view of one joined application: the accepted fingerprint and bundle digests, peer
     * address overrides, and members added or removed through governance since seal.
     */
    record Local(String fingerprint, Map<String, String> bundles, Map<String, String> peerOverrides,
                 List<String> extraMembers, List<String> removedMembers) {
        Local {
            bundles = new TreeMap<>(bundles == null ? Map.of() : bundles);
            peerOverrides = new TreeMap<>(peerOverrides == null ? Map.of() : peerOverrides);
            extraMembers = List.copyOf(extraMembers == null ? List.of() : extraMembers);
            removedMembers = List.copyOf(removedMembers == null ? List.of() : removedMembers);
        }
    }

    private final Path root;

    NodeDir(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    Path path(String first, String... more) {
        return root.resolve(Path.of(first, more));
    }

    boolean hasKey() {
        return Files.isRegularFile(path("secrets", "member.key"));
    }

    String seed() {
        Path key = path("secrets", "member.key");
        if (!Files.isRegularFile(key)) {
            throw CliException.failure("No member key in " + root + "; run `yano.sh appchain request` first");
        }
        return read(key).strip();
    }

    String publicKey() {
        return Ed25519.publicKey(seed());
    }

    /** Creates the member key once, or imports {@code importFrom}; never replaces an existing key. */
    String ensureKey(Path importFrom) {
        if (hasKey()) {
            if (importFrom != null && !read(importFrom).strip().equals(seed())) {
                throw CliException.failure("This node directory already has a different member key; "
                        + "use another --node-dir for another key");
            }
            return seed();
        }
        String seed = importFrom == null ? Ed25519.newSeed() : read(importFrom).strip();
        Ed25519.publicKey(seed);
        writeSecret(path("secrets", "member.key"), seed + "\n");
        return seed;
    }

    String apiKey() {
        Path key = path("secrets", "api.key");
        if (!Files.isRegularFile(key)) writeSecret(key, Ed25519.newSeed() + "\n");
        return read(key).strip();
    }

    Identity identity() {
        Path file = path("node.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return JSON.readValue(file.toFile(), Identity.class);
        } catch (IOException error) {
            throw CliException.failure("Cannot read " + file + ": " + error.getMessage());
        }
    }

    void saveIdentity(Identity identity) {
        try {
            write(path("node.json"), JSON.writeValueAsString(identity) + "\n");
        } catch (IOException error) {
            throw CliException.failure("Cannot write node.json: " + error.getMessage());
        }
    }

    Identity requireIdentity() {
        Identity identity = identity();
        if (identity == null) {
            throw CliException.failure("No node set up in " + root + "; run `yano.sh appchain request` first");
        }
        return identity;
    }

    Local local(String app) {
        Path file = path("apps", app + ".local.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return JSON.readValue(file.toFile(), Local.class);
        } catch (IOException error) {
            throw CliException.failure("Cannot read " + file + ": " + error.getMessage());
        }
    }

    void saveLocal(String app, Local local) {
        try {
            write(path("apps", app + ".local.json"), JSON.writeValueAsString(local) + "\n");
        } catch (IOException error) {
            throw CliException.failure("Cannot write the local state of " + app + ": " + error.getMessage());
        }
    }

    /** Joined applications with their local view, for configuration rendering. */
    List<NodeConfig.Joined> joined() {
        List<NodeConfig.Joined> result = new ArrayList<>();
        for (SealedApp app : apps()) {
            Local local = local(app.genesis().name());
            if (local == null) continue;
            List<org.yanoproject.x.appchain.formats.MemberCard> extra = local.extraMembers().stream()
                    .map(org.yanoproject.x.appchain.formats.MemberCard::parse).toList();
            result.add(new NodeConfig.Joined(app, local.peerOverrides(), extra, local.removedMembers()));
        }
        return result;
    }

    /**
     * The config index of every joined chain, assigned once in join order and never changed, so settings an operator
     * writes in config/local.yaml under {@code chains[i]} keep meaning the same chain.
     */
    Map<String, Integer> chainIndexes(List<NodeConfig.Joined> joined) {
        Path file = path("apps", "chain-indexes.json");
        Map<String, Integer> indexes = new java.util.LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                JSON.readTree(file.toFile()).properties()
                        .forEach(entry -> indexes.put(entry.getKey(), entry.getValue().asInt()));
            } catch (IOException error) {
                throw CliException.failure("Cannot read " + file + ": " + error.getMessage());
            }
        }
        boolean changed = false;
        for (NodeConfig.Joined entry : joined) {
            for (SealedApp.ChainGenesis chain : entry.app().genesis().chains()) {
                if (!indexes.containsKey(chain.id())) {
                    indexes.put(chain.id(), indexes.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);
                    changed = true;
                }
            }
        }
        if (changed) {
            try {
                write(file, JSON.writeValueAsString(indexes) + "\n");
            } catch (IOException error) {
                throw CliException.failure("Cannot write " + file + ": " + error.getMessage());
            }
        }
        return indexes;
    }

    Path appFile(String app) {
        return path("apps", app + ".chain");
    }

    List<SealedApp> apps() {
        Path apps = path("apps");
        List<SealedApp> result = new ArrayList<>();
        if (!Files.isDirectory(apps)) return result;
        try (var files = Files.list(apps)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".chain")).sorted().toList()) {
                result.add(SealedApp.parse(read(file)));
            }
        } catch (IOException error) {
            throw CliException.failure("Cannot read joined applications: " + error.getMessage());
        }
        return result;
    }

    SealedApp app(String name) {
        Path file = appFile(name);
        if (!Files.isRegularFile(file)) {
            List<String> joined = apps().stream().map(app -> app.genesis().name()).toList();
            throw CliException.failure("This node has not joined '" + name + "'"
                    + (joined.isEmpty() ? "; run `yano.sh appchain join <app>.chain`"
                    : "; it has joined " + String.join(", ", joined)));
        }
        return SealedApp.parse(read(file));
    }

    /** The only application, when a command was given none. */
    SealedApp onlyApp() {
        List<SealedApp> apps = apps();
        if (apps.size() == 1) return apps.getFirst();
        if (apps.isEmpty()) throw CliException.failure("This node has not joined an application yet");
        throw CliException.usage("This node runs several applications; name one: "
                + String.join(", ", apps.stream().map(app -> app.genesis().name()).toList()));
    }

    /** True once the node has written app-chain or Cardano data. */
    boolean hasData() {
        for (String store : new String[]{"appchain-chainstate", "chainstate"}) {
            Path data = path("data", store);
            try (var entries = Files.exists(data) ? Files.list(data) : java.util.stream.Stream.<Path>empty()) {
                if (entries.findAny().isPresent()) return true;
            } catch (IOException error) {
                return true;
            }
        }
        return false;
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw CliException.failure("Cannot read " + file + ": " + error.getMessage());
        }
    }

    /** Writes through a temporary file and an atomic rename, so readers never see half a file. */
    static void write(Path file, String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".tmp-", ".part");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw CliException.failure("Cannot write " + file + ": " + error.getMessage());
        }
    }

    static void writeSecret(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            try {
                Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system.
            }
            write(file, content);
            try {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX file system.
            }
        } catch (IOException error) {
            throw CliException.failure("Cannot write " + file + ": " + error.getMessage());
        }
    }
}
