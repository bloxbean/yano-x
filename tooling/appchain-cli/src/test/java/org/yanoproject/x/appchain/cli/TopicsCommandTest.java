package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.devtools.TopicsCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        var entries = new ConcurrentHashMap<String, String>();
        var votes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/app-chain/chains/games", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = "{}";
            int status = 200;
            if (path.endsWith("/admin/topics/governance")) {
                votes.incrementAndGet();
                body = "{\"messageId\":\"" + "77".repeat(32) + "\"}";
                status = 202;
            } else if (path.contains("/topics/entries/")) {
                // The target is one encoded path segment.
                String name = path.substring(path.indexOf("/topics/entries/") + 16);
                body = entries.get(name);
                if (body == null) status = 404;
            } else if (path.endsWith("/topics/config")) {
                body = "{\"enabled\":true,\"config\":{\"settings\":{\"governanceWindowBlocks\":600}}}";
            } else if (path.endsWith("/status")) {
                body = "{\"stateCommitment\":{\"version\":20}}";
            } else if (path.contains("/state/entry/")) {
                body = "{\"presence\":\"ABSENT\"}";
            }
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var json = new ObjectMapper();
            var owners = json.readTree("{\"threshold\":1,\"keys\":[\"" + "aa".repeat(32) + "\"]}");
            var vote = new TopicsCli.Vote("games", "22".repeat(32), "arena/", "create", "a1".repeat(40), 1, 12,
                    owners);
            NodeClient client = new NodeClient("http://127.0.0.1:" + server.getAddress().getPort(), null);
            var out = new ByteArrayOutputStream();
            Terminal terminal = new Terminal(null, new PrintStream(out, true), false);
            TopicsCommand command = new TopicsCommand(terminal, System.err);
            String path = "/api/v1/app-chain/chains/games";

            assertThat(command.castVotes(client, path, "bb".repeat(32), List.of(vote))).hasSize(1);
            assertThat(votes.get()).isEqualTo(1);
            // Decided but not active: a second vote would open a second tally, so none is sent.
            entries.put("arena%2F", "{\"name\":\"arena/\",\"head\":1,\"pending\":1,\"active\":null}");
            assertThat(command.castVotes(client, path, "bb".repeat(32), List.of(vote))).hasSize(1);
            assertThat(votes.get()).isEqualTo(1);
            entries.put("arena%2F", "{\"name\":\"arena/\",\"head\":1,\"pending\":0,"
                    + "\"active\":{\"revision\":1,"
                    + "\"content\":{\"owners\":" + owners + ",\"retired\":false}}}");
            assertThat(command.castVotes(client, path, "bb".repeat(32), List.of(vote))).isEmpty();
            assertThat(out.toString()).contains("voted (message", "enough operators voted", "already in effect");
            entries.put("arena%2F", "{\"name\":\"arena/\",\"head\":2,\"pending\":0,"
                    + "\"active\":{\"revision\":2,"
                    + "\"content\":{\"owners\":" + owners + ",\"retired\":false}}}");
            assertThatThrownBy(() -> command.castVotes(client, path, "bb".repeat(32), List.of(vote)))
                    .hasMessageContaining("arena/ changed since this plan was made");
            assertThat(votes.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }
}
