import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.api.appchain.AppChainConsensusProfileCommitment;
import org.yanoproject.api.appchain.consensus.ConsensusContext;
import org.yanoproject.api.appchain.consensus.ConsensusQuorum;
import org.yanoproject.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.Hex;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.client.TopicAuthoring;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.CompositeCommitmentV1;
import org.yanoproject.x.devtools.CddlSchemaCompiler;
import org.yanoproject.x.devtools.TopicsCli;
import org.yanoproject.x.stdlib.contracts.BalancesContract;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs as a Java source file against tools/lib from the final ZIP, never the source/test runtime classpath. */
public final class TopicPolicyAcceptance {
    private static final String CHAIN = "topics-acceptance";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CDDL = "root = { frames: uint }";
    private static final List<String> MEMBERS = List.of(
            "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
            "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1");
    private static final byte[] RIDE = Hex.decode("a1666672616d65731905dc"); // {frames:1500}
    private final Path work;
    private final Path nodes;
    private final Path home;
    private final int port;
    private final String apiKey = System.getenv("YANO_TOPICS_ACCEPTANCE_API_KEY");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AppChainClient[] clients = new AppChainClient[3];
    private final TopicAuthoring.Signer owner;
    private String generation;
    private String profile;
    private String genesis;
    private byte[] consensusProfilePin;
    private byte[] observerProfilePin;
    private JsonNode capabilityPin;
    private long schemaActivationHeight;
    private String heldOldPolicyMessageId;
    private int fileNumber;
    private boolean followerStopped;

    private TopicPolicyAcceptance(Path work, Path home, int port) throws IOException {
        this.work = work;
        this.nodes = work.resolve("nodes");
        this.home = home;
        this.port = port;
        byte[] seed = Hex.decode(Files.readString(work.resolve("owner.key")).strip());
        owner = new TopicAuthoring.Signer() {
            @Override public String publicKey() { return Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(seed)); }
            @Override public byte[] sign(byte[] bytes) {
                return CryptoConfiguration.INSTANCE.getSigningProvider().sign(bytes, seed);
            }
        };
        for (int node = 0; node < clients.length; node++) {
            clients[node] = AppChainClient.builder(base(node)).chainId(CHAIN).apiKey(apiKey)
                    .directConnections().build();
        }
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[1]);
        if (args[0].equals("prepare")) {
            prepare(work);
        } else {
            new TopicPolicyAcceptance(work, Path.of(args[2]), Integer.parseInt(args[3])).run();
        }
    }

    private static void prepare(Path work) throws IOException {
        byte[] schema = CddlSchemaCompiler.compile(CDDL).definition();
        String hash = TopicWireCodec.blake2b256Hex(schema);
        Files.writeString(work.resolve("owner.key"), "11".repeat(32) + "\n");
        Files.setPosixFilePermissions(work.resolve("owner.key"), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(work.resolve("owner.pub"), Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(
                Hex.decode("11".repeat(32)))) + "\n");
        for (int node = 0; node < 3; node++) {
            Path seed = work.resolve("member-" + node + ".key");
            Files.writeString(seed, String.format("%02d", node + 1).repeat(32) + "\n");
            Files.setPosixFilePermissions(seed, PosixFilePermissions.fromString("rw-------"));
        }
        Files.writeString(work.resolve("bindings.yaml"), """
                composite:
                  payloadSchemas:
                    %s: {cddl: 'root = { frames: uint }'}
                  components:
                    - {id: tick, machine: ordered-log, topic: heartbeat}
                    - {id: points, machine: balances, topic: ledger/points}
                    - id: rides
                      machine: ordered-log
                      topic: games/rides
                      payloadSchemas: [%s]
                      admission: [{rule: ride}]
                    - id: permitted
                      machine: ordered-log
                      topic: games/permitted
                      payloadSchemas: [%s]
                      admission: [{rule: ride}]
                    - id: audit
                      machine: ordered-log
                      topic: audit/rides
                      admission: [{rule: provenance}]
                    - id: unregistered-source
                      machine: ordered-log
                      topic: games/unregistered
                      payloadSchemas: [%s]
                      admission: [{rule: ride}]
                    - id: unregistered-permitted
                      machine: ordered-log
                      topic: games/unregistered-permitted
                      payloadSchemas: [%s]
                      admission: [{rule: ride}]
                    - {id: uncovered, machine: ordered-log, topic: unregistered/sink}
                  rules:
                    - id: ride
                      deny: RIDE_DENIED
                      require:
                        - {expr: 'payload.frames >= 1200 && payload.frames <= 3600 && writer.kind == "direct"'}
                    - id: provenance
                      deny: PROVENANCE_DENIED
                      require:
                        - {expr: 'writer.kind == "none" && size(writer.signers) == 0 && source.writer.kind == "direct"'}
                  bindings:
                    - id: audit-ride
                      from: {component: rides, event: composite.command-accepted.v1}
                      to: {component: audit, command: append, rawBody: body}
                    - id: permitted-ride
                      crossesTopicPolicy: true
                      from: {component: permitted, event: composite.command-accepted.v1}
                      to: {component: audit, command: append, rawBody: body}
                    - id: uncovered-ride
                      from: {component: unregistered-source, event: composite.command-accepted.v1}
                      to: {component: uncovered, command: append, rawBody: body}
                    - id: uncovered-permitted
                      crossesTopicPolicy: true
                      from: {component: unregistered-permitted, event: composite.command-accepted.v1}
                      to: {component: uncovered, command: append, rawBody: body}
                """.formatted(hash, hash, hash, hash, hash));
    }

    private void run() throws Exception {
        await("three ready nodes", () -> ready(0) && ready(1) && ready(2));
        String tick = submit("heartbeat", new byte[]{1});
        long initial = finalized(tick);
        JsonNode identity = clients[0].status().path("stateCommitment");
        generation = identity.path("generationId").asText();
        profile = identity.path("profile").asText();
        genesis = identity.path("genesisId").asText();
        check(generation.matches("[0-9a-f]{64}"), "missing generation identity");
        var initialProof = clients[0].proof(AppChainConsensusProfileCommitment.markerKey(), initial).orElseThrow();
        check(profile.equals(initialProof.profile()) && genesis.equals(initialProof.genesisIdHex()),
                "initial state proof identity differs from the inspected chain");
        consensusProfilePin = AppChainConsensusProfileCommitment.digest(
                AppChainConsensusProfileCommitment.decode(Hex.decode(initialProof.valueHex())));
        long magic = JSON.readTree(nodes.resolve("node0/runtime/devnet-genesis.json").toFile())
                .path("networkMagic").asLong();
        byte[] networkDomain = "yano-l1-network-no-observers-v1\0".getBytes(StandardCharsets.US_ASCII);
        byte[] network = Blake2bUtil.blake2bHash256(ByteBuffer.allocate(networkDomain.length + Long.BYTES)
                .put(networkDomain).putLong(magic).array());
        byte[] observerDomain = "yano-observer-profile-v2\0".getBytes(StandardCharsets.US_ASCII);
        observerProfilePin = Blake2bUtil.blake2bHash256(ByteBuffer.allocate(observerDomain.length + network.length)
                .put(observerDomain).put(network).array());
        verify(initialProof);
        capabilityPin = clients[0].status().path("capabilityManifest").deepCopy();
        check(capabilityPin.path("manifestDigest").asText().matches("[0-9a-f]{64}"),
                "capability manifest digest is missing");
        agree(initial);

        // The sealed draft enabled topics at genesis: the owner is the only registrar, with short limits.
        JsonNode sealed = get(0, "/topics/config");
        check(sealed.path("enabled").asBoolean() && sealed.path("config").path("revision").asLong() == 1,
                "the sealed draft did not enable topics at genesis");
        var seeded = JSON.treeToValue(sealed.path("config").path("settings"), TopicWire.Settings.class);
        check(seeded.equals(new TopicWire.Settings(true, owners(), false, false, 3_600_000, 3_600_000,
                4, 1000, 100, 2, 600)), "the sealed topic settings differ from the draft");
        // Members reconfigure by governance: identical votes from two of the three nodes.
        governance(new TopicWire.GovConfigure(new TopicWire.Settings(true, owners(), false, false, 3_600_000,
                3_600_000, 4, 2000, 100, 2, 600)));
        advanceUntil("member-governed reconfiguration",
                () -> get(0, "/topics/config").path("config").path("revision").asLong() == 2);
        voteNamespace("arena/");
        registry("ledger/", "create", TopicWire.AUTHORITY_REGISTRARS,
                policy(false, "none"), true);
        ObjectNode audit = policy(false, null);
        audit.put("schemaCddl", CDDL);
        registry("audit/", "create", TopicWire.AUTHORITY_REGISTRARS, audit, true);
        registry("heartbeat", "create", TopicWire.AUTHORITY_REGISTRARS, policy(false, "none"), true);
        ObjectNode games = policy(true, null);
        games.put("schemaCddl", CDDL);
        registry("games/", "create", TopicWire.AUTHORITY_REGISTRARS, games, true);
        advanceUntil("namespace activation", () -> active("ledger/") && active("audit/") && active("games/"));
        System.out.println("PASS: registrar namespaces planned, signed and submitted with the packaged CLI");

        byte[] mint = BalancesContract.mint("alice", BigInteger.valueOf(100));
        String plain = submit("ledger/points", mint);
        finalized(plain);
        balance(100);
        registry("ledger/", "update", "ledger/", policy(true, "none"), false);
        advanceUntil("typed topic protection", () -> protectedTopic("ledger/"));
        expectRefusal("ledger/points", mint, "TOPIC_FRAME_INVALID");
        byte[] framedMint = frame("ledger/points", mint);
        String framed = submit("ledger/points", framedMint);
        long framedHeight = finalized(framed);
        balance(200);
        check(!plain.equals(framed), "framing must preserve distinct original message identities");
        check(receipt(framed, 0).accepted(), "framed typed command receipt rejected");
        check(Arrays.equals(receipt(framed, 0).sourceMessageId(), Hex.decode(framed)), "wrong receipt source id");
        check(get(0, "/messages/" + framed).path("bodyHex").asText().equals(Hex.encode(framedMint)),
                "message retrieval did not preserve the original frame");
        certify(CompositeCommitmentV1.componentKey("points", BalancesContract.accountKey("alice")), framedHeight);
        messageProof(framed, framedHeight);
        System.out.println("PASS: framed/unframed typed balances preserve equal deltas and original proofs/receipts");

        String ride = submit("games/rides", frame("games/rides", RIDE));
        long rideHeight = finalized(ride);
        check(receipt(ride, 0).accepted() && receipt(ride, 0).steps().size() == 2, "open target cascade failed");
        agree(rideHeight);
        check(cli("topics", "resolve", "games/rides", "--chain", CHAIN, "--peer", base(0),
                "--height", Long.toString(rideHeight), "--api-key-env", "YANO_TOPICS_ACCEPTANCE_API_KEY", "--json")
                .path("outcome").path("policy").path("schema").asText()
                .equals(TopicWireCodec.blake2b256Hex(CddlSchemaCompiler.compile(CDDL).definition())),
                "inherited schema was not resolved at the inclusion height");
        expectRefusal("games/rides", frame("games/rides", Hex.decode("a1666672616d65731864")),
                "ADMISSION_RULE_DENIED");
        expectRefusal("games/rides", frame("games/rides", Hex.decode("a1666672616d65736178")),
                "TOPIC_SCHEMA_REJECTED");
        System.out.println("PASS: inherited opaque schema and verified writer/payload rules enforce ingress negatives");

        stopNode(2);
        followerStopped = true;
        registry("audit/", "update", "audit/", policy(true, "none"), false);
        JsonNode doctor = cli("topics", "doctor", "--bindings", work.resolve("bindings.yaml").toString(),
                "--chain", CHAIN, "--peer", base(0), "--api-key-env", "YANO_TOPICS_ACCEPTANCE_API_KEY", "--json");
        check(doctor.path("checks").toString().contains("RULE_TOPIC_POLICY_CROSSING"),
                "doctor did not warn about pending/active crossing protection");
        advanceUntil("derived target protection", () -> protectedTopic("audit/"));
        rejectedCascade("games/rides", "rides", "RULE_TOPIC_POLICY_CROSSING");
        String permitted = submit("games/permitted", frame("games/permitted", RIDE));
        long permittedHeight = finalized(permitted);
        check(receipt(permitted, 0).accepted(), "explicit crossing did not execute");
        check(receipt(permitted, 0).steps().size() == 2, "explicit crossing did not derive a step");
        startNode(2);
        followerStopped = false;
        agree(permittedHeight);
        check(Arrays.equals(receipt(permitted, 0).encode(), receipt(permitted, 2).encode()),
                "catch-up changed receipt bytes");
        System.out.println("PASS: live protection activation, crossing refusal/permission and follower catch-up parity");

        for (String topic : List.of("games/unregistered", "games/unregistered-permitted")) {
            String id = submit(topic, frame(topic, RIDE));
            finalized(id);
            check(receipt(id, 0).accepted(), "unregistered-open target should permit either crossing flag");
        }
        governance(new TopicWire.GovConfigure(new TopicWire.Settings(true, owners(), false, true, 3_600_000,
                3_600_000, 4, 1000, 100, 2, 600)));
        advanceUntil("unregistered-reject activation", () -> get(0, "/topics/config").path("config")
                .path("settings").path("unregisteredReject").asBoolean());
        rejectedCascade("games/unregistered", "unregistered-source", "TOPIC_UNREGISTERED");
        rejectedCascade("games/unregistered-permitted", "unregistered-permitted", "TOPIC_UNREGISTERED");

        ObjectNode small = policy(true, "none");
        small.put("maxPayloadBytes", RIDE.length - 1);
        registry("audit/", "update", "audit/", small, false);
        advanceUntil("derived payload bound", () -> entry("audit/").path("active").path("content")
                .path("policy").path("maxPayloadBytes").asLong() == RIDE.length - 1);
        rejectedCascade("games/permitted", "permitted", "TOPIC_PAYLOAD_TOO_LARGE");

        ObjectNode invalidSchema = policy(true, null);
        invalidSchema.put("schemaCddl", "root = tstr");
        registry("audit/", "update", "audit/", invalidSchema, false);
        advanceUntil("derived target schema", () -> !schema("audit/").isEmpty());
        rejectedCascade("games/permitted", "permitted", "TOPIC_SCHEMA_REJECTED");
        registry("audit/", "retire", "audit/", null, false);
        advanceUntil("derived target retirement", () -> entry("audit/").path("active")
                .path("content").path("retired").asBoolean());
        rejectedCascade("games/rides", "rides", "TOPIC_RETIRED");
        rejectedCascade("games/permitted", "permitted", "TOPIC_RETIRED");

        ObjectNode nextSchema = policy(true, null);
        nextSchema.put("schemaCddl", "root = { frames: uint .le 10000 }");
        String oldSchema = schema("games/");
        registry("games/", "update", "games/", nextSchema, false);
        long pendingHeight = clients[0].tip().height();
        stopNode(2);
        startNode(2);
        agree(pendingHeight);
        JsonNode pendingOnRestart = get(2, "/topics/entries/games%2F");
        check(pendingOnRestart.path("pending").asLong() > 0
                        && pendingOnRestart.path("active").path("content").path("policy").path("schema")
                        .asText().equals(oldSchema), "restart did not preserve the pending schema boundary");
        reAdmitAcrossActivation(oldSchema);
        expectRefusal("games/rides", frame("games/rides", RIDE), "RULE_PAYLOAD_SCHEMA_MISMATCH");
        var savedTip = clients[0].tip();
        long savedHeight = savedTip.height();
        String savedRoot = savedTip.stateRootHex();
        agree(savedHeight);
        for (int node = 2; node >= 0; node--) stopNode(node);
        for (int node = 0; node < 3; node++) startNode(node);
        agree(savedHeight);
        for (var client : clients) check(client.block(savedHeight).orElseThrow().stateRootHex().equals(savedRoot),
                "restart changed a finalized state root");
        expectRefusal("games/rides", frame("games/rides", RIDE), "RULE_PAYLOAD_SCHEMA_MISMATCH");
        certify(CompositeCommitmentV1.componentKey("points", BalancesContract.accountKey("alice")), savedHeight);
        messageProof(framed, framedHeight);
        checkNoSecrets();
        ObjectNode evidence = JSON.createObjectNode().put("status", "PASS").put("height", savedHeight)
                .put("stateRoot", savedRoot).put("generationId", generation).put("profile", profile)
                .put("genesisId", genesis).put("consensusContextDigest", consensusContext(savedHeight))
                .put("consensusProfileDigest", Hex.encode(consensusProfilePin))
                .put("finalityThreshold", 2).put("framedMessageId", framed).put("framedHeight", framedHeight)
                .put("rideMessageId", ride).put("rideHeight", rideHeight)
                .put("schemaActivationHeight", schemaActivationHeight)
                .put("heldOldPolicyMessageId", heldOldPolicyMessageId);
        evidence.set("members", JSON.valueToTree(MEMBERS));
        evidence.set("capabilityManifest", capabilityPin);
        evidence.put("stateIdentityTrust", "First finalized peer state, checked against all three nodes "
                + "and the configured member signatures; no Cardano anchoring claim.");
        Files.writeString(work.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println("PASS: target schema/retirement and source schema activation remain fail-closed after restart");
    }

    private TopicWire.KeySet owners() { return new TopicWire.KeySet(1, List.of(owner.publicKey())); }

    private ObjectNode policy(boolean protect, String schema) {
        ObjectNode result = JSON.createObjectNode();
        if (protect) result.set("writers", JSON.valueToTree(owners()));
        else result.put("writers", "open");
        if (schema != null) result.put("schema", schema);
        result.put("maxPayloadBytes", 4096);
        return result;
    }

    private void registry(String target, String operation, String authority, ObjectNode policy, boolean create)
            throws Exception {
        ObjectNode document = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "Topics").put("chainId", CHAIN);
        ObjectNode command = document.putArray("commands").addObject().put("target", target)
                .put("operation", operation).put("authority", authority).put("nonce", TopicAuthoring.nonce())
                .put("notAfter", System.currentTimeMillis() + 600_000);
        if (create) command.set("owners", JSON.valueToTree(owners()));
        if (policy != null) command.set("policy", policy);
        Path file = work.resolve("topics-" + (++fileNumber) + ".json");
        Path planned = work.resolve("plan-" + fileNumber + ".json");
        Path signed = work.resolve("signed-" + fileNumber + ".json");
        Files.writeString(file, document.toString());
        JsonNode plan = cli("topics", "plan", "--file", file.toString(), "--peer", base(0),
                "--api-key-env", "YANO_TOPICS_ACCEPTANCE_API_KEY", "--json");
        Files.writeString(planned, plan.toString());
        Files.writeString(signed, cli("topics", "sign", "--plan", planned.toString(),
                "--key-file", work.resolve("owner.key").toString(), "--json").toString());
        JsonNode submitted = cli("topics", "submit", "--plan", signed.toString(), "--peer", base(0),
                "--api-key-env", "YANO_TOPICS_ACCEPTANCE_API_KEY", "--json");
        String id = submitted.path("commands").get(0).path("submission").path("messageId").asText();
        finalized(id);
        check(get(0, "/topics/receipts/" + id).path("code").asText().equals("ACCEPTED"), "registry command refused");
    }

    /**
     * The operator path of ADR-056: one plan from a node directory, then a vote from two operators' node
     * directories, each through their own node; repeating a vote after activation is a no-op.
     */
    private void voteNamespace(String namespace) throws Exception {
        ObjectNode document = JSON.createObjectNode().put("schemaVersion", 1).put("kind", "Topics")
                .put("chainId", CHAIN);
        document.putArray("commands").addObject().put("target", namespace).put("operation", "create")
                .put("authority", "governance").put("nonce", TopicAuthoring.nonce())
                .<ObjectNode>set("owners", JSON.valueToTree(owners()))
                .set("policy", JSON.createObjectNode().put("writers", "open").put("schema", "none"));
        Files.writeString(work.resolve("arena.yaml"), document.toString());
        String planned = cliText("topics", "plan", "arena.yaml", "--node-dir", node(0));
        check(planned.contains("create " + namespace + ": a member vote")
                && planned.contains("topics approve arena.plan.json"), "plan did not ask for member votes");
        for (int node : List.of(0, 1)) {
            check(cliText("topics", "approve", "arena.plan.json", "--node-dir", node(node), "--no-wait", "--yes")
                    .contains("voted (message"), "operator vote was not cast");
        }
        // Decided but not yet active (no traffic): voting again would open a second tally, so approve must not.
        await("the decided namespace", () -> head(namespace) >= 1);
        String again = cliText("topics", "approve", "arena.plan.json", "--node-dir", node(0), "--no-wait", "--yes");
        check(again.contains("enough operators voted") && !again.contains("voted (message"),
                "a repeated vote after quorum was cast again");
        advanceUntil("member-voted namespace", () -> active(namespace));
        await("the third node sees the namespace", () -> {
            JsonNode active = get(2, "/topics/entries/" + namespace.replace("/", "%2F")).path("active");
            return !active.isMissingNode() && !active.isNull();
        });
        for (int node : List.of(2, 0)) {
            check(cliText("topics", "approve", "arena.plan.json", "--node-dir", node(node), "--timeout", "30",
                    "--yes").contains("already in effect"), "a vote after activation was not a no-op");
        }
        check(head(namespace) == 1, "the namespace was changed more than once");
        // The tally holds no fresh approval of this command: the re-runs cast nothing a later quorum could apply.
        String hash = TopicsCli.commandHash(TopicsCli.votes(work.resolve("arena.plan.json")).getFirst());
        JsonNode tally = get(0, "/state/entry/" + Hex.encode(TopicKeys.governance()));
        check(!"PRESENT".equals(tally.path("presence").asText()) || TopicWireCodec.decodeGovernancePending(
                Hex.decode(tally.path("valueHex").asText())).stream()
                .noneMatch(approval -> approval.commandHash().equals(hash)), "a repeated vote opened a new tally");
        System.out.println("PASS: genesis-enabled topics from the draft, member reconfiguration and CLI namespace "
                + "votes");
    }

    private String node(int node) { return nodes.resolve("node" + node).toString(); }

    /** The target's head revision on the first node, or 0 before it exists. */
    private long head(String name) {
        try {
            return entry(name).path("head").asLong(0);
        } catch (AssertionError | RuntimeException absent) { // 404 until the name exists
            return 0;
        }
    }

    private void governance(TopicWire.GovernanceOperation operation) throws Exception {
        byte[] bytes = TopicWireCodec.encodeGovernanceCommand(new TopicWire.GovernanceCommand(CHAIN, generation,
                TopicAuthoring.nonce(), operation));
        for (int node : List.of(0, 1)) {
            String id = request(node, "/admin/topics/governance", Map.of("commandHex", Hex.encode(bytes)), 202)
                    .path("messageId").asText();
            finalized(id);
        }
    }

    private byte[] frame(String topic, byte[] payload) {
        return TopicWireCodec.encodeFrame(TopicAuthoring.signWrite(TopicAuthoring.prepareWrite(CHAIN, generation,
                topic, payload, TopicAuthoring.nonce(), System.currentTimeMillis() + 600_000, null), List.of(owner)));
    }

    private String submit(String topic, byte[] body) { return clients[0].submit(topic, body).messageId(); }

    private long finalized(String id) {
        await("message finality", () -> get(0, "/messages/" + id).path("height").asLong() > 0);
        return get(0, "/messages/" + id).path("height").asLong();
    }

    private void advanceUntil(String reason, BooleanSupplier condition) {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) return;
            finalized(submit("heartbeat", new byte[]{(byte) (attempt + 1)}));
        }
        throw new AssertionError(reason + " did not become active");
    }

    private void rejectedCascade(String topic, String component, String code) {
        String id = submit(topic, frame(topic, RIDE));
        long height = finalized(id);
        BindingReceiptV1 receipt = receipt(id, 0);
        check(!receipt.accepted() && receipt.code().equals(code), "unexpected derived refusal: " + receipt.code());
        agree(height);
        for (int node = 0; node < (followerStopped ? 2 : 3); node++) {
            var proof = clients[node].proof(CompositeCommitmentV1.componentKey(component, Hex.decode(id)), height)
                    .orElseThrow();
            check(proof.presence() == AppChainClient.ProofPresence.ABSENT, "refused cascade retained source writes");
            verify(proof);
            check(Arrays.equals(receipt.encode(), receipt(id, node).encode()), "refused receipt differs on a peer");
            for (var step : receipt.steps()) {
                if (step.depth() == 0) continue;
                var target = clients[node].proof(CompositeCommitmentV1.componentKey(step.targetComponentId(),
                        step.messageId()), height).orElseThrow();
                check(target.presence() == AppChainClient.ProofPresence.ABSENT,
                        "refused cascade retained derived target writes");
                verify(target);
            }
        }
    }

    private void expectRefusal(String topic, byte[] body, String code) {
        expectRefusal(0, topic, body, code);
    }

    private void expectRefusal(int node, String topic, byte[] body, String code) {
        try {
            clients[node].submit(topic, body);
            throw new AssertionError("expected refusal " + code);
        } catch (AppChainClient.AppChainClientException refused) {
            check(refused.getMessage().contains(code), "unexpected ingress refusal; expected " + code);
        }
    }

    private BindingReceiptV1 receipt(String id, int node) {
        return BindingReceiptV1.decode(clients[node].query("composite/binding-receipt-v1/" + id, new byte[0]).payload());
    }

    private void balance(long expected) {
        long height = clients[0].tip().height();
        agree(height);
        for (var client : clients) {
            var proof = client.proof(CompositeCommitmentV1.componentKey("points", BalancesContract.accountKey("alice")),
                    height).orElseThrow();
            check(BalancesContract.decodeBalance(Hex.decode(proof.valueHex())).longValueExact() == expected,
                    "framing changed the typed business result");
            verify(proof);
        }
    }

    private void certify(byte[] key, long height) {
        agree(height);
        for (var client : clients) verify(client.proof(key, height).orElseThrow());
    }

    private void verify(AppChainClient.Proof proof) {
        check(ProofVerifier.verifyCertified(proof, new ProofVerifier.FinalityTrustContext(CHAIN, profile, genesis,
                Set.copyOf(MEMBERS), 2, consensusContext(proof.committedHeight()))),
                "certified state proof failed pinned membership/context verification");
    }

    private String consensusContext(long height) {
        return Hex.encode(new ConsensusContext(3, CHAIN, Hex.decode(genesis), height,
                new ConsensusQuorum(3, 2, 0), MEMBERS.stream().map(Hex::decode).toList(), consensusProfilePin,
                observerProfilePin, ObservationProfileV1.disabled().digest()).digest());
    }

    private void messageProof(String id, long height) {
        for (var client : clients) {
            var proof = client.messageProof(Hex.decode(id)).orElseThrow();
            var block = client.block(height).orElseThrow();
            var certified = client.proof(AppChainConsensusProfileCommitment.markerKey(), height).orElseThrow();
            verify(certified);
            check(proof.blockHeight() == height && proof.verifiesRoot()
                    && Hex.encode(proof.messagesRoot()).equals(block.messagesRootHex())
                    && Hex.encode(proof.messagesRoot()).equals(certified.block().messagesRootHex())
                    && Hex.encode(proof.blockHash()).equals(certified.block().blockHashHex())
                    && Arrays.equals(proof.messageId(), Hex.decode(id)), "original message proof mismatch");
            check(block.certSignatures() >= 2, "finality certificate is below threshold");
        }
    }

    /** Hold quorum briefly so one old-policy message is admitted after the activation-boundary proposal is fixed. */
    private void reAdmitAcrossActivation(String oldSchema) throws Exception {
        long parent = clients[0].tip().height();
        agree(parent);
        check(schema("games/").equals(oldSchema), "source schema activated before the boundary test");
        String heartbeat;
        String held;
        signal(1, "STOP");
        try {
            signal(2, "STOP");
            try {
                heartbeat = submit("heartbeat", new byte[]{99});
                // The shipped cadence is one second. With both peers suspended, the proposer holds its
                // original one-message proposal awaiting quorum and cannot finalize it yet.
                Thread.sleep(2250);
                check(clients[0].tip().height() == parent, "no-quorum boundary advanced unexpectedly");
                held = submit("games/rides", frame("games/rides", RIDE));
                check(clients[0].status().path("poolSize").asInt() >= 2,
                        "old-policy message did not enter the pending pool");
            } finally { signal(2, "CONT"); }
        } finally { signal(1, "CONT"); }
        long boundary = finalized(heartbeat);
        check(boundary == parent + 1, "activation proposal finalized at an unexpected height");
        check(clients[0].block(boundary).orElseThrow().messages().stream()
                .noneMatch(message -> message.messageId().equals(held)),
                "old-policy message was not held behind the fixed activation-boundary proposal");
        check(!schema("games/").equals(oldSchema), "source schema did not activate at the expected boundary");
        schemaActivationHeight = boundary + 1;
        heldOldPolicyMessageId = held;
        await("candidate-height mempool re-admission", () -> clients[0].status().path("poolSize").asInt() == 0);
        check(get(0, "/messages/" + held).path("height").asLong() == 0,
                "old-policy pending message finalized after its schema contract stopped matching");
        long after = finalized(submit("heartbeat", new byte[]{100}));
        agree(after);
        for (int node = 0; node < 3; node++) {
            check(get(node, "/messages/" + held).path("height").asLong() == 0,
                    "a peer indexed a message that candidate-height re-admission refused");
            expectRefusal(node, "games/rides", frame("games/rides", RIDE), "RULE_PAYLOAD_SCHEMA_MISMATCH");
        }
        System.out.println("PASS: an old-policy pooled message is re-admitted and dropped at the schema activation height");
    }

    private void agree(long height) {
        String root = clients[0].block(height).orElseThrow().stateRootHex();
        int count = followerStopped ? 2 : 3;
        await("cross-node root agreement", () -> {
            for (int node = 0; node < count; node++) {
                var block = clients[node].block(height);
                if (block.isEmpty() || !block.orElseThrow().stateRootHex().equals(root)) return false;
            }
            return true;
        });
        for (int node = 0; node < count; node++) {
            JsonNode state = clients[node].status().path("stateCommitment");
            check(state.path("generationId").asText().equals(generation), "cross-node generation mismatch");
            check(state.path("profile").asText().equals(profile)
                    && state.path("genesisId").asText().equals(genesis), "cross-node commitment identity mismatch");
            check(clients[node].status().path("capabilityManifest").equals(capabilityPin),
                    "cross-node capability manifest mismatch");
        }
    }

    private void signal(int node, String name) throws Exception {
        String pid = Files.readString(nodes.resolve("node" + node + "/run/node.pid")).strip().split(" ")[0];
        Process signal = new ProcessBuilder("kill", "-" + name, pid).start();
        check(signal.waitFor(5, TimeUnit.SECONDS) && signal.exitValue() == 0, "node signal failed");
    }

    private void stopNode(int node) throws Exception {
        cliText("stop", "--node-dir", node(node));
    }

    private void startNode(int node) throws Exception {
        cliText("start", "--node-dir", node(node));
        await("restarted node readiness", () -> ready(node));
    }

    private JsonNode cli(String... arguments) throws Exception {
        return JSON.readTree(cliText(arguments));
    }

    private String cliText(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", home.resolve("yano.sh").toString(), "appchain"));
        command.addAll(List.of(arguments));
        Path stdout = Files.createTempFile(work, "cli-", ".out");
        Path stderr = Files.createTempFile(work, "cli-", ".err");
        Process child = new ProcessBuilder(command).directory(work.toFile()).redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile()).start();
        if (!child.waitFor(300, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("CLI timed out");
        }
        check(child.exitValue() == 0, "packaged CLI " + String.join(" ", arguments) + " failed: "
                + Files.readString(stderr) + Files.readString(stdout));
        return Files.readString(stdout);
    }

    private boolean active(String name) { return !entry(name).path("active").isMissingNode()
            && !entry(name).path("active").isNull(); }
    private boolean protectedTopic(String name) {
        return entry(name).path("active").path("content").path("policy").path("writers").hasNonNull("keys");
    }
    private String schema(String name) {
        return entry(name).path("active").path("content").path("policy").path("schema").asText("");
    }
    private JsonNode entry(String name) { return get(0, "/topics/entries/" + name.replace("/", "%2F")); }
    private String base(int node) { return "http://127.0.0.1:" + (port + node) + "/api/v1"; }
    private JsonNode get(int node, String suffix) { return request(node, suffix, null, 200); }

    private JsonNode request(int node, String suffix, Object body, int expected) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base(node) + "/app-chain/chains/" + CHAIN + suffix))
                    .timeout(Duration.ofSeconds(10)).header("X-API-Key", apiKey);
            if (body != null) builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 && body == null) return JSON.createObjectNode();
            check(response.statusCode() == expected, "unexpected HTTP status " + response.statusCode() + " for " + suffix);
            return JSON.readTree(response.body());
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException("local acceptance HTTP failed", failure);
        }
    }

    private boolean ready(int node) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + (port + node)
                            + "/q/health/ready")).timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception unavailable) { return false; }
    }

    private void checkNoSecrets() throws IOException {
        List<String> secrets = List.of(apiKey, "01".repeat(32), "02".repeat(32), "03".repeat(32), "11".repeat(32));
        try (var files = Files.walk(nodes)) {
            for (Path path : files.filter(Files::isRegularFile)
                    .filter(file -> file.getParent().getFileName().toString().equals("logs")).toList()) {
                String contents = Files.readString(path);
                for (String secret : secrets) check(!contents.contains(secret), "acceptance log contains a secret");
            }
        }
    }

    private static void await(String reason, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        do {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(250); } catch (InterruptedException stop) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(stop);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Timed out: " + reason);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
