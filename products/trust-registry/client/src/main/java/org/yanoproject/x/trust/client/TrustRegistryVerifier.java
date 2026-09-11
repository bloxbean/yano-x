package org.yanoproject.x.trust.client;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.anchor.AnchorDatumV1;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundleCodec;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.attest.client.AttestTrust;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.AuthenticatedMapProofBundle;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Verifies a {@link StatusAnswer} offline (ADR-049 §2.2, §4). Finality is established the
 * way Attest establishes it: the answer's evidence bundle carries the certified block in
 * full, and the SDK {@code EvidenceVerifier} checks its finality certificate under the trust
 * input. The block's state root then becomes the trusted root every state proof is verified
 * against: receipt-bound answers go through the SDK's {@link AuthenticatedMapProofBundle},
 * direct-role answers additionally through {@link DirectRoleBinding}, and exclusions and
 * genesis-seeded entries are single proofs.
 */
public final class TrustRegistryVerifier {
    private static final HexFormat HEX = HexFormat.of();

    private TrustRegistryVerifier() {
    }

    public record Verification(boolean consistent, ProofLabVocabulary.TrustLevel trustLevel,
                               int certSignatures, List<String> checks, List<String> failures) {
        public Verification {
            checks = List.copyOf(checks);
            failures = List.copyOf(failures);
        }
    }

    public static Verification verify(StatusAnswer answer, AttestTrust trust) {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(trust, "trust");
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        ProofLabVocabulary.TrustLevel level = trust.level();

        List<AuthenticatedMapProofBundle.Fact> facts = decodeFacts(answer, failures);
        if (facts == null) {
            return new Verification(false, level, 0, checks, failures);
        }
        checks.add(facts.size() + " proof envelope(s) name the answer's chain, genesis, height, and root");
        AppChainClient.Proof entryProof = facts.getFirst().proof();
        if (entryProof.block() == null
                || !answer.blockHashHex().equals(entryProof.block().blockHashHex())) {
            failures.add("entry proof does not carry the answer's certified block");
            return new Verification(false, level, 0, checks, failures);
        }
        if (!entryShapeMatches(answer, facts.getFirst(), failures)) {
            return new Verification(false, level, 0, checks, failures);
        }
        checks.add("presence " + answer.presence() + " matches the entry fact");

        EvidenceVerifier.Result finality = finality(answer, trust, checks, failures);
        if (finality == null) {
            return new Verification(false, level, 0, checks, failures);
        }
        ProofVerifier.TrustedStateRoot root = new ProofVerifier.TrustedStateRoot(
                answer.chainId(), answer.profile(), answer.genesisIdHex(), answer.height(),
                answer.stateRootHex(),
                trust instanceof AttestTrust.IndependentAnchor
                        ? ProofVerifier.TrustedRootSource.CARDANO_ANCHOR
                        : ProofVerifier.TrustedRootSource.FINALITY_CERTIFICATE,
                answer.blockHashHex());

        boolean verified;
        switch (answer.provenance().kind()) {
            case RECEIPT, DIRECT_ROLE -> {
                // The SDK bundle binds entry and receipt under one root. Its DIRECT_ROLE kind
                // expects the proof envelopes' genesis id to equal the id the actor signed; on
                // a composite runtime the state identity is application-profile-bound and
                // differs from the map genesis id, so the actor-side facts are bound by
                // DirectRoleBinding with the map genesis id taken from the proven marker.
                AuthenticatedMapProofBundle bundle;
                try {
                    bundle = new AuthenticatedMapProofBundle(
                            AuthenticatedMapProofBundle.Kind.BASIC,
                            answer.chainId(), answer.profile(), answer.genesisIdHex(),
                            answer.height(), answer.stateRootHex(), answer.actionCommitment(),
                            null, facts.subList(0, 2));
                } catch (RuntimeException malformed) {
                    failures.add("proof bundle cannot be assembled: " + malformed.getMessage());
                    return new Verification(false, level, finality.certSignatures(), checks, failures);
                }
                verified = bundle.verify(root);
                if (!verified) {
                    failures.add("entry and receipt proofs do not verify against the certified root");
                    break;
                }
                checks.add("receipt binds the entry's revision and value hash");
                if (answer.provenance().kind() == StatusAnswer.ProvenanceKind.DIRECT_ROLE) {
                    verified = DirectRoleBinding.verify(answer, facts, root, checks, failures);
                }
            }
            case GENESIS, NONE -> {
                verified = ProofVerifier.verify(entryProof, root);
                if (!verified) {
                    failures.add("entry proof does not verify against the certified root");
                } else if (answer.provenance().kind() == StatusAnswer.ProvenanceKind.GENESIS) {
                    if (answer.entry().createdHeight() != 0
                            || answer.entry().lastMutationHeight() != 0) {
                        failures.add("genesis provenance claimed for an entry written after genesis");
                        verified = false;
                    } else {
                        checks.add("entry was seeded at genesis; no receipt exists");
                    }
                } else {
                    checks.add("absence proof verifies: the key has no entry at this height");
                }
            }
            default -> {
                failures.add("unknown provenance kind");
                verified = false;
            }
        }
        return new Verification(verified && failures.isEmpty(), level, finality.certSignatures(),
                checks, failures);
    }

    private static List<AuthenticatedMapProofBundle.Fact> decodeFacts(
            StatusAnswer answer, List<String> failures) {
        List<AuthenticatedMapProofBundle.Fact> facts = new ArrayList<>();
        for (StatusAnswer.Fact fact : answer.facts()) {
            AppChainClient.Proof proof;
            try {
                proof = AppChainClient.decodeProofEnvelope(fact.proofJson());
            } catch (RuntimeException malformed) {
                failures.add("fact " + fact.name() + " carries a malformed proof envelope");
                return null;
            }
            if (!answer.chainId().equals(proof.chainId())
                    || !answer.profile().equals(proof.profile())
                    || !answer.genesisIdHex().equals(proof.genesisIdHex())
                    || proof.committedHeight() == null || proof.committedHeight() != answer.height()
                    || !answer.stateRootHex().equals(proof.stateRootHex())) {
                failures.add("fact " + fact.name()
                        + " is not a proof for the answer's chain, genesis, height, and root");
                return null;
            }
            if (!HEX.formatHex(fact.expectedKey()).equals(proof.keyHex())) {
                failures.add("fact " + fact.name() + " proves a different key than it claims");
                return null;
            }
            try {
                facts.add(new AuthenticatedMapProofBundle.Fact(fact.name(), fact.expectedKey(),
                        fact.expectedValue(), proof));
            } catch (RuntimeException malformed) {
                failures.add("fact " + fact.name() + " is malformed: " + malformed.getMessage());
                return null;
            }
        }
        if (facts.isEmpty() || !AuthenticatedMapProofBundle.ENTRY.equals(facts.getFirst().name())) {
            failures.add("the first fact must be the entry");
            return null;
        }
        return facts;
    }

    private static boolean entryShapeMatches(StatusAnswer answer,
                                             AuthenticatedMapProofBundle.Fact entryFact,
                                             List<String> failures) {
        byte[] expected = entryFact.expectedValue();
        if (answer.presence() == StatusAnswer.Presence.ABSENT) {
            if (expected != null) {
                failures.add("an exclusion answer must carry an absence fact");
                return false;
            }
            return true;
        }
        if (expected == null || answer.entry() == null
                || !Arrays.equals(expected, answer.entry().encode())) {
            failures.add("the entry fact does not carry the answer's entry");
            return false;
        }
        int status = answer.entry().status();
        boolean matches = answer.presence() == StatusAnswer.Presence.ACTIVE
                ? status == AuthenticatedMapContract.STATUS_ACTIVE
                : status == AuthenticatedMapContract.STATUS_REVOKED;
        if (!matches) {
            failures.add("presence and entry status disagree");
        }
        return matches;
    }

    /** Finality of the answer's certified block under the trust input, or null with failures. */
    private static EvidenceVerifier.Result finality(StatusAnswer answer, AttestTrust trust,
                                                    List<String> checks, List<String> failures) {
        EvidenceBundle bundle;
        try {
            bundle = EvidenceBundleCodec.fromJson(answer.evidenceJson());
        } catch (RuntimeException malformed) {
            failures.add("evidence bundle is malformed");
            return null;
        }
        if (!bundle.chainId().equals(answer.chainId())) {
            failures.add("evidence bundle belongs to another chain");
            return null;
        }
        AppBlock block = bundle.blocks().stream()
                .filter(candidate -> candidate.height() == answer.height()).findFirst().orElse(null);
        if (block == null
                || !HEX.formatHex(AppBlockCodec.blockHash(block)).equals(answer.blockHashHex())
                || !HEX.formatHex(block.stateRoot()).equals(answer.stateRootHex())) {
            failures.add("evidence bundle does not carry the certified block the answer names");
            return null;
        }
        if (bundle.stateCommitment() != null) {
            StateCommitmentIdentity identity = bundle.stateCommitment().identity();
            if (!identity.profile().id().equals(answer.profile())
                    || !HEX.formatHex(identity.genesisId()).equals(answer.genesisIdHex())) {
                failures.add("evidence bundle commitment identity differs from the answer");
                return null;
            }
        }
        EvidenceVerifier.Result result;
        try {
            result = switch (trust) {
                case AttestTrust.BundleDeclared ignored ->
                        EvidenceVerifier.verifyInternalConsistencyAgainstDeclaredMembers(bundle);
                case AttestTrust.CallerPinned pinned -> {
                    if (!pinned.chainId().equals(answer.chainId())) {
                        failures.add("pinned chain id differs from the answer");
                        yield null;
                    }
                    if (pinned.profile() != null && (!pinned.profile().equals(answer.profile())
                            || !pinned.genesisIdHex().equals(answer.genesisIdHex()))) {
                        failures.add("pinned commitment identity differs from the answer");
                        yield null;
                    }
                    yield EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            pinned.chainId(), pinned.memberKeysHex(), pinned.threshold(),
                            pinned.profile(), pinned.genesisIdHex()));
                }
                case AttestTrust.IndependentAnchor anchor -> {
                    AnchorDatumV1 datum = anchor.datum();
                    List<String> problems = new ArrayList<>();
                    if (!datum.chainId().equals(answer.chainId())) problems.add("chain id");
                    if (datum.height() != answer.height()) problems.add("height");
                    if (!HEX.formatHex(datum.stateRoot()).equals(answer.stateRootHex())) {
                        problems.add("state root");
                    }
                    if (!HEX.formatHex(datum.blockHash()).equals(answer.blockHashHex())) {
                        problems.add("block hash");
                    }
                    if (!HEX.formatHex(datum.chainGenesisId()).equals(answer.genesisIdHex())) {
                        problems.add("genesis id");
                    }
                    if (!datum.commitmentProfileId().equals(answer.profile())) {
                        problems.add("commitment profile");
                    }
                    if (datum.threshold() != bundle.threshold()) problems.add("threshold");
                    if (!new TreeSet<>(datum.memberKeysHex())
                            .equals(new TreeSet<>(bundle.memberKeysHex()))) {
                        problems.add("member set");
                    }
                    String application = anchor.applicationIdOverride() != null
                            ? anchor.applicationIdOverride()
                            : AuthenticatedMapContract.STATE_MACHINE_ID;
                    if (!datum.applicationId().equals(application)) problems.add("application id");
                    if (!problems.isEmpty()) {
                        failures.add("anchor datum does not bind the answer: "
                                + String.join(", ", problems));
                        yield null;
                    }
                    Set<String> members = new LinkedHashSet<>(datum.memberKeysHex());
                    yield EvidenceVerifier.verify(bundle, new EvidenceVerifier.TrustContext(
                            datum.chainId(), members, datum.threshold(),
                            datum.commitmentProfileId(), HEX.formatHex(datum.chainGenesisId())));
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
            case AttestTrust.BundleDeclared ignored ->
                    "finality certificate consistent with the bundle's own " + result.certSignatures()
                            + " signature(s): internal consistency only";
            case AttestTrust.CallerPinned pinned ->
                    result.certSignatures() + " certificate signature(s) verified under "
                            + pinned.memberKeysHex().size() + " pinned member(s), threshold "
                            + pinned.threshold();
            case AttestTrust.IndependentAnchor ignored ->
                    "anchor datum binds this height, root, block hash, genesis, and member set; "
                            + result.certSignatures() + " signature(s) verified";
        });
        return result;
    }
}
