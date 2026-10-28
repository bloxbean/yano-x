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

/** Cases from the implementation reviews of ADR-056 that must stay fixed. */
class ReviewRegressionTest {
    private static final List<String> OPERATORS = List.of("alice", "bob", "carol");

    @TempDir
    Path temporary;

    @Test
    void anUnknownOrRemovedCommandSaysSoInsteadOfPrintingTheConfigUsage() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);

        CeremonyFixture.Result removed = fixture.run("init", "--help");
        CeremonyFixture.Result unknown = fixture.run("frobnicate\u001b[2J");
        CeremonyFixture.Result tool = fixture.run("recipes");

        assertThat(removed.exit()).isEqualTo(CliException.USAGE);
        assertThat(removed.err()).contains("'init' is not a yano.sh appchain command; use new")
                .doesNotContain("config validate");
        assertThat(unknown.exit()).isEqualTo(CliException.USAGE);
        assertThat(unknown.err()).contains("'frobnicate??2J' is not a yano.sh appchain command.")
                .doesNotContain("\u001b");
        assertThat(tool.exit()).isZero();
    }

    @Test
    void aSealedFileWhoseSettingsDisagreeWithItsRosterIsRefused() throws Exception {
        CeremonyFixture fixture = sealed();
        String text = Files.readString(fixture.sealedFile("supply"));
        SealedApp app = fixture.sealed("supply");
        String alice = app.genesis().member("alice").publicKey();
        String swapped = text.replaceFirst("\"members\" : \"[0-9a-f,]+\"", "\"members\" : \"" + alice + "\"");
        assertThatThrownBy(() -> SealedApp.parse(swapped)).isInstanceOf(FormatException.class)
                .hasMessageContaining("differs from the sealed roster");
        String lowered = text.replace("\"threshold\" : \"2\"", "\"threshold\" : \"1\"");
        assertThatThrownBy(() -> SealedApp.parse(lowered)).hasMessageContaining("differs from the sealed roster");
    }

    @Test
    void aSealedValueThatReadsTheNodeEnvironmentIsRefused() throws Exception {
        CeremonyFixture fixture = sealed();
        String text = Files.readString(fixture.sealedFile("supply"))
                .replace("\"block.interval-ms\" : \"1000\"", "\"block.interval-ms\" : \"${YANO_APPCHAIN_SIGNING_KEY}\"");
        assertThatThrownBy(() -> SealedApp.parse(text)).hasMessageContaining("refers to the node's environment");
    }

    @Test
    void draftSettingsCannotOverrideTheRosterAndBindingsStayBesideTheDraft() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("supply", "devnet", OPERATORS, "orders=audit-log");
        Path draft = fixture.draft("supply");
        Draft parsed = Draft.parse(Files.readString(draft));
        for (Map<String, String> settings : List.of(Map.of("members", "aa"), Map.of("sequencer.proposer", "aa"),
                Map.of("threshold", "1"), Map.of("state.genesis-id", "00"))) {
            Files.writeString(draft, withChain(parsed, new Draft.Chain("orders", "audit-log", List.of(), Map.of(), null,
                    null, null, null, settings)).toYaml());
            var refused = fixture.run("seal", draft.toString());
            assertThat(refused.exit()).as(settings.toString()).isEqualTo(CliException.FAILURE);
            assertThat(refused.err()).contains("cannot be set under settings");
        }
        Files.writeString(temporary.resolve("outside.yaml"), "composite: {components: [], bindings: []}\n");
        Files.writeString(draft, withChain(parsed, new Draft.Chain("orders", "declarative-composite", List.of(),
                Map.of(), "../outside.yaml", null, null, null, Map.of())).toYaml());
        assertThat(fixture.run("seal", draft.toString()).err()).contains("must be inside the draft's directory");
    }

    @Test
    void thresholdsStayValidForBothMembershipsIncludingTheFaultBound() {
        assertThat(ApproveCommand.safeThreshold(2, 3, 4, 0)).isEqualTo(3);
        assertThat(ApproveCommand.safeThreshold(2, 3, 2, 0)).isEqualTo(2);
        assertThat(ApproveCommand.safeThreshold(3, 3, 2, 0)).isEqualTo(2);
        assertThat(ApproveCommand.safeThreshold(4, 6, 7, 1)).isEqualTo(5);
        assertThat(ApproveCommand.valid(4, 7, 1)).isFalse();
    }

    @Test
    void aStrangerCardUnderAFoundingNameIsCaughtByTheOperatorItReplaced() throws Exception {
        CeremonyFixture fixture = sealed();
        // The real bob's node directory exists; an attacker's node made a card for the sealed file under bob's name.
        CeremonyFixture attacker = new CeremonyFixture(temporary.resolve("attacker"));
        attacker.card(fixture.sealedFile("supply"), "bob", 7);
        var refused = attacker.run("join", fixture.sealedFile("supply").toString(), "--expect-fingerprint",
                fixture.sealed("supply").displayFingerprint(), "--node-dir", attacker.node("bob").toString());
        assertThat(refused.exit()).isEqualTo(CliException.FAILURE);
        assertThat(refused.err()).contains("Someone else's card was sealed under your name");
    }

    @Test
    void bundleChangesAreSeenInBothDirections() {
        assertThat(Distribution.bundleChanges(Map.of("a", "1", "b", "2"), Map.of("a", "1", "b", "2"))).isEmpty();
        assertThat(Distribution.bundleChanges(Map.of("a", "1"), Map.of("a", "1", "file:x.jar", "9")))
                .containsExactly("file:x.jar (added)");
        assertThat(Distribution.bundleChanges(Map.of("a", "1", "b", "2"), Map.of("a", "3")))
                .containsExactlyInAnyOrder("a (changed)", "b (missing)");
    }

    @Test
    void localSettingsCannotChangeSealedValuesEvenInAProfileSection() throws Exception {
        CeremonyFixture fixture = sealed();
        fixture.ok(fixture.join("supply", "bob"));
        NodeDir bob = new NodeDir(fixture.node("bob"));
        Files.writeString(NodeConfig.localFile(bob), """
                "%devnet":
                  yano:
                    app-chain:
                      chains:
                        - threshold: 3
                """);
        assertThatThrownBy(() -> NodeConfig.requireLocalLeavesConsensusAlone(bob))
                .hasMessageContaining("yano.app-chain.chains[0].threshold");
        Files.writeString(NodeConfig.localFile(bob), "yano:\n  app-chain:\n    chains:\n      - effects:\n"
                + "          enabled: true\n");
        NodeConfig.requireLocalLeavesConsensusAlone(bob);
    }

    @Test
    void anExplicitSignatureCountSealsLikeANamedOne() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        List<String> six = List.of("alice", "bob", "carol", "dave", "erin", "frank");
        fixture.draftAndCards("big", "devnet", six, "orders=audit-log");
        Draft parsed = Draft.parse(Files.readString(fixture.draft("big")));
        Files.writeString(fixture.draft("big"), new Draft(parsed.name(), parsed.invite(), parsed.network(),
                parsed.operators(), "5", parsed.membership(), parsed.sequencing(), parsed.chains()).toYaml());
        fixture.ok("seal", fixture.draft("big").toString());
        assertThat(fixture.sealed("big").genesis().chain("orders").settings()).containsEntry("threshold", "5");
    }

    @Test
    void anImportedKeyMustBeAPrivateFileAndMayBeUpperCase() throws Exception {
        Path key = temporary.resolve("member.seed");
        Files.writeString(key, "AB".repeat(32) + "\n");
        Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> NodeDir.readSeedFile(key)).hasMessageContaining("readable by others");
        Files.setPosixFilePermissions(key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(NodeDir.readSeedFile(key)).isEqualTo("ab".repeat(32));
        Path link = Files.createSymbolicLink(temporary.resolve("link.seed"), key);
        assertThatThrownBy(() -> NodeDir.readSeedFile(link)).hasMessageContaining("not a link");
        Files.writeString(key, "not a key\n");
        assertThatThrownBy(() -> NodeDir.readSeedFile(key)).hasMessageContaining("64 hex characters");
    }

    @Test
    void aRepeatedNewFromATemplateRefusesBeforeTouchingCopiedBindings() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        Path template = temporary.resolve("t.yaml");
        Files.writeString(template, "chains:\n  - id: flow\n    recipe: declarative-composite\n"
                + "    bindings: ./flow.yaml\n");
        Files.writeString(temporary.resolve("flow.yaml"), "composite: {components: [], bindings: []}\n");
        String[] create = {"new", "app", "--from", template.toString(), "--network", "devnet", "--operators", "alice",
                "--output", fixture.coordinator.toString(), "--yes"};
        fixture.ok(create);
        Path copied = fixture.coordinator.resolve("flow.bindings.yaml");
        Files.writeString(copied, "# edited\n");
        assertThat(fixture.run(create).err()).contains("already exists");
        assertThat(copied).hasContent("# edited\n");
    }

    private CeremonyFixture sealed() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("supply", "devnet", OPERATORS, "orders=audit-log");
        fixture.ok("seal", fixture.draft("supply").toString());
        return fixture;
    }

    private static Draft withChain(Draft draft, Draft.Chain chain) {
        return new Draft(draft.name(), draft.invite(), draft.network(), draft.operators(), draft.threshold(),
                draft.membership(), draft.sequencing(), List.of(chain));
    }
}
