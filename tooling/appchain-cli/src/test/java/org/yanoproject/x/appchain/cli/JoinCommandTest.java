package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoinCommandTest {
    private static final List<String> OPERATORS = List.of("alice", "bob", "carol");

    @TempDir
    Path temporary;

    @Test
    void foundingJoinWritesSealedConsensusAndLocalNodeSettingsWithoutSecrets() throws Exception {
        CeremonyFixture fixture = sealed("devnet");
        fixture.ok(fixture.join("supply", "bob"));
        Path bob = fixture.node("bob");
        String consensus = Files.readString(bob.resolve("config/consensus.yaml"));
        String node = Files.readString(bob.resolve("config/node.yaml"));
        assertThat(consensus).contains("chain-id: \"orders\"", "genesis-id: \"" + fixture.sealed("supply").genesis()
                .chain("orders").genesisId() + "\"").doesNotContain("anchor:\n          enabled");
        assertThat(node).contains("peers: \"alice.example:13337,carol.example:13339\"",
                "signing-key: \"${YANO_APPCHAIN_SIGNING_KEY}\"", "port: \"8081\"", "port: \"13338\"",
                // A devnet follower takes Cardano blocks from the lead.
                "host: \"alice.example\"", "enabled: \"false\"",
                // Read indexes stay in this node's directory instead of a store shared through the distribution.
                "path: \"" + bob.resolve("data/appchain-indexers") + "\"",
                "storage-path: \"" + bob.resolve("data/appchain-indexers") + "\"");
        String seed = Files.readString(bob.resolve("secrets/member.key")).strip();
        assertThat(consensus + node).doesNotContain(seed);
        assertThat(Files.readString(fixture.node("alice").resolve("secrets/member.key"))).isNotEqualTo(seed);

        // Joining the same file again is a no-op.
        fixture.ok(fixture.join("supply", "bob"));
        assertThat(Files.readString(bob.resolve("config/node.yaml"))).isEqualTo(node);
    }

    @Test
    void theLeadProducesTheDevnetAndPublicNetworksHaveNoProducer() throws Exception {
        CeremonyFixture devnet = sealed("devnet");
        devnet.ok(devnet.join("supply", "alice"));
        assertThat(Files.readString(devnet.node("alice").resolve("config/node.yaml")))
                .contains("genesis-timestamp: \"${YANO_APPCHAIN_GENESIS_TIMESTAMP}\"")
                .doesNotContain("remote:");

        CeremonyFixture preprod = new CeremonyFixture(temporary.resolve("preprod"));
        preprod.draftAndCards("supply", "preprod", OPERATORS, "orders=audit-log");
        preprod.ok("seal", preprod.draft("supply").toString());
        preprod.ok(preprod.join("supply", "bob"));
        assertThat(Files.readString(preprod.node("bob").resolve("config/node.yaml")))
                .doesNotContain("block-producer", "shelley-genesis-file", "remote:");
    }

    @Test
    void refusesAWrongFingerprintAndScriptsThatSkipTheCheck() throws Exception {
        CeremonyFixture fixture = sealed("devnet");
        var wrong = fixture.run("join", fixture.sealedFile("supply").toString(), "--expect-fingerprint",
                "0000-0000-0000-0000-0000-0000-0000-0000", "--node-dir", fixture.node("bob").toString());
        assertThat(wrong.exit()).isEqualTo(CliException.FAILURE);
        assertThat(wrong.err()).contains("Do not start");
        var unchecked = fixture.run("join", fixture.sealedFile("supply").toString(), "--node-dir",
                fixture.node("bob").toString());
        assertThat(unchecked.exit()).isEqualTo(CliException.USAGE);
        assertThat(unchecked.err()).contains("--expect-fingerprint");
        assertThat(fixture.node("bob").resolve("config")).doesNotExist();
    }

    @Test
    void anOperatorWhoseCardWasReplacedIsToldToStop() throws Exception {
        CeremonyFixture fixture = sealed("devnet");
        // mallory made a card on her own machine but is not in the roster.
        new CeremonyFixture(temporary.resolve("mallory")).card(fixture.draft("supply"), "mallory", 5);
        Path mallory = temporary.resolve("mallory").resolve("mallory");
        var refused = fixture.run("join", fixture.sealedFile("supply").toString(), "--expect-fingerprint",
                fixture.sealed("supply").displayFingerprint(), "--node-dir", mallory.toString());
        assertThat(refused.exit()).isEqualTo(CliException.FAILURE);
        assertThat(refused.err()).contains("is not a member of supply", "stop and tell them");
    }

    @Test
    void aLaterMemberJoinsOnlyAfterRequestingAndKeepsTheOriginalGenesis() throws Exception {
        CeremonyFixture fixture = sealed("devnet");
        var card = fixture.card(fixture.sealedFile("supply"), "dave", 3);
        assertThat(card.out()).contains("approve dave.member");
        fixture.ok(fixture.join("supply", "dave"));
        assertThat(Files.readString(fixture.node("dave").resolve("config/node.yaml")))
                .contains("peers: \"alice.example:13337,bob.example:13338,carol.example:13339\"");
        assertThat(Files.readString(fixture.node("dave").resolve("config/consensus.yaml")))
                .doesNotContain(Files.readString(fixture.node("dave").resolve("node.json")).replaceAll(
                        "(?s).*\"publicKey\" : \"([0-9a-f]{64})\".*", "$1"));
    }

    @Test
    void oneNodeRefusesApplicationsOfAnotherNetworkOrWithAClashingChainId() throws Exception {
        CeremonyFixture fixture = sealed("devnet");
        fixture.ok(fixture.join("supply", "bob"));
        for (String[] second : new String[][]{{"ledger", "preprod", "payments"}, {"audit", "devnet", "orders"}}) {
            CeremonyFixture other = new CeremonyFixture(temporary.resolve(second[0]));
            other.ok("new", second[0], "--network", second[1], "--operators", "bob", "--chain",
                    second[2] + "=audit-log", "--output", other.coordinator.toString(), "--yes");
            // bob's existing node directory makes the card, so its key is in the second roster.
            other.ok("request", other.draft(second[0]).toString(), "--name", "bob", "--node-dir",
                    fixture.node("bob").toString(), "--output", other.coordinator.toString(), "--yes");
            other.ok("seal", other.draft(second[0]).toString());
            var refused = other.run("join", other.sealedFile(second[0]).toString(), "--expect-fingerprint",
                    other.sealed(second[0]).displayFingerprint(), "--node-dir", fixture.node("bob").toString());
            assertThat(refused.exit()).isEqualTo(CliException.FAILURE);
            assertThat(refused.err()).containsAnyOf("one Cardano network only", "already runs a chain named",
                    "must share the lead");
        }
    }

    private CeremonyFixture sealed(String network) throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("supply", network, OPERATORS, "orders=audit-log");
        fixture.ok("seal", fixture.draft("supply").toString());
        return fixture;
    }
}
