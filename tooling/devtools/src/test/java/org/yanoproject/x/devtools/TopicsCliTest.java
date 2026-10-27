package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.util.StrictEd25519;
import org.yanoproject.appchain.proof.TopicPolicyProofVerifier;
import org.yanoproject.x.client.Hex;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsCliTest {
    @TempDir Path temporary;
    private final AppChainDevtoolsCli cli = new AppChainDevtoolsCli();

    @Test
    void initCreatesYamlWithoutSecretsAndRefusesOverwrite() throws Exception {
        Path yaml = temporary.resolve("topics.yaml");
        Result created = run("topics", "init", "games/", "--output", yaml.toString());
        assertThat(created.exit).as(created.err).isZero();
        JsonNode document = TopicPlan.read(Files.readAllBytes(yaml), true);
        assertThat(document.path("commands").get(0).path("target").asText()).isEqualTo("games/");
        assertThat(document.path("commands").get(0).path("nonce").asText()).hasSize(32);
        assertThat(run("topics", "init", "games/", "--output", yaml.toString()).exit)
                .isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        assertThat(run("topics", "init", "../", "--output", temporary.resolve("bad").toString()).exit)
                .isNotZero();
    }

    @Test
    void offlinePlanningAndTwoOwnerSigningAccumulateWithoutExposingKeys() throws Exception {
        var snapshot = twoOwners();
        ObjectNode document = liveDocument();
        Path doc = write("topics.yaml", TopicPlan.YAML.writeValueAsString(document));
        Path state = write("snapshot.json", TopicPlan.JSON.writeValueAsString(snapshot));
        Result planned = run("topics", "plan", "--file", doc.toString(), "--snapshot", state.toString());
        assertThat(planned.exit).as(planned.err).isZero();
        Path plan = write("plan.json", planned.out);
        Path first = seedFile("first.key", 1);
        Path second = seedFile("second.key", 2);
        Result signed1 = run("topics", "sign", "--plan", plan.toString(), "--key-file", first.toString());
        assertThat(signed1.exit).as(signed1.err).isZero();
        Path once = write("once.json", signed1.out);
        Result signed2 = run("topics", "sign", "--plan", once.toString(), "--key-file", second.toString());
        assertThat(signed2.exit).as(signed2.err).isZero();
        JsonNode signed = TopicPlan.JSON.readTree(signed2.out);
        assertThat(signed.path("commands").get(0).path("signatures")).hasSize(2);
        assertThat(TopicPlan.equivalent(TopicPlan.validate(signed), signed)).isTrue();
        assertThat(run("topics", "sign", "--plan", once.toString(), "--key-file", first.toString()).exit)
                .isNotZero();
        assertThat(signed1.out + signed1.err + signed2.out + signed2.err)
                .doesNotContain(Hex.encode(seed(1)), Hex.encode(seed(2)));
        Result forbidden = run("topics", "sign", "--plan", plan.toString(), "--key", Hex.encode(seed(1)));
        assertThat(forbidden.exit).isNotZero();
        assertThat(forbidden.out + forbidden.err).doesNotContain(Hex.encode(seed(1)));
    }

    @Test
    void submissionPreflightsEveryThresholdAndHeadBeforeAnyPost() throws Exception {
        AtomicReference<TopicPlan.Snapshot> current = new AtomicReference<>(twoOwners());
        AtomicInteger posts = new AtomicInteger();
        List<JsonNode> submitted = new ArrayList<>();
        HttpServer server = server(current, posts, submitted);
        try {
            String peer = "http://127.0.0.1:" + server.getAddress().getPort();
            Path plan = write("unsigned.json", TopicPlan.compile(liveDocument(), current.get()).toString());
            Result first = run("topics", "sign", "--plan", plan.toString(), "--key-file", seedFile("one", 1).toString());
            Path once = write("signed-one.json", first.out);
            assertThat(run("topics", "submit", "--plan", once.toString(), "--peer", peer).exit).isNotZero();
            assertThat(posts.get()).isZero();
            Result second = run("topics", "sign", "--plan", once.toString(), "--key-file", seedFile("two", 2).toString());
            Path complete = write("complete.json", second.out);
            Result success = run("topics", "submit", "--plan", complete.toString(), "--peer", peer);
            assertThat(success.exit).as(success.err).isZero();
            assertThat(posts.get()).isEqualTo(1);
            assertThat(submitted.getFirst().path("topic").asText()).isEqualTo("~topics/registry");
            assertThat(TopicWireCodec.decodeRegistryCommand(Hex.decode(submitted.getFirst().path("bodyHex").asText()))
                    .signatures()).hasSize(2);

            var before = current.get();
            var entry = before.entries().get("games/");
            EntryRecord updated = new EntryRecord(entry.name(), new ActiveRevision(2, 12, 1,
                    entry.active().content()), 2, 0, 1);
            current.set(new TopicPlan.Snapshot(before.chainId(), before.generationId(), 12, "55".repeat(32),
                    before.maxMessageBytes(), before.config(), Map.of("games/", updated), Map.of()));
            Result stale = run("topics", "submit", "--plan", complete.toString(), "--peer", peer);
            assertThat(stale.exit).isNotZero();
            assertThat(stale.err).contains("TOPIC_COMMAND_STALE");
            assertThat(posts.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void delegationUsesOnlyTheKeyFileAndMatchesCoreSignature() throws Exception {
        Result result = run("topics", "delegate", "--chain", TopicPlanFixtures.CHAIN,
                "--generation", TopicPlanFixtures.GENERATION, "--scope", "games/", "--session-key", publicKey(2),
                "--not-before", "1000", "--not-after", "2000", "--key-file", seedFile("issuer", 1).toString());
        assertThat(result.exit).as(result.err).isZero();
        JsonNode output = TopicPlan.JSON.readTree(result.out);
        var delegation = TopicWireCodec.decodeDelegation(Hex.decode(output.path("delegationHex").asText()));
        assertThat(StrictEd25519.verify(Hex.decode(publicKey(1)), delegation.sig(),
                TopicWireCodec.delegationSignedBytes(TopicPlanFixtures.CHAIN, TopicPlanFixtures.GENERATION, delegation)))
                .isTrue();
        assertThat(result.out + result.err).doesNotContain(Hex.encode(seed(1)));
    }

    @Test
    void offlineVerifyRequiresPinnedBlockAndRejectsEntryOnlyProofs() throws Exception {
        var fixture = TopicAuthorshipVerifierTest.fixture(true);
        Path trust = write("trusted-block.json", fixture.trust().toString());
        Path bundle = write("authorship-bundle.json", fixture.bundle().toString());
        Result valid = run("topics", "verify", fixture.id(), "--bundle", bundle.toString(),
                "--trusted-block", trust.toString());
        assertThat(valid.exit).as(valid.err).isZero();
        assertThat(valid.out).contains("delegated", "CALLER_PINNED", "NOT_PROVEN");
        ObjectNode entryOnly = fixture.bundle().deepCopy();
        entryOnly.set("policyProof", TopicPlan.JSON.createObjectNode().put("subjectType", "topic-entry-v1"));
        Path insufficient = write("entry-only.json", entryOnly.toString());
        assertThat(run("topics", "verify", fixture.id(), "--bundle", insufficient.toString(),
                "--trusted-block", trust.toString()).exit).isNotZero();
        assertThat(run("topics", "verify", fixture.id(), "--bundle", bundle.toString()).exit).isNotZero();
    }

    @Test
    void rejectsChangedAncestorEffectAndOwnerEpochEvenWithIdenticalSigningKeys() throws Exception {
        var original = twoOwners();
        AtomicReference<TopicPlan.Snapshot> current = new AtomicReference<>(original);
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = server(current, posts, new ArrayList<>());
        try {
            ObjectNode document = TopicPlanFixtures.document("games/child/", "create", "games/");
            ((ObjectNode) document.path("commands").get(0)).put("notAfter", System.currentTimeMillis() + 300_000);
            Path plan = write("create-child.json", TopicPlan.compile(document, original).toString());
            Result first = run("topics", "sign", "--plan", plan.toString(), "--key-file", seedFile("owner-one", 1).toString());
            Path one = write("owner-one.json", first.out);
            Result second = run("topics", "sign", "--plan", one.toString(), "--key-file", seedFile("owner-two", 2).toString());
            Path signed = write("owners.json", second.out);
            var content = original.entries().get("games/").active().content();
            for (boolean rotate : List.of(false, true)) {
                EntryContent changed = rotate ? content : new EntryContent(content.owners(),
                        new Policy(new Writers(content.owners()), null, null, "", null), false);
                EntryRecord entry = new EntryRecord("games/", new ActiveRevision(2, 12, rotate ? 2 : 1, changed),
                        2, 0, 1);
                current.set(new TopicPlan.Snapshot(original.chainId(), original.generationId(), 12, "55".repeat(32),
                        original.maxMessageBytes(), original.config(), Map.of("games/", entry), Map.of()));
                Result stale = run("topics", "submit", "--plan", signed.toString(), "--peer",
                        "http://127.0.0.1:" + server.getAddress().getPort());
                assertThat(stale.err).contains("TOPIC_COMMAND_STALE");
                assertThat(posts.get()).isZero();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolveVerifiesEffectiveProofAndBindsTheRequestedHeight() throws Exception {
        var fixture = TopicAuthorshipVerifierTest.fixture(false);
        ObjectNode proof = ((ObjectNode) fixture.bundle().path("policyProof")).deepCopy();
        var decoded = TopicAuthorshipVerifier.policy(proof);
        var outcome = TopicPolicyProofVerifier.verify(decoded, decoded.parentRoot()).orElseThrow();
        proof.set("outcome", TopicPlan.JSON.valueToTree(outcome));
        proof.put("effectivePolicyDigest", TopicWireCodec.outcomeDigest(outcome));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = TopicPlan.JSON.writeValueAsBytes(proof);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String peer = "http://127.0.0.1:" + server.getAddress().getPort();
            Result valid = run("topics", "resolve", "games/rides", "--chain", "authorship-test", "--peer", peer,
                    "--height", "3");
            assertThat(valid.exit).as(valid.err).isZero();
            assertThat(valid.out).contains("topic-policy-v1", "UNPINNED_PEER_ROOT", "configRevision");
            assertThat(run("topics", "resolve", "games/rides", "--chain", "authorship-test", "--peer", peer,
                    "--height", "4").exit).isNotZero();
            proof.put("effectivePolicyDigest", "00".repeat(32));
            assertThat(run("topics", "resolve", "games/rides", "--chain", "authorship-test", "--peer", peer,
                    "--height", "3").exit).isNotZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void governancePrintsCommandsAndDoesNotSubmitMemberMessages() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(TopicPlanFixtures.snapshot()), posts, new ArrayList<>());
        try {
            ObjectNode document = TopicPlanFixtures.document("new/", "create", "governance");
            Path plan = write("governance.json", TopicPlan.compile(document, TopicPlanFixtures.snapshot()).toString());
            Result result = run("topics", "submit", "--plan", plan.toString(), "--peer",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(result.exit).as(result.err).isZero();
            assertThat(result.out).contains("MEMBER_GOVERNANCE_REQUIRED", "commandHex");
            assertThat(posts.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedPlansAndExpiredStatementsFailWithoutLoggingInput() throws Exception {
        Path malformed = write("malformed.json", "{\"secret\":\"DO_NOT_ECHO_THIS\"}");
        Result result = run("topics", "sign", "--plan", malformed.toString(), "--key-file", "irrelevant");
        assertThat(result.exit).isNotZero();
        assertThat(result.out + result.err).doesNotContain("DO_NOT_ECHO_THIS");
        Path expired = write("expired.json", TopicPlan.compile(TopicPlanFixtures.document("games/", "update", "games/"),
                TopicPlanFixtures.snapshot()).toString());
        assertThat(run("topics", "sign", "--plan", expired.toString(), "--key-file", "irrelevant").err)
                .contains("TOPIC_COMMAND_EXPIRED");
    }

    private static HttpServer server(AtomicReference<TopicPlan.Snapshot> snapshot, AtomicInteger posts,
                                     List<JsonNode> submissions) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/" + TopicPlanFixtures.CHAIN, exchange -> {
            String path = exchange.getRequestURI().getPath();
            TopicPlan.Snapshot state = snapshot.get();
            JsonNode response;
            int status = 200;
            if (path.endsWith("/status")) {
                ObjectNode value = TopicPlan.JSON.createObjectNode().put("chainId", state.chainId());
                value.putObject("stateCommitment").put("generationId", state.generationId())
                        .put("version", state.height()).put("stateRoot", state.stateRoot());
                value.putObject("consensusProfile").put("maxMessageBytes", state.maxMessageBytes());
                response = value;
            } else if (path.endsWith("/topics/config")) {
                ObjectNode value = TopicPlan.JSON.createObjectNode().put("enabled", state.config() != null);
                value.set("config", TopicPlan.JSON.valueToTree(state.config()));
                response = value;
            } else if (path.contains("/topics/entries/")) {
                String name = path.substring(path.indexOf("/topics/entries/") + "/topics/entries/".length());
                response = TopicPlan.JSON.valueToTree(state.entries().get(name));
                if (response.isNull()) status = 404;
            } else if (path.endsWith("/messages")) {
                posts.incrementAndGet();
                submissions.add(TopicPlan.JSON.readTree(exchange.getRequestBody()));
                response = TopicPlan.JSON.createObjectNode().put("messageId", "66".repeat(32));
            } else if (path.contains("/state/entry/")) {
                response = TopicPlan.JSON.createObjectNode().put("stateRoot", state.stateRoot()).put("presence", "ABSENT");
            } else {
                response = TopicPlan.JSON.createObjectNode().put("error", "not found");
                status = 404;
            }
            byte[] bytes = TopicPlan.JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static TopicPlan.Snapshot twoOwners() {
        var state = TopicPlanFixtures.snapshot();
        var existing = state.entries().get("games/");
        var owners = new KeySet(2, List.of(publicKey(1), publicKey(2)).stream().sorted().toList());
        var entry = new EntryRecord("games/", new ActiveRevision(1, 2, 1,
                new EntryContent(owners, existing.active().content().policy(), false)), 1, 0, 1);
        return new TopicPlan.Snapshot(state.chainId(), state.generationId(), state.height(), state.stateRoot(),
                state.maxMessageBytes(), new Config(1, 1, state.config().settings()), Map.of("games/", entry), Map.of());
    }

    private static ObjectNode liveDocument() {
        ObjectNode document = TopicPlanFixtures.document("games/", "update", "games/");
        ((ObjectNode) document.path("commands").get(0)).put("notAfter", System.currentTimeMillis() + 300_000);
        return document;
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(temporary.resolve(name), content);
    }

    private Path seedFile(String name, int value) throws Exception {
        return write(name, Hex.encode(seed(value)));
    }

    private static byte[] seed(int value) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) value);
        return seed;
    }

    private static String publicKey(int value) {
        return Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(seed(value)));
    }

    private Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = cli.run(args, new PrintWriter(out), new PrintWriter(err));
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int exit, String out, String err) {
    }
}
