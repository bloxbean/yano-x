package com.bloxbean.cardano.yano.appchain.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The portable bundles of ADR-050 §2.5. A row bundle aggregates node-issued objects only (the
 * compact inclusion proof, the block record proof envelope, the evidence bundle); a state bundle
 * carries one typed proof envelope with the evidence of its block. The {@code verification}
 * object is explanatory and importers discard it.
 */
public final class Bundles {
    public static final String ROW_SCHEMA = "explorer-row-proof-v1";
    public static final String STATE_SCHEMA = "explorer-state-proof-v1";
    public static final int MAX_JSON_BYTES = 48 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() { };

    private Bundles() {
    }

    public record RowBundle(
            String chainId, String applicationId, String profile, String stateGenesisIdHex,
            long height, int index, String blockHashHex, String stateRootHex,
            IndexedMessage message, String inclusionProofJson, String blockRecordProofJson,
            String evidenceJson, VerificationLevel ingestLevel, Map<String, Object> verification
    ) {
        public RowBundle {
            chainId = Objects.requireNonNull(chainId, "chainId");
            applicationId = Objects.requireNonNullElse(applicationId, "");
            profile = Objects.requireNonNull(profile, "profile");
            stateGenesisIdHex = Objects.requireNonNull(stateGenesisIdHex, "stateGenesisIdHex");
            if (height < 1 || index < 0) throw new IllegalArgumentException("invalid row position");
            blockHashHex = Objects.requireNonNull(blockHashHex, "blockHashHex");
            stateRootHex = Objects.requireNonNull(stateRootHex, "stateRootHex");
            message = Objects.requireNonNull(message, "message");
            inclusionProofJson = Objects.requireNonNull(inclusionProofJson, "inclusionProofJson");
            blockRecordProofJson = Objects.requireNonNullElse(blockRecordProofJson, "");
            evidenceJson = Objects.requireNonNull(evidenceJson, "evidenceJson");
            ingestLevel = Objects.requireNonNull(ingestLevel, "ingestLevel");
            verification = verification == null ? Map.of() : Map.copyOf(verification);
        }

        public RowBundle withVerification(Map<String, Object> explanation) {
            return new RowBundle(chainId, applicationId, profile, stateGenesisIdHex, height, index,
                    blockHashHex, stateRootHex, message, inclusionProofJson, blockRecordProofJson,
                    evidenceJson, ingestLevel, explanation);
        }
    }

    public record StateBundle(
            String chainId, String applicationId, String profile, String stateGenesisIdHex,
            long height, String blockHashHex, String stateRootHex, String subjectId,
            Map<String, String> coordinates, String componentId, String keyHex, String proofJson,
            String evidenceJson, Map<String, Object> decodedFact, Map<String, Object> verification
    ) {
        public StateBundle {
            chainId = Objects.requireNonNull(chainId, "chainId");
            applicationId = Objects.requireNonNullElse(applicationId, "");
            profile = Objects.requireNonNull(profile, "profile");
            stateGenesisIdHex = Objects.requireNonNull(stateGenesisIdHex, "stateGenesisIdHex");
            if (height < 1) throw new IllegalArgumentException("invalid height");
            blockHashHex = Objects.requireNonNull(blockHashHex, "blockHashHex");
            stateRootHex = Objects.requireNonNull(stateRootHex, "stateRootHex");
            subjectId = Objects.requireNonNull(subjectId, "subjectId");
            coordinates = coordinates == null ? Map.of() : Map.copyOf(coordinates);
            componentId = Objects.requireNonNullElse(componentId, "");
            keyHex = Objects.requireNonNull(keyHex, "keyHex");
            proofJson = Objects.requireNonNull(proofJson, "proofJson");
            evidenceJson = Objects.requireNonNull(evidenceJson, "evidenceJson");
            decodedFact = decodedFact == null ? Map.of() : Map.copyOf(decodedFact);
            verification = verification == null ? Map.of() : Map.copyOf(verification);
        }

        public StateBundle withVerification(Map<String, Object> explanation) {
            return new StateBundle(chainId, applicationId, profile, stateGenesisIdHex, height, blockHashHex,
                    stateRootHex, subjectId, coordinates, componentId, keyHex, proofJson, evidenceJson,
                    decodedFact, explanation);
        }
    }

    public static String toJson(RowBundle bundle) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(bundle));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static ObjectNode toNode(RowBundle bundle) {
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("schema", ROW_SCHEMA);
            node.put("chainId", bundle.chainId());
            node.put("applicationId", bundle.applicationId());
            node.put("profile", bundle.profile());
            node.put("stateGenesisId", bundle.stateGenesisIdHex());
            node.put("height", bundle.height());
            node.put("index", bundle.index());
            node.put("blockHash", bundle.blockHashHex());
            node.put("stateRoot", bundle.stateRootHex());
            node.set("message", messageNode(bundle.message()));
            node.set("inclusionProof", JSON.readTree(bundle.inclusionProofJson()));
            if (!bundle.blockRecordProofJson().isEmpty()) {
                node.set("blockRecordProof", JSON.readTree(bundle.blockRecordProofJson()));
            }
            node.set("evidence", JSON.readTree(bundle.evidenceJson()));
            node.put("ingestLevel", bundle.ingestLevel().name());
            node.set("verification", JSON.valueToTree(bundle.verification()));
            return node;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static String toJson(StateBundle bundle) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(bundle));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static ObjectNode toNode(StateBundle bundle) {
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("schema", STATE_SCHEMA);
            node.put("chainId", bundle.chainId());
            node.put("applicationId", bundle.applicationId());
            node.put("profile", bundle.profile());
            node.put("stateGenesisId", bundle.stateGenesisIdHex());
            node.put("height", bundle.height());
            node.put("blockHash", bundle.blockHashHex());
            node.put("stateRoot", bundle.stateRootHex());
            node.put("subjectId", bundle.subjectId());
            node.set("coordinates", JSON.valueToTree(bundle.coordinates()));
            node.put("componentId", bundle.componentId());
            node.put("keyHex", bundle.keyHex());
            node.set("proof", JSON.readTree(bundle.proofJson()));
            node.set("evidence", JSON.readTree(bundle.evidenceJson()));
            node.set("decodedFact", JSON.valueToTree(bundle.decodedFact()));
            node.set("verification", JSON.valueToTree(bundle.verification()));
            return node;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The schema of a bundle document, or empty for anything else. */
    public static String schemaOf(String json) {
        try {
            return read(json).path("schema").asText("");
        } catch (ExplorerException malformed) {
            return "";
        }
    }

    public static RowBundle rowFromJson(String json) {
        JsonNode node = read(json);
        if (!ROW_SCHEMA.equals(node.path("schema").asText(""))) {
            throw new ExplorerException(ExplorerException.Error.INVALID, "not an " + ROW_SCHEMA + " document");
        }
        try {
            JsonNode message = node.path("message");
            IndexedMessage indexed = new IndexedMessage(node.path("height").asLong(), node.path("index").asInt(),
                    message.path("messageId").asText(""), message.path("topic").asText(""),
                    message.path("sender").asText(""), message.path("senderSeq").asLong(),
                    message.path("expiresAt").asLong(), message.path("bodyHex").asText(""),
                    message.path("authScheme").asInt(-1), message.path("authProofHex").asText(""),
                    IndexedMessage.State.valueOf(message.path("state").asText("FULL")));
            return new RowBundle(node.path("chainId").asText(""), node.path("applicationId").asText(""),
                    node.path("profile").asText(""), node.path("stateGenesisId").asText(""),
                    node.path("height").asLong(), node.path("index").asInt(), node.path("blockHash").asText(""),
                    node.path("stateRoot").asText(""), indexed, JSON.writeValueAsString(node.path("inclusionProof")),
                    node.has("blockRecordProof") ? JSON.writeValueAsString(node.path("blockRecordProof")) : "",
                    JSON.writeValueAsString(node.path("evidence")),
                    VerificationLevel.valueOf(node.path("ingestLevel").asText("JSON_ONLY")), Map.of());
        } catch (RuntimeException | IOException malformed) {
            throw new ExplorerException(ExplorerException.Error.INVALID,
                    "row bundle is malformed: " + malformed.getMessage(), malformed);
        }
    }

    public static StateBundle stateFromJson(String json) {
        JsonNode node = read(json);
        if (!STATE_SCHEMA.equals(node.path("schema").asText(""))) {
            throw new ExplorerException(ExplorerException.Error.INVALID, "not an " + STATE_SCHEMA + " document");
        }
        try {
            return new StateBundle(node.path("chainId").asText(""), node.path("applicationId").asText(""),
                    node.path("profile").asText(""), node.path("stateGenesisId").asText(""),
                    node.path("height").asLong(), node.path("blockHash").asText(""), node.path("stateRoot").asText(""),
                    node.path("subjectId").asText(""), JSON.convertValue(node.path("coordinates"), STRINGS),
                    node.path("componentId").asText(""), node.path("keyHex").asText(""),
                    JSON.writeValueAsString(node.path("proof")), JSON.writeValueAsString(node.path("evidence")),
                    JSON.convertValue(node.path("decodedFact"), OBJECT), Map.of());
        } catch (RuntimeException | IOException malformed) {
            throw new ExplorerException(ExplorerException.Error.INVALID,
                    "state bundle is malformed: " + malformed.getMessage(), malformed);
        }
    }

    static ObjectNode messageNode(IndexedMessage message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageId", message.messageIdHex());
        node.put("height", message.height());
        node.put("index", message.index());
        node.put("topic", message.topic());
        node.put("sender", message.senderHex());
        node.put("senderSeq", message.senderSeq());
        node.put("expiresAt", message.expiresAt());
        node.put("bodyHex", message.bodyHex());
        node.put("authScheme", message.authScheme());
        node.put("authProofHex", message.authProofHex());
        node.put("state", message.state().name());
        return node;
    }

    static Map<String, Object> fields(JsonNode node) {
        return JSON.convertValue(node, OBJECT);
    }

    private static JsonNode read(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new ExplorerException(ExplorerException.Error.INVALID, "bundle exceeds the size bound");
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                throw new ExplorerException(ExplorerException.Error.INVALID, "bundle must be a JSON object");
            }
            return node;
        } catch (IOException malformed) {
            throw new ExplorerException(ExplorerException.Error.INVALID, "bundle is not JSON", malformed);
        }
    }

    static Map<String, Object> explanation(RowVerifier.Verification verification) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("note", "explanatory only; importers recompute");
        map.put("consistent", verification.consistent());
        map.put("trustLevel", verification.trustLevel().name());
        map.put("certSignatures", verification.certSignatures());
        map.put("checks", verification.checks());
        map.put("failures", verification.failures());
        return map;
    }
}
