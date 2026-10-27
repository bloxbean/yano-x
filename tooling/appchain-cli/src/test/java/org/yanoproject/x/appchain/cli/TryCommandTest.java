package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code try} takes the same chains as {@code new}; a chain it cannot create leaves no rehearsal behind. */
class TryCommandTest {
    @TempDir
    Path temporary;

    @Test
    void aChainTryCannotCreateLeavesNoRehearsal() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        Path rehearsal = temporary.resolve("rehearsal");

        var unknown = fixture.run("try", "--chain", "orders=no-such-recipe", "--dir", rehearsal.toString());
        assertThat(unknown.exit()).isEqualTo(CliException.FAILURE);
        assertThat(unknown.err()).contains("Unknown recipe 'no-such-recipe'", "audit-log");
        assertThat(rehearsal).doesNotExist();

        var unanswered = fixture.run("try", "--chain", "ledger=eutxo-ledger", "--dir", rehearsal.toString());
        assertThat(unanswered.exit()).isEqualTo(CliException.FAILURE);
        assertThat(unanswered.err()).contains("--answer ledger.");
        assertThat(rehearsal).doesNotExist();
    }

    @Test
    void newNamesTheRecipesForAnUnknownOneAndSendsCompositionToBindings() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        var unknown = fixture.run("new", "app", "--network", "devnet", "--operators", "alice", "--chain",
                "orders=nope", "--output", fixture.coordinator.toString(), "--yes");
        assertThat(unknown.exit()).isEqualTo(CliException.USAGE);
        assertThat(unknown.err()).contains("Unknown recipe 'nope'", "owned-registry")
                .doesNotContain("declarative-composite");

        var composite = fixture.run("new", "app", "--network", "devnet", "--operators", "alice", "--chain",
                "flow=declarative-composite", "--output", fixture.coordinator.toString(), "--yes");
        assertThat(composite.err()).contains("--chain flow=bindings:<file>");
        assertThat(Files.exists(fixture.coordinator.resolve("app.draft.yaml"))).isFalse();
    }
}
