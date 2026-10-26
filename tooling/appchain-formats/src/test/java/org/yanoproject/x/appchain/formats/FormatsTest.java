package org.yanoproject.x.appchain.formats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormatsTest {
    private static final String DRAFT = """
            name: supply
            invite: 7c1e-52aa-9d03
            network: devnet
            operators: [alice, bob, carol]
            chains:
              - id: orders
                recipe: audit-log
              - id: approvals
                recipe: declarative-composite
                bindings: ./approval-to-audit.yaml
                anchor: {mode: script, every-blocks: 5}
            """;

    @Test
    void draftDefaultsAndRoundTrip() {
        Draft draft = Draft.parse(DRAFT);
        assertThat(draft.lead()).isEqualTo("alice");
        assertThat(draft.threshold()).isEqualTo("majority");
        assertThat(draft.membership()).isEqualTo("governed");
        assertThat(Draft.thresholdFor(draft.threshold(), 3)).isEqualTo(2);
        assertThat(draft.chains().get(1).anchor()).isEqualTo(new Draft.Anchor("script", 5));
        assertThat(Draft.parse(draft.toYaml())).isEqualTo(draft);
    }

    @Test
    void draftRejectsTyposAndUnsafeThresholds() {
        assertThatThrownBy(() -> Draft.parse(DRAFT + "treshold: 2\n"))
                .hasMessageContaining("Unknown draft field 'treshold'");
        assertThatThrownBy(() -> Draft.parse(DRAFT.replace("network: devnet", "network: testnet")))
                .hasMessageContaining("must be one of");
        assertThatThrownBy(() -> Draft.thresholdFor("1", 3)).hasMessageContaining("not safe");
        assertThatThrownBy(() -> Draft.parse(DRAFT.replace("[alice, bob, carol]", "[alice, alice]")))
                .hasMessageContaining("listed twice");
    }

    @Test
    void memberCardSignsEveryField() {
        String seed = Ed25519.newSeed();
        MemberCard card = MemberCard.create(seed, "7c1e-52aa-9d03", "alice", "node-a.example:13337",
                "http://node-a.example:8080");
        assertThat(card.publicKey()).isEqualTo(Ed25519.publicKey(seed));
        assertThat(MemberCard.parse(card.toJson())).isEqualTo(card);
        assertThat(card.verifiedFor("7c1e-52aa-9d03")).isEqualTo(card);

        MemberCard renamed = new MemberCard(card.format(), card.invite(), "mallory", card.publicKey(),
                card.node(), card.http(), card.signature());
        assertThatThrownBy(() -> renamed.verifiedFor("7c1e-52aa-9d03")).hasMessageContaining("bad signature");
        assertThatThrownBy(() -> card.verifiedFor("0000-0000-0000")).hasMessageContaining("different invite");
    }

    @Test
    void fingerprintCoversGenesisButNotPeers() {
        SealedApp sealed = sealed("node-a.example:13337");
        SealedApp moved = sealed("10.0.0.9:13337");
        assertThat(moved.fingerprint()).isEqualTo(sealed.fingerprint());
        assertThat(sealed.displayFingerprint()).matches("([0-9a-f]{4}-){7}[0-9a-f]{4}");
        assertThat(sealed.matches(sealed.displayFingerprint())).isTrue();
        assertThat(sealed.matches(sealed.fingerprint())).isTrue();
        assertThat(sealed.matches("0000")).isFalse();

        SealedApp parsed = SealedApp.parse(sealed.toJson());
        assertThat(parsed).isEqualTo(sealed);
        assertThat(parsed.fingerprint()).isEqualTo(sealed.fingerprint());

        String tampered = sealed.toJson().replace("\"recipe\" : \"audit-log\"", "\"recipe\" : \"audit-log-2\"");
        assertThat(SealedApp.parse(tampered).fingerprint()).isNotEqualTo(sealed.fingerprint());
    }

    @Test
    void genesisIdDependsOnInviteChainAndContent() {
        String id = SealedApp.genesisId("7c1e-52aa-9d03", "orders", Map.of("members", "a"));
        assertThat(id).hasSize(64);
        assertThat(SealedApp.genesisId("7c1e-52aa-9d04", "orders", Map.of("members", "a"))).isNotEqualTo(id);
        assertThat(SealedApp.genesisId("7c1e-52aa-9d03", "order", Map.of("members", "a"))).isNotEqualTo(id);
        assertThat(SealedApp.genesisId("7c1e-52aa-9d03", "orders", Map.of("members", "b"))).isNotEqualTo(id);
    }

    @Test
    void canonicalBytesIgnoreKeyOrder() {
        assertThat(Canonical.bytes(Map.of("b", 1, "a", List.of(Map.of("y", 2, "x", 3)))))
                .isEqualTo("{\"a\":[{\"x\":3,\"y\":2}],\"b\":1}".getBytes());
    }

    private static final String ALICE = Ed25519.publicKey(Ed25519.newSeed());
    private static final String BOB = Ed25519.publicKey(Ed25519.newSeed());

    private static SealedApp sealed(String aliceNode) {
        String alice = ALICE;
        String bob = BOB;
        SealedApp.Genesis genesis = new SealedApp.Genesis("supply", "7c1e-52aa-9d03", "devnet", "1", "2",
                "alice", "governed", 2,
                List.of(new SealedApp.Member("alice", alice), new SealedApp.Member("bob", bob)),
                Map.of("org.yanoproject.x.stdlib", "ab".repeat(32)), Map.of(), List.of(new SealedApp.ChainGenesis("orders", "audit-log",
                Map.of("chain-id", "orders", "members", alice + "," + bob, "threshold", "2", "state.genesis-id",
                "00".repeat(32)), Map.of(), List.of("stdlib"))));
        return new SealedApp(SealedApp.FORMAT, genesis, Map.of(
                "alice", new SealedApp.Peer(aliceNode, "http://node-a.example:8080"),
                "bob", new SealedApp.Peer("node-b.example:13337", "http://node-b.example:8080")));
    }
}
