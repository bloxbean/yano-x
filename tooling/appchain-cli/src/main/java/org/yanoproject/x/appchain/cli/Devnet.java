package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Cardano devnet of an application (ADR-056 D11): the lead creates it at its first start, every other
 * node downloads the lead's genesis at its first start and keeps it.
 */
final class Devnet {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Devnet() {
    }

    static Path genesis(NodeDir node) {
        return node.path("runtime", "devnet-genesis.json");
    }

    /**
     * Makes sure this node has the devnet genesis. A genesis that never backed a running node (no Cardano data yet)
     * is replaced: the lead makes a fresh one, and a follower downloads the lead's current one, because a devnet
     * genesis cannot start long after its start time. Once a node has run on it, it is pinned for life.
     */
    static void prepare(NodeDir node, SealedApp app, Distribution distribution, Terminal terminal, long timeoutMillis) {
        Path file = genesis(node);
        Path pin = pinFile(node);
        if (Files.isRegularFile(file) && (Files.isRegularFile(pin) || hasCardanoData(node))) {
            String digest = Distribution.sha256(file);
            if (Files.isRegularFile(pin) && !NodeDir.read(pin).strip().equals(digest)) {
                throw CliException.failure(file + " changed since this node first ran on it; restore it "
                        + "(nodes keep their devnet genesis for life)");
            }
            return;
        }
        if (!Files.isRegularFile(file) && hasCardanoData(node)) {
            throw CliException.failure("This node has Cardano data but no devnet genesis; restore " + file);
        }
        NodeDir.Identity identity = node.requireIdentity();
        if (identity.name().equals(app.genesis().lead())) {
            create(distribution, file);
            terminal.ok("Created this application's Cardano devnet (you are the lead)");
            return;
        }
        String lead = app.genesis().lead();
        NodeClient client = new NodeClient(app.peers().get(lead).http(), null);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        byte[] zip = null;
        boolean told = false;
        while (zip == null) {
            try {
                zip = client.download("/api/v1/devnet/genesis/download");
            } catch (IOException notYet) {
                if (System.currentTimeMillis() > deadline) {
                    throw CliException.notReady("The lead (" + lead + ") is not reachable at " + client.base()
                            + "; ask " + lead + " to run  yano.sh appchain start  first, then start again");
                }
                if (!told) {
                    terminal.say("Waiting for the lead (" + lead + ") to start the devnet at " + client.base() + " …");
                    told = true;
                }
                sleep(3000);
            }
        }
        NodeDir.write(file, shelley(zip));
        terminal.ok("Downloaded the devnet genesis from the lead (" + lead + ")");
    }

    /** Called once the node is ready: from now on this genesis is the node's for life. */
    static void pinAfterStart(NodeDir node) {
        if (Files.isRegularFile(genesis(node)) && !Files.isRegularFile(pinFile(node))) {
            NodeDir.write(pinFile(node), Distribution.sha256(genesis(node)) + "\n");
        }
    }

    private static Path pinFile(NodeDir node) {
        return node.path("runtime", "devnet-genesis.sha256");
    }

    private static boolean hasCardanoData(NodeDir node) {
        try (var entries = Files.exists(node.path("data", "chainstate")) ? Files.list(node.path("data", "chainstate"))
                : java.util.stream.Stream.<Path>empty()) {
            return entries.findAny().isPresent();
        } catch (IOException error) {
            return true;
        }
    }

    static long systemStartMillis(NodeDir node) {
        try {
            return Instant.parse(JSON.readTree(genesis(node).toFile()).path("systemStart").asText()).toEpochMilli();
        } catch (IOException | RuntimeException invalid) {
            throw CliException.failure("Cannot read systemStart from " + genesis(node));
        }
    }

    private static void create(Distribution distribution, Path file) {
        try {
            ObjectNode genesis = (ObjectNode) JSON.readTree(distribution.devnetGenesisTemplate().toFile());
            Instant start = Instant.now().plusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
            genesis.put("systemStart", start.toString());
            genesis.put("epochLength", 500);
            NodeDir.write(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(genesis) + "\n");
        } catch (IOException error) {
            throw CliException.failure("Cannot create the devnet genesis from "
                    + distribution.devnetGenesisTemplate() + ": " + error.getMessage());
        }
    }

    private static String shelley(byte[] zip) {
        try (ZipInputStream stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                if (entry.getName().endsWith("shelley-genesis.json")) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException error) {
            throw CliException.failure("The lead returned an unreadable devnet genesis: " + error.getMessage());
        }
        throw CliException.failure("The lead's devnet genesis download has no shelley-genesis.json");
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw CliException.failure("Interrupted");
        }
    }
}
