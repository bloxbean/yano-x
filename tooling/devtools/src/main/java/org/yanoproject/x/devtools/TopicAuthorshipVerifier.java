package org.yanoproject.x.devtools;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.api.appchain.AppChainConsensusProfileCommitment;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.evidence.MessageInclusionProof;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.topics.TopicPolicyProof;
import org.yanoproject.api.appchain.topics.TopicState;
import org.yanoproject.api.appchain.topics.TopicWriteRules;
import org.yanoproject.appchain.proof.AppChainProofVerifier;
import org.yanoproject.appchain.proof.TopicPolicyProofVerifier;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.Hex;
import org.yanoproject.x.client.PortableProofBundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Offline authorship verification. Every state read requires a proof; missing is never interpreted as absent. */
final class TopicAuthorshipVerifier {
    private TopicAuthorshipVerifier() {
    }

    static ObjectNode verify(String messageId, JsonNode bundle, JsonNode trust) {
        TopicPlan.hash(messageId, "messageId");
        TopicPlan.fields(bundle, Set.of("schemaVersion", "kind", "message", "messageProof", "policyProof",
                "additionalStateProofs"));
        if (TopicPlan.number(bundle, "schemaVersion", 1) != 1
                || !"TopicAuthorshipBundle".equals(TopicPlan.text(bundle, "kind"))) {
            throw TopicPlan.invalid("expected TopicAuthorshipBundle schemaVersion 1");
        }
        TopicPlan.fields(trust, Set.of("chainId", "profile", "genesisId", "height", "blockHash", "messagesRoot",
                "parentStateRoot", "timestamp"));
        String chain = TopicPlan.text(trust, "chainId");
        String profile = TopicPlan.text(trust, "profile");
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.require(profile),
                exact32(trust, "genesisId"));
        long height = TopicPlan.number(trust, "height", 2);
        long timestamp = TopicPlan.number(trust, "timestamp", 0);
        byte[] parentRoot = exact32(trust, "parentStateRoot");
        var trustedBlock = new PortableProofBundle.TrustedBlock(chain, height, exact32(trust, "blockHash"),
                exact32(trust, "messagesRoot"), PortableProofBundle.TrustedBlock.Source.CALLER_PINNED);
        MessageInclusionProof inclusion = AppChainClient.decodeMessageProof(bundle.path("messageProof").toString());
        AppMessage message = message(bundle.path("message"));
        if (!messageId.equals(Hex.encode(message.getMessageId()))) throw TopicPlan.invalid("message id mismatch");
        var messageCheck = new PortableProofBundle<>(1, inclusion, message, null, null).verify(trustedBlock, null);
        if (!messageCheck.valid()
                || messageCheck.content() != PortableProofBundle.ContentStatus.SUPPLIED_CONTENT_VERIFIED) {
            throw TopicPlan.invalid("message inclusion or signed content does not verify");
        }
        TopicPolicyProof policy = policy(bundle.path("policyProof"));
        if (!policy.profile().equals(profile) || policy.height() != height || !policy.topic().equals(message.getTopic())
                || TopicPolicyProofVerifier.verify(policy, parentRoot).isEmpty()) {
            throw TopicPlan.invalid("complete topic-policy-v1 proof required at the trusted parent root");
        }
        Map<String, Optional<byte[]>> proven = new HashMap<>();
        for (TopicPolicyProof.KeyProof proof : policy.proofs()) put(proven, proof);
        JsonNode additional = bundle.path("additionalStateProofs");
        if (!additional.isArray() || additional.size() > 64) throw TopicPlan.invalid("invalid additional state proofs");
        for (JsonNode value : additional) {
            TopicPolicyProof.KeyProof proof = keyProof(value);
            if (!AppChainProofVerifier.verify(profile, AppChainProofVerifier.Presence.valueOf(proof.presence()),
                    parentRoot, proof.key(), proof.value(), proof.proofWire())) {
                throw TopicPlan.invalid("additional state proof does not verify");
            }
            put(proven, proof);
        }
        AppStateReader reader = new AppStateReader() {
            @Override public Optional<byte[]> get(byte[] key) {
                Optional<byte[]> value = proven.get(Hex.encode(key));
                if (value == null) throw TopicPlan.invalid("missing state proof required for authorship");
                return value.map(byte[]::clone);
            }
            @Override public byte[] stateRoot() { return parentRoot.clone(); }
            @Override public long committedHeight() { return height - 1; }
        };
        long maxMessageBytes = AppChainConsensusProfileCommitment.decode(
                reader.get(AppChainConsensusProfileCommitment.markerKey()).orElseThrow()).maxMessageBytes();
        TopicWriteRules.Result checked = TopicWriteRules.check(message.getTopic(), message.getBody(), chain,
                Hex.encode(identity.digest()), timestamp, new TopicState(reader, maxMessageBytes), Set.of());
        if (!checked.allowed()) throw TopicPlan.invalid(checked.refusal().name());
        ObjectNode result = TopicPlan.JSON.createObjectNode();
        result.put("verified", true).put("messageId", messageId).put("chainId", chain)
                .put("height", height).put("generationId", Hex.encode(identity.digest()))
                .put("subjectType", "topic-policy-v1").put("trust", "CALLER_PINNED_BLOCK_AND_PARENT_ROOT")
                .put("availability", "NOT_PROVEN");
        result.set("outcome", TopicPlan.JSON.valueToTree(checked.outcome()));
        if (checked.writer() == null) result.put("writerKind", "none");
        else {
            result.put("writerKind", checked.writer().kind().name().toLowerCase(java.util.Locale.ROOT));
            result.set("signers", TopicPlan.JSON.valueToTree(checked.writer().signers()));
            if (checked.writer().issuer() != null) result.put("issuer", checked.writer().issuer());
        }
        return result;
    }

    static TopicPolicyProof policy(JsonNode node) {
        // A topic-entry-v1 proof cannot pass: it has neither this required list nor the ancestor absences.
        TopicPlan.fields(node, Set.of("topic", "height", "profile", "parentRootHex", "proofs", "outcome",
                "effectivePolicyDigest"));
        JsonNode proofs = node.get("proofs");
        if (proofs == null || !proofs.isArray() || proofs.size() > 258) throw TopicPlan.invalid("policy proof required");
        List<TopicPolicyProof.KeyProof> decoded = new ArrayList<>();
        for (JsonNode item : proofs) decoded.add(keyProof(item));
        return new TopicPolicyProof(TopicPlan.text(node, "topic"), TopicPlan.number(node, "height", 2),
                TopicPlan.text(node, "profile"), exact32(node, "parentRootHex"), decoded);
    }

    private static TopicPolicyProof.KeyProof keyProof(JsonNode node) {
        TopicPlan.fields(node, Set.of("keyHex", "presence", "valueHex", "proofWireHex"));
        String presence = TopicPlan.text(node, "presence");
        AppChainProofVerifier.Presence.valueOf(presence);
        byte[] value = node.hasNonNull("valueHex") ? boundedHex(node, "valueHex", 1_048_576, true) : null;
        return new TopicPolicyProof.KeyProof(boundedHex(node, "keyHex", 256, false), presence, value,
                boundedHex(node, "proofWireHex", 1_048_576, true));
    }

    private static void put(Map<String, Optional<byte[]>> proven, TopicPolicyProof.KeyProof proof) {
        if (proven.putIfAbsent(Hex.encode(proof.key()), proof.present() ? Optional.of(proof.value()) : Optional.empty())
                != null) throw TopicPlan.invalid("duplicate state proof");
    }

    private static AppMessage message(JsonNode node) {
        TopicPlan.fields(node, Set.of("version", "messageId", "chainId", "topic", "sender", "senderSeq",
                "expiresAt", "bodyHex", "authScheme", "authProofHex"));
        if (TopicPlan.number(node, "version", 0) != AppMessage.ENVELOPE_VERSION
                || TopicPlan.number(node, "authScheme", 0) != 0) throw TopicPlan.invalid("unsupported message envelope");
        return AppMessage.builder().version(AppMessage.ENVELOPE_VERSION).messageId(exact32(node, "messageId"))
                .chainId(TopicPlan.text(node, "chainId")).topic(TopicPlan.text(node, "topic"))
                .sender(exact32(node, "sender")).senderSeq(TopicPlan.number(node, "senderSeq", 0))
                .expiresAt(TopicPlan.number(node, "expiresAt", 0)).body(boundedHex(node, "bodyHex", 1_048_576, true))
                .authScheme(0).authProof(TopicPlan.hex(node, "authProofHex", 64)).build();
    }

    private static byte[] exact32(JsonNode node, String field) {
        return Hex.decode(TopicPlan.hash(TopicPlan.text(node, field), field));
    }

    private static byte[] boundedHex(JsonNode node, String field, int maximum, boolean empty) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > maximum * 2
                || !value.textValue().matches(empty ? "(?:[0-9a-f]{2})*" : "(?:[0-9a-f]{2})+")) {
            throw TopicPlan.invalid("invalid proof hex");
        }
        return Hex.decode(value.textValue());
    }
}
