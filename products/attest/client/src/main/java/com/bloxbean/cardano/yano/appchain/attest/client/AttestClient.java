package com.bloxbean.cardano.yano.appchain.attest.client;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Attestation client over a doc-trail app chain's REST surface, ADR-047 §7.
 *
 * <p>The client submits canonical doc-trail appends, waits for finality, and
 * assembles {@link AttestCertificate}s from the node's strict documents. The
 * signed envelope in the certificate is copied from the evidence block, never
 * from the message endpoint, which omits the expiry and the auth proof.</p>
 */
public final class AttestClient {
    public static final String GENERATOR = "yano-x-attest-client/1";
    static final int MAX_SMALL_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final int MAX_EVIDENCE_RESPONSE_BYTES = EvidenceBundleCodec.MAX_JSON_BYTES;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(500);

    private final String baseUrl;
    private final String chainId;
    private final String apiKey;
    private final HttpClient http;

    private AttestClient(Builder builder) {
        baseUrl = builder.baseUrl.replaceAll("/+$", "");
        chainId = builder.chainId;
        apiKey = builder.apiKey;
        http = builder.http != null ? builder.http
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static Builder builder(String baseUrl, String chainId) {
        return new Builder(baseUrl, chainId);
    }

    public String chainId() {
        return chainId;
    }

    // ------------------------------------------------------------------ attest

    /** Hashes the document with SHA-256 and records the digest. */
    public Submission attest(byte[] document, AttestRequest request) {
        return attestDigest(AttestVerifier.sha256(document), request);
    }

    /** Records a caller-computed SHA-256 digest. */
    public Submission attestDigest(byte[] entryHash, AttestRequest request) {
        Objects.requireNonNull(entryHash, "entryHash");
        Objects.requireNonNull(request, "request");
        if (entryHash.length != 32) {
            throw new IllegalArgumentException("entryHash must be a 32-byte SHA-256 digest");
        }
        String entryHashHex = HEX.formatHex(entryHash);
        String entityId = request.entityId() != null ? request.entityId() : derivedEntityId(entryHashHex);
        String reference = request.reference() != null ? request.reference() : "";
        byte[] command = DocTrailContract.append(entityId, entryHash, reference);
        ObjectNode body = JSON.createObjectNode();
        body.put("topic", DocTrailContract.DEFAULT_TOPIC);
        body.put("bodyHex", HEX.formatHex(command));
        JsonNode response = post(chainPath("/messages"), body.toString(), 202);
        String messageId = response.path("messageId").asText("");
        if (messageId.length() != 64 || !AttestCertificateCodec.canonicalHex(messageId)) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE,
                    "submit response did not carry a message id");
        }
        return new Submission(messageId, entityId, entryHashHex);
    }

    /** Entity id used when the caller supplies none: one series per digest. */
    public static String derivedEntityId(String entryHashHex) {
        return "sha256:" + entryHashHex;
    }

    /** Finalized position of a message, or empty while it is still pending. */
    public Optional<FinalizedMessage> finalizedMessage(String messageIdHex) {
        requireMessageId(messageIdHex);
        Optional<JsonNode> node = getOptional(chainPath("/messages/" + messageIdHex),
                MAX_SMALL_RESPONSE_BYTES);
        return node.map(value -> new FinalizedMessage(
                value.path("height").asLong(-1), value.path("index").asInt(-1),
                value.path("topic").asText(""), value.path("sender").asText(""),
                value.path("senderSeq").asLong(0), value.path("bodyHex").asText("")))
                .filter(value -> value.height() > 0 && value.index() >= 0);
    }

    public FinalizedMessage awaitFinalized(String messageIdHex, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<FinalizedMessage> finalized = finalizedMessage(messageIdHex);
            if (finalized.isPresent()) {
                return finalized.get();
            }
            if (System.nanoTime() >= deadline) {
                throw new AttestClientException(Error.NOT_FINALIZED,
                        "message " + messageIdHex + " was not finalized within " + timeout);
            }
            try {
                Thread.sleep(DEFAULT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AttestClientException(Error.TRANSPORT, "finality wait interrupted");
            }
        }
    }

    // ------------------------------------------------------------------ certificate

    /** Assembles a certificate for a finalized doc-trail append. */
    public AttestCertificate certificate(String messageIdHex, SubjectMetadata metadata) {
        requireMessageId(messageIdHex);
        SubjectMetadata meta = metadata != null ? metadata : SubjectMetadata.none();
        FinalizedMessage finalized = finalizedMessage(messageIdHex).orElseThrow(() ->
                new AttestClientException(Error.NOT_FINALIZED,
                        "message " + messageIdHex + " is not finalized on this node"));

        String messageProofJson = AttestCertificateCodec.canonicalDocument(
                getText(chainPath("/messages/" + messageIdHex + "/proof"), MAX_SMALL_RESPONSE_BYTES),
                "messageProof");
        MessageInclusionProof proof;
        try {
            proof = AppChainClient.decodeMessageProof(messageProofJson);
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "node returned a malformed message proof");
        }
        String evidenceJson = AttestCertificateCodec.canonicalDocument(
                getText(chainPath("/evidence/" + messageIdHex), MAX_EVIDENCE_RESPONSE_BYTES), "evidence");
        EvidenceBundle bundle;
        try {
            bundle = EvidenceBundleCodec.fromJson(evidenceJson);
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "node returned a malformed evidence bundle");
        }
        if (bundle.blocks().isEmpty() || !bundle.chainId().equals(chainId)
                || !bundle.messageIdHex().equals(messageIdHex)
                || bundle.blocks().getFirst().height() != finalized.height()
                || proof.blockHeight() != finalized.height()
                || proof.messageIndex() != finalized.index()) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE,
                    "node documents disagree about the message position");
        }
        AppBlock block = bundle.blocks().getFirst();
        if (finalized.index() >= block.messages().size()) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE,
                    "message index is outside the evidence block");
        }
        AppMessage envelope = block.messages().get(finalized.index());
        if (!HEX.formatHex(envelope.getMessageId()).equals(messageIdHex)) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE,
                    "evidence block envelope does not carry the message id");
        }
        DocTrailContract.Append command;
        try {
            command = DocTrailContract.decodeCommand(envelope.getBody());
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE,
                    "message " + messageIdHex + " is not a doc-trail append");
        }
        AttestCertificate.Message message = new AttestCertificate.Message(messageIdHex,
                block.height(), finalized.index(), envelope.getTopic(),
                HEX.formatHex(envelope.getSender()), envelope.getSenderSeq(), envelope.getExpiresAt(),
                HEX.formatHex(envelope.getBody()), envelope.getAuthScheme(),
                HEX.formatHex(envelope.getAuthProof()));
        AttestCertificate.Subject subject = new AttestCertificate.Subject(command.entityId(),
                HEX.formatHex(command.entryHash()), meta.fileName(), meta.sizeBytes(),
                meta.mediaType(), command.reference().isEmpty() ? null : command.reference(),
                meta.label());

        ChainStatus status = status();
        AttestCertificate.TrailHead trailHead = trailHead(command.entityId(), block.height());
        AttestCertificate.AnchorReference anchorReference = null;
        if (bundle.anchor() != null) {
            AppBlock last = bundle.blocks().getLast();
            anchorReference = new AttestCertificate.AnchorReference(chainId, anchorMode(status),
                    bundle.anchor().anchoredHeight(), HEX.formatHex(last.stateRoot()),
                    bundle.anchor().anchoredBlockHashHex(), bundle.anchor().txHash(),
                    bundle.anchor().l1Slot());
        }
        return new AttestCertificate(GENERATOR, Instant.now().toString(), chainId,
                status.applicationId(),
                anchorReference != null ? AttestCertificate.Status.ANCHORED
                        : AttestCertificate.Status.FINALIZED,
                subject, message, messageProofJson, evidenceJson, trailHead, anchorReference);
    }

    private AttestCertificate.TrailHead trailHead(String entityId, long height) {
        String keyHex = HEX.formatHex(DocTrailContract.entityKey(entityId));
        Optional<String> json = getOptionalText(
                chainPath("/state/proof/" + keyHex + "?height=" + height), MAX_SMALL_RESPONSE_BYTES);
        if (json.isEmpty()) {
            return null;
        }
        AppChainClient.Proof proof;
        try {
            proof = AppChainClient.decodeProofEnvelope(json.get());
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "node returned a malformed state proof");
        }
        if (proof.presence() != AppChainClient.ProofPresence.PRESENT || proof.valueHex() == null) {
            return null;
        }
        DocTrailContract.Head head;
        try {
            head = DocTrailContract.decodeHead(HEX.parseHex(proof.valueHex()));
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "trail head value is not a doc-trail head");
        }
        return new AttestCertificate.TrailHead(
                AttestCertificateCodec.canonicalDocument(json.get(), "trailHead.stateProof"),
                head.count(), HEX.formatHex(head.headHash()));
    }

    private String anchorMode(ChainStatus status) {
        Optional<JsonNode> commitment = getOptional(chainPath("/anchor/commitment"), MAX_SMALL_RESPONSE_BYTES);
        String mode = commitment.map(node -> node.path("mode").asText("")).orElse("");
        if (mode.isEmpty()) {
            mode = status.anchorMode() != null ? status.anchorMode() : "unknown";
        }
        return mode;
    }

    // ------------------------------------------------------------------ trail and status

    /** Current revision count and head digest of an entity, at the node's finalized tip. */
    public Trail trail(String entityId) {
        String keyHex = HEX.formatHex(DocTrailContract.entityKey(entityId));
        String json = getText(chainPath("/state/proof/" + keyHex), MAX_SMALL_RESPONSE_BYTES);
        AppChainClient.Proof proof;
        try {
            proof = AppChainClient.decodeProofEnvelope(json);
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "node returned a malformed state proof");
        }
        long height = proof.committedHeight() != null ? proof.committedHeight() : -1;
        if (proof.presence() != AppChainClient.ProofPresence.PRESENT || proof.valueHex() == null) {
            return new Trail(entityId, false, 0, null, height, proof.stateRootHex());
        }
        DocTrailContract.Head head;
        try {
            head = DocTrailContract.decodeHead(HEX.parseHex(proof.valueHex()));
        } catch (RuntimeException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "trail head value is not a doc-trail head");
        }
        return new Trail(entityId, true, head.count(), HEX.formatHex(head.headHash()),
                height, proof.stateRootHex());
    }

    /** Chain status as the attest product reads it. */
    public ChainStatus status() {
        JsonNode node = get(chainPath("/status"), MAX_SMALL_RESPONSE_BYTES);
        JsonNode manifest = node.path("capabilityManifest");
        String applicationId = manifest.hasNonNull("applicationId")
                ? manifest.get("applicationId").asText() : null;
        String stateMachine = node.path("stateMachine").asText("");
        boolean docTrail = DocTrailContract.STATE_MACHINE_ID.equals(stateMachine)
                || hasDocTrailComponent(manifest);
        JsonNode anchor = node.path("anchor");
        return new ChainStatus(node.path("chainId").asText(chainId), stateMachine, applicationId,
                node.path("tipHeight").asLong(-1), node.path("stateRoot").asText(""),
                node.path("members").asInt(0), node.path("threshold").asInt(0),
                anchor.isObject() && anchor.hasNonNull("mode") ? anchor.get("mode").asText() : null,
                anchor.isObject(), docTrail);
    }

    /** Application namespace of a standalone stdlib component; composite embeddings use their own. */
    static final String STANDALONE_STATE_NAMESPACE = "application/v1";

    private static boolean hasDocTrailComponent(JsonNode manifest) {
        for (JsonNode component : manifest.path("components")) {
            if (DocTrailContract.STATE_MACHINE_ID.equals(component.path("id").asText(""))
                    && STANDALONE_STATE_NAMESPACE.equals(component.path("stateNamespace").asText(""))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ HTTP

    private String chainPath(String suffix) {
        return baseUrl + "/app-chain/chains/" + URLEncoder.encode(chainId, StandardCharsets.UTF_8) + suffix;
    }

    private JsonNode get(String endpoint, int maxBytes) {
        return parse(getText(endpoint, maxBytes));
    }

    private Optional<JsonNode> getOptional(String endpoint, int maxBytes) {
        return getOptionalText(endpoint, maxBytes).map(AttestClient::parse);
    }

    private String getText(String endpoint, int maxBytes) {
        return getOptionalText(endpoint, maxBytes).orElseThrow(() ->
                new AttestClientException(Error.UNAVAILABLE, "not found: " + endpoint));
    }

    private Optional<String> getOptionalText(String endpoint, int maxBytes) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60)).header("Accept", "application/json").GET();
        Response response = send(request, maxBytes);
        if (response.status() == 404) {
            return Optional.empty();
        }
        if (response.status() == 503) {
            throw new AttestClientException(Error.UNAVAILABLE, "node reports the chain unavailable");
        }
        if (response.status() != 200) {
            throw new AttestClientException(Error.TRANSPORT,
                    "request failed with HTTP " + response.status() + ": " + endpoint);
        }
        return Optional.of(response.body());
    }

    private JsonNode post(String endpoint, String body, int expectedStatus) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        Response response = send(request, MAX_SMALL_RESPONSE_BYTES);
        if (response.status() == 503) {
            throw new AttestClientException(Error.UNAVAILABLE, "node reports the chain unavailable");
        }
        if (response.status() != expectedStatus) {
            throw new AttestClientException(Error.TRANSPORT,
                    "submit failed with HTTP " + response.status() + ": " + response.body());
        }
        return parse(response.body());
    }

    private Response send(HttpRequest.Builder request, int maxBytes) {
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("X-API-Key", apiKey);
        }
        try {
            HttpResponse<InputStream> response = http.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(maxBytes + 1);
            }
            if (body.length > maxBytes) {
                throw new AttestClientException(Error.RESPONSE_TOO_LARGE,
                        "node response exceeded " + maxBytes + " bytes");
            }
            return new Response(response.statusCode(), new String(body, StandardCharsets.UTF_8));
        } catch (AttestClientException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AttestClientException(Error.TRANSPORT, "request interrupted");
        } catch (IOException | RuntimeException failure) {
            throw new AttestClientException(Error.TRANSPORT,
                    "request failed: " + failure.getMessage(), failure);
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new AttestClientException(Error.MALFORMED_RESPONSE, "node response is not a JSON object");
            }
            return node;
        } catch (IOException malformed) {
            throw new AttestClientException(Error.MALFORMED_RESPONSE, "node response is not JSON");
        }
    }

    private static void requireMessageId(String messageIdHex) {
        if (messageIdHex == null || messageIdHex.length() != 64
                || !AttestCertificateCodec.canonicalHex(messageIdHex)) {
            throw new IllegalArgumentException("messageIdHex must be 32 bytes of lowercase hex");
        }
    }

    private record Response(int status, String body) {
    }

    // ------------------------------------------------------------------ types

    public enum Error { UNAVAILABLE, TRANSPORT, RESPONSE_TOO_LARGE, MALFORMED_RESPONSE, NOT_FINALIZED }

    public static final class AttestClientException extends RuntimeException {
        private final Error error;

        public AttestClientException(Error error, String message) {
            super(message);
            this.error = error;
        }

        public AttestClientException(Error error, String message, Throwable cause) {
            super(message, cause);
            this.error = error;
        }

        public Error error() {
            return error;
        }
    }

    /** Optional descriptive metadata about the attested document. */
    public record SubjectMetadata(String fileName, Long sizeBytes, String mediaType, String label) {
        public static SubjectMetadata none() {
            return new SubjectMetadata(null, null, null, null);
        }
    }

    /**
     * @param entityId  series id, or null to derive one from the digest
     * @param reference optional doc-trail reference bound by the command
     */
    public record AttestRequest(String entityId, String reference) {
        public static AttestRequest none() {
            return new AttestRequest(null, null);
        }
    }

    public record Submission(String messageIdHex, String entityId, String entryHashHex) {
    }

    public record FinalizedMessage(long height, int index, String topic, String senderHex,
                                   long senderSeq, String bodyHex) {
    }

    public record Trail(String entityId, boolean present, long revision, String headDigestHex,
                        long committedHeight, String stateRootHex) {
    }

    public record ChainStatus(String chainId, String stateMachine, String applicationId,
                              long tipHeight, String stateRootHex, int members, int threshold,
                              String anchorMode, boolean anchoringConfigured, boolean docTrail) {
    }

    public static final class Builder {
        private final String baseUrl;
        private final String chainId;
        private String apiKey;
        private HttpClient http;

        private Builder(String baseUrl, String chainId) {
            this.baseUrl = requireText(baseUrl, "baseUrl");
            this.chainId = requireText(chainId, "chainId");
        }

        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder httpClient(HttpClient value) {
            http = value;
            return this;
        }

        public AttestClient build() {
            return new AttestClient(this);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value.trim();
        }
    }
}
