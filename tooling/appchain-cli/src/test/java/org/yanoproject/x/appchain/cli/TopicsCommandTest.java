package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.devtools.TopicsCli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

        var noPeer = fixture.run("topics", "resolve", "games/rides", "--node-dir", temporary.resolve("node").toString());
        assertThat(noPeer.exit()).isNotZero();
        assertThat(noPeer.err()).contains("missing --chain");
    }
}
