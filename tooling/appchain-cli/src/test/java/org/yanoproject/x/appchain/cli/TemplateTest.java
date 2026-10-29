package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code new --from}: a template's chains in a fresh draft, adjusted with --without and --setting. */
class TemplateTest {
    private static final String TEMPLATE = """
            # An application others can start from.
            threshold: all
            chains:
              - id: orders
                recipe: audit-log
                settings:
                  block.interval-ms: "2000"
              - id: registry
                recipe: owned-registry
              - id: flow
                recipe: declarative-composite
                bindings: ./flow.yaml
            """;

    @TempDir
    Path temporary;

    @Test
    void aTemplateBecomesADraftWithItsOwnInviteAndTheChosenChanges() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary.resolve("ceremony"));
        Path templates = Files.createDirectories(temporary.resolve("templates"));
        Path template = templates.resolve("shop.template.yaml");
        Files.writeString(template, TEMPLATE);
        Files.writeString(templates.resolve("flow.yaml"), "composite: {components: [], bindings: []}\n");

        fixture.ok("new", "shop", "--from", template.toString(), "--network", "devnet", "--operators", "alice,bob",
                "--without", "registry", "--setting", "orders.anchor.mode=script",
                "--setting", "orders.block.interval-ms=3000", "--output", fixture.coordinator.toString(), "--yes");
        Draft draft = Draft.parse(Files.readString(fixture.draft("shop")));
        assertThat(draft.chains()).extracting(Draft.Chain::id).containsExactly("orders", "flow");
        assertThat(draft.threshold()).as("the template's default").isEqualTo("all");
        assertThat(draft.chains().getFirst().settings())
                .containsEntry("block.interval-ms", "3000").containsEntry("anchor.mode", "script");
        // A bindings file beside the template is copied beside the draft, like a --chain one.
        assertThat(draft.chains().get(1).bindings()).isEqualTo("./flow.bindings.yaml");
        assertThat(fixture.coordinator.resolve("flow.bindings.yaml")).hasContent(
                "composite: {components: [], bindings: []}\n");

        // Each draft made from the template gets its own invite code.
        Path second = Files.createDirectories(temporary.resolve("second"));
        fixture.ok("new", "shop", "--from", template.toString(), "--network", "devnet", "--operators", "alice",
                "--output", second.toString(), "--yes");
        assertThat(Draft.parse(Files.readString(second.resolve("shop.draft.yaml"))).invite())
                .isNotEqualTo(draft.invite());
    }

    @Test
    void mistakesInATemplateOrItsChangesAreNamed() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        Path template = temporary.resolve("bad.template.yaml");
        Files.writeString(template, "chains:\n  - id: orders\n    recipe: nope\n");
        var unknown = fixture.run("new", "app", "--from", template.toString(), "--network", "devnet",
                "--operators", "alice", "--output", fixture.coordinator.toString(), "--yes");
        assertThat(unknown.exit()).isEqualTo(CliException.USAGE);
        assertThat(unknown.err()).contains("Unknown recipe 'nope' for chain 'orders'");

        Files.writeString(template, "chains:\n  - id: orders\n    recipe: audit-log\n");
        for (List<String> change : List.of(List.of("--without", "registry"), List.of("--setting", "registry.a=b"),
                List.of("--setting", "orders=b"))) {
            List<String> arguments = new java.util.ArrayList<>(List.of("new", "app", "--from", template.toString(),
                    "--network", "devnet", "--operators", "alice", "--output", fixture.coordinator.toString(),
                    "--yes", "--force"));
            arguments.addAll(change);
            var refused = fixture.run(arguments.toArray(String[]::new));
            assertThat(refused.exit()).as(change.toString()).isEqualTo(CliException.USAGE);
        }
    }

    /** A setting the runtime reads from the chain's state section may be sealed; its identity may not. */
    @Test
    void draftsMaySetStateSettingsButNotTheChainIdentity() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("supply", "devnet", List.of("alice", "bob"), "orders=audit-log");
        Path draft = fixture.draft("supply");
        Draft parsed = Draft.parse(Files.readString(draft));
        Files.writeString(draft, withSettings(parsed, java.util.Map.of("state.l1-proof-consumption-required",
                "true")).toYaml());
        fixture.ok("seal", draft.toString());
        SealedApp sealed = fixture.sealed("supply");
        assertThat(sealed.genesis().chain("orders").settings())
                .containsEntry("state.l1-proof-consumption-required", "true");

        for (String identity : List.of("state.genesis-id", "state.format-fingerprint", "state.commitment-profile")) {
            Files.writeString(draft, withSettings(parsed, java.util.Map.of(identity, "00")).toYaml());
            var refused = fixture.run("seal", draft.toString(), "--force");
            assertThat(refused.exit()).as(identity).isEqualTo(CliException.FAILURE);
            assertThat(refused.err()).contains("is set by seal");
        }
    }

    private static Draft withSettings(Draft draft, java.util.Map<String, String> settings) {
        Draft.Chain chain = draft.chains().getFirst();
        return new Draft(draft.name(), draft.invite(), draft.network(), draft.operators(), draft.threshold(),
                draft.membership(), draft.sequencing(), List.of(new Draft.Chain(chain.id(), chain.recipe(),
                chain.capabilities(), chain.answers(), chain.bindings(), chain.composite(), chain.authenticatedMap(),
                chain.anchor(), settings)));
    }
}
