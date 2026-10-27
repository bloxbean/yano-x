package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainConsensusProfileCommitment;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicPolicyProof;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.client.Hex;
import org.yanoproject.x.client.TopicAuthoring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicAuthorshipVerifierTest {
    private static final String CHAIN = "authorship-test";
    private static final String TOPIC = "games/rides";
    private static final String PROFILE = "mpf-blake2b256-v1";

    @Test
    void verifiesDirectFramedAuthorshipWithRealMpfAbsenceProofsOffline() {
        Fixture fixture = fixture(false);
        JsonNode checked = TopicAuthorshipVerifier.verify(fixture.id, fixture.bundle, fixture.trust);
        assertThat(checked.path("verified").asBoolean()).isTrue();
        assertThat(checked.path("writerKind").asText()).isEqualTo("direct");
        assertThat(checked.path("availability").asText()).isEqualTo("NOT_PROVEN");
    }

    @Test
    void verifiesSessionIssuerAndRevocationAbsenceWithoutTrustingPeerClaims() {
        Fixture fixture = fixture(true);
        JsonNode checked = TopicAuthorshipVerifier.verify(fixture.id, fixture.bundle, fixture.trust);
        assertThat(checked.path("writerKind").asText()).isEqualTo("delegated");
        assertThat(checked.path("issuer").asText()).isEqualTo(signer(1).publicKey());
        ((ObjectNode) fixture.bundle.path("policyProof")).put("outcome", "untrusted annotation");
        assertThat(TopicAuthorshipVerifier.verify(fixture.id, fixture.bundle, fixture.trust)
                .path("verified").asBoolean()).isTrue();
    }

    @Test
    void refusesEntryOnlyMissingReplayOrRevocationProofsAndWrongGeneration() {
        Fixture fixture = fixture(true);
        ObjectNode entryOnly = fixture.bundle.deepCopy();
        entryOnly.putObject("policyProof").put("subjectType", "topic-entry-v1");
        assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, entryOnly, fixture.trust))
                .isInstanceOf(IllegalArgumentException.class);
        for (int index = 0; index < fixture.bundle.path("additionalStateProofs").size(); index++) {
            ObjectNode missing = fixture.bundle.deepCopy();
            ((ArrayNode) missing.path("additionalStateProofs")).remove(index);
            assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, missing, fixture.trust))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing state proof");
        }
        ObjectNode wrongGeneration = fixture.trust.deepCopy();
        wrongGeneration.put("genesisId", "99".repeat(32));
        assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, fixture.bundle, wrongGeneration))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesTamperedFrameForeignRootExpiredProofAndDuplicateStateEvidence() {
        Fixture fixture = fixture(false);
        ObjectNode changed = fixture.bundle.deepCopy();
        ((ObjectNode) changed.path("message")).put("bodyHex", "00");
        assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, changed, fixture.trust))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode foreign = fixture.trust.deepCopy();
        foreign.put("parentStateRoot", "88".repeat(32));
        assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, fixture.bundle, foreign))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode expired = fixture.trust.deepCopy();
        expired.put("timestamp", 3_000);
        assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, fixture.bundle, expired))
                .hasMessage("TOPIC_PROOF_EXPIRED");
        ObjectNode duplicate = fixture.bundle.deepCopy();
        ((ArrayNode) duplicate.path("additionalStateProofs")).add(duplicate.path("policyProof").path("proofs").get(0));
        assertThatThrownBy(() -> TopicAuthorshipVerifier.verify(fixture.id, duplicate, fixture.trust))
                .hasMessage("duplicate state proof");
    }

    static Fixture fixture(boolean delegated) {
        byte[] genesis = Hex.decode("11".repeat(32));
        String generation = Hex.encode(StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.require(PROFILE), genesis).digest());
        var issuer = signer(1);
        var session = signer(2);
        var delegation = delegated ? TopicAuthoring.issueDelegation(CHAIN, generation, session.publicKey(),
                "games/", 500, 2_000, issuer) : null;
        var request = TopicAuthoring.prepareWrite(CHAIN, generation, TOPIC, Hex.decode("a1617801"),
                "44".repeat(16), 2_000, delegation);
        byte[] frame = TopicWireCodec.encodeFrame(TopicAuthoring.signWrite(request,
                List.of(delegated ? session : issuer)));
        byte[] sender = Hex.decode(signer(3).publicKey());
        byte[] id = AppMessage.computeMessageId(CHAIN, TOPIC, sender, 1, 5_000, frame);
        byte[] envelopeSignature = signer(3).sign(AppMessage.signedBodyBytes(CHAIN, TOPIC, sender, 1, 5_000, frame));

        Map<String, byte[]> nodes = new HashMap<>();
        MpfTrie trie = new MpfTrie(new NodeStore() {
            @Override public byte[] get(byte[] hash) { return nodes.get(Hex.encode(hash)); }
            @Override public void put(byte[] hash, byte[] node) { nodes.put(Hex.encode(hash), node); }
            @Override public void delete(byte[] hash) { nodes.remove(Hex.encode(hash)); }
        });
        trie.put(AppChainConsensusProfileCommitment.markerKey(), AppChainConsensusProfileCommitment.encode(
                new AppChainConsensusProfile(AppChainConsensusProfile.SCHEMA_VERSION, 65_536, 64, 4_194_304,
                        0, 0, 5_000, false, false, 0, 0, 0, 0, FinalityGate.APP_FINAL,
                        EffectOutcomeCommitment.PER_EFFECT, true, List.of())));
        trie.put(TopicKeys.config(), TopicWireCodec.encodeConfig(new Config(1, 1,
                new Settings(true, null, false, true, 3_600_000, 3_600_000, 4, 100, 10, 2, 600))));
        KeySet owners = new KeySet(1, List.of(issuer.publicKey()));
        trie.put(TopicKeys.entry("games/"), TopicWireCodec.encodeEntryRecord(new EntryRecord("games/",
                new ActiveRevision(1, 2, 1, new EntryContent(owners,
                        new Policy(new Writers(owners), List.of(issuer.publicKey()), null, "", null), false)),
                1, 0, 1)));

        ObjectNode trust = TopicPlan.JSON.createObjectNode().put("chainId", CHAIN).put("profile", PROFILE)
                .put("genesisId", Hex.encode(genesis)).put("height", 3).put("blockHash", "55".repeat(32))
                .put("messagesRoot", Hex.encode(id)).put("parentStateRoot", Hex.encode(trie.getRootHash()))
                .put("timestamp", 1_000);
        ObjectNode bundle = TopicPlan.JSON.createObjectNode().put("schemaVersion", 1).put("kind", "TopicAuthorshipBundle");
        bundle.putObject("message").put("version", AppMessage.ENVELOPE_VERSION).put("messageId", Hex.encode(id))
                .put("chainId", CHAIN).put("topic", TOPIC).put("sender", Hex.encode(sender)).put("senderSeq", 1)
                .put("expiresAt", 5_000).put("bodyHex", Hex.encode(frame)).put("authScheme", 0)
                .put("authProofHex", Hex.encode(envelopeSignature));
        bundle.putObject("messageProof").put("schemaVersion", 1).put("treeId", "binary-merkle-blake2b256-v1")
                .put("chainId", CHAIN).put("blockHeight", 3).put("blockHash", "55".repeat(32))
                .put("messagesRoot", Hex.encode(id)).put("messageId", Hex.encode(id)).put("messageIndex", 0)
                .put("leafCount", 1).putArray("siblings");
        ObjectNode policy = bundle.putObject("policyProof").put("topic", TOPIC).put("height", 3)
                .put("profile", PROFILE).put("parentRootHex", Hex.encode(trie.getRootHash()));
        ArrayNode proofs = policy.putArray("proofs");
        TopicPolicyProof.requiredKeys(TOPIC).forEach(key -> proofs.add(proof(trie, key)));
        ArrayNode additional = bundle.putArray("additionalStateProofs");
        additional.add(proof(trie, TopicKeys.used(TopicWireCodec.blake2b256Hex(request.signingBytes()))));
        if (delegated) additional.add(proof(trie, TopicKeys.revoked(TopicWireCodec.delegationHash(
                CHAIN, generation, delegation))));
        return new Fixture(Hex.encode(id), bundle, trust);
    }

    private static ObjectNode proof(MpfTrie trie, byte[] key) {
        byte[] value = trie.get(key);
        ObjectNode proof = TopicPlan.JSON.createObjectNode().put("keyHex", Hex.encode(key))
                .put("presence", value == null ? "ABSENT" : "PRESENT")
                .put("proofWireHex", Hex.encode(trie.getProofWire(key).orElseThrow()));
        if (value == null) proof.putNull("valueHex");
        else proof.put("valueHex", Hex.encode(value));
        return proof;
    }

    private static TopicAuthoring.Signer signer(int value) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) value);
        return new TopicAuthoring.Signer() {
            @Override public String publicKey() { return Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(seed)); }
            @Override public byte[] sign(byte[] bytes) {
                return CryptoConfiguration.INSTANCE.getSigningProvider().sign(bytes, seed);
            }
        };
    }

    record Fixture(String id, ObjectNode bundle, ObjectNode trust) {
    }
}
