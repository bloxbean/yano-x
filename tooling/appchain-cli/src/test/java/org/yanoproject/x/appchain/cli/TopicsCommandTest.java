package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.devtools.TopicsCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicsCommandTest {
    @TempDir
    Path temporary;

    @Test
    void aJoinedNodeSuppliesItsAddressKeyAndOnlyChain() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("arcade", "devnet", List.of("alice", "bob", "carol"), "games=audit-log");
        fixture.ok("seal", fixture.draft("arcade").toString());
        fixture.ok(fixture.join("arcade", "alice", "--yes"));

        NodeDir alice = new NodeDir(fixture.node("alice"));
        TopicsCli.Defaults defaults = TopicsCommand.defaults(alice, null, true);
        assertThat(defaults.peer()).isEqualTo("http://127.0.0.1:8080");
        assertThat(defaults.chain()).isEqualTo("games");
        assertThat(defaults.apiKey()).isEqualTo(alice.apiKey());
        assertThat(defaults.guided()).isTrue();

        // Outside a node directory nothing is assumed.
        TopicsCli.Defaults none = TopicsCommand.defaults(new NodeDir(temporary.resolve("nowhere")), null, false);
        assertThat(none.peer()).isNull();
        assertThat(none.apiKey()).isNull();
        assertThat(none.chain()).isNull();
    }

    @Test
    void aRevocationWaitsForOneBlockNotTheActivationLag() {
        assertThat(TopicsCommand.waitReason(true)).isEqualTo("a revocation takes effect in the next block");
        assertThat(TopicsCommand.waitReason(false)).contains("activation lag");
    }

    @Test
    void topicsHelpAndRefusalsNameTheNextStep() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        assertThat(fixture.ok("topics", "--help").out()).contains("topics approve <plan.json>", "--node-dir");
        assertThat(fixture.ok("help").out()).contains("topics approve <plan>", "topics plan|sign|submit");

        var missing = fixture.run("topics", "approve");
        assertThat(missing.exit()).isEqualTo(CliException.USAGE);
        assertThat(missing.err()).contains("Name the plan");

        Path bogus = Files.writeString(temporary.resolve("bogus.plan.json"), "{\"kind\":\"Topics\"}");
        var invalid = fixture.run("topics", "approve", bogus.toString(), "--node-dir",
                temporary.resolve("node").toString());
        assertThat(invalid.exit()).isEqualTo(CliException.FAILURE);
        assertThat(invalid.err()).contains("bogus.plan.json is not a valid topics plan");

        var noPeer = fixture.run("topics", "resolve", "games/rides", "--node-dir",
                temporary.resolve("node").toString());
        assertThat(noPeer.exit()).isNotZero();
        assertThat(noPeer.err()).contains("missing --chain");
    }

    @Test
    void approveVotesOnceAndRemembersEveryVoteItCast() throws Exception {
        var state = new ConcurrentHashMap<String, String>();
        var votes = new AtomicInteger();
        var failing = new AtomicBoolean();
        String member = "bb".repeat(32);
        String tallyKey = HexFormat.of().formatHex(TopicKeys.governance());
        var owners = new TopicWire.KeySet(1, List.of("aa".repeat(32)));
        var policy = new TopicWire.Policy(TopicWire.Writers.OPEN, List.of(), null, "", null);
        String commandHex = HexFormat.of().formatHex(TopicWireCodec.encodeGovernanceCommand(
                new TopicWire.GovernanceCommand("games", "22".repeat(32), "44".repeat(16),
                        new TopicWire.GovCreate("arena/", owners, policy, null))));
        String tally = HexFormat.of().formatHex(TopicWireCodec.encodeGovernancePending(List.of(
                new TopicWire.GovernanceApproval(TopicWireCodec.blake2b256Hex(HexFormat.of().parseHex(commandHex)), 15,
                        List.of(member)))));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/games", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = "{}";
            int status = 200;
            if (path.endsWith("/admin/topics/governance")) {
                votes.incrementAndGet();
                state.put(tallyKey, tally); // the next block includes the vote
                body = "{\"messageId\":\"" + "77".repeat(32) + "\"}";
                status = 202;
            } else if (path.contains("/state/entry/")) {
                // Every read names the height it is pinned to.
                assertThat(exchange.getRequestURI().getQuery()).isEqualTo("height=20");
                String value = state.get(path.substring(path.lastIndexOf('/') + 1));
                body = value == null ? "{\"presence\":\"ABSENT\"}"
                        : "{\"presence\":\"PRESENT\",\"valueHex\":\"" + value + "\"}";
                if (failing.get()) status = 500;
            } else if (path.endsWith("/topics/config")) {
                body = "{\"enabled\":true,\"config\":{\"settings\":{\"governanceWindowBlocks\":600}}}";
            } else if (path.endsWith("/status")) {
                body = "{\"stateCommitment\":{\"version\":20}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var vote = new TopicsCli.Vote("games", "22".repeat(32), "arena/", "create", commandHex, 1, 12,
                    new ObjectMapper().valueToTree(owners));
            NodeClient client = new NodeClient("http://127.0.0.1:" + server.getAddress().getPort(), null);
            var out = new ByteArrayOutputStream();
            TopicsCommand command = new TopicsCommand(new Terminal(null, new PrintStream(out, true), false),
                    System.err);
            Path records = temporary.resolve("topic-votes");
            Path record = records.resolve(TopicsCli.commandHash(vote) + ".json");
            TopicsCommand.Ballot ballot = command.new Ballot(client, "/api/v1/app-chain/chains/games", member,
                    records, 600_000);
            String entryKey = HexFormat.of().formatHex(TopicKeys.entry("arena/"));
            var content = new TopicWire.EntryContent(owners, policy, false);

            assertThat(ballot.standing(vote)).isEqualTo(TopicsCommand.Standing.OPEN);
            assertThat(cast(ballot, vote, false)).hasSize(1);
            assertThat(votes.get()).isEqualTo(1);
            assertThat(record).isRegularFile();
            // This member's vote is in the tally: no second vote.
            assertThat(ballot.standing(vote)).isEqualTo(TopicsCommand.Standing.VOTED);
            assertThat(cast(ballot, vote, false)).hasSize(1);
            // Cast but not in a block yet: still this node's vote, so none is sent.
            state.remove(tallyKey);
            assertThat(ballot.standing(vote)).isEqualTo(TopicsCommand.Standing.IN_FLIGHT);
            assertThat(cast(ballot, vote, false)).hasSize(1);
            assertThat(votes.get()).isEqualTo(1);
            // The chain refused it: reported, never resent.
            state.put(HexFormat.of().formatHex(TopicKeys.receipt("77".repeat(32))), HexFormat.of().formatHex(
                    TopicWireCodec.encodeReceipt(new TopicWire.Receipt("TOPIC_COMMAND_EXPIRED", 0))));
            assertThat(ballot.standing(vote)).isEqualTo(TopicsCommand.Standing.REFUSED);
            assertThat(cast(ballot, vote, true)).isEmpty();
            state.remove(HexFormat.of().formatHex(TopicKeys.receipt("77".repeat(32))));
            // An old vote that never counted lapses; only --again votes again.
            Files.writeString(record, Files.readString(record).replaceAll("\"at\":\\d+", "\"at\":0"));
            assertThat(ballot.standing(vote)).isEqualTo(TopicsCommand.Standing.LAPSED);
            assertThat(cast(ballot, vote, false)).isEmpty();
            assertThat(votes.get()).isEqualTo(1);
            assertThat(cast(ballot, vote, true)).hasSize(1);
            assertThat(votes.get()).isEqualTo(2);
            // A reservation left by an interrupted send (no message id) may still be pending: nothing is resent.
            state.remove(tallyKey);
            Files.writeString(record, "{\"chain\":\"games\",\"at\":" + System.currentTimeMillis() + "}\n");
            assertThat(ballot.standing(vote)).isEqualTo(TopicsCommand.Standing.IN_FLIGHT);
            assertThat(cast(ballot, vote, true)).hasSize(1);
            assertThat(votes.get()).isEqualTo(2);
            // Decided and queued: a vote now would open a second tally, so none is sent.
            state.remove(tallyKey);
            Files.delete(record);
            state.put(entryKey, HexFormat.of().formatHex(TopicWireCodec.encodeEntryRecord(
                    new TopicWire.EntryRecord("arena/", null, 1, 1, 0))));
            state.put(HexFormat.of().formatHex(TopicKeys.queued("arena/", 1)), HexFormat.of().formatHex(
                    TopicWireCodec.encodeQueuedRevision(new TopicWire.QueuedRevision(1, 12, "~governance", 0, content,
                            "77".repeat(32)))));
            assertThat(cast(ballot, vote, false)).hasSize(1);
            state.put(entryKey, HexFormat.of().formatHex(TopicWireCodec.encodeEntryRecord(new TopicWire.EntryRecord(
                    "arena/", new TopicWire.ActiveRevision(1, 12, 1, content), 1, 0, 0))));
            assertThat(cast(ballot, vote, false)).isEmpty();
            assertThat(votes.get()).isEqualTo(2);
            assertThat(out.toString()).contains("voted (message", "this node already voted",
                    "waiting to be included", "vote was refused", "Run with --again", "enough operators voted",
                    "already in effect");
            // Someone else's content at the planned revision makes the plan out of date.
            state.put(entryKey, HexFormat.of().formatHex(TopicWireCodec.encodeEntryRecord(new TopicWire.EntryRecord(
                    "arena/", new TopicWire.ActiveRevision(1, 12, 1, new TopicWire.EntryContent(
                    new TopicWire.KeySet(1, List.of("cc".repeat(32))), policy, false)), 1, 0, 0))));
            assertThatThrownBy(() -> cast(ballot, vote, false))
                    .hasMessageContaining("arena/ changed since this plan was made");
            // A read the node cannot answer stops the command; it never counts as no vote.
            state.remove(entryKey);
            failing.set(true);
            assertThatThrownBy(() -> ballot.standing(vote)).hasMessageContaining("Cannot read where the vote stands");
            assertThat(votes.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aVoteLapsesOnlyAfterTheNodesOwnMessageLifetime() throws Exception {
        NodeDir node = new NodeDir(temporary.resolve("node"));
        assertThat(TopicsCommand.messageLifetimeMs(node)).isEqualTo(600_000);
        Files.createDirectories(node.path("config"));
        Files.writeString(node.path("config").resolve("local.yaml"),
                "yano:\n  app-chain:\n    default-ttl-seconds: 3600\n");
        assertThat(TopicsCommand.messageLifetimeMs(node)).isEqualTo(3_600_000);
    }

    @Test
    void approveHelpListsItsOptions() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        assertThat(fixture.ok("topics", "approve", "--help").out()).contains("Usage: " + TopicsCommand.APPROVE_USAGE,
                "--plan-sha256 <hex>  refuse the plan", "--again", "--timeout <seconds>");
    }

    @Test
    void submitJsonWaitsRecordsTheSubmissionAndFollowsItOnARerun() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String chain = "games";
        String generation = "22".repeat(32);
        String root = "33".repeat(32);
        String messageId = "66".repeat(32);
        Path seed = Files.writeString(temporary.resolve("owner.seed"), "01".repeat(32));
        String owner = tool(json, "public-key", seed.toString()).path("publicKey").asText();
        var owners = new TopicWire.KeySet(1, List.of(owner));
        var settings = new TopicWire.Settings(true, null, false, false, 3_600_000, 3_600_000, 16, 1_024, 128, 2,
                600);
        var config = new TopicWire.Config(1, 1, settings);
        var entry = new TopicWire.EntryRecord("games/", new TopicWire.ActiveRevision(1, 2, 1,
                new TopicWire.EntryContent(owners, new TopicWire.Policy(TopicWire.Writers.OPEN, List.of(), null, "",
                        null), false)), 1, 0, 0);
        var snapshot = json.createObjectNode().put("chainId", chain).put("generationId", generation)
                .put("height", 10).put("stateRoot", root).put("maxMessageBytes", 65_536);
        snapshot.set("config", json.valueToTree(config));
        snapshot.putObject("entries").set("games/", json.valueToTree(entry));
        snapshot.putObject("freedHeads");
        Path snapshotFile = Files.writeString(temporary.resolve("snapshot.json"), snapshot.toString());
        var document = json.createObjectNode().put("schemaVersion", 1).put("kind", "Topics").put("chainId", chain);
        document.putArray("commands").addObject().put("target", "games/").put("operation", "update")
                .put("authority", "games/").put("nonce", "44".repeat(16))
                .put("notAfter", System.currentTimeMillis() + 300_000).putObject("policy").put("writers", "open");
        Path doc = Files.writeString(temporary.resolve("games.json"), document.toString());
        Path plan = temporary.resolve("games.plan.json");
        Path signed = temporary.resolve("games.signed.json");
        tool(json, "plan", doc.toString(), "--snapshot", snapshotFile.toString(), "--output", plan.toString());
        tool(json, "sign", plan.toString(), "--key-file", seed.toString(), "--output", signed.toString());
        String statementHash = json.readTree(signed.toFile()).path("commands").get(0).path("statementHash").asText();

        // A node that takes the command once: its receipt and the new revision are in the next block, and the
        // same statement again is refused as pending.
        var state = new ConcurrentHashMap<String, String>();
        var posts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/" + chain, exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body;
            int status = 200;
            if (path.endsWith("/status")) {
                var value = json.createObjectNode().put("chainId", chain);
                value.putObject("stateCommitment").put("generationId", generation).put("version", 10)
                        .put("stateRoot", root);
                value.putObject("consensusProfile").put("maxMessageBytes", 65_536);
                body = value.toString();
            } else if (path.endsWith("/topics/config")) {
                var value = json.createObjectNode().put("enabled", true);
                value.set("config", json.valueToTree(config));
                body = value.toString();
            } else if (path.contains("/topics/entries/")) {
                body = json.writeValueAsString(entry);
            } else if (path.endsWith("/messages")) {
                if (posts.incrementAndGet() > 1) {
                    status = 409;
                    body = "{\"code\":\"TOPIC_COMMAND_PENDING\"}";
                } else {
                    state.put(HexFormat.of().formatHex(TopicKeys.receipt(messageId)), HexFormat.of().formatHex(
                            TopicWireCodec.encodeReceipt(new TopicWire.Receipt(TopicWire.Receipt.ACCEPTED, 13))));
                    state.put(HexFormat.of().formatHex(TopicKeys.entry("games/")), HexFormat.of().formatHex(
                            TopicWireCodec.encodeEntryRecord(new TopicWire.EntryRecord("games/",
                                    new TopicWire.ActiveRevision(2, 13, 1, entry.active().content()), 2, 0, 0))));
                    state.put(HexFormat.of().formatHex(TopicKeys.used(statementHash)), "01");
                    status = 202;
                    body = "{\"messageId\":\"" + messageId + "\"}";
                }
            } else if (path.contains("/state/entry/")) {
                String value = state.get(path.substring(path.lastIndexOf('/') + 1));
                body = value == null ? "{\"stateRoot\":\"" + root + "\",\"presence\":\"ABSENT\"}"
                        : "{\"stateRoot\":\"" + root + "\",\"presence\":\"PRESENT\",\"valueHex\":\""
                        + value + "\"}";
            } else {
                status = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            NodeDir node = new NodeDir(temporary.resolve("node"));
            node.saveIdentity(new NodeDir.Identity("alice", "bb".repeat(32), "127.0.0.1:1", "127.0.0.1:"
                    + server.getAddress().getPort(), 1, server.getAddress().getPort()));
            CeremonyFixture fixture = new CeremonyFixture(temporary);
            var first = fixture.run("topics", "submit", signed.toString(), "--json", "--timeout", "30",
                    "--node-dir", node.root().toString());
            assertThat(first.exit()).as(first.err()).isZero();
            // Standard output is only the result; the progress is on standard error.
            var result = json.readTree(first.out());
            assertThat(result.path("commands").get(0).path("submission").path("messageId").asText())
                    .isEqualTo(messageId);
            assertThat(result.path("commands").get(0).path("inEffect").asBoolean()).isTrue();
            assertThat(first.err()).contains("Submitted games/ (message " + messageId + ")",
                    "games/ is in effect");
            assertThat(node.path("topic-submissions").resolve(statementHash + ".json")).isRegularFile();

            // Run again, as JSON and without waiting: the node refuses the copy, and this node directory knows
            // the earlier submission, so it follows that one instead.
            var again = fixture.run("topics", "submit", signed.toString(), "--json", "--node-dir",
                    node.root().toString());
            assertThat(again.exit()).as(again.err()).isZero();
            assertThat(again.err()).contains("was already submitted").doesNotContain("not from this node directory");
            assertThat(json.readTree(again.out()).path("alreadySubmitted").asBoolean()).isTrue();
            assertThat(posts.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static JsonNode tool(ObjectMapper json, String... args) throws Exception {
        var out = new StringWriter();
        var err = new StringWriter();
        int exit = new TopicsCli().run(args, new PrintWriter(out), new PrintWriter(err),
                TopicsCli.Defaults.NONE);
        assertThat(exit).as(err.toString()).isZero();
        return out.toString().startsWith("Written") ? json.createObjectNode() : json.readTree(out.toString());
    }

    private static List<TopicsCli.Vote> cast(TopicsCommand.Ballot ballot, TopicsCli.Vote vote, boolean again) {
        return ballot.cast(List.of(vote), again);
    }
}
