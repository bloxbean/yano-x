package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yano.appchain.feed.profile.FeedStarterProfile;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedValues;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistrySigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The {@code feed-round-request-v1} document that travels between the operator who proposed a
 * round close, the publishers who decide, and whoever applies the map command (ADR-052 §2.2).
 * It carries the plain map command (one {@code PUT_IF_ABSENT} of the round record); the
 * approval-routed action, its commitment, and the payload hash are recomputed from it so that
 * every party signs the same statement, and the record a publisher approves is decoded from it
 * so that a publisher can recompute the round before signing.
 */
public record RoundRequest(
        String chainId,
        String genesisIdHex,
        String policyId,
        long policyRevision,
        String proposalId,
        String commandHex,
        String payloadHashHex,
        long deadlineHeight,
        String feedId,
        long round,
        String proposeMessageIdHex
) {
    public static final String TYPE = "feed-round-request-v1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 256 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    public RoundRequest {
        Objects.requireNonNull(chainId, "chainId");
        if (!genesisIdHex.matches("[0-9a-f]{64}") || !payloadHashHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("genesis id and payload hash are 32-byte hex");
        }
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(commandHex, "commandHex");
        FeedStarterProfile.requireFeedId(feedId);
        FeedStarterProfile.requireRound(round);
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

    /** The round record the command would write, decoded from its single mutation. */
    public FeedValues.RoundValue recordValue() {
        AuthenticatedMapContract.Mutation mutation = mutation();
        return FeedValues.RoundValue.decode(mutation.value());
    }

    private AuthenticatedMapContract.Mutation mutation() {
        var mutations = command().mutations();
        if (mutations.size() != 1) {
            throw new IllegalArgumentException("a round request carries exactly one mutation");
        }
        AuthenticatedMapContract.Mutation mutation = mutations.getFirst();
        if (!FeedStarterProfile.ROUNDS.equals(mutation.collectionId())
                || !Arrays.equals(mutation.applicationKey(), FeedStarterProfile.roundKey(feedId, round))
                || mutation.operation() != AuthenticatedMapContract.OP_PUT_IF_ABSENT) {
            throw new IllegalArgumentException("a round request writes rounds/" + feedId + "/" + round
                    + " with PUT_IF_ABSENT");
        }
        return mutation;
    }

    /**
     * The payload hash recomputed from the command and genesis must equal the one carried, and
     * the command must be the round record write the document says it is.
     */
    public boolean consistent() {
        try {
            recordValue();
        } catch (RuntimeException malformed) {
            return false;
        }
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
        root.put("feedId", feedId);
        root.put("round", round);
        root.put("proposeMessageId", proposeMessageIdHex);
        try {
            FeedValues.RoundValue record = recordValue();
            ObjectNode node = root.putObject("record");
            node.put("status", record.statusName());
            node.put("closedAtHeight", record.closedAtHeight());
            node.put("aggregate", record.aggregate());
            node.put("decimal", FeedValues.decimal(record.aggregate(), record.scale()));
            node.put("scale", record.scale());
            ArrayNode accepted = node.putArray("acceptedSources");
            record.acceptedSources().forEach(accepted::add);
            node.put("policySha256", HEX.formatHex(record.policySha256()));
            node.put("datumSha256", HEX.formatHex(record.datumSha256()));
        } catch (RuntimeException malformed) {
            root.putNull("record");
        }
        return root;
    }

    public String toJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static RoundRequest fromJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("round request exceeds " + MAX_JSON_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("round request is not well-formed JSON", malformed);
        }
        return fromJsonNode(root);
    }

    public static RoundRequest fromJsonNode(JsonNode root) {
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("round request has an unsupported identity");
        }
        RoundRequest request = new RoundRequest(
                root.path("chainId").asText(), root.path("genesisId").asText(),
                root.path("policyId").asText(), root.path("policyRevision").asLong(),
                root.path("proposalId").asText(), root.path("commandHex").asText(),
                root.path("payloadHash").asText(), root.path("deadlineHeight").asLong(),
                root.path("feedId").asText(), root.path("round").asLong(-1),
                root.path("proposeMessageId").asText(""));
        if (!request.consistent()) {
            throw new IllegalArgumentException(
                    "round request payload hash or command does not match the document");
        }
        return request;
    }
}
