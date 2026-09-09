package com.bloxbean.cardano.yano.appchain.attest.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttestCertificateCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEX32 = "ab".repeat(32);
    private static final String HEX64 = "cd".repeat(64);

    static AttestCertificate sample(boolean anchored, boolean trail) {
        AttestCertificate.Subject subject = new AttestCertificate.Subject(
                "sha256:" + HEX32, HEX32, "report.pdf", 1234L, "application/pdf", "ref-1", "Q3 report");
        AttestCertificate.Message message = new AttestCertificate.Message(
                HEX32, 7, 2, "doc-trail.command.v1", HEX32, 3, 1_800_000_000L, "8301a0", 0, HEX64);
        AttestCertificate.TrailHead head = trail
                ? new AttestCertificate.TrailHead("{\"key\":\"6531\",\"nested\":{\"a\":[1,2]}}", 1, HEX32)
                : null;
        AttestCertificate.AnchorReference anchor = anchored
                ? new AttestCertificate.AnchorReference("chain-a", "script", 9, HEX32, HEX32, "ff".repeat(32), 42)
                : null;
        return new AttestCertificate("yano-x-attest-client/1", "2026-09-05T00:00:00Z", "chain-a",
                "doc-trail", anchored ? AttestCertificate.Status.ANCHORED : AttestCertificate.Status.FINALIZED,
                subject, message, "{\"schemaVersion\":1,\"siblings\":[]}", "{\"chainId\":\"chain-a\"}",
                head, anchor);
    }

    @Test
    void roundTripsEveryField() throws Exception {
        for (boolean anchored : new boolean[] {false, true}) {
            for (boolean trail : new boolean[] {false, true}) {
                AttestCertificate original = sample(anchored, trail);
                String json = AttestCertificateCodec.toJson(original);
                AttestCertificate decoded = AttestCertificateCodec.fromJson(json);
                assertThat(decoded).isEqualTo(original);
                JsonNode tree = JSON.readTree(json);
                assertThat(tree.get("schema").asText()).isEqualTo(AttestCertificate.SCHEMA);
                assertThat(tree.get("subject").get("hashAlgorithm").asText()).isEqualTo("sha-256");
                assertThat(tree.get("trailHead").isNull()).isEqualTo(!trail);
                assertThat(tree.get("anchorReference").isNull()).isEqualTo(!anchored);
                assertThat(AttestCertificateCodec.toJson(decoded)).isEqualTo(json);
            }
        }
    }

    @Test
    void omitsAbsentSubjectMetadata() throws Exception {
        AttestCertificate.Subject bare = new AttestCertificate.Subject(
                "e1", HEX32, null, null, null, null, null);
        AttestCertificate certificate = new AttestCertificate("g", "t", "chain-a", null,
                AttestCertificate.Status.FINALIZED, bare, sample(false, false).message(),
                "{}", "{}", null, null);
        JsonNode tree = JSON.readTree(AttestCertificateCodec.toJson(certificate));
        assertThat(tree.get("subject").size()).isEqualTo(3);
        assertThat(tree.get("applicationId").isNull()).isTrue();
        assertThat(AttestCertificateCodec.fromJson(tree.toString())).isEqualTo(certificate);
    }

    @Test
    void rejectsUnknownAndMissingFields() throws Exception {
        ObjectNode tree = (ObjectNode) JSON.readTree(AttestCertificateCodec.toJson(sample(true, true)));
        ObjectNode extra = tree.deepCopy();
        extra.put("comment", "x");
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(extra.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("field set");

        ObjectNode missing = tree.deepCopy();
        missing.remove("evidence");
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(missing.toString()))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode messageExtra = tree.deepCopy();
        ((ObjectNode) messageExtra.get("message")).put("receivedAt", 1);
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(messageExtra.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("message");
    }

    @Test
    void rejectsNonCanonicalHexAndBadNumbers() throws Exception {
        ObjectNode tree = (ObjectNode) JSON.readTree(AttestCertificateCodec.toJson(sample(false, false)));
        ObjectNode upper = tree.deepCopy();
        ((ObjectNode) upper.get("subject")).put("entryHashHex", HEX32.toUpperCase());
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(upper.toString()))
                .hasMessageContaining("entryHashHex");

        ObjectNode shortId = tree.deepCopy();
        ((ObjectNode) shortId.get("message")).put("messageIdHex", "abcd");
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(shortId.toString()))
                .hasMessageContaining("messageIdHex");

        ObjectNode negative = tree.deepCopy();
        ((ObjectNode) negative.get("message")).put("height", -1);
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(negative.toString()))
                .hasMessageContaining("height");

        ObjectNode zeroHeight = tree.deepCopy();
        ((ObjectNode) zeroHeight.get("message")).put("height", 0);
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(zeroHeight.toString()))
                .hasMessageContaining("height");
    }

    @Test
    void rejectsTrailingTokensDuplicatesAndWrongStatus() throws Exception {
        String json = AttestCertificateCodec.toJson(sample(false, false));
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(json + " {}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(
                json.replaceFirst("\"chainId\" : \"chain-a\",", "\"chainId\" : \"chain-a\", \"chainId\" : \"b\",")))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode tree = (ObjectNode) JSON.readTree(json);
        tree.put("status", "ANCHORED");
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(tree.toString()))
                .hasMessageContaining("anchor reference");
        tree.put("status", "PENDING");
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(tree.toString()))
                .hasMessageContaining("status");
    }

    @Test
    void rejectsEmptyOversizedAndWrongSchema() {
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(new byte[AttestCertificateCodec.MAX_JSON_BYTES + 1]))
                .hasMessageContaining("size bound");
        String wrong = AttestCertificateCodec.toJson(sample(false, false))
                .replace(AttestCertificate.SCHEMA, "yano-x-attest-certificate-v2");
        assertThatThrownBy(() -> AttestCertificateCodec.fromJson(wrong)).hasMessageContaining("schema");
    }
}
