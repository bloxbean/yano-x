package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.appchain.dpp.profile.DppStarterProfile;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppValues;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistrySigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DppCodecsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final String PRODUCT = "gtin:09506000134352";

    @TempDir
    Path temp;

    @Test
    void certificationRequestRoundTripsAndRefusesATamperedPayloadHash() {
        byte[] genesisId = HEX.parseHex("11".repeat(32));
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.putIfAbsent(DppStarterProfile.CERTIFICATES,
                        DppStarterProfile.certificateKey("cert-1"),
                        new DppValues.CertificateValue(PRODUCT, "EU-Ecodesign", "cert-body-a",
                                new byte[32], 0, 0).encode()));
        var action = TrustRegistrySigner.approvalAction(command, DppStarterProfile.CERTIFICATION_POLICY);
        String payloadHash = HEX.formatHex(TrustRegistrySigner.approvalPayloadHash(genesisId, action));
        CertificationRequest request = new CertificationRequest("dpp-starter-chain",
                HEX.formatHex(genesisId), DppStarterProfile.CERTIFICATION_POLICY, 1, "cert-abcd",
                HEX.formatHex(AuthenticatedMapContract.encodeCommand(command)), payloadHash, 120,
                PRODUCT, "cert-1", "PUT_IF_ABSENT", "");
        assertThat(request.consistent()).isTrue();
        CertificationRequest parsed = CertificationRequest.fromJson(request.toJson());
        assertThat(parsed).isEqualTo(request);
        assertThat(parsed.command().mutations()).hasSize(1);

        ObjectNode tampered = request.toJsonNode();
        tampered.put("payloadHash", "22".repeat(32));
        assertThatThrownBy(() -> CertificationRequest.fromJsonNode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload hash");
        ObjectNode wrongType = request.toJsonNode();
        wrongType.put("type", "other");
        assertThatThrownBy(() -> CertificationRequest.fromJsonNode(wrongType))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disclosureRoundTripsAndCommits() {
        Disclosure disclosure = Disclosure.create("c", PRODUCT, "carbon-footprint", "cf-1", "12.5 kgCO2e");
        Disclosure parsed = Disclosure.fromJson(disclosure.toJson());
        assertThat(parsed).isEqualTo(disclosure);
        assertThat(parsed.commitment()).isEqualTo(
                DppValues.claimCommitment(HEX.parseHex(disclosure.saltHex()), "12.5 kgCO2e"));
        assertThat(disclosure.toJsonNode().path("commitment").asText())
                .isEqualTo(HEX.formatHex(disclosure.commitment()));
        assertThatThrownBy(() -> new Disclosure("c", PRODUCT, "t", "id", "zz", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectionAttributesCertificatesToTheirProductAndOrdersTheTimeline() {
        PassportProjection projection = new PassportProjection();
        projection.apply(1, 0, "aa".repeat(32), AuthenticatedMapContract.Mutation.putIfAbsent(
                DppStarterProfile.PRODUCTS, DppStarterProfile.productKey(PRODUCT),
                new DppValues.ProductValue("acme", 0, 0, "", "p").encode()));
        projection.apply(2, 0, "bb".repeat(32), AuthenticatedMapContract.Mutation.putIfAbsent(
                DppStarterProfile.CERTIFICATES, DppStarterProfile.certificateKey("cert-1"),
                new DppValues.CertificateValue(PRODUCT, "type", "cert-body-a", new byte[32], 0, 0).encode()));
        projection.apply(3, 1, "cc".repeat(32), AuthenticatedMapContract.Mutation.putIfAbsent(
                DppStarterProfile.EVENTS, DppStarterProfile.eventKey("gtin:00000000000001", "e-1"),
                new DppValues.EventValue("SHIPPED", "org", 1, "", null, "").encode()));
        projection.apply(4, 0, "dd".repeat(32), AuthenticatedMapContract.Mutation.revoke(
                DppStarterProfile.CERTIFICATES, DppStarterProfile.certificateKey("cert-1"), 1, null));
        // An unknown certificate revocation cannot be attributed and is dropped.
        projection.apply(5, 0, "ee".repeat(32), AuthenticatedMapContract.Mutation.revoke(
                DppStarterProfile.CERTIFICATES, DppStarterProfile.certificateKey("cert-x"), 1, null));
        projection.markReplayed(5);

        assertThat(projection.timeline(PRODUCT, 5)).extracting(PassportProjection.Applied::operation)
                .containsExactly("PUT_IF_ABSENT", "PUT_IF_ABSENT", "REVOKE");
        assertThat(projection.timeline(PRODUCT, 3)).hasSize(2);
        assertThat(projection.timeline("gtin:00000000000001", 5)).hasSize(1);
        PassportProjection.ProductKeys keys = projection.keysAt(PRODUCT, 5);
        assertThat(keys.certificates()).containsOnlyKeys("cert-1");
        assertThat(keys.events()).isEmpty();
        assertThat(projection.appliedCount()).isEqualTo(4);
        assertThat(projection.replayedHeight()).isEqualTo(5);
    }

    @Test
    void documentStoreServesBytesByTheirHashOnly() throws Exception {
        DocumentStore store = new DocumentStore(temp.resolve("documents"));
        byte[] body = "passport v1".getBytes(StandardCharsets.UTF_8);
        String sha = store.put(body);
        assertThat(sha).isEqualTo(HEX.formatHex(DppValues.sha256(body)));
        assertThat(store.has(sha)).isTrue();
        assertThat(store.get(sha)).contains(body);
        assertThat(store.get("00".repeat(32))).isEmpty();
        assertThat(store.has("nope")).isFalse();
        Files.writeString(temp.resolve("documents").resolve(sha), "corrupted");
        assertThat(store.get(sha)).as("a corrupted file is never served").isEmpty();
        assertThat(store.put(body)).isEqualTo(sha);
    }

    @Test
    void passportBundleRefusesForeignDocuments() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("type", "other");
        assertThatThrownBy(() -> PassportBundle.fromJsonNode(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
        root.put("type", PassportBundle.TYPE);
        root.putArray("answers");
        assertThatThrownBy(() -> PassportBundle.fromJsonNode(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("answers");
        assertThatThrownBy(() -> PassportBundle.fromJson("{"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
