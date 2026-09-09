package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.appchain.dpp.profile.DppStarterProfile;
import com.bloxbean.cardano.yano.appchain.trust.client.AnswerCodec;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
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
import java.util.Objects;

/**
 * The {@code dpp-passport-v1} document (ADR-051 §2.2): every record of one product answered
 * with a proof at one height under one root, and the ledger-ordered timeline the projection
 * derived. Offline, {@link PassportVerifier} checks the answers, their agreement, and the
 * timeline against the answered entries.
 */
public record PassportBundle(
        String chainId,
        String profile,
        String genesisIdHex,
        long height,
        String stateRootHex,
        String blockHashHex,
        String productId,
        StatusAnswer product,
        List<StatusAnswer> versions,
        List<StatusAnswer> claims,
        List<StatusAnswer> events,
        List<StatusAnswer> certificates,
        List<PassportProjection.Applied> timeline
) {
    public static final String TYPE = "dpp-passport-v1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 48 * 1024 * 1024;
    public static final int MAX_ANSWERS = 2_048;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final HexFormat HEX = HexFormat.of();

    public PassportBundle {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(genesisIdHex, "genesisIdHex");
        Objects.requireNonNull(stateRootHex, "stateRootHex");
        Objects.requireNonNull(blockHashHex, "blockHashHex");
        DppStarterProfile.requireProductId(productId);
        Objects.requireNonNull(product, "product");
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        certificates = List.copyOf(Objects.requireNonNull(certificates, "certificates"));
        timeline = List.copyOf(Objects.requireNonNull(timeline, "timeline"));
        if (1 + versions.size() + claims.size() + events.size() + certificates.size() > MAX_ANSWERS) {
            throw new IllegalArgumentException("a passport carries at most " + MAX_ANSWERS + " answers");
        }
        if (height < 1) {
            throw new IllegalArgumentException("height must be positive");
        }
    }

    /** Every answer, product first, then versions, claims, events, certificates. */
    public List<StatusAnswer> answers() {
        List<StatusAnswer> all = new ArrayList<>();
        all.add(product);
        all.addAll(versions);
        all.addAll(claims);
        all.addAll(events);
        all.addAll(certificates);
        return List.copyOf(all);
    }

    // ------------------------------------------------------------------ JSON

    public ObjectNode toJsonNode() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("type", TYPE);
        root.put("chainId", chainId);
        root.put("profile", profile);
        root.put("genesisId", genesisIdHex);
        root.put("height", height);
        root.put("stateRoot", stateRootHex);
        root.put("blockHash", blockHashHex);
        root.put("productId", productId);
        root.put("prototype", DppStarterProfile.PROTOTYPE_NOTICE);
        ArrayNode answers = root.putArray("answers");
        for (StatusAnswer answer : answers()) {
            answers.add(AnswerCodec.toJsonNode(answer));
        }
        ArrayNode entries = root.putArray("timeline");
        for (PassportProjection.Applied applied : timeline) {
            ObjectNode node = entries.addObject();
            node.put("height", applied.height());
            node.put("position", applied.position());
            node.put("messageId", applied.messageIdHex());
            node.put("collection", applied.collection());
            node.put("keyHex", applied.keyHex());
            node.put("key", applied.keyText());
            node.put("operation", applied.operation());
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

    public static PassportBundle fromJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("passport document exceeds " + MAX_JSON_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("passport document is not well-formed JSON", malformed);
        }
        return fromJsonNode(root);
    }

    public static PassportBundle fromJsonNode(JsonNode root) {
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("passport document has an unsupported identity");
        }
        JsonNode answersNode = root.path("answers");
        if (!answersNode.isArray() || answersNode.isEmpty() || answersNode.size() > MAX_ANSWERS) {
            throw new IllegalArgumentException("passport document carries 1-" + MAX_ANSWERS + " answers");
        }
        StatusAnswer product = null;
        List<StatusAnswer> versions = new ArrayList<>();
        List<StatusAnswer> claims = new ArrayList<>();
        List<StatusAnswer> events = new ArrayList<>();
        List<StatusAnswer> certificates = new ArrayList<>();
        for (JsonNode node : answersNode) {
            StatusAnswer answer = AnswerCodec.fromJsonNode(node);
            switch (answer.collection()) {
                case DppStarterProfile.PRODUCTS -> {
                    if (product != null) {
                        throw new IllegalArgumentException("passport document carries two product answers");
                    }
                    product = answer;
                }
                case DppStarterProfile.VERSIONS -> versions.add(answer);
                case DppStarterProfile.CLAIMS -> claims.add(answer);
                case DppStarterProfile.EVENTS -> events.add(answer);
                case DppStarterProfile.CERTIFICATES -> certificates.add(answer);
                default -> throw new IllegalArgumentException(
                        "passport document answers an unknown collection " + answer.collection());
            }
        }
        if (product == null) {
            throw new IllegalArgumentException("passport document carries no product answer");
        }
        List<PassportProjection.Applied> timeline = new ArrayList<>();
        JsonNode timelineNode = root.path("timeline");
        if (!timelineNode.isArray() || timelineNode.size() > MAX_ANSWERS * 4) {
            throw new IllegalArgumentException("passport timeline is malformed");
        }
        String productId = root.path("productId").asText();
        for (JsonNode node : timelineNode) {
            String keyHex = node.path("keyHex").asText();
            if (!keyHex.matches("([0-9a-f]{2}){1,128}")) {
                throw new IllegalArgumentException("timeline key is malformed");
            }
            timeline.add(new PassportProjection.Applied(node.path("height").asLong(),
                    node.path("position").asInt(), node.path("messageId").asText(),
                    node.path("collection").asText(), HEX.parseHex(keyHex),
                    node.path("operation").asText(), productId));
        }
        return new PassportBundle(root.path("chainId").asText(), root.path("profile").asText(),
                root.path("genesisId").asText(), root.path("height").asLong(),
                root.path("stateRoot").asText(), root.path("blockHash").asText(), productId,
                product, versions, claims, events, certificates, timeline);
    }
}
