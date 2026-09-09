package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryProfile;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryValues;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** JSON form of a {@link StatusAnswer}, {@code trust-registry-answer-v1}. */
public final class AnswerCodec {
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final HexFormat HEX = HexFormat.of();

    private AnswerCodec() {
    }

    public static ObjectNode toJsonNode(StatusAnswer answer) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", StatusAnswer.SCHEMA_VERSION);
        root.put("type", StatusAnswer.TYPE);
        root.put("chainId", answer.chainId());
        root.put("profile", answer.profile());
        root.put("genesisId", answer.genesisIdHex());
        root.put("height", answer.height());
        root.put("stateRoot", answer.stateRootHex());
        root.put("blockHash", answer.blockHashHex());
        root.put("collection", answer.collection());
        root.put("keyHex", answer.keyHex());
        String keyText = answer.keyText();
        if (keyText != null) root.put("key", keyText);
        root.put("presence", answer.presence().name());
        if (answer.entry() != null) {
            StatusAnswer.Entry entry = answer.entry();
            ObjectNode node = root.putObject("entry");
            node.put("status", entry.status() == 0 ? "ACTIVE" : "REVOKED");
            node.put("revision", entry.revision());
            node.put("controllerHex", HEX.formatHex(entry.controller()));
            node.put("valueHex", HEX.formatHex(entry.value()));
            node.put("logicalValueHash", HEX.formatHex(entry.logicalValueHash()));
            node.put("createdHeight", entry.createdHeight());
            node.put("lastMutationHeight", entry.lastMutationHeight());
            JsonNode decoded = decodedValue(answer.collection(), entry);
            if (decoded != null) root.set("decoded", decoded);
        }
        StatusAnswer.Provenance provenance = answer.provenance();
        ObjectNode node = root.putObject("provenance");
        node.put("kind", provenance.kind().name());
        if (provenance.messageIdHex() != null) node.put("messageId", provenance.messageIdHex());
        if (provenance.appliedHeight() > 0) node.put("appliedHeight", provenance.appliedHeight());
        if (provenance.actorId() != null) {
            node.put("actorId", provenance.actorId());
            node.put("organizationId", provenance.organizationId());
            node.put("keyId", provenance.keyId());
            node.put("policyId", provenance.policyId());
            node.put("policyRevision", provenance.policyRevision());
            node.put("role", provenance.role());
        }
        if (answer.actionCommitment().length > 0) {
            root.put("actionCommitmentHex", HEX.formatHex(answer.actionCommitment()));
        }
        if (answer.authorizationEvidence().length > 0) {
            root.put("authorizationEvidenceHex", HEX.formatHex(answer.authorizationEvidence()));
        }
        ArrayNode facts = root.putArray("facts");
        for (StatusAnswer.Fact fact : answer.facts()) {
            ObjectNode item = facts.addObject();
            item.put("name", fact.name());
            item.put("keyHex", HEX.formatHex(fact.expectedKey()));
            if (fact.expectedValue() != null) {
                item.put("valueHex", HEX.formatHex(fact.expectedValue()));
            }
            try {
                item.set("proof", JSON.readTree(fact.proofJson()));
            } catch (IOException malformed) {
                throw new IllegalArgumentException("fact proof is not JSON", malformed);
            }
        }
        try {
            root.set("evidence", JSON.readTree(answer.evidenceJson()));
        } catch (IOException malformed) {
            throw new IllegalArgumentException("evidence bundle is not JSON", malformed);
        }
        return root;
    }

    public static String toJson(StatusAnswer answer) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode(answer));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static StatusAnswer fromJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("answer document exceeds " + MAX_JSON_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("answer document is not well-formed JSON", malformed);
        }
        return fromJsonNode(root);
    }

    public static StatusAnswer fromJsonNode(JsonNode root) {
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != StatusAnswer.SCHEMA_VERSION
                || !StatusAnswer.TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("answer document has an unsupported identity");
        }
        StatusAnswer.Entry entry = null;
        if (root.hasNonNull("entry")) {
            JsonNode node = root.get("entry");
            entry = new StatusAnswer.Entry(
                    "ACTIVE".equals(text(node, "status")) ? 0 : 1,
                    node.path("revision").asLong(),
                    HEX.parseHex(text(node, "controllerHex")),
                    HEX.parseHex(text(node, "valueHex")),
                    HEX.parseHex(text(node, "logicalValueHash")),
                    node.path("createdHeight").asLong(),
                    node.path("lastMutationHeight").asLong());
        }
        JsonNode provenanceNode = root.path("provenance");
        StatusAnswer.Provenance provenance = new StatusAnswer.Provenance(
                StatusAnswer.ProvenanceKind.valueOf(text(provenanceNode, "kind")),
                optionalText(provenanceNode, "messageId"),
                provenanceNode.path("appliedHeight").asLong(0),
                optionalText(provenanceNode, "actorId"),
                optionalText(provenanceNode, "organizationId"),
                optionalText(provenanceNode, "keyId"),
                optionalText(provenanceNode, "policyId"),
                provenanceNode.path("policyRevision").asLong(0),
                optionalText(provenanceNode, "role"));
        List<StatusAnswer.Fact> facts = new ArrayList<>();
        JsonNode factNodes = root.path("facts");
        if (!factNodes.isArray() || factNodes.size() > 16) {
            throw new IllegalArgumentException("answer facts must be an array of at most 16");
        }
        for (JsonNode node : factNodes) {
            JsonNode proof = node.path("proof");
            if (!proof.isObject()) {
                throw new IllegalArgumentException("fact proof must be an object");
            }
            facts.add(new StatusAnswer.Fact(text(node, "name"), HEX.parseHex(text(node, "keyHex")),
                    node.hasNonNull("valueHex") ? HEX.parseHex(node.get("valueHex").asText()) : null,
                    proof.toString()));
        }
        JsonNode evidence = root.path("evidence");
        if (!evidence.isObject()) {
            throw new IllegalArgumentException("answer evidence must be an object");
        }
        return new StatusAnswer(
                text(root, "chainId"), text(root, "profile"), text(root, "genesisId"),
                root.path("height").asLong(), text(root, "stateRoot"), text(root, "blockHash"),
                text(root, "collection"), HEX.parseHex(text(root, "keyHex")),
                StatusAnswer.Presence.valueOf(text(root, "presence")), entry, provenance,
                root.hasNonNull("actionCommitmentHex")
                        ? HEX.parseHex(root.get("actionCommitmentHex").asText()) : null,
                root.hasNonNull("authorizationEvidenceHex")
                        ? HEX.parseHex(root.get("authorizationEvidenceHex").asText()) : null,
                facts, evidence.toString());
    }

    /** Human view of an active entry's value under the profile's layouts; null when it does not decode. */
    public static JsonNode decodedValue(String collection, StatusAnswer.Entry entry) {
        if (entry == null || entry.status() != 0) return null;
        try {
            ObjectNode node = JSON.createObjectNode();
            switch (collection) {
                case TrustRegistryProfile.SUBJECTS -> {
                    var value = TrustRegistryValues.SubjectValue.decode(entry.value());
                    node.put("controllerOrganizationId", value.controllerOrganizationId());
                    node.put("kind", value.kind());
                    node.put("metadataHash", HEX.formatHex(value.metadataHash()));
                }
                case TrustRegistryProfile.STATUS -> {
                    var value = TrustRegistryValues.StatusValue.decode(entry.value());
                    node.put("bit", value.bit());
                    node.put("reasonCode", value.reasonCode());
                }
                case TrustRegistryProfile.STATUS_LISTS -> {
                    var value = TrustRegistryValues.StatusListValue.decode(entry.value());
                    node.put("purpose", value.purpose());
                    node.put("bitLength", value.bitLength());
                    node.put("listSha256", HEX.formatHex(value.listSha256()));
                    node.put("publishedHeight", value.publishedHeight());
                }
                case TrustRegistryProfile.ISSUERS -> {
                    var value = TrustRegistryValues.IssuerValue.decode(entry.value());
                    node.put("framework", value.framework());
                    ArrayNode authorizations = node.putArray("authorizations");
                    value.authorizations().forEach(authorizations::add);
                    node.put("validFromHeight", value.validFromHeight());
                    node.put("validUntilHeight", value.validUntilHeight());
                }
                default -> {
                    return null;
                }
            }
            return node;
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("answer field " + field + " must be text");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }
}
