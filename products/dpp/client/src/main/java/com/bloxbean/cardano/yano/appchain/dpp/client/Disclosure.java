package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.appchain.dpp.profile.DppStarterProfile;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppValues;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The {@code dpp-disclosure-v1} document an issuer hands a verifier for a committed claim: the
 * salt and the text whose commitment the chain holds (ADR-051 §2.1). Checking it needs a
 * verified passport bundle; the disclosure itself proves nothing.
 */
public record Disclosure(String chainId, String productId, String claimType, String claimId,
                         String saltHex, String text) {
    public static final String TYPE = "dpp-disclosure-v1";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    public Disclosure {
        Objects.requireNonNull(chainId, "chainId");
        DppStarterProfile.requireProductId(productId);
        DppStarterProfile.requireClaimType(claimType);
        DppStarterProfile.requireClaimId(claimId);
        if (saltHex == null || !saltHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("salt must be 32 bytes of lowercase hex");
        }
        Objects.requireNonNull(text, "text");
    }

    public static Disclosure create(String chainId, String productId, String claimType,
                                    String claimId, String text) {
        byte[] salt = new byte[32];
        RANDOM.nextBytes(salt);
        return new Disclosure(chainId, productId, claimType, claimId, HEX.formatHex(salt), text);
    }

    public byte[] commitment() {
        return DppValues.claimCommitment(HEX.parseHex(saltHex), text);
    }

    public enum Outcome { MATCH, MISMATCH, NOT_COMMITTED, ABSENT, WRONG_PRODUCT }

    public record Check(Outcome outcome, String message) {
        public boolean matches() {
            return outcome == Outcome.MATCH;
        }
    }

    /** Recomputes the commitment and compares it with the claim answer in the bundle. */
    public Check check(PassportBundle bundle) {
        if (!bundle.productId().equals(productId) || !bundle.chainId().equals(chainId)) {
            return new Check(Outcome.WRONG_PRODUCT, "the disclosure names " + productId + " on "
                    + chainId + ", the passport " + bundle.productId() + " on " + bundle.chainId());
        }
        byte[] key = DppStarterProfile.claimKey(productId, claimType, claimId);
        for (StatusAnswer answer : bundle.claims()) {
            if (!Arrays.equals(answer.key(), key)) continue;
            if (answer.presence() != StatusAnswer.Presence.ACTIVE) {
                return new Check(Outcome.ABSENT, "the claim is " + answer.presence()
                        + " at height " + answer.height());
            }
            DppValues.ClaimValue claim;
            try {
                claim = DppValues.ClaimValue.decode(answer.entry().value());
            } catch (RuntimeException malformed) {
                return new Check(Outcome.MISMATCH, "the claim value is malformed");
            }
            if (claim.isPublic()) {
                return new Check(Outcome.NOT_COMMITTED, "the claim is public; nothing to disclose");
            }
            return Arrays.equals(claim.value(), commitment())
                    ? new Check(Outcome.MATCH, "the disclosed text and salt reproduce the committed"
                    + " value at height " + answer.height())
                    : new Check(Outcome.MISMATCH, "the disclosed text and salt do not reproduce"
                    + " the committed value");
        }
        return new Check(Outcome.ABSENT, "the passport carries no answer for claim " + claimType
                + "/" + claimId);
    }

    public ObjectNode toJsonNode() {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("type", TYPE);
        root.put("chainId", chainId);
        root.put("productId", productId);
        root.put("claimType", claimType);
        root.put("claimId", claimId);
        root.put("saltHex", saltHex);
        root.put("text", text);
        root.put("commitment", HEX.formatHex(commitment()));
        return root;
    }

    public String toJson() {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJsonNode());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Disclosure fromJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("disclosure exceeds " + MAX_JSON_BYTES + " bytes");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("disclosure is not well-formed JSON", malformed);
        }
        if (root == null || !root.isObject()
                || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("disclosure has an unsupported identity");
        }
        return new Disclosure(root.path("chainId").asText(), root.path("productId").asText(),
                root.path("claimType").asText(), root.path("claimId").asText(),
                root.path("saltHex").asText(), root.path("text").asText());
    }
}
