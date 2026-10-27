package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.FormatException;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A draft's {@code topics:} block becomes the chain's sealed topic-registry genesis seed (ADR-038, ADR-056). */
class TopicsSealTest {
    private static final List<String> OPERATORS = List.of("alice", "bob", "carol");

    @TempDir
    Path temporary;

    @Test
    void registrarsNamedByOperatorAreSealedAsTheirMemberKeys() throws Exception {
        CeremonyFixture fixture = draft("--chain", "games=audit-log", "--topics", "games=registrars:alice+bob");
        Draft draft = Draft.parse(Files.readString(fixture.draft("arcade")));
        assertThat(draft.chains().getFirst().topics().registrars()).containsExactly("alice", "bob");
        assertThat(Files.readString(fixture.draft("arcade"))).contains("topics:", "create: registrars",
                "registrars: [alice, bob]", "unregistered: open");

        var sealed = fixture.ok("seal", fixture.draft("arcade").toString());
        assertThat(sealed.out()).contains("topics: namespaces created by alice, bob or by member vote; "
                + "unregistered topics open");
        SealedApp app = fixture.sealed("arcade");
        Map<String, String> settings = app.genesis().chain("games").settings();
        String alice = app.genesis().member("alice").publicKey();
        String bob = app.genesis().member("bob").publicKey();
        assertThat(settings).containsEntry("topics.mode", "enabled")
                .containsEntry("topics.creation.governance", "true")
                .containsEntry("topics.creation.open-claims", "false")
                .containsEntry("topics.creation.registrars.threshold", "1")
                .containsEntry("topics.creation.registrars.keys",
                        String.join(",", List.of(alice, bob).stream().sorted().toList()))
                .containsEntry("topics.unregistered-topics", "open");
        // The genesis id covers the seed, so changing the policy later means a new application.
        assertThat(app.genesis().chain("games").genesisId()).hasSize(64);
    }

    @Test
    void openClaimsAndLimitsSealOverTheHostDefaults() throws Exception {
        CeremonyFixture fixture = draft("--chain", "games=audit-log", "--topics", "games=anyone");
        Path file = fixture.draft("arcade");
        Files.writeString(file, Files.readString(file).replace("unregistered: open",
                "unregistered: reject\n      limits:\n        activation-lag: 3"));
        fixture.ok("seal", file.toString());
        Map<String, String> settings = fixture.sealed("arcade").genesis().chain("games").settings();
        assertThat(settings).containsEntry("topics.creation.open-claims", "true")
                .containsEntry("topics.unregistered-topics", "reject")
                .containsEntry("topics.activation-lag", "3")
                .containsEntry("topics.max-delegation-ms", "3600000")
                // A registrar threshold without keys is refused by the host, so the default is not sealed.
                .doesNotContainKeys("topics.creation.registrars.keys", "topics.creation.registrars.threshold");
    }

    @Test
    void chainsWithoutTopicsSealTheRegistryDisabled() throws Exception {
        CeremonyFixture fixture = draft("--chain", "orders=audit-log");
        var sealed = fixture.ok("seal", fixture.draft("arcade").toString());
        assertThat(sealed.out()).doesNotContain("topics:");
        assertThat(fixture.sealed("arcade").genesis().chain("orders").settings())
                .containsEntry("topics.mode", "disabled");
    }

    @Test
    void refusesTopicsOnStaticMembershipUnknownRegistrarsAndRawSettings() throws Exception {
        var staticMembers = new CeremonyFixture(temporary.resolve("static")).run("new", "arcade", "--network",
                "devnet", "--operators", "alice,bob,carol", "--chain", "games=audit-log", "--membership", "static",
                "--topics", "games=members", "--output", temporary.resolve("static").toString(), "--yes");
        assertThat(staticMembers.exit()).isNotZero();
        assertThat(staticMembers.err()).contains("needs membership: governed");

        var stranger = new CeremonyFixture(temporary.resolve("stranger")).run("new", "arcade", "--network",
                "devnet", "--operators", "alice,bob,carol", "--chain", "games=audit-log",
                "--topics", "games=registrars:dave", "--output", temporary.resolve("stranger").toString(), "--yes");
        assertThat(stranger.exit()).isNotZero();
        assertThat(stranger.err()).contains("registrar 'dave' is neither an operator");

        CeremonyFixture fixture = draft("--chain", "games=audit-log");
        Path file = fixture.draft("arcade");
        Files.writeString(file, Files.readString(file).replace("    recipe: audit-log\n",
                "    recipe: audit-log\n    settings:\n      topics.mode: enabled\n"));
        var raw = fixture.run("seal", file.toString());
        assertThat(raw.exit()).isEqualTo(CliException.FAILURE);
        assertThat(raw.err()).contains("set topic policies in the chain's topics: block");
    }

    @Test
    void draftBlockIsCheckedWhenRead() {
        assertThatThrownBy(() -> new Draft.Topics("everyone", List.of(), 0, null, Map.of()))
                .isInstanceOf(FormatException.class).hasMessageContaining("members, registrars or anyone");
        assertThatThrownBy(() -> new Draft.Topics("registrars", List.of(), 0, null, Map.of()))
                .isInstanceOf(FormatException.class).hasMessageContaining("name the operators");
        assertThatThrownBy(() -> new Draft.Topics("members", List.of("alice"), 1, null, Map.of()))
                .isInstanceOf(FormatException.class).hasMessageContaining("only applies with create: registrars");
        assertThatThrownBy(() -> new Draft.Topics("registrars", List.of("alice"), 2, null, Map.of()))
                .isInstanceOf(FormatException.class).hasMessageContaining("registrar-threshold");
        assertThatThrownBy(() -> new Draft.Topics("members", List.of(), 0, "maybe", Map.of()))
                .isInstanceOf(FormatException.class).hasMessageContaining("'reject' or 'open'");
        assertThatThrownBy(() -> new Draft.Topics("members", List.of(), 0, null, Map.of("max-cats", 1L)))
                .isInstanceOf(FormatException.class).hasMessageContaining("Unknown topics limit 'max-cats'");
        assertThat(Draft.Topics.members().summary())
                .isEqualTo("namespaces created by member vote; unregistered topics open");
    }

    @Test
    void inspectShowsTheDraftTopicPolicy() throws Exception {
        CeremonyFixture fixture = draft("--chain", "games=audit-log", "--topics", "games=members");
        assertThat(fixture.ok("inspect", fixture.draft("arcade").toString()).out())
                .contains("topics: namespaces created by member vote; unregistered topics open");
    }

    private CeremonyFixture draft(String... chains) throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        List<String> create = new java.util.ArrayList<>(List.of("new", "arcade", "--network", "devnet",
                "--operators", String.join(",", OPERATORS), "--output", fixture.coordinator.toString(), "--yes"));
        create.addAll(List.of(chains));
        fixture.ok(create.toArray(String[]::new));
        int index = 0;
        for (String operator : OPERATORS) fixture.card(fixture.draft("arcade"), operator, index++);
        return fixture;
    }
}
