package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistrySigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The {@code dpp-certification-request-v1} document that travels between the certifier who
 * proposed, the auditors who decide, and whoever applies the map command (ADR-051 §2.2). It
 * carries the plain map command; the approval-routed action, its commitment, and the payload
 * hash are recomputed from it so that every party signs the same statement.
 */
public record CertificationRequest(
        String chainId,
        String genesisIdHex,
        String policyId,
        long policyRevision,
        String proposalId,
        String commandHex,
        String payloadHashHex,
        long deadlineHeight,
        String productId,
        String certificateId,
        String operation,
        String proposeMessageIdHex
) {
    public static final String TYPE = "dpp-certification-request-v1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    public CertificationRequest {
        Objects.requireNonNull(chainId, "chainId");
        if (!genesisIdHex.matches("[0-9a-f]{64}") || !payloadHashHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("genesis id and payload hash are 32-byte hex");
        }
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(commandHex, "commandHex");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(certificateId, "certificateId");
        Objects.requireNonNull(operation, "operation");
        proposeMessageIdHex = proposeMessageIdHex == null ? "" : proposeMessageIdHex;
        if (policyRevision < 1 || deadlineHeight < 1) {
            throw new IllegalArgumentException("policy revision and deadline are positive");
        }
    }

    public AuthenticatedMapContract.Command command() {
        return AuthenticatedMapContract.decodeCommand(HEX.parseHex(commandHex));
    }

    public AuthenticatedMapAuthorizationContract.MapActionV1 action() {
        return TrustRegistrySigner.approvalAction(command(), policyId);
    }

    public byte[] payloadHash() {
        return HEX.parseHex(payloadHashHex);
    }

    /** The payload hash recomputed from the command and genesis must equal the one carried. */
    public boolean consistent() {
        return HEX.formatHex(TrustRegistrySigner.approvalPayloadHash(
                HEX.parseHex(genesisIdHex), action())).equals(payloadHashHex);
    }

    public ObjectNode toJsonNode() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("type", TYPE);
        root.put("chainId", chainId);
        root.put("genesisId", genesisIdHex);
        root.put("policyId", policyId);
        root.put("policyRevision", policyRevision);
        root.put("proposalId", proposalId);
        root.put("commandHex", commandHex);
        root.put("payloadHash", payloadHashHex);
        root.put("deadlineHeight", deadlineHeight);
        root.put("productId", productId);
        root.put("certificateId", certificateId);
        root.put("operation", operation);
        root.put("proposeMessageId", proposeMessageIdHex);
        return root;
    }

    public String toJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static CertificationRequest fromJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("certification request exceeds " + MAX_JSON_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("certification request is not well-formed JSON", malformed);
        }
        return fromJsonNode(root);
    }

    public static CertificationRequest fromJsonNode(JsonNode root) {
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("certification request has an unsupported identity");
        }
        CertificationRequest request = new CertificationRequest(
                root.path("chainId").asText(), root.path("genesisId").asText(),
                root.path("policyId").asText(), root.path("policyRevision").asLong(),
                root.path("proposalId").asText(), root.path("commandHex").asText(),
                root.path("payloadHash").asText(), root.path("deadlineHeight").asLong(),
                root.path("productId").asText(), root.path("certificateId").asText(),
                root.path("operation").asText(), root.path("proposeMessageId").asText(""));
        if (!request.consistent()) {
            throw new IllegalArgumentException(
                    "certification request payload hash does not match its command and genesis");
        }
        return request;
    }
}
