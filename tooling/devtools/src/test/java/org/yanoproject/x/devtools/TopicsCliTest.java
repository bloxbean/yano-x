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
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicWire;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicsCliTest {
    private static final List<String> API_KEYS = java.util.Collections.synchronizedList(new ArrayList<>());
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

    @Test
    void guidedRunWritesNamedFilesSaysWhatIsNextAndUsesTheNodeDefaults() throws Exception {
        AtomicReference<TopicPlan.Snapshot> current = new AtomicReference<>(twoOwners());
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = server(current, posts, new ArrayList<>());
        try {
            String peer = "http://127.0.0.1:" + server.getAddress().getPort();
            var defaults = new TopicsCli.Defaults(peer, "node-key", TopicPlanFixtures.CHAIN, true);
            Path doc = write("games.yaml", TopicPlan.YAML.writeValueAsString(liveDocument()));
            Result planned = guided(defaults, "plan", doc.toString());
            assertThat(planned.exit).as(planned.err).isZero();
            Path plan = temporary.resolve("games.plan.json");
            assertThat(plan).isRegularFile();
            assertThat(planned.out).contains("Wrote " + plan, "update games/: 2 signatures needed from keys",
                    "Next: each signer runs  yano.sh appchain topics sign " + plan);
            assertThat(API_KEYS).contains("node-key");

            Result once = guided(defaults, "sign", plan.toString(), "--key-file", seedFile("one", 1).toString());
            assertThat(once.exit).as(once.err).isZero();
            Path signed = temporary.resolve("games.signed.json");
            assertThat(once.out).contains("Signed " + signed, "1 of 2 signatures", "to the next signer");
            // The second owner signs the signed file in place.
            Result twice = guided(defaults, "sign", signed.toString(), "--key-file", seedFile("two", 2).toString());
            assertThat(twice.out).contains("2 of 2 signatures", "Next: yano.sh appchain topics submit " + signed);
            Result submitted = guided(defaults, "submit", signed.toString());
            assertThat(submitted.exit).as(submitted.err).isZero();
            assertThat(submitted.out).contains("Submitted games/ (message " + "66".repeat(32) + ")",
                    "topics resolve games/<topic>");
            assertThat(posts.get()).isEqualTo(1);

            // A node's API key is never sent to a peer named on the command line.
            API_KEYS.clear();
            assertThat(guided(defaults, "plan", doc.toString(), "--peer", peer, "--output",
                    temporary.resolve("other.plan.json").toString()).exit).isZero();
            assertThat(API_KEYS).isNotEmpty().allMatch("null"::equals);

            // A guided plan replaces only an earlier unsigned plan, never a signed one or anything else.
            Path signedTarget = temporary.resolve("kept.signed.json");
            Files.copy(signed, signedTarget);
            Result refused = guided(defaults, "plan", doc.toString(), "--output", signedTarget.toString());
            assertThat(refused.exit).isNotZero();
            assertThat(refused.err).contains("kept.signed.json exists and is not an unsigned plan");
            assertThat(Files.readString(signedTarget)).isEqualTo(Files.readString(signed));
            assertThat(guided(defaults, "plan", doc.toString()).exit).isZero();

            // Mistakes are named: the wrong key, a missing signature, an edited plan.
            Result stranger = guided(defaults, "sign", plan.toString(), "--key-file", seedFile("three", 3).toString());
            assertThat(stranger.err).contains("is not a signer of any command in the plan; it needs");
            Result early = guided(defaults, "submit", temporary.resolve("games.plan.json").toString());
            assertThat(early.err).contains("games/ needs 2 more signatures");
            java.util.regex.Matcher expiry = java.util.regex.Pattern.compile("\"notAfter\" : (\\d+)")
                    .matcher(Files.readString(plan));
            assertThat(expiry.find()).isTrue();
            Path edited = write("edited.plan.json", Files.readString(plan).replace(expiry.group(),
                    "\"notAfter\" : " + (Long.parseLong(expiry.group(1)) + 1)));
            assertThat(guided(defaults, "sign", edited.toString(), "--key-file", seedFile("four", 1).toString()).err)
                    .contains("the plan was edited after planning; plan it again");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void signedChangesNeedAnExpiryAndVotesHaveNone() throws Exception {
        ObjectNode document = liveDocument();
        ((ObjectNode) document.path("commands").get(0)).remove("notAfter");
        Path doc = write("no-expiry.yaml", TopicPlan.YAML.writeValueAsString(document));
        Path state = write("state.json", TopicPlan.JSON.writeValueAsString(twoOwners()));
        Result missing = run("topics", "plan", "--file", doc.toString(), "--snapshot", state.toString());
        assertThat(missing.err).contains("games/: a signed change needs notAfter");
        Result keyless = run("topics", "sign", "--plan", doc.toString(), "--key-file",
                write("bad.key", "zz").toString());
        assertThat(keyless.err).doesNotContain("zz");
    }

    @Test
    void initFollowsTheChainsCreationPolicyAndPublicKeyNeverPrintsTheSeed() throws Exception {
        AtomicReference<TopicPlan.Snapshot> current = new AtomicReference<>(twoOwners());
        HttpServer server = server(current, new AtomicInteger(), new ArrayList<>());
        try {
            String peer = "http://127.0.0.1:" + server.getAddress().getPort();
            var defaults = new TopicsCli.Defaults(peer, null, TopicPlanFixtures.CHAIN, true);
            Path owner = seedFile("owner.seed", 7);
            Path child = temporary.resolve("child.yaml");
            Result under = guided(defaults, "init", "games/neon-rush/", "--owner-key-file", owner.toString(),
                    "--output", child.toString());
            assertThat(under.exit).as(under.err).isZero();
            assertThat(under.out).contains("create games/neon-rush/ by the owners of games/");
            JsonNode command = TopicPlan.read(Files.readAllBytes(child), true).path("commands").get(0);
            assertThat(command.path("authority").asText()).isEqualTo("games/");
            assertThat(command.path("notAfter").asLong()).isGreaterThan(System.currentTimeMillis());
            assertThat(command.path("owners").path("keys").get(0).asText()).isEqualTo(publicKey(7));
            // The fixture chain has registrar keys, so a new top-level name is the registrars' to create.
            Path top = temporary.resolve("top.yaml");
            assertThat(guided(defaults, "init", "arena/", "--output", top.toString()).out)
                    .contains("by registrar signatures (~registrars)");
            // An existing namespace gets an update by its own owners, not a second creation.
            Path existing = temporary.resolve("existing.yaml");
            Result update = guided(defaults, "init", "games/", "--output", existing.toString());
            assertThat(update.out).contains("update games/ by the owners of games/");
            JsonNode updateCommand = TopicPlan.read(Files.readAllBytes(existing), true).path("commands").get(0);
            assertThat(updateCommand.path("operation").asText()).isEqualTo("update");
            assertThat(updateCommand.has("owners")).isFalse();
            // Without a node directory or peer, nothing was checked, and it says so.
            Path offline = temporary.resolve("offline.yaml");
            assertThat(guided(new TopicsCli.Defaults(null, null, null, true), "init", "arena/", "--output",
                    offline.toString()).out).contains("The chain was not read");
            Result key = guided(defaults, "public-key", owner.toString());
            assertThat(key.out.strip()).isEqualTo(publicKey(7));
            assertThat(key.out + under.out).doesNotContain(Hex.encode(seed(7)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void voteProgressReadsCommittedStateAndTellsThisChangeFromAnother() throws Exception {
        var snapshot = TopicPlanFixtures.snapshot();
        Path planFile = write("vote.plan.json", TopicPlan.compile(TopicPlanFixtures.document("arena/", "create",
                "governance"), snapshot).toString());
        TopicsCli.Vote vote = TopicsCli.votes(planFile).getFirst();
        var create = (TopicWire.GovCreate) TopicWireCodec.decodeGovernanceCommand(Hex.decode(vote.commandHex()))
                .operation();
        var content = new EntryContent(create.owners(), create.policy(), false);
        var other = new EntryContent(new KeySet(1, List.of(publicKey(2))), create.policy(), false);
        String member = publicKey(5);
        Map<String, byte[]> state = new java.util.HashMap<>();
        TopicsCli.StateReader reader = key -> java.util.Optional.ofNullable(state.get(Hex.encode(key)));
        java.util.function.Consumer<TopicWire.EntryRecord> entry = record ->
                state.put(Hex.encode(TopicKeys.entry("arena/")), TopicWireCodec.encodeEntryRecord(record));

        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.OPEN);
        state.put(Hex.encode(TopicKeys.governance()), TopicWireCodec.encodeGovernancePending(List.of(
                new TopicWire.GovernanceApproval(TopicWireCodec.blake2b256Hex(Hex.decode(vote.commandHex())), 10,
                        List.of(member)))));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.VOTED);
        // An expired tally no longer counts the vote, and another member's tally is not this one's.
        assertThat(TopicsCli.progress(vote, member, reader, 700, 600)).isEqualTo(TopicsCli.Progress.OPEN);
        assertThat(TopicsCli.progress(vote, publicKey(6), reader, 20, 600)).isEqualTo(TopicsCli.Progress.OPEN);

        // Decided: the planned revision is queued with the planned content.
        entry.accept(new TopicWire.EntryRecord("arena/", null, 1, 1, 0));
        byte[] queued = TopicKeys.queued("arena/", 1);
        state.put(Hex.encode(queued), TopicWireCodec.encodeQueuedRevision(new TopicWire.QueuedRevision(1, 12,
                "~governance", 0, content, "77".repeat(32))));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.DECIDED);
        // Another change queued under the same revision, or a cancelled one, is not this vote's.
        state.put(Hex.encode(queued), TopicWireCodec.encodeQueuedRevision(new TopicWire.QueuedRevision(1, 12,
                "~registrars", 0, other, "77".repeat(32))));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.SUPERSEDED);
        state.remove(Hex.encode(queued));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.SUPERSEDED);

        entry.accept(new TopicWire.EntryRecord("arena/", new ActiveRevision(1, 12, 1, content), 1, 0, 0));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.IN_EFFECT);
        entry.accept(new TopicWire.EntryRecord("arena/", new ActiveRevision(1, 12, 1, other), 1, 0, 0));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.SUPERSEDED);
        entry.accept(new TopicWire.EntryRecord("arena/", new ActiveRevision(2, 14, 1, content), 2, 0, 0));
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.SUPERSEDED);

        // A cancelled creation leaves only its freed head; the old plan must not create the name again.
        state.remove(Hex.encode(TopicKeys.entry("arena/")));
        state.put(Hex.encode(TopicKeys.freed("arena/")), java.nio.ByteBuffer.allocate(8).putLong(1).array());
        assertThat(TopicsCli.progress(vote, member, reader, 20, 600)).isEqualTo(TopicsCli.Progress.SUPERSEDED);
    }

    @Test
    void eachSubcommandHasItsOwnHelpAndVotesDescribeWhatTheyDo() throws Exception {
        assertThat(run("topics", "init", "--help").out).contains("Usage: yano.sh appchain topics init")
                .doesNotContain("Usage: yano.sh appchain topics sign");
        assertThat(run("topics", "--help").out).contains("init", "plan", "sign", "submit");
        Path planFile = write("described.plan.json", TopicPlan.compile(TopicPlanFixtures.document("arena/", "create",
                "governance"), TopicPlanFixtures.snapshot()).toString());
        List<String> details = TopicsCli.describe(TopicsCli.votes(planFile).getFirst());
        assertThat(details.getFirst()).isEqualTo("create arena/");
        assertThat(String.join("\n", details)).contains("owners:", "writers:", "payload schema:");
    }

    @Test
    void messagesNeverRepeatAPastedKey() throws Exception {
        String seed = Hex.encode(seed(9));
        ObjectNode document = liveDocument();
        ((ObjectNode) document.path("commands").get(0)).put("operation", seed).put(seed, 1);
        Path doc = write("pasted.yaml", TopicPlan.YAML.writeValueAsString(document));
        Path state = write("state.json", TopicPlan.JSON.writeValueAsString(twoOwners()));
        Result planned = run("topics", "plan", "--file", doc.toString(), "--snapshot", state.toString());
        assertThat(planned.exit).isNotZero();
        assertThat(planned.err).contains("(name not shown)").doesNotContain(seed);
        ((ObjectNode) document.path("commands").get(0)).remove(seed);
        Path op = write("operation.yaml", TopicPlan.YAML.writeValueAsString(document));
        Result operation = run("topics", "plan", "--file", op.toString(), "--snapshot", state.toString());
        assertThat(operation.err).contains("unknown operation").doesNotContain(seed);
        Result missing = run("topics", "sign", "--plan", temporary.resolve(seed).toString(), "--key-file", seed);
        assertThat(missing.err).contains("(path not shown)").doesNotContain(seed);
    }

    private Result guided(TopicsCli.Defaults defaults, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = new TopicsCli().run(args, new PrintWriter(out), new PrintWriter(err), defaults);
        return new Result(code, out.toString(), err.toString());
    }

    private static HttpServer server(AtomicReference<TopicPlan.Snapshot> snapshot, AtomicInteger posts,
                                     List<JsonNode> submissions) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/" + TopicPlanFixtures.CHAIN, exchange -> {
            String path = exchange.getRequestURI().getPath();
            API_KEYS.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-API-Key")));
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
