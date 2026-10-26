package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class SealCommandTest {
    private static final List<String> OPERATORS = List.of("alice", "bob", "carol");

    @TempDir
    Path temporary;

    @Test
    void sealsResolvedSettingsWithTheD2GenesisIdAndOneFingerprintForEveryReader() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("supply", "preprod", OPERATORS, "orders=audit-log");
        var sealed = fixture.ok("seal", fixture.draft("supply").toString());
        assertThat(sealed.out()).contains("chain fingerprint", "alice", "(lead)", "3 members, 2 signatures");

        SealedApp app = fixture.sealed("supply");
        SealedApp.ChainGenesis orders = app.genesis().chain("orders");
        assertThat(orders.settings()).containsEntry("state-machine", "ordered-log")
                .containsEntry("threshold", "2")
                .containsEntry("membership.mode", "governed")
                .containsEntry("l1.stability-depth", "36")
                .containsEntry("sequencer.proposer", app.genesis().member("alice").publicKey());
        assertThat(orders.settings().get("members")).contains(app.genesis().member("carol").publicKey());

        // The genesis id is recomputable from the invite, the chain id and the settings it covers.
        Map<String, String> covered = new TreeMap<>(orders.settings());
        covered.remove("state.genesis-id");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("settings", covered);
        assertThat(orders.genesisId()).isEqualTo(SealedApp.genesisId(app.genesis().invite(), "orders", input));

        // Sealing again with the same inputs is a no-op; the file is stable and its fingerprint reproducible.
        String bytes = Files.readString(fixture.sealedFile("supply"));
        assertThat(fixture.ok("seal", fixture.draft("supply").toString()).out()).contains("already sealed");
        assertThat(Files.readString(fixture.sealedFile("supply"))).isEqualTo(bytes);
        assertThat(SealedApp.parse(bytes).fingerprint()).isEqualTo(app.fingerprint());
    }

    @Test
    void reportsMissingCardsWithExitThreeAndRefusesForeignOrDuplicateCards() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("supply", "devnet", List.of("alice", "bob"), "orders=audit-log");
        Files.delete(fixture.coordinator.resolve("bob.member"));
        var waiting = fixture.run("seal", fixture.draft("supply").toString());
        assertThat(waiting.exit()).isEqualTo(CliException.NOT_READY);
        assertThat(waiting.out()).contains("Members 1/2: waiting for bob");

        // A card made for another draft names the invite it was made for.
        CeremonyFixture other = new CeremonyFixture(temporary.resolve("other"));
        other.draftAndCards("supply", "devnet", List.of("alice", "bob"), "orders=audit-log");
        Files.copy(other.coordinator.resolve("bob.member"), fixture.coordinator.resolve("bob.member"));
        var foreign = fixture.run("seal", fixture.draft("supply").toString());
        assertThat(foreign.exit()).isEqualTo(CliException.FAILURE);
        assertThat(foreign.err()).contains("made for a different invite");

        // The same key under two names is refused.
        Files.delete(fixture.coordinator.resolve("bob.member"));
        Files.writeString(fixture.coordinator.resolve("bob.member"), Files.readString(
                fixture.coordinator.resolve("alice.member")).replace("\"alice\"", "\"bob\""));
        var reused = fixture.run("seal", fixture.draft("supply").toString());
        assertThat(reused.exit()).isEqualTo(CliException.FAILURE);
        assertThat(reused.err()).contains("bad signature");
    }

    @Test
    void documentTrailChainSealsWithItsStockProvider() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("trail", "devnet", OPERATORS, "documents=document-trail");
        fixture.ok("seal", fixture.draft("trail").toString());
        SealedApp.ChainGenesis documents = fixture.sealed("trail").genesis().chain("documents");
        assertThat(documents.settings()).containsEntry("state-machine", "doc-trail");
        assertThat(documents.bundles()).contains("org.yanoproject.x.stdlib");
    }

    @Test
    void authenticatedMapChainKeepsItsDerivedGenesisId() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("records", "devnet", OPERATORS, "registry=authenticated-map");
        fixture.ok("seal", fixture.draft("records").toString());
        SealedApp app = fixture.sealed("records");
        SealedApp.ChainGenesis registry = app.genesis().chain("registry");
        // The map derives its own genesis id from its genesis, which covers the members; the invite does not apply.
        assertThat(registry.settings()).containsKey("machines.authenticated-map.genesis-cbor-hex");
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, String> covered = new TreeMap<>(registry.settings());
        covered.remove("state.genesis-id");
        input.put("settings", covered);
        assertThat(registry.genesisId()).hasSize(64)
                .isNotEqualTo(SealedApp.genesisId(app.genesis().invite(), "registry", input));
    }

    @Test
    void roleApprovalChainGetsItsBootstrapPlanTemplate() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("roles", "devnet", OPERATORS, "approvals=role-approval");
        var sealed = fixture.ok("seal", fixture.draft("roles").toString());
        assertThat(sealed.out()).contains("approvals.role-plan.yaml", "role bootstrap --plan");
        assertThat(Files.readString(fixture.coordinator.resolve("approvals.role-plan.yaml")))
                .contains("kind: RoleBootstrapPlan", "chainId: approvals", "threshold: 2");
    }

    @Test
    void draftSettingsAndAnchorPolicyAreSealedButAnchoringIsNeverEnabledThere() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("anchored", "devnet", List.of("alice", "bob"), "orders=audit-log");
        Path draft = fixture.draft("anchored");
        Draft parsed = Draft.parse(Files.readString(draft));
        Draft.Chain chain = parsed.chains().getFirst();
        Draft withPolicy = new Draft(parsed.name(), parsed.invite(), parsed.network(), parsed.operators(), "all",
                parsed.membership(), parsed.sequencing(), List.of(new Draft.Chain(chain.id(), chain.recipe(), List.of(), Map.of(), null,
                null, null, new Draft.Anchor("script", 7), Map.of("block.interval-ms", "500"))));
        Files.writeString(draft, withPolicy.toYaml());
        fixture.ok("seal", draft.toString());
        SealedApp.ChainGenesis orders = fixture.sealed("anchored").genesis().chain("orders");
        assertThat(orders.settings()).containsEntry("anchor.mode", "script")
                .containsEntry("anchor.every-blocks", "7")
                .containsEntry("block.interval-ms", "500")
                .containsEntry("threshold", "2")
                .containsEntry("anchor.enabled", "false");

        Files.writeString(draft, Files.readString(draft).replace("block.interval-ms: \"500\"",
                "anchor.enabled: \"true\""));
        var refused = fixture.run("seal", draft.toString(), "--force");
        assertThat(refused.exit()).isEqualTo(CliException.FAILURE);
        assertThat(refused.err()).contains("anchoring is turned on later");
    }
}
