package org.yanoproject.x.attest.client;

import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.anchor.AnchorDatumV1;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.evidence.EvidenceBundle;
import org.yanoproject.api.appchain.evidence.EvidenceBundleCodec;
import org.yanoproject.api.appchain.proof.ProofLabVocabulary;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end attest flow on a real three-node doc-trail cluster: submit through
 * the REST bridge, wait for finality, assemble the certificate, and verify it
 * offline in every trust mode, including targeted mutations per ADR-047 §12.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttestClusterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] DOCUMENT = "Yano attest golden document\n".getBytes(StandardCharsets.UTF_8);
    private static final String API_KEY = "attest-test-key";

    private DocTrailTestCluster cluster;
    private GatewayHttpBridge bridge;
    private AttestClient client;
    private AttestCertificate certificate;
    private AttestTrust.CallerPinned pinned;

    @BeforeAll
    void startCluster() throws Exception {
        cluster = DocTrailTestCluster.start("attest-golden", 3);
        bridge = GatewayHttpBridge.start(cluster.node(2), API_KEY);
        client = AttestClient.builder(bridge.baseUrl(), cluster.chainId()).apiKey(API_KEY).build();
        AttestClient.Submission submission = client.attest(DOCUMENT,
                new AttestClient.AttestRequest("golden-series", "invoice-2026-09"));
        client.awaitFinalized(submission.messageIdHex(), Duration.ofSeconds(30));
        cluster.awaitFinalized(submission.messageIdHex());
        certificate = client.certificate(submission.messageIdHex(), new AttestClient.SubjectMetadata(
                "golden.txt", (long) DOCUMENT.length, "text/plain", "Golden fixture"));
        pinned = new AttestTrust.CallerPinned(cluster.chainId(), Set.copyOf(cluster.memberKeysHex()),
                cluster.threshold(), null, null);
    }

    @AfterAll
    void stopCluster() {
        if (bridge != null) bridge.close();
        if (cluster != null) cluster.close();
    }

    @Test
    void certificateCarriesTheSignedEnvelopeAndTrailHead() {
        assertThat(certificate.status()).isEqualTo(AttestCertificate.Status.FINALIZED);
        assertThat(certificate.applicationId()).isEqualTo("doc-trail");
        assertThat(certificate.subject().entityId()).isEqualTo("golden-series");
        assertThat(certificate.subject().entryHashHex()).isEqualTo(AttestVerifier.sha256Hex(DOCUMENT));
        assertThat(certificate.subject().reference()).isEqualTo("invoice-2026-09");
        assertThat(certificate.message().topic()).isEqualTo("doc-trail.command.v1");
        assertThat(certificate.message().senderHex()).isIn(cluster.memberKeysHex());
        assertThat(certificate.message().authProofHex()).hasSize(128);
        assertThat(certificate.trailHead()).isNotNull();
        assertThat(certificate.trailHead().revision()).isEqualTo(1);
        assertThat(certificate.anchorReference()).isNull();
        AttestCertificate decoded = AttestCertificateCodec.fromJson(AttestCertificateCodec.toJson(certificate));
        assertThat(decoded).isEqualTo(certificate);
    }

    @Test
    void verifiesInCallerPinnedMode() {
        AttestVerification result = AttestVerifier.verify(certificate, DOCUMENT, pinned);
        assertThat(result.failures()).isEmpty();
        assertThat(result.accepted()).isTrue();
        assertThat(result.digest()).isEqualTo(AttestVerification.Digest.MATCH);
        assertThat(result.commandBinding()).isEqualTo(AttestVerification.CommandBinding.BOUND);
        assertThat(result.inclusion()).isEqualTo(AttestVerification.Inclusion.INCLUDED);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.VALID);
        assertThat(result.certSignatures()).isEqualTo(3);
        assertThat(result.anchor()).isEqualTo(AttestVerification.Anchor.NONE);
        assertThat(result.trailHead()).isEqualTo(AttestVerification.TrailHead.VERIFIED);
        assertThat(result.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);
    }

    @Test
    void bundleDeclaredModeIsConsistentButNeverAccepted() {
        AttestVerification result = AttestVerifier.verify(certificate, null, AttestTrust.bundleDeclared());
        assertThat(result.failures()).isEmpty();
        assertThat(result.consistent()).isTrue();
        assertThat(result.accepted()).isFalse();
        assertThat(result.digest()).isEqualTo(AttestVerification.Digest.NOT_SUPPLIED);
        assertThat(result.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY);
    }

    @Test
    void pinnedProfileAndGenesisAreHonoured() {
        EvidenceBundle bundle = EvidenceBundleCodec.fromJson(certificate.evidenceJson());
        StateCommitmentIdentity identity = bundle.stateCommitment().identity();
        AttestTrust.CallerPinned full = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.copyOf(cluster.memberKeysHex()), cluster.threshold(), identity.profile().id(),
                HEX.formatHex(identity.genesisId()));
        assertThat(AttestVerifier.verify(certificate, DOCUMENT, full).accepted()).isTrue();
        AttestTrust.CallerPinned wrongGenesis = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.copyOf(cluster.memberKeysHex()), cluster.threshold(), identity.profile().id(),
                "00".repeat(32));
        AttestVerification result = AttestVerifier.verify(certificate, DOCUMENT, wrongGenesis);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.INVALID);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void digestMismatchFailsOnlyTheDigestCheck() {
        AttestVerification result = AttestVerifier.verify(certificate, "other".getBytes(), pinned);
        assertThat(result.digest()).isEqualTo(AttestVerification.Digest.MISMATCH);
        assertThat(result.commandBinding()).isEqualTo(AttestVerification.CommandBinding.BOUND);
        assertThat(result.inclusion()).isEqualTo(AttestVerification.Inclusion.INCLUDED);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.VALID);
        assertThat(result.trailHead()).isEqualTo(AttestVerification.TrailHead.VERIFIED);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void tamperedSubjectFailsCommandBinding() {
        AttestCertificate.Subject subject = certificate.subject();
        AttestCertificate tampered = with(certificate, new AttestCertificate.Subject(
                subject.entityId(), "11".repeat(32), subject.fileName(), subject.sizeBytes(),
                subject.mediaType(), subject.reference(), subject.label()));
        AttestVerification result = AttestVerifier.verify(tampered, null, pinned);
        assertThat(result.commandBinding()).isEqualTo(AttestVerification.CommandBinding.UNBOUND);
        assertThat(result.inclusion()).isEqualTo(AttestVerification.Inclusion.INCLUDED);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.VALID);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void tamperedEnvelopeCopyFailsCommandBinding() {
        AttestCertificate.Message message = certificate.message();
        AttestCertificate tampered = with(certificate, new AttestCertificate.Message(
                message.messageIdHex(), message.height(), message.index(), message.topic(),
                message.senderHex(), message.senderSeq(), message.expiresAt() + 1, message.bodyHex(),
                message.authScheme(), message.authProofHex()));
        AttestVerification result = AttestVerifier.verify(tampered, null, pinned);
        assertThat(result.commandBinding()).isEqualTo(AttestVerification.CommandBinding.UNBOUND);
        assertThat(result.failures()).anyMatch(reason -> reason.contains("envelope"));
    }

    @Test
    void tamperedSiblingFailsInclusionOnly() throws Exception {
        ObjectNode proof = (ObjectNode) JSON.readTree(certificate.messageProofJson());
        ArrayNode siblings = (ArrayNode) proof.get("siblings");
        ArrayNode swapped = JSON.createArrayNode();
        swapped.add("22".repeat(32));
        for (int i = 1; i < siblings.size(); i++) swapped.add(siblings.get(i));
        proof.set("siblings", swapped);
        proof.put("leafCount", Math.max(2, proof.get("leafCount").asInt()));
        AttestCertificate tampered = new AttestCertificate(certificate.generator(), certificate.issuedAt(),
                certificate.chainId(), certificate.applicationId(), certificate.status(), certificate.subject(),
                certificate.message(), proof.toString(), certificate.evidenceJson(), certificate.trailHead(),
                certificate.anchorReference());
        AttestVerification result = AttestVerifier.verify(tampered, DOCUMENT, pinned);
        assertThat(result.inclusion()).isEqualTo(AttestVerification.Inclusion.NOT_INCLUDED);
        assertThat(result.commandBinding()).isEqualTo(AttestVerification.CommandBinding.BOUND);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.VALID);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void wrongMemberSetFailsFinality() {
        List<String> members = new ArrayList<>(cluster.memberKeysHex());
        members.set(0, "33".repeat(32));
        AttestTrust.CallerPinned wrong = new AttestTrust.CallerPinned(cluster.chainId(),
                Set.copyOf(members), cluster.threshold(), null, null);
        AttestVerification result = AttestVerifier.verify(certificate, DOCUMENT, wrong);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.INVALID);
        assertThat(result.commandBinding()).isEqualTo(AttestVerification.CommandBinding.BOUND);
        assertThat(result.inclusion()).isEqualTo(AttestVerification.Inclusion.INCLUDED);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void tamperedSignatureInEvidenceFailsFinality() throws Exception {
        ObjectNode evidence = (ObjectNode) JSON.readTree(certificate.evidenceJson());
        EvidenceBundle bundle = EvidenceBundleCodec.fromJson(certificate.evidenceJson());
        AppBlock block = bundle.blocks().getFirst();
        byte[] encoded = AppBlockCodec.serialize(block);
        // The certificate signatures are the trailing bytes of the canonical block; flip one.
        encoded[encoded.length - 1] ^= 0x01;
        ArrayNode blocks = JSON.createArrayNode();
        blocks.add(HEX.formatHex(encoded));
        evidence.set("blocksCbor", blocks);
        AttestCertificate tampered = new AttestCertificate(certificate.generator(), certificate.issuedAt(),
                certificate.chainId(), certificate.applicationId(), certificate.status(), certificate.subject(),
                certificate.message(), certificate.messageProofJson(), evidence.toString(),
                certificate.trailHead(), certificate.anchorReference());
        AttestVerification result = AttestVerifier.verify(tampered, DOCUMENT, pinned);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.INVALID);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void tamperedTrailHeadFailsOnlyTheTrailCheck() {
        AttestCertificate.TrailHead head = certificate.trailHead();
        AttestCertificate tampered = new AttestCertificate(certificate.generator(), certificate.issuedAt(),
                certificate.chainId(), certificate.applicationId(), certificate.status(), certificate.subject(),
                certificate.message(), certificate.messageProofJson(), certificate.evidenceJson(),
                new AttestCertificate.TrailHead(head.stateProofJson(), head.revision() + 1, head.headDigestHex()),
                certificate.anchorReference());
        AttestVerification result = AttestVerifier.verify(tampered, DOCUMENT, pinned);
        assertThat(result.trailHead()).isEqualTo(AttestVerification.TrailHead.INVALID);
        assertThat(result.finality()).isEqualTo(AttestVerification.Finality.VALID);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void independentAnchorModeNeedsAnAnchoredSegment() {
        AnchorDatumV1 datum = syntheticDatum(certificate, "doc-trail", 0);
        AttestVerification result = AttestVerifier.verify(certificate, DOCUMENT,
                new AttestTrust.IndependentAnchor(datum, null));
        assertThat(result.anchor()).isEqualTo(AttestVerification.Anchor.INVALID);
        assertThat(result.accepted()).isFalse();
    }

    @Test
    void syntheticAnchorDatumBindsAnAnchoredCertificate() {
        AttestCertificate anchored = anchoredCopy(certificate);
        assertThat(AttestVerifier.verify(anchored, DOCUMENT, pinned).anchor())
                .isEqualTo(AttestVerification.Anchor.NODE_REFERENCE);

        AnchorDatumV1 datum = syntheticDatum(anchored, "doc-trail", 0);
        AttestVerification good = AttestVerifier.verify(anchored, DOCUMENT,
                new AttestTrust.IndependentAnchor(datum, null));
        assertThat(good.failures()).isEmpty();
        assertThat(good.anchor()).isEqualTo(AttestVerification.Anchor.INDEPENDENTLY_VERIFIED);
        assertThat(good.trustLevel()).isEqualTo(ProofLabVocabulary.TrustLevel.INDEPENDENTLY_VERIFIED_L1_ANCHOR);
        assertThat(good.accepted()).isTrue();

        // Datum for another application of the same chain
        AttestVerification otherApp = AttestVerifier.verify(anchored, DOCUMENT,
                new AttestTrust.IndependentAnchor(syntheticDatum(anchored, "kv-registry", 0), null));
        assertThat(otherApp.anchor()).isEqualTo(AttestVerification.Anchor.INVALID);
        assertThat(otherApp.failures()).anyMatch(reason -> reason.contains("application id"));

        // Caller override supplies the id when the certificate recorded none
        AttestCertificate noApp = new AttestCertificate(anchored.generator(), anchored.issuedAt(),
                anchored.chainId(), null, anchored.status(), anchored.subject(), anchored.message(),
                anchored.messageProofJson(), anchored.evidenceJson(), anchored.trailHead(),
                anchored.anchorReference());
        assertThat(AttestVerifier.verify(noApp, DOCUMENT, new AttestTrust.IndependentAnchor(datum, null))
                .anchor()).isEqualTo(AttestVerification.Anchor.INVALID);
        assertThat(AttestVerifier.verify(noApp, DOCUMENT, new AttestTrust.IndependentAnchor(datum, "doc-trail"))
                .accepted()).isTrue();

        // Datum bound to another height, state root, or member set
        for (int variant = 1; variant <= 3; variant++) {
            AttestVerification bad = AttestVerifier.verify(anchored, DOCUMENT,
                    new AttestTrust.IndependentAnchor(syntheticDatum(anchored, "doc-trail", variant), null));
            assertThat(bad.anchor()).as("variant " + variant).isEqualTo(AttestVerification.Anchor.INVALID);
            assertThat(bad.accepted()).isFalse();
        }
    }

    @Test
    void trailAndStatusReadBack() {
        AttestClient.Trail trail = client.trail("golden-series");
        assertThat(trail.present()).isTrue();
        assertThat(trail.revision()).isEqualTo(1);
        assertThat(trail.headDigestHex()).isEqualTo(certificate.trailHead().headDigestHex());
        assertThat(client.trail("missing-series").present()).isFalse();
        AttestClient.ChainStatus status = client.status();
        assertThat(status.docTrail()).isTrue();
        assertThat(status.applicationId()).isEqualTo("doc-trail");
        assertThat(status.members()).isEqualTo(3);
        assertThat(status.threshold()).isEqualTo(3);
    }

    @Test
    void secondRevisionChainsTheTrail() throws Exception {
        byte[] revised = "Yano attest golden document, revised\n".getBytes(StandardCharsets.UTF_8);
        AttestClient.Submission second = client.attest(revised,
                new AttestClient.AttestRequest("golden-series", null));
        client.awaitFinalized(second.messageIdHex(), Duration.ofSeconds(30));
        cluster.awaitFinalized(second.messageIdHex());
        AttestCertificate next = client.certificate(second.messageIdHex(), null);
        assertThat(next.trailHead()).isNotNull();
        assertThat(next.trailHead().revision()).isEqualTo(2);
        assertThat(next.subject().reference()).isNull();
        AttestVerification result = AttestVerifier.verify(next, revised, pinned);
        assertThat(result.failures()).isEmpty();
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void goldenFixturesStayVerifiable() throws Exception {
        String membersJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.createObjectNode()
                .put("chainId", cluster.chainId())
                .put("threshold", cluster.threshold())
                .set("memberKeysHex", JSON.valueToTree(cluster.memberKeysHex())));
        if (Boolean.getBoolean("yano.attest.golden.write")) {
            for (String property : List.of("yano.attest.golden.dir", "yano.attest.cli.golden.dir",
                    "yano.attest.ui.golden.dir")) {
                Path dir = Path.of(System.getProperty(property));
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("golden-certificate.json"),
                        AttestCertificateCodec.toJson(certificate) + System.lineSeparator());
                Files.writeString(dir.resolve("golden-members.json"), membersJson + System.lineSeparator());
                Files.write(dir.resolve("golden-document.txt"), DOCUMENT);
            }
        }
        Path golden = Path.of(System.getProperty("yano.attest.golden.dir"));
        if (!Files.exists(golden.resolve("golden-certificate.json"))) {
            return;
        }
        AttestCertificate committed = AttestCertificateCodec.fromJson(
                Files.readString(golden.resolve("golden-certificate.json")));
        AttestTrust.CallerPinned committedMembers = AttestTrust.CallerPinned.fromJson(
                Files.readString(golden.resolve("golden-members.json")));
        AttestVerification result = AttestVerifier.verify(committed,
                Files.readAllBytes(golden.resolve("golden-document.txt")), committedMembers);
        assertThat(result.failures()).isEmpty();
        assertThat(result.accepted()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static AttestCertificate with(AttestCertificate base, AttestCertificate.Subject subject) {
        return new AttestCertificate(base.generator(), base.issuedAt(), base.chainId(), base.applicationId(),
                base.status(), subject, base.message(), base.messageProofJson(), base.evidenceJson(),
                base.trailHead(), base.anchorReference());
    }

    private static AttestCertificate with(AttestCertificate base, AttestCertificate.Message message) {
        return new AttestCertificate(base.generator(), base.issuedAt(), base.chainId(), base.applicationId(),
                base.status(), base.subject(), message, base.messageProofJson(), base.evidenceJson(),
                base.trailHead(), base.anchorReference());
    }

    /** Re-encodes the evidence with an anchor reference on its last block, as an anchoring node would. */
    static AttestCertificate anchoredCopy(AttestCertificate base) {
        EvidenceBundle bundle = EvidenceBundleCodec.fromJson(base.evidenceJson());
        AppBlock last = bundle.blocks().getLast();
        String blockHashHex = HEX.formatHex(AppBlockCodec.blockHash(last));
        String txHash = "ab".repeat(32);
        EvidenceBundle anchored = new EvidenceBundle(bundle.chainId(), bundle.messageIdHex(), bundle.blocks(),
                bundle.memberKeysHex(), bundle.threshold(),
                new EvidenceBundle.AnchorRef(last.height(), blockHashHex, txHash, 4242),
                bundle.stateCommitment());
        AttestCertificate.AnchorReference reference = new AttestCertificate.AnchorReference(base.chainId(),
                "script", last.height(), HEX.formatHex(last.stateRoot()), blockHashHex, txHash, 4242);
        return new AttestCertificate(base.generator(), base.issuedAt(), base.chainId(), base.applicationId(),
                AttestCertificate.Status.ANCHORED, base.subject(), base.message(), base.messageProofJson(),
                EvidenceBundleCodec.toJson(anchored), base.trailHead(), reference);
    }

    /**
     * Synthetic state-thread datum bound to the segment's last block.
     * Variant 1 names another height, 2 another state root, 3 another member set.
     */
    static AnchorDatumV1 syntheticDatum(AttestCertificate base, String applicationId, int variant) {
        EvidenceBundle bundle = EvidenceBundleCodec.fromJson(base.evidenceJson());
        AppBlock last = bundle.blocks().getLast();
        StateCommitmentIdentity identity = bundle.stateCommitment().identity();
        List<byte[]> members = new ArrayList<>(bundle.memberKeysHex().stream().sorted().map(HEX::parseHex).toList());
        if (variant == 3) {
            members.set(0, HEX.parseHex("44".repeat(32)));
            members.sort((a, b) -> HEX.formatHex(a).compareTo(HEX.formatHex(b)));
        }
        byte[] stateRoot = variant == 2 ? HEX.parseHex("55".repeat(32)) : last.stateRoot();
        long height = variant == 1 ? last.height() + 1 : last.height();
        return new AnchorDatumV1(base.chainId(), identity.genesisId(), applicationId, identity.profile().id(),
                identity.profile().formatFingerprint(), height, AppBlockCodec.blockHash(last), stateRoot,
                members, bundle.threshold());
    }
}
