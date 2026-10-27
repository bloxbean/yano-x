package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.devtools.TopicsCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
    void approveVotesOnlyWhenThisMemberHasNotVotedAndStopsOnAnOutOfDatePlan() throws Exception {
        var state = new ConcurrentHashMap<String, String>();
        var votes = new AtomicInteger();
        var failing = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/games", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = "{}";
            int status = 200;
            if (path.endsWith("/admin/topics/governance")) {
                votes.incrementAndGet();
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
            var owners = new TopicWire.KeySet(1, List.of("aa".repeat(32)));
            var policy = new TopicWire.Policy(TopicWire.Writers.OPEN, List.of(), null, "", null);
            String commandHex = HexFormat.of().formatHex(TopicWireCodec.encodeGovernanceCommand(
                    new TopicWire.GovernanceCommand("games", "22".repeat(32), "44".repeat(16),
                            new TopicWire.GovCreate("arena/", owners, policy, null))));
            var vote = new TopicsCli.Vote("games", "22".repeat(32), "arena/", "create", commandHex, 1, 12,
                    new ObjectMapper().valueToTree(owners));
            NodeClient client = new NodeClient("http://127.0.0.1:" + server.getAddress().getPort(), null);
            var out = new ByteArrayOutputStream();
            TopicsCommand command = new TopicsCommand(new Terminal(null, new PrintStream(out, true), false),
                    System.err);
            String path = "/api/v1/app-chain/chains/games";
            String member = "bb".repeat(32);
            String entryKey = HexFormat.of().formatHex(TopicKeys.entry("arena/"));
            var content = new TopicWire.EntryContent(owners, policy, false);

            assertThat(command.castVotes(client, path, member, List.of(vote))).hasSize(1);
            assertThat(votes.get()).isEqualTo(1);
            // This member's vote is in the tally: no second vote.
            state.put(HexFormat.of().formatHex(TopicKeys.governance()), HexFormat.of().formatHex(
                    TopicWireCodec.encodeGovernancePending(List.of(new TopicWire.GovernanceApproval(
                            TopicWireCodec.blake2b256Hex(HexFormat.of().parseHex(commandHex)), 15, List.of(member))))));
            assertThat(command.castVotes(client, path, member, List.of(vote))).hasSize(1);
            // Decided and queued: a vote now would open a second tally, so none is sent.
            state.put(entryKey, HexFormat.of().formatHex(TopicWireCodec.encodeEntryRecord(
                    new TopicWire.EntryRecord("arena/", null, 1, 1, 0))));
            state.put(HexFormat.of().formatHex(TopicKeys.queued("arena/", 1)), HexFormat.of().formatHex(
                    TopicWireCodec.encodeQueuedRevision(new TopicWire.QueuedRevision(1, 12, "governance", 0, content,
                            "77".repeat(32)))));
            assertThat(command.castVotes(client, path, member, List.of(vote))).hasSize(1);
            assertThat(votes.get()).isEqualTo(1);
            state.put(entryKey, HexFormat.of().formatHex(TopicWireCodec.encodeEntryRecord(new TopicWire.EntryRecord(
                    "arena/", new TopicWire.ActiveRevision(1, 12, 1, content), 1, 0, 0))));
            assertThat(command.castVotes(client, path, member, List.of(vote))).isEmpty();
            assertThat(out.toString()).contains("voted (message", "this node already voted",
                    "enough operators voted", "already in effect");
            // Someone else's content at the planned revision makes the plan out of date.
            state.put(entryKey, HexFormat.of().formatHex(TopicWireCodec.encodeEntryRecord(new TopicWire.EntryRecord(
                    "arena/", new TopicWire.ActiveRevision(1, 12, 1, new TopicWire.EntryContent(
                    new TopicWire.KeySet(1, List.of("cc".repeat(32))), policy, false)), 1, 0, 0))));
            assertThatThrownBy(() -> command.castVotes(client, path, member, List.of(vote)))
                    .hasMessageContaining("arena/ changed since this plan was made");
            // A read the node cannot answer stops the command; it never counts as no vote.
            state.remove(entryKey);
            failing.set(true);
            assertThatThrownBy(() -> command.castVotes(client, path, member, List.of(vote)))
                    .hasMessageContaining("Cannot read where the vote stands");
            assertThat(votes.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
