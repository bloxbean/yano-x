package org.yanoproject.x.attest.client;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import com.bloxbean.cardano.yano.api.appchain.evidence.MessageInclusionProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Offline verifier for {@link AttestCertificate}, ADR-047 §6.
 *
 * <p>Every check re-decodes the embedded node documents with the strict
 * decoders of the pinned Yano release and composes the release's own
 * primitives: {@link EvidenceVerifier} for finality, {@link MessageInclusionProof}
 * for inclusion, {@link AppBlockCodec} for block identity, {@link DocTrailContract}
 * for the command and head, {@link ProofVerifier} for the trail head, and
 * {@link AnchorDatumV1} for the independent anchor.</p>
 */
public final class AttestVerifier {
    private static final HexFormat HEX = HexFormat.of();

    private AttestVerifier() {
    }

    /**
     * @param certificate decoded certificate
     * @param fileBytes   the original document, or null when only the record is checked
     * @param trust       out-of-band trust input
     */
    public static AttestVerification verify(AttestCertificate certificate, byte[] fileBytes,
                                            AttestTrust trust) {
        Objects.requireNonNull(certificate, "certificate");
        Objects.requireNonNull(trust, "trust");
        List<String> failures = new ArrayList<>();

        AttestVerification.Digest digest = digest(certificate, fileBytes, failures);

        EvidenceBundle bundle = null;
        try {
            bundle = EvidenceBundleCodec.fromJson(certificate.evidenceJson());
            if (!bundle.chainId().equals(certificate.chainId())) {
                failures.add("evidence bundle chain id differs from the certificate");
                bundle = null;
            } else if (!bundle.messageIdHex().equals(certificate.message().messageIdHex())) {
                failures.add("evidence bundle message id differs from the certificate");
                bundle = null;
            } else if (bundle.blocks().isEmpty()) {
                failures.add("evidence bundle carries no blocks");
                bundle = null;
            }
        } catch (RuntimeException malformed) {
            failures.add("evidence bundle is malformed");
        }

        AppBlock messageBlock = bundle == null ? null : bundle.blocks().getFirst();
        AttestVerification.CommandBinding binding = commandBinding(certificate, messageBlock, failures);
        AttestVerification.Inclusion inclusion = inclusion(certificate, messageBlock, failures);
        EvidenceVerifier.Result finality = finality(bundle, certificate, trust, failures);
        AttestVerification.Anchor anchor = anchor(certificate, bundle, trust, failures);
        AttestVerification.TrailHead trailHead = trailHead(certificate, bundle, messageBlock, failures);

        return AttestVerification.of(digest, binding, inclusion,
                finality != null && finality.valid()
                        ? AttestVerification.Finality.VALID : AttestVerification.Finality.INVALID,
                finality != null && finality.valid() ? finality.certSignatures() : 0,
                anchor, trailHead, trust, failures);
    }

    /** SHA-256 of the supplied bytes as lowercase hex, the certificate's digest form. */
    public static String sha256Hex(byte[] bytes) {
        return HEX.formatHex(sha256(bytes));
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes, "bytes"));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }

    // ------------------------------------------------------------------ C1

    private static AttestVerification.Digest digest(AttestCertificate certificate, byte[] fileBytes,
                                                    List<String> failures) {
        if (fileBytes == null) {
            return AttestVerification.Digest.NOT_SUPPLIED;
        }
        if (sha256Hex(fileBytes).equals(certificate.subject().entryHashHex())) {
            return AttestVerification.Digest.MATCH;
        }
        failures.add("supplied bytes do not hash to the attested digest");
        return AttestVerification.Digest.MISMATCH;
    }

    // ------------------------------------------------------------------ C2

    private static AttestVerification.CommandBinding commandBinding(
            AttestCertificate certificate, AppBlock block, List<String> failures) {
        if (block == null) {
            failures.add("command binding skipped: no evidence block");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        AttestCertificate.Message message = certificate.message();
        if (block.height() != message.height()) {
            failures.add("first evidence block height differs from the message height");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        if (message.index() >= block.messages().size()) {
            failures.add("message index is outside the evidence block");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        AppMessage envelope = block.messages().get(message.index());
        if (!HEX.formatHex(envelope.getMessageId()).equals(message.messageIdHex())
                || !certificate.chainId().equals(envelope.getChainId())
                || !message.topic().equals(envelope.getTopic())
                || !HEX.formatHex(envelope.getSender()).equals(message.senderHex())
                || envelope.getSenderSeq() != message.senderSeq()
                || envelope.getExpiresAt() != message.expiresAt()
                || !HEX.formatHex(envelope.getBody()).equals(message.bodyHex())
                || envelope.getAuthScheme() != message.authScheme()
                || !HEX.formatHex(envelope.getAuthProof()).equals(message.authProofHex())) {
            failures.add("certificate message section differs from the evidence block envelope");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        byte[] sender = envelope.getSender();
        byte[] body = envelope.getBody();
        byte[] expectedId = AppMessage.computeMessageId(envelope.getChainId(), envelope.getTopic(),
                sender, envelope.getSenderSeq(), envelope.getExpiresAt(), body);
        if (!Arrays.equals(expectedId, envelope.getMessageId())) {
            failures.add("message id does not recompute from the signed body");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        if (envelope.getAuthScheme() != 0 || sender.length != 32
                || envelope.getAuthProof() == null || envelope.getAuthProof().length != 64) {
            failures.add("message auth proof is not an Ed25519 signature");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        byte[] signedBody = AppMessage.signedBodyBytes(envelope.getChainId(), envelope.getTopic(),
                sender, envelope.getSenderSeq(), envelope.getExpiresAt(), body);
        boolean signatureValid;
        try {
            signatureValid = CryptoConfiguration.INSTANCE.getSigningProvider()
                    .verify(envelope.getAuthProof(), signedBody, sender);
        } catch (RuntimeException failure) {
            signatureValid = false;
        }
        if (!signatureValid) {
            failures.add("message signature does not verify under the sender key");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        if (!DocTrailContract.DEFAULT_TOPIC.equals(envelope.getTopic())) {
            failures.add("message topic is not the doc-trail command topic");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        DocTrailContract.Append command;
        try {
            command = DocTrailContract.decodeCommand(body);
        } catch (RuntimeException malformed) {
            failures.add("message body is not a canonical doc-trail append command");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        AttestCertificate.Subject subject = certificate.subject();
        String reference = subject.reference() == null ? "" : subject.reference();
        if (!command.entityId().equals(subject.entityId())
                || !HEX.formatHex(command.entryHash()).equals(subject.entryHashHex())
                || !command.reference().equals(reference)) {
            failures.add("doc-trail command does not carry the certificate subject");
            return AttestVerification.CommandBinding.UNBOUND;
        }
        return AttestVerification.CommandBinding.BOUND;
    }

    // ------------------------------------------------------------------ C3

    private static AttestVerification.Inclusion inclusion(AttestCertificate certificate,
                                                          AppBlock block, List<String> failures) {
        MessageInclusionProof proof;
        try {
            proof = AppChainClient.decodeMessageProof(certificate.messageProofJson());
        } catch (RuntimeException malformed) {
            failures.add("message proof is malformed");
            return AttestVerification.Inclusion.NOT_INCLUDED;
        }
        if (block == null) {
            failures.add("inclusion skipped: no evidence block");
            return AttestVerification.Inclusion.NOT_INCLUDED;
        }
        AttestCertificate.Message message = certificate.message();
        byte[] messageId = HEX.parseHex(message.messageIdHex());
        if (proof.messageIndex() != message.index()
                || !proof.verifies(certificate.chainId(), message.height(),
                AppBlockCodec.blockHash(block), block.messagesRoot(), messageId)) {
            failures.add("message proof does not bind the message to the evidence block");
            return AttestVerification.Inclusion.NOT_INCLUDED;
        }
        return AttestVerification.Inclusion.INCLUDED;
    }

    // ------------------------------------------------------------------ C4

    private static EvidenceVerifier.Result finality(EvidenceBundle bundle, AttestCertificate certificate,
                                                    AttestTrust trust, List<String> failures) {
        if (bundle == null) {
            failures.add("finality skipped: no evidence bundle");
            return null;
        }
        EvidenceVerifier.Result result;
        try {
            result = switch (trust) {
                case AttestTrust.BundleDeclared ignored ->
                        EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(bundle);
                case AttestTrust.CallerPinned pinned -> {
                    if (!pinned.chainId().equals(certificate.chainId())) {
                        yield new EvidenceVerifier.Result(false,
                                "pinned chain id differs from the certificate", 0, false, null, false);
                    }
                    yield EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            pinned.chainId(), pinned.memberKeysHex(), pinned.threshold(),
                            pinned.profile(), pinned.genesisIdHex()));
                }
                case AttestTrust.IndependentAnchor anchor -> {
                    AnchorDatumV1 datum = anchor.datum();
                    Set<String> members = new LinkedHashSet<>();
                    for (byte[] key : datum.memberKeys()) {
                        members.add(HEX.formatHex(key));
                    }
                    yield EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            datum.chainId(), members, datum.threshold(),
                            datum.commitmentProfileId(), HEX.formatHex(datum.chainGenesisId())));
                }
            };
        } catch (RuntimeException invalidContext) {
            failures.add("trust input rejected: " + invalidContext.getMessage());
            return null;
        }
        if (!result.valid()) {
            failures.add("finality evidence invalid: " + result.failure());
        }
        return result;
    }

    // ------------------------------------------------------------------ C5

    private static AttestVerification.Anchor anchor(AttestCertificate certificate, EvidenceBundle bundle,
                                                    AttestTrust trust, List<String> failures) {
        if (bundle == null) {
            failures.add("anchor linkage skipped: no evidence bundle");
            return AttestVerification.Anchor.INVALID;
        }
        EvidenceBundle.AnchorRef evidenceAnchor = bundle.anchor();
        AttestCertificate.AnchorReference reference = certificate.anchorReference();
        if (evidenceAnchor == null) {
            if (reference != null) {
                failures.add("certificate names an anchor the evidence segment does not reach");
                return AttestVerification.Anchor.INVALID;
            }
            if (trust instanceof AttestTrust.IndependentAnchor) {
                failures.add("evidence segment carries no anchor reference; request an anchored certificate");
                return AttestVerification.Anchor.INVALID;
            }
            return AttestVerification.Anchor.NONE;
        }
        AppBlock last = bundle.blocks().getLast();
        String lastBlockHashHex = HEX.formatHex(AppBlockCodec.blockHash(last));
        String lastStateRootHex = HEX.formatHex(last.stateRoot());
        if (evidenceAnchor.anchoredHeight() != last.height()
                || !lastBlockHashHex.equals(evidenceAnchor.anchoredBlockHashHex())) {
            failures.add("evidence anchor reference does not bind the segment's last block");
            return AttestVerification.Anchor.INVALID;
        }
        if (reference != null && (!reference.chainId().equals(certificate.chainId())
                || reference.anchoredHeight() != evidenceAnchor.anchoredHeight()
                || !reference.blockHashHex().equals(evidenceAnchor.anchoredBlockHashHex())
                || !reference.transactionHash().equals(evidenceAnchor.txHash())
                || reference.l1Slot() != evidenceAnchor.l1Slot()
                || !reference.stateRootHex().equals(lastStateRootHex))) {
            failures.add("certificate anchor reference differs from the evidence anchor");
            return AttestVerification.Anchor.INVALID;
        }
        if (!(trust instanceof AttestTrust.IndependentAnchor independent)) {
            return AttestVerification.Anchor.NODE_REFERENCE;
        }
        AnchorDatumV1 datum = independent.datum();
        List<String> problems = new ArrayList<>();
        if (!datum.chainId().equals(certificate.chainId())) {
            problems.add("chain id");
        }
        if (datum.height() != last.height()) {
            problems.add("height");
        }
        if (!Arrays.equals(datum.blockHash(), AppBlockCodec.blockHash(last))) {
            problems.add("block hash");
        }
        if (!Arrays.equals(datum.stateRoot(), last.stateRoot())) {
            problems.add("state root");
        }
        if (datum.threshold() != bundle.threshold()) {
            problems.add("threshold");
        }
        if (!sortedHex(datum.memberKeys()).equals(sortedMembers(bundle.memberKeysHex()))) {
            problems.add("member set");
        }
        if (bundle.stateCommitment() == null) {
            problems.add("state commitment (bundle predates ADR-025 identity)");
        } else {
            StateCommitmentIdentity identity = bundle.stateCommitment().identity();
            if (!Arrays.equals(datum.chainGenesisId(), identity.genesisId())) {
                problems.add("genesis id");
            }
            if (!datum.commitmentProfileId().equals(identity.profile().id())) {
                problems.add("commitment profile");
            }
            if (!Arrays.equals(datum.formatFingerprint(), identity.profile().formatFingerprint())) {
                problems.add("format fingerprint");
            }
        }
        String expectedApplication = independent.applicationIdOverride() != null
                ? independent.applicationIdOverride() : certificate.applicationId();
        if (expectedApplication == null) {
            problems.add("application id unknown; supply it explicitly");
        } else if (!datum.applicationId().equals(expectedApplication)) {
            problems.add("application id");
        }
        if (!problems.isEmpty()) {
            failures.add("anchor datum does not bind the evidence segment: "
                    + String.join(", ", problems));
            return AttestVerification.Anchor.INVALID;
        }
        return AttestVerification.Anchor.INDEPENDENTLY_VERIFIED;
    }

    // ------------------------------------------------------------------ C6

    private static AttestVerification.TrailHead trailHead(AttestCertificate certificate,
                                                          EvidenceBundle bundle, AppBlock block,
                                                          List<String> failures) {
        AttestCertificate.TrailHead trail = certificate.trailHead();
        if (trail == null) {
            return AttestVerification.TrailHead.NOT_INCLUDED;
        }
        if (block == null || bundle == null) {
            failures.add("trail head skipped: no evidence block");
            return AttestVerification.TrailHead.INVALID;
        }
        AppChainClient.Proof proof;
        try {
            proof = AppChainClient.decodeProofEnvelope(trail.stateProofJson());
        } catch (RuntimeException malformed) {
            failures.add("trail head state proof is malformed");
            return AttestVerification.TrailHead.INVALID;
        }
        String expectedKeyHex = HEX.formatHex(DocTrailContract.entityKey(certificate.subject().entityId()));
        String blockHashHex = HEX.formatHex(AppBlockCodec.blockHash(block));
        String stateRootHex = HEX.formatHex(block.stateRoot());
        if (!proof.chainId().equals(certificate.chainId())
                || proof.committedHeight() == null
                || proof.committedHeight() != certificate.message().height()
                || !proof.keyHex().equals(expectedKeyHex)
                || !proof.stateRootHex().equals(stateRootHex)
                || proof.block() == null
                || !proof.block().blockHashHex().equals(blockHashHex)
                || proof.presence() != AppChainClient.ProofPresence.PRESENT
                || proof.valueHex() == null) {
            failures.add("trail head proof is not pinned to the message block");
            return AttestVerification.TrailHead.INVALID;
        }
        if (bundle.stateCommitment() != null) {
            StateCommitmentIdentity identity = bundle.stateCommitment().identity();
            if (!proof.profile().equals(identity.profile().id())
                    || !proof.genesisIdHex().equals(HEX.formatHex(identity.genesisId()))) {
                failures.add("trail head proof commitment identity differs from the evidence");
                return AttestVerification.TrailHead.INVALID;
            }
        }
        boolean verified;
        try {
            verified = ProofVerifier.verify(proof, new ProofVerifier.TrustedStateRoot(
                    certificate.chainId(), proof.profile(), proof.genesisIdHex(),
                    certificate.message().height(), stateRootHex,
                    ProofVerifier.TrustedRootSource.LOCALLY_VERIFIED_BLOCK, blockHashHex));
        } catch (RuntimeException failure) {
            verified = false;
        }
        if (!verified) {
            failures.add("trail head proof does not verify against the certified state root");
            return AttestVerification.TrailHead.INVALID;
        }
        DocTrailContract.Head head;
        try {
            head = DocTrailContract.decodeHead(HEX.parseHex(proof.valueHex()));
        } catch (RuntimeException malformed) {
            failures.add("trail head value is not a doc-trail head");
            return AttestVerification.TrailHead.INVALID;
        }
        if (head.count() != trail.revision()
                || !HEX.formatHex(head.headHash()).equals(trail.headDigestHex())) {
            failures.add("trail head revision or digest differs from the proven value");
            return AttestVerification.TrailHead.INVALID;
        }
        return AttestVerification.TrailHead.VERIFIED;
    }

    private static List<String> sortedHex(List<byte[]> keys) {
        return keys.stream().map(HEX::formatHex).sorted().toList();
    }

    private static List<String> sortedMembers(List<String> keys) {
        return keys.stream().map(key -> key.toLowerCase(java.util.Locale.ROOT))
                .sorted(Comparator.naturalOrder()).toList();
    }
}
