package org.yanoproject.x.attest.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

/**
 * Strict JSON codec for {@link AttestCertificate}: exact field sets per
 * section, canonical lowercase hex, bounded sizes, and no trailing tokens.
 * Embedded node documents are carried as JSON objects and re-serialized
 * compactly; their own strict decoders run at verification time.
 */
public final class AttestCertificateCodec {
    /** Above the 40 MiB evidence bundle bound plus the remaining sections. */
    public static final int MAX_JSON_BYTES = 44 * 1024 * 1024;
    public static final int MAX_LABEL_CHARS = 256;
    public static final int MAX_TEXT_CHARS = 1024;
    public static final int MAX_ENTITY_ID_BYTES = 254;
    private static final int MAX_BODY_HEX_CHARS = 2 * 1024 * 1024;

    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(32)
                    .maxStringLength(MAX_JSON_BYTES)
                    .maxNumberLength(20)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final ObjectMapper MAPPER = JsonMapper.builder(JSON_FACTORY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "generator", "issuedAt", "chainId", "applicationId", "status",
            "subject", "message", "messageProof", "evidence", "trailHead", "anchorReference");
    private static final Set<String> SUBJECT_FIELDS = Set.of(
            "entityId", "entryHashHex", "hashAlgorithm", "fileName", "sizeBytes",
            "mediaType", "reference", "label");
    private static final Set<String> MESSAGE_FIELDS = Set.of(
            "messageIdHex", "height", "index", "topic", "senderHex", "senderSeq",
            "expiresAt", "bodyHex", "authScheme", "authProofHex");
    private static final Set<String> TRAIL_HEAD_FIELDS = Set.of(
            "stateProof", "revision", "headDigestHex");
    private static final Set<String> ANCHOR_FIELDS = Set.of(
            "chainId", "mode", "anchoredHeight", "stateRootHex", "blockHashHex",
            "transactionHash", "l1Slot");

    private AttestCertificateCodec() {
    }

    public static String toJson(AttestCertificate certificate) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", AttestCertificate.SCHEMA);
        root.put("generator", certificate.generator());
        root.put("issuedAt", certificate.issuedAt());
        root.put("chainId", certificate.chainId());
        if (certificate.applicationId() == null) {
            root.putNull("applicationId");
        } else {
            root.put("applicationId", certificate.applicationId());
        }
        root.put("status", certificate.status().name());

        AttestCertificate.Subject subject = certificate.subject();
        ObjectNode subjectNode = root.putObject("subject");
        subjectNode.put("entityId", subject.entityId());
        subjectNode.put("entryHashHex", subject.entryHashHex());
        subjectNode.put("hashAlgorithm", AttestCertificate.HASH_ALGORITHM);
        putOptional(subjectNode, "fileName", subject.fileName());
        if (subject.sizeBytes() != null) {
            subjectNode.put("sizeBytes", subject.sizeBytes());
        }
        putOptional(subjectNode, "mediaType", subject.mediaType());
        putOptional(subjectNode, "reference", subject.reference());
        putOptional(subjectNode, "label", subject.label());

        AttestCertificate.Message message = certificate.message();
        ObjectNode messageNode = root.putObject("message");
        messageNode.put("messageIdHex", message.messageIdHex());
        messageNode.put("height", message.height());
        messageNode.put("index", message.index());
        messageNode.put("topic", message.topic());
        messageNode.put("senderHex", message.senderHex());
        messageNode.put("senderSeq", message.senderSeq());
        messageNode.put("expiresAt", message.expiresAt());
        messageNode.put("bodyHex", message.bodyHex());
        messageNode.put("authScheme", message.authScheme());
        messageNode.put("authProofHex", message.authProofHex());

        root.set("messageProof", parseEmbedded(certificate.messageProofJson(), "messageProof"));
        root.set("evidence", parseEmbedded(certificate.evidenceJson(), "evidence"));

        if (certificate.trailHead() == null) {
            root.putNull("trailHead");
        } else {
            ObjectNode trail = root.putObject("trailHead");
            trail.set("stateProof", parseEmbedded(
                    certificate.trailHead().stateProofJson(), "trailHead.stateProof"));
            trail.put("revision", certificate.trailHead().revision());
            trail.put("headDigestHex", certificate.trailHead().headDigestHex());
        }
        if (certificate.anchorReference() == null) {
            root.putNull("anchorReference");
        } else {
            AttestCertificate.AnchorReference anchor = certificate.anchorReference();
            ObjectNode anchorNode = root.putObject("anchorReference");
            anchorNode.put("chainId", anchor.chainId());
            anchorNode.put("mode", anchor.mode());
            anchorNode.put("anchoredHeight", anchor.anchoredHeight());
            anchorNode.put("stateRootHex", anchor.stateRootHex());
            anchorNode.put("blockHashHex", anchor.blockHashHex());
            anchorNode.put("transactionHash", anchor.transactionHash());
            anchorNode.put("l1Slot", anchor.l1Slot());
        }
        try {
            String encoded = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                throw invalid("certificate exceeds the size bound");
            }
            return encoded;
        } catch (IOException failure) {
            throw new UncheckedIOException("Attest certificate JSON encode failed", failure);
        }
    }

    public static AttestCertificate fromJson(String json) {
        if (json == null || json.isEmpty()) {
            throw invalid("empty certificate");
        }
        return fromJson(json.getBytes(StandardCharsets.UTF_8));
    }

    public static AttestCertificate fromJson(byte[] json) {
        if (json == null || json.length == 0 || json.length > MAX_JSON_BYTES) {
            throw invalid("certificate is empty or exceeds the size bound");
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException malformed) {
            throw invalid("certificate is not well-formed JSON");
        }
        if (root == null || !root.isObject() || !exactFields(root, ROOT_FIELDS)) {
            throw invalid("certificate root has an unexpected field set");
        }
        if (!AttestCertificate.SCHEMA.equals(text(root, "schema", MAX_TEXT_CHARS))) {
            throw invalid("unsupported certificate schema");
        }
        String generator = text(root, "generator", MAX_TEXT_CHARS);
        String issuedAt = text(root, "issuedAt", MAX_TEXT_CHARS);
        String chainId = text(root, "chainId", MAX_TEXT_CHARS);
        String applicationId = root.get("applicationId").isNull()
                ? null : text(root, "applicationId", MAX_TEXT_CHARS);
        AttestCertificate.Status status;
        try {
            status = AttestCertificate.Status.valueOf(text(root, "status", 16));
        } catch (IllegalArgumentException unknown) {
            throw invalid("unknown certificate status");
        }
        AttestCertificate.Subject subject = subject(root.get("subject"));
        AttestCertificate.Message message = message(root.get("message"));
        String messageProof = embedded(root.get("messageProof"), "messageProof");
        String evidence = embedded(root.get("evidence"), "evidence");
        AttestCertificate.TrailHead trailHead = trailHead(root.get("trailHead"));
        AttestCertificate.AnchorReference anchor = anchor(root.get("anchorReference"));
        if (status == AttestCertificate.Status.ANCHORED && anchor == null) {
            throw invalid("anchored certificate without an anchor reference");
        }
        return new AttestCertificate(generator, issuedAt, chainId, applicationId, status,
                subject, message, messageProof, evidence, trailHead, anchor);
    }

    private static AttestCertificate.Subject subject(JsonNode node) {
        if (node == null || !node.isObject() || !onlyFields(node, SUBJECT_FIELDS)
                || !node.has("entityId") || !node.has("entryHashHex")
                || !node.has("hashAlgorithm")) {
            throw invalid("subject has an unexpected field set");
        }
        String entityId = text(node, "entityId", MAX_ENTITY_ID_BYTES);
        if (entityId.getBytes(StandardCharsets.UTF_8).length > MAX_ENTITY_ID_BYTES) {
            throw invalid("entityId exceeds the doc-trail key bound");
        }
        if (!AttestCertificate.HASH_ALGORITHM.equals(text(node, "hashAlgorithm", 16))) {
            throw invalid("unsupported subject hash algorithm");
        }
        Long size = null;
        if (node.has("sizeBytes")) {
            size = nonNegativeLong(node, "sizeBytes");
        }
        return new AttestCertificate.Subject(entityId,
                hex(node, "entryHashHex", 32),
                optionalText(node, "fileName", MAX_TEXT_CHARS),
                size,
                optionalText(node, "mediaType", MAX_LABEL_CHARS),
                optionalText(node, "reference", MAX_TEXT_CHARS),
                optionalText(node, "label", MAX_LABEL_CHARS));
    }

    private static AttestCertificate.Message message(JsonNode node) {
        if (node == null || !node.isObject() || !exactFields(node, MESSAGE_FIELDS)) {
            throw invalid("message has an unexpected field set");
        }
        long height = nonNegativeLong(node, "height");
        if (height < 1) {
            throw invalid("message height must be positive");
        }
        long index = nonNegativeLong(node, "index");
        if (index > Integer.MAX_VALUE) {
            throw invalid("message index is out of range");
        }
        long authScheme = nonNegativeLong(node, "authScheme");
        if (authScheme > Integer.MAX_VALUE) {
            throw invalid("auth scheme is out of range");
        }
        String bodyHex = text(node, "bodyHex", MAX_BODY_HEX_CHARS);
        if (bodyHex.isEmpty() || bodyHex.length() % 2 != 0 || !canonicalHex(bodyHex)) {
            throw invalid("bodyHex must be non-empty canonical hex");
        }
        return new AttestCertificate.Message(
                hex(node, "messageIdHex", 32), height, (int) index,
                text(node, "topic", MAX_TEXT_CHARS), hex(node, "senderHex", 32),
                nonNegativeLong(node, "senderSeq"), nonNegativeLong(node, "expiresAt"),
                bodyHex, (int) authScheme, hex(node, "authProofHex", 64));
    }

    private static AttestCertificate.TrailHead trailHead(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || !exactFields(node, TRAIL_HEAD_FIELDS)) {
            throw invalid("trailHead has an unexpected field set");
        }
        return new AttestCertificate.TrailHead(
                embedded(node.get("stateProof"), "trailHead.stateProof"),
                nonNegativeLong(node, "revision"), hex(node, "headDigestHex", 32));
    }

    private static AttestCertificate.AnchorReference anchor(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject() || !exactFields(node, ANCHOR_FIELDS)) {
            throw invalid("anchorReference has an unexpected field set");
        }
        long height = nonNegativeLong(node, "anchoredHeight");
        if (height < 1) {
            throw invalid("anchoredHeight must be positive");
        }
        return new AttestCertificate.AnchorReference(
                text(node, "chainId", MAX_TEXT_CHARS), text(node, "mode", MAX_LABEL_CHARS),
                height, hex(node, "stateRootHex", 32), hex(node, "blockHashHex", 32),
                text(node, "transactionHash", MAX_LABEL_CHARS), nonNegativeLong(node, "l1Slot"));
    }

    /**
     * Compact re-serialization of a node document, the form the certificate
     * stores so that encode and decode round-trip byte for byte.
     */
    public static String canonicalDocument(String json, String name) {
        return embedded(parseEmbedded(json, name), name);
    }

    private static String embedded(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be a JSON object");
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException failure) {
            throw invalid(name + " could not be re-encoded");
        }
    }

    private static JsonNode parseEmbedded(String json, String name) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_BYTES) {
            throw invalid(name + " document is empty or too large");
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw invalid(name + " document must be a JSON object");
            }
            return node;
        } catch (IOException malformed) {
            throw invalid(name + " document is not well-formed JSON");
        }
    }

    private static boolean exactFields(JsonNode node, Set<String> fields) {
        return onlyFields(node, fields) && node.size() == fields.size();
    }

    private static boolean onlyFields(JsonNode node, Set<String> fields) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode node, String field, int maxChars) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || value.textValue().length() > maxChars) {
            throw invalid(field + " must be a non-empty bounded string");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field, int maxChars) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > maxChars) {
            throw invalid(field + " must be a bounded string");
        }
        return value.textValue();
    }

    private static String hex(JsonNode node, String field, int bytes) {
        String value = text(node, field, bytes * 2);
        if (value.length() != bytes * 2 || !canonicalHex(value)) {
            throw invalid(field + " must be " + bytes + " bytes of lowercase hex");
        }
        return value;
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    static boolean canonicalHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid attest certificate: " + reason);
    }
}
