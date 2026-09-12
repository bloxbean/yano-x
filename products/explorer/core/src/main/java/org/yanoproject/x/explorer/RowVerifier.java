package org.yanoproject.x.explorer;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.anchor.AnchorDatumV1;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.evidence.EvidenceBundle;
import org.yanoproject.api.appchain.evidence.EvidenceBundleCodec;
import org.yanoproject.api.appchain.evidence.EvidenceVerifier;
import org.yanoproject.api.appchain.evidence.MessageInclusionProof;
import org.yanoproject.api.appchain.proof.ProofLabVocabulary;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.transition.FinalizedBlockMessageRootIndex;
import org.yanoproject.x.attest.client.AttestTrust;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Offline verification of ADR-050 §2.5 bundles. Finality is established through the evidence
 * bundle under the caller's trust input (ADR-047 levels); the verified block's state root then
 * anchors every native proof through {@code ProofVerifier.verify}.
 */
public final class RowVerifier {
    private static final HexFormat HEX = HexFormat.of();

    private RowVerifier() {
    }

    public record Verification(boolean consistent, ProofLabVocabulary.TrustLevel trustLevel,
                               int certSignatures, List<String> checks, List<String> failures) {
        public Verification {
            checks = List.copyOf(checks);
            failures = List.copyOf(failures);
        }
    }

    private record Finality(AppBlock block, EvidenceVerifier.Result result, EvidenceBundle bundle) { }

    public static Verification verify(Bundles.RowBundle bundle, AttestTrust trust) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(trust, "trust");
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        ProofLabVocabulary.TrustLevel level = trust.level();
        Finality finality = finality(bundle.chainId(), bundle.applicationId(), bundle.profile(),
                bundle.stateGenesisIdHex(), bundle.height(), bundle.blockHashHex(), bundle.stateRootHex(),
                bundle.evidenceJson(), trust, checks, failures);
        if (finality == null) return new Verification(false, level, 0, checks, failures);
        AppBlock block = finality.block();

        MessageInclusionProof proof;
        try {
            proof = AppChainClient.decodeMessageProof(bundle.inclusionProofJson());
        } catch (RuntimeException malformed) {
            failures.add("inclusion proof is malformed: " + malformed.getMessage());
            return new Verification(false, level, finality.result().certSignatures(), checks, failures);
        }
        byte[] messageId = HEX.parseHex(bundle.message().messageIdHex());
        if (proof.messageIndex() != bundle.index()
                || !proof.verifies(bundle.chainId(), bundle.height(), AppBlockCodec.blockHash(block),
                        block.messagesRoot(), messageId)) {
            failures.add("inclusion proof does not bind the message to the certified block at index " + bundle.index());
        } else {
            checks.add("compact path recomputes the certified block's messages root at index " + bundle.index());
        }

        if (bundle.index() >= block.messages().size()) {
            failures.add("the certified block has no message at index " + bundle.index());
        } else {
            AppMessage canonical = block.messages().get(bundle.index());
            IndexedMessage copy = bundle.message();
            boolean same = canonical.getMessageIdHex().equals(copy.messageIdHex())
                    && canonical.getTopic().equals(copy.topic())
                    && HEX.formatHex(canonical.getSender()).equals(copy.senderHex())
                    && canonical.getSenderSeq() == copy.senderSeq()
                    && (copy.state() == IndexedMessage.State.JSON
                        || (canonical.getExpiresAt() == copy.expiresAt()
                            && canonical.getAuthScheme() == copy.authScheme()
                            && HEX.formatHex(canonical.getAuthProof()).equals(copy.authProofHex())))
                    && HEX.formatHex(canonical.getBody()).equals(copy.bodyHex());
            if (!same) {
                failures.add("the bundle's message copy differs from the certified block's envelope");
            } else if (copy.state() == IndexedMessage.State.TOMBSTONE) {
                checks.add("the message is a retention tombstone; its id and position are certified, its body is not retained");
            } else if (!canonical.hasValidMessageId()) {
                failures.add("the certified envelope's id does not recompute from its signed body");
            } else {
                checks.add("message copy equals the certified envelope; id recomputes from the signed body"
                        + (finality.result().messageContentVerified() ? "; sender signature verified" : ""));
            }
        }

        if (!bundle.blockRecordProofJson().isEmpty()) {
            AppChainClient.Proof record;
            try {
                record = AppChainClient.decodeProofEnvelope(bundle.blockRecordProofJson());
            } catch (RuntimeException malformed) {
                failures.add("block record proof is malformed: " + malformed.getMessage());
                record = null;
            }
            if (record != null) {
                ProofVerifier.TrustedStateRoot root = trustedRoot(bundle.chainId(), bundle.profile(),
                        bundle.stateGenesisIdHex(), bundle.height(), bundle.stateRootHex(), bundle.blockHashHex(),
                        trust, failures);
                if (root != null) {
                    if (!ProofVerifier.verify(record, root)) {
                        failures.add("block record proof does not verify against the certified state root");
                    } else if (record.presence() != AppChainClient.ProofPresence.PRESENT) {
                        failures.add("block record proof proves absence; the chain has no block record at this height");
                    } else {
                        try {
                            FinalizedBlockMessageRootIndex.BlockRecord decoded =
                                    FinalizedBlockMessageRootIndex.decode(HEX.parseHex(record.valueHex()));
                            boolean bound = decoded.height() == bundle.height()
                                    && Arrays.equals(decoded.messagesRoot(), block.messagesRoot())
                                    && decoded.messageCount() == block.messages().size();
                            if (bound) {
                                checks.add("authenticated block record [height, messagesRoot, count] verified at the certified root");
                            } else {
                                failures.add("authenticated block record differs from the certified block");
                            }
                        } catch (RuntimeException malformed) {
                            failures.add("block record value does not decode");
                        }
                    }
                }
            }
        } else {
            checks.add("no block record proof carried; the message binds to the certified header's messages root directly");
        }
        anchorNote(finality, checks);
        return new Verification(failures.isEmpty(), level, finality.result().certSignatures(), checks, failures);
    }

    public static Verification verify(Bundles.StateBundle bundle, AttestTrust trust) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(trust, "trust");
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        ProofLabVocabulary.TrustLevel level = trust.level();
        Finality finality = finality(bundle.chainId(), bundle.applicationId(), bundle.profile(),
                bundle.stateGenesisIdHex(), bundle.height(), bundle.blockHashHex(), bundle.stateRootHex(),
                bundle.evidenceJson(), trust, checks, failures);
        if (finality == null) return new Verification(false, level, 0, checks, failures);
        AppChainClient.Proof proof;
        try {
            proof = AppChainClient.decodeProofEnvelope(bundle.proofJson());
        } catch (RuntimeException malformed) {
            failures.add("state proof is malformed: " + malformed.getMessage());
            return new Verification(false, level, finality.result().certSignatures(), checks, failures);
        }
        byte[] expectedKey;
        try {
            expectedKey = Subjects.key(bundle.subjectId(), bundle.coordinates(), bundle.componentId());
        } catch (ExplorerException invalid) {
            failures.add("subject coordinates are invalid: " + invalid.getMessage());
            return new Verification(false, level, finality.result().certSignatures(), checks, failures);
        }
        if (!HEX.formatHex(expectedKey).equals(proof.keyHex()) || !bundle.keyHex().equals(proof.keyHex())) {
            failures.add("the proof's key is not the canonical key of the subject coordinates");
        } else {
            checks.add("proof key equals the canonical key derived from the coordinates");
        }
        ProofVerifier.TrustedStateRoot root = trustedRoot(bundle.chainId(), bundle.profile(),
                bundle.stateGenesisIdHex(), bundle.height(), bundle.stateRootHex(), bundle.blockHashHex(),
                trust, failures);
        if (root != null) {
            if (!ProofVerifier.verify(proof, root)) {
                failures.add("state proof does not verify against the certified state root");
            } else {
                checks.add("native " + proof.profile() + " proof verified at the certified root (" + proof.presence() + ")");
                if (proof.presence() == AppChainClient.ProofPresence.PRESENT) {
                    try {
                        Map<String, Object> decoded = Subjects.decode(bundle.subjectId(), HEX.parseHex(proof.valueHex()));
                        if (sameFact(decoded, bundle.decodedFact())) {
                            checks.add("decoded fact equals the bundle's decoded fact");
                        } else {
                            failures.add("the bundle's decoded fact differs from the proof-carried value");
                        }
                    } catch (RuntimeException malformed) {
                        failures.add("proof-carried value does not decode as " + bundle.subjectId());
                    }
                } else if (!bundle.decodedFact().isEmpty()) {
                    failures.add("the bundle carries a fact for an absent subject");
                } else {
                    checks.add("the subject is absent at this root (raw key exclusion)");
                }
            }
        }
        anchorNote(finality, checks);
        return new Verification(failures.isEmpty(), level, finality.result().certSignatures(), checks, failures);
    }

    private static boolean sameFact(Map<String, Object> decoded, Map<String, Object> claimed) {
        if (decoded.size() != claimed.size()) return false;
        for (Map.Entry<String, Object> entry : decoded.entrySet()) {
            Object other = claimed.get(entry.getKey());
            if (other == null || !String.valueOf(entry.getValue()).equals(String.valueOf(other))) return false;
        }
        return true;
    }

    private static ProofVerifier.TrustedStateRoot trustedRoot(String chainId, String profile, String genesisIdHex,
                                                              long height, String stateRootHex, String blockHashHex,
                                                              AttestTrust trust, List<String> failures) {
        try {
            ProofVerifier.TrustedRootSource source = trust instanceof AttestTrust.IndependentAnchor
                    ? ProofVerifier.TrustedRootSource.CARDANO_ANCHOR
                    : ProofVerifier.TrustedRootSource.FINALITY_CERTIFICATE;
            return new ProofVerifier.TrustedStateRoot(chainId, profile, genesisIdHex, height, stateRootHex,
                    source, blockHashHex);
        } catch (RuntimeException invalid) {
            failures.add("trusted root rejected: " + invalid.getMessage());
            return null;
        }
    }

    private static void anchorNote(Finality finality, List<String> checks) {
        if (finality.result().anchorTxHash() != null) {
            checks.add("evidence names anchor transaction " + finality.result().anchorTxHash()
                    + (finality.result().anchoredToL1() ? " and the signed segment reaches the anchored block"
                    : "; the segment does not reach the anchored block"));
        }
    }

    /** Finality of the certified block under the trust input, or null with failures recorded. */
    private static Finality finality(String chainId, String applicationId, String profile, String genesisIdHex,
                                     long height, String blockHashHex, String stateRootHex, String evidenceJson,
                                     AttestTrust trust, List<String> checks, List<String> failures) {
        EvidenceBundle bundle;
        try {
            bundle = EvidenceBundleCodec.fromJson(evidenceJson);
        } catch (RuntimeException malformed) {
            failures.add("evidence bundle is malformed");
            return null;
        }
        if (!bundle.chainId().equals(chainId)) {
            failures.add("evidence bundle belongs to another chain");
            return null;
        }
        AppBlock block = bundle.blocks().stream().filter(candidate -> candidate.height() == height)
                .findFirst().orElse(null);
        if (block == null || !HEX.formatHex(AppBlockCodec.blockHash(block)).equals(blockHashHex)
                || !HEX.formatHex(block.stateRoot()).equals(stateRootHex)) {
            failures.add("evidence bundle does not carry the certified block the bundle names");
            return null;
        }
        if (bundle.stateCommitment() != null) {
            StateCommitmentIdentity identity = bundle.stateCommitment().identity();
            if (!identity.profile().id().equals(profile) || !HEX.formatHex(identity.genesisId()).equals(genesisIdHex)) {
                failures.add("evidence bundle commitment identity differs from the bundle");
                return null;
            }
        }
        EvidenceVerifier.Result result;
        try {
            result = switch (trust) {
                case AttestTrust.BundleDeclared ignored ->
                        EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(bundle);
                case AttestTrust.CallerPinned pinned -> {
                    if (!pinned.chainId().equals(chainId)) {
                        failures.add("pinned chain id differs from the bundle");
                        yield null;
                    }
                    if (pinned.profile() != null && (!pinned.profile().equals(profile)
                            || !pinned.genesisIdHex().equals(genesisIdHex))) {
                        failures.add("pinned commitment identity differs from the bundle");
                        yield null;
                    }
                    yield EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            pinned.chainId(), pinned.memberKeysHex(), pinned.threshold(),
                            pinned.profile(), pinned.genesisIdHex()));
                }
                case AttestTrust.IndependentAnchor anchor -> {
                    AnchorDatumV1 datum = anchor.datum();
                    List<String> problems = new ArrayList<>();
                    if (!datum.chainId().equals(chainId)) problems.add("chain id");
                    if (datum.height() != height) problems.add("height");
                    if (!HEX.formatHex(datum.stateRoot()).equals(stateRootHex)) problems.add("state root");
                    if (!HEX.formatHex(datum.blockHash()).equals(blockHashHex)) problems.add("block hash");
                    if (!HEX.formatHex(datum.chainGenesisId()).equals(genesisIdHex)) problems.add("genesis id");
                    if (!datum.commitmentProfileId().equals(profile)) problems.add("commitment profile");
                    if (datum.threshold() != bundle.threshold()) problems.add("threshold");
                    if (!new TreeSet<>(datum.memberKeysHex()).equals(new TreeSet<>(bundle.memberKeysHex()))) {
                        problems.add("member set");
                    }
                    String application = anchor.applicationIdOverride() != null
                            ? anchor.applicationIdOverride() : applicationId;
                    if (!application.isEmpty() && !datum.applicationId().equals(application)) {
                        problems.add("application id");
                    }
                    if (!problems.isEmpty()) {
                        failures.add("anchor datum does not bind the bundle: " + String.join(", ", problems));
                        yield null;
                    }
                    Set<String> members = new LinkedHashSet<>(datum.memberKeysHex());
                    yield EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            datum.chainId(), members, datum.threshold(), datum.commitmentProfileId(),
                            HEX.formatHex(datum.chainGenesisId())));
                }
            };
        } catch (RuntimeException invalid) {
            failures.add("trust input rejected: " + invalid.getMessage());
            return null;
        }
        if (result == null) return null;
        if (!result.valid()) {
            failures.add("finality evidence invalid: " + result.failure());
            return null;
        }
        checks.add(switch (trust) {
            case AttestTrust.BundleDeclared ignored -> "finality certificate consistent with the bundle's own "
                    + result.certSignatures() + " signature(s): internal consistency only";
            case AttestTrust.CallerPinned pinned -> result.certSignatures()
                    + " certificate signature(s) verified under " + pinned.memberKeysHex().size()
                    + " pinned member(s), threshold " + pinned.threshold();
            case AttestTrust.IndependentAnchor ignored -> "anchor datum binds this height, root, block hash, genesis,"
                    + " and member set; " + result.certSignatures() + " signature(s) verified";
        });
        return new Finality(block, result, bundle);
    }
}
