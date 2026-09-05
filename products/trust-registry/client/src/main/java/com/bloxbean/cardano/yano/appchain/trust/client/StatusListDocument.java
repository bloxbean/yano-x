package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.yano.appchain.trust.profile.StatusBitstring;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusProjection;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Renders a projection as a {@code BitstringStatusListCredential}-shaped document
 * (ADR-049 §2.3). The list is presentation over the chain's entries; the {@code x-yano}
 * block says which height was replayed, which {@code status-lists} entry the chain holds,
 * and whether the projection's hash matches it. No JSON-LD processing happens anywhere.
 */
public final class StatusListDocument {
    public static final String CONTEXT = "https://www.w3.org/ns/credentials/v2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private StatusListDocument() {
    }

    public record Rendered(ObjectNode document, StatusBitstring bitstring, boolean matchesChain) {
    }

    /**
     * @param listEntry the chain's {@code status-lists} answer at the replayed height, or null
     */
    public static Rendered render(String listId, StatusProjection projection, long replayedHeight,
                                  TrustRegistryValues.StatusListValue list, StatusAnswer listEntry,
                                  String chainId) {
        StatusBitstring bitstring = projection.bitstring(listId, list.bitLength());
        boolean matches = Arrays.equals(bitstring.sha256(), list.listSha256());
        ObjectNode document = JSON.createObjectNode();
        document.putArray("@context").add(CONTEXT);
        document.putArray("type").add("VerifiableCredential").add("BitstringStatusListCredential");
        document.put("id", listId);
        ObjectNode subject = document.putObject("credentialSubject");
        subject.put("type", "BitstringStatusList");
        subject.put("statusPurpose", list.purpose());
        subject.put("statusSize", 1);
        subject.put("encodedList", bitstring.encodedList());
        ObjectNode yano = document.putObject("x-yano");
        yano.put("chainId", chainId);
        yano.put("listId", listId);
        yano.put("replayedHeight", replayedHeight);
        yano.put("bitLength", list.bitLength());
        yano.put("setCount", bitstring.setCount());
        yano.put("bitstringSha256", bitstring.sha256Hex());
        yano.put("chainListSha256", HEX.formatHex(list.listSha256()));
        yano.put("publishedHeight", list.publishedHeight());
        yano.put("matchesChain", matches);
        yano.put("mutationCount", projection.mutationCount());
        if (listEntry != null) {
            yano.set("listEntry", AnswerCodec.toJsonNode(listEntry));
        }
        return new Rendered(document, bitstring, matches);
    }

    /** Reads a served document back: the bitstring and the chain hash it claims. */
    public record Served(String listId, String purpose, long bitLength, StatusBitstring bitstring,
                         String chainListSha256Hex, long publishedHeight) {
    }

    public static Served parse(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("status list document is not JSON", malformed);
        }
        JsonNode subject = root.path("credentialSubject");
        JsonNode yano = root.path("x-yano");
        if (!"BitstringStatusList".equals(subject.path("type").asText())
                || subject.path("statusSize").asInt(1) != 1
                || !subject.path("encodedList").isTextual()) {
            throw new IllegalArgumentException("document is not a Bitstring Status List");
        }
        long bitLength = yano.path("bitLength").asLong(0);
        StatusBitstring bitstring = StatusBitstring.decodeEncodedList(
                subject.get("encodedList").asText(), bitLength);
        return new Served(root.path("id").asText(""), subject.path("statusPurpose").asText(""),
                bitLength, bitstring, yano.path("chainListSha256").asText(""),
                yano.path("publishedHeight").asLong(0));
    }
}
