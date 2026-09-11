package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndex;
import org.yanoproject.x.client.AppChainClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Controlled 5 -> 6 -> 5 membership drill using finalized, independently verified approvals. */
public final class ObservationQualificationMembership {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String TOPIC = "~governance/membership";
    private static final int LIMIT = 4 * 1024 * 1024;

    private ObservationQualificationMembership() { }

    static boolean validStart(long height, boolean recoverAdd, boolean removeOnly) {
        if (recoverAdd && removeOnly) return false;
        if (removeOnly) return height == 74;
        return recoverAdd ? height >= 54 && height < 62 : height == 53;
    }

    record Epoch(long fromHeight, List<String> members) {
        Epoch { members = members.stream().sorted().toList(); }
    }

    static byte[] command(boolean add, byte[] member) {
        if (member.length != 32) throw new IllegalArgumentException("Expected 32-byte member key");
        return ByteBuffer.allocate(38).put((byte) 0x84).put((byte) 1).put((byte) (add ? 0 : 1))
                .put((byte) 0x58).put((byte) 32).put(member).put((byte) 10).array();
    }

    static List<String> selectMembers(List<Epoch> epochs, List<String> original, long height) {
        if (height < 0 || epochs.isEmpty() || epochs.size() > 3 || epochs.getFirst().fromHeight() != 0
                || !epochs.getFirst().members().equals(original.stream().sorted().toList())
                || original.size() != 5 || new HashSet<>(original).size() != 5) {
            throw new IllegalArgumentException("Invalid qualification membership history");
        }
        long previous = -1;
        List<String> selected = original;
        for (int index = 0; index < epochs.size(); index++) {
            Epoch epoch = epochs.get(index);
            if (epoch.fromHeight() <= previous || epoch.members().size() != (index == 1 ? 6 : 5)
                    || new HashSet<>(epoch.members()).size() != epoch.members().size()
                    || !epoch.members().containsAll(original)
                    || epoch.members().stream().anyMatch(key -> !key.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid qualification membership epoch");
            }
            previous = epoch.fromHeight();
            if (height >= epoch.fromHeight()) selected = epoch.members();
        }
        return selected;
    }

    /** This retained file is a caller-owned trust pin, written only after certified approval verification. */
    static List<String> membersAt(Path root, List<String> original, long height) {
        Path history = root.resolve("membership-epochs.json");
        if (!Files.exists(history)) return original;
        try {
            if (Files.isSymbolicLink(history) || Files.size(history) > 16_384) {
                throw new IllegalArgumentException("Unbounded or linked membership history");
            }
            JsonNode value = JSON.readTree(history.toFile());
            JsonNode manifest = JSON.readTree(root.resolve("qualification.json").toFile());
            Map<String, String> pinned = new LinkedHashMap<>();
            manifest.path("chainSettings").properties()
                    .forEach(entry -> pinned.put(entry.getKey(), entry.getValue().asText()));
            String genesis = HEX.formatHex(ObservationQualificationConfig.effectiveIdentity(pinned).genesisId());
            if (!ObservationQualificationConfig.CHAIN_ID.equals(value.path("chainId").asText())
                    || !genesis.equals(value.path("genesisId").asText())
                    || manifest.has("effectiveGenesisId")
                        && !genesis.equals(manifest.path("effectiveGenesisId").asText())) {
                throw new IllegalArgumentException("Membership trust pin identifies another fixture");
            }
            List<Epoch> epochs = new ArrayList<>();
            if (!value.path("epochs").isArray()) throw new IllegalArgumentException("Missing membership epochs");
            for (JsonNode entry : value.path("epochs")) {
                if (!entry.path("fromHeight").isIntegralNumber() || !entry.path("fromHeight").canConvertToLong()
                        || !entry.path("members").isArray()) {
                    throw new IllegalArgumentException("Malformed membership epoch fields");
                }
                List<String> members = new ArrayList<>();
                entry.path("members").forEach(key -> members.add(key.asText()));
                epochs.add(new Epoch(entry.path("fromHeight").asLong(-1), members));
            }
            return selectMembers(epochs, original, height);
        } catch (IOException invalid) {
            throw new IllegalStateException("Cannot read retained membership trust pin", invalid);
        }
    }

    public static void main(String[] args) throws Exception {
        boolean recover = args.length == 2 && "recover-add-approvals".equals(args[1]);
        boolean removeOnly = args.length == 2 && "remove-after-rounds".equals(args[1]);
        if (args.length != 1 && !recover && !removeOnly) {
            throw new IllegalArgumentException("Expected directory, optional recover-add-approvals or remove-after-rounds");
        }
        Path root = Path.of(args[0]).toRealPath();
        JsonNode manifest = JSON.readTree(root.resolve("qualification.json").toFile());
        Map<String, String> settings = new LinkedHashMap<>();
        manifest.path("chainSettings").properties().forEach(e -> settings.put(e.getKey(), e.getValue().asText()));
        if (!ObservationQualificationConfig.CHAIN_ID.equals(settings.get("chain-id"))
                || !"false".equals(settings.get("anchor.enabled")) || !"false".equals(settings.get("effects.enabled"))
                || !"governed".equals(settings.get("membership.mode"))) {
            throw new IllegalArgumentException("Expected non-spending governed qualification fixture");
        }
        byte[] genesis = ObservationQualificationConfig.effectiveIdentity(settings).genesisId();
        byte[] consensus = HEX.parseHex(manifest.path("consensusProfileDigest").asText());
        var profile = ObservationProfileV1.decode(HEX.parseHex(settings.get("observations.profile-cbor-hex")));
        List<String> original = new ArrayList<>();
        manifest.path("validators").forEach(key -> original.add(key.asText()));
        Properties secrets = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("reporters.private.properties"))) { secrets.load(reader); }
        String apiKey = secrets.getProperty("api-key");
        int port = manifest.path("httpBase").asInt();
        List<AppChainClient> clients = new ArrayList<>();
        for (int node = 0; node < 5; node++) {
            clients.add(AppChainClient.builder("http://127.0.0.1:" + (port + node) + "/api/v1")
                    .chainId(ObservationQualificationConfig.CHAIN_ID).apiKey(apiKey).build());
        }
        long initial = clients.getFirst().status().path("tipHeight").asLong();
        if (!validStart(initial, recover, removeOnly)) {
            throw new IllegalStateException("Wrong retained checkpoint for the selected membership drill stage");
        }
        byte[] latest = null;
        for (var client : clients) {
            var status = client.status();
            if (!status.path("running").asBoolean() || !status.path("genericObservations").path("ready").asBoolean()
                    || status.path("tipHeight").asLong() != initial
                    || status.path("genericObservations").path("openRounds").asLong() != 0) {
                throw new IllegalStateException("Requires converged ready nodes between rounds");
            }
            byte[] certified = ObservationQualificationBaseline.prove(client,
                    "ada-usd/latest-result".getBytes(StandardCharsets.UTF_8), initial,
                    removeOnly ? membersAt(root, original, initial) : original, genesis, consensus, profile.digest());
            if (latest != null && !Arrays.equals(latest, certified)) {
                throw new IllegalStateException("Initial certified results differ");
            }
            latest = certified;
        }
        if (removeOnly) {
            var result = ObservationResult.decode(latest);
            if (result.roundNumber() != 7 || result.finalizedHeight() != 74
                    || result.status() != ObservationResultStatus.VALUE) {
                throw new IllegalStateException("Removal recovery requires the certified round-7 VALUE checkpoint");
            }
        }
        List<Epoch> epochs = new ArrayList<>(List.of(new Epoch(0, original)));
        selectMembers(epochs, original, initial);
        String spare;
        if (recover || removeOnly) {
            JsonNode plan = JSON.readTree(root.resolve("membership-plan.json").toFile());
            JsonNode history = JSON.readTree(root.resolve("membership-epochs.json").toFile());
            spare = plan.path("sparePublicKey").asText();
            if (plan.path("initialHeight").asLong(-1) != 53 || !spare.matches("[0-9a-f]{64}")
                    || original.contains(spare) || history.path("epochs").size() != (removeOnly ? 2 : 1)) {
                throw new IllegalStateException("Recovery requires the original pending add plan and trust pins");
            }
            List<String> expected = new ArrayList<>(original);
            if (removeOnly) expected.add(spare);
            if (!membersAt(root, original, initial).equals(expected.stream().sorted().toList())) {
                throw new IllegalStateException("Retained membership does not match the original add plan");
            }
            if (removeOnly) {
                epochs.add(new Epoch(history.path("epochs").get(1).path("fromHeight").asLong(), expected));
            }
        } else {
            byte[] seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            spare = HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
            Arrays.fill(seed, (byte) 0); // The absent sixth member never signs; no operational key is installed.
            Files.writeString(root.resolve("membership-plan.json"), JSON.writeValueAsString(Map.of(
                    "initialHeight", initial, "sparePublicKey", spare, "scope", "5 -> 6 -> 5, q=4 f=1")),
                    StandardOpenOption.CREATE_NEW);
            writeHistory(root, epochs, genesis, true);
        }
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (boolean add : List.of(true, false)) {
                if (removeOnly && add) continue;
                long approvalHeight = transition(root, clients, http, port, apiKey, original, spare, add,
                        genesis, consensus, profile.digest(), recover && add);
                List<String> updated = new ArrayList<>(original);
                if (add) updated.add(spare);
                epochs.add(new Epoch(approvalHeight + 10, updated));
                selectMembers(epochs, original, approvalHeight + 10);
                writeHistory(root, epochs, genesis, false);
                // Two scheduled rounds span the activation lag and authenticate old/new epoch contexts.
                ObservationQualificationCadence.main(new String[]{root.toString(), "2"});
                if (clients.getFirst().status().path("tipHeight").asLong() < approvalHeight + 10) {
                    throw new IllegalStateException("Cadence did not cross the pinned activation height");
                }
            }
        }
        Files.writeString(root.resolve("membership-result.json"), JSON.writeValueAsString(Map.of(
                "epochs", epochs, "finalHeight", clients.getFirst().status().path("tipHeight").asLong(),
                "certifiedApprovalQuorum", 4, "physicalNodes", 5)), StandardOpenOption.CREATE_NEW);
    }

    private static long transition(Path root, List<AppChainClient> clients, HttpClient http, int port,
                                   String apiKey, List<String> original, String spare, boolean add,
                                   byte[] genesis, byte[] consensus, byte[] profile, boolean recover) throws Exception {
        long start = recover ? 53 : clients.getFirst().status().path("tipHeight").asLong();
        byte[] expected = command(add, HEX.parseHex(spare));
        for (int node = 0; !recover && node < 4; node++) {
            exchange(http, port + node, apiKey, "admin/members/" + (add ? "add" : "remove"),
                    JSON.writeValueAsString(Map.of("publicKey", spare)));
        }
        var approvers = new HashSet<String>();
        long cursor = start + 1;
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            long tip = clients.stream().mapToLong(client -> client.status().path("tipHeight").asLong()).min().orElseThrow();
            while (cursor <= tip) {
                var block = clients.getFirst().block(cursor).orElseThrow();
                List<String> members = membersAt(root, original, cursor);
                for (var message : block.messages()) {
                    if (!TOPIC.equals(message.topic()) || !Arrays.equals(expected, message.body())) continue;
                    JsonNode wire = exchange(http, port, apiKey,
                            "messages/" + message.messageId() + "/proof-package", null);
                    AppMessage signed = signedMessage(wire.path("suppliedMessage"));
                    verifyApproval(signed, expected, message.messageId(), members);
                    for (var client : clients) {
                        var record = FinalizedBlockMessageRootIndex.decode(ObservationQualificationBaseline.prove(client,
                                FinalizedBlockMessageRootIndex.blockKey(cursor), cursor, members, genesis, consensus, profile));
                        var proof = client.messageProof(signed.getMessageId()).orElseThrow();
                        if (!proof.verifiesRoot() || proof.blockHeight() != cursor || record.height() != cursor
                                || record.messageCount() != proof.leafCount()
                                || !Arrays.equals(record.messagesRoot(), proof.messagesRoot())) {
                            throw new IllegalStateException("Governance inclusion differs from certified block record");
                        }
                    }
                    Files.writeString(root.resolve("membership-approval-" + (add ? "add-" : "remove-")
                                    + message.messageId() + ".json"), JSON.writeValueAsString(wire),
                            StandardOpenOption.CREATE_NEW);
                    approvers.add(HEX.formatHex(signed.getSender()));
                    if (approvers.size() == 4) return cursor;
                }
                cursor++;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Membership approvals timed out; preserve partial governance state");
    }

    static void verifyApproval(AppMessage signed, byte[] expected, String id, List<String> members) {
        if (!signed.hasValidMessageId() || !ObservationQualificationConfig.CHAIN_ID.equals(signed.getChainId())
                || !TOPIC.equals(signed.getTopic()) || !Arrays.equals(expected, signed.getBody())
                || !HEX.formatHex(signed.getMessageId()).equals(id)
                || !members.contains(HEX.formatHex(signed.getSender())) || signed.getAuthScheme() != 0
                || !CryptoConfiguration.INSTANCE.getSigningProvider().verify(
                        signed.getAuthProof(), signed.signedBodyBytes(), signed.getSender())) {
            throw new IllegalStateException("Governance approval is not the expected authenticated command");
        }
    }

    static AppMessage signedMessage(JsonNode value) throws IOException {
        return new AppMessage(value.path("version").asInt(), value.path("messageId").binaryValue(),
                value.path("chainId").asText(), value.path("topic").asText(), value.path("sender").binaryValue(),
                value.path("senderSeq").asLong(), value.path("expiresAt").asLong(), value.path("body").binaryValue(),
                value.path("authScheme").asInt(-1), value.path("authProof").binaryValue());
    }

    private static JsonNode exchange(HttpClient http, int port, String apiKey, String path, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/app-chain/chains/" + ObservationQualificationConfig.CHAIN_ID + "/" + path))
                .timeout(Duration.ofSeconds(15)).header("X-API-Key", apiKey).header("Accept", "application/json");
        var request = body == null ? builder.GET().build() : builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            byte[] bytes = input.readNBytes(LIMIT + 1);
            if (response.statusCode() != 200 || bytes.length > LIMIT) {
                throw new IOException("Qualification HTTP response rejected: " + response.statusCode());
            }
            return JSON.readTree(bytes);
        }
    }

    private static void writeHistory(Path root, List<Epoch> epochs, byte[] genesis, boolean initial) throws IOException {
        String json = JSON.writeValueAsString(Map.of("chainId", ObservationQualificationConfig.CHAIN_ID,
                "genesisId", HEX.formatHex(genesis), "epochs", epochs));
        Path target = root.resolve("membership-epochs.json");
        if (initial) Files.writeString(target, json, StandardOpenOption.CREATE_NEW);
        else {
            Path pending = root.resolve("membership-epochs-" + epochs.size() + ".pending.json");
            Files.writeString(pending, json, StandardOpenOption.CREATE_NEW);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
