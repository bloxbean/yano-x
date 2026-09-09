package com.bloxbean.cardano.yano.appchain.attest.client;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link AttestVerifier#verify}: one result per ADR-047 §6 check,
 * the trust level the inputs allowed, and the derived verdicts.
 *
 * @param certSignatures number of valid member signatures on the weakest block
 *                       of the verified segment, or zero when finality failed
 * @param accepted       true when every check passed and the trust level is
 *                       above internal consistency
 * @param consistent     true when every check passed regardless of trust level
 * @param failures       human-readable reasons for every failed check
 */
public record AttestVerification(Digest digest,
                                 CommandBinding commandBinding,
                                 Inclusion inclusion,
                                 Finality finality,
                                 int certSignatures,
                                 Anchor anchor,
                                 TrailHead trailHead,
                                 ProofLabVocabulary.TrustLevel trustLevel,
                                 boolean accepted,
                                 boolean consistent,
                                 List<String> failures) {

    public AttestVerification {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(commandBinding, "commandBinding");
        Objects.requireNonNull(inclusion, "inclusion");
        Objects.requireNonNull(finality, "finality");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(trailHead, "trailHead");
        Objects.requireNonNull(trustLevel, "trustLevel");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    }

    public enum Digest { NOT_SUPPLIED, MATCH, MISMATCH }

    public enum CommandBinding { BOUND, UNBOUND }

    public enum Inclusion { INCLUDED, NOT_INCLUDED }

    public enum Finality { VALID, INVALID }

    public enum Anchor { NONE, NODE_REFERENCE, INDEPENDENTLY_VERIFIED, INVALID }

    public enum TrailHead { NOT_INCLUDED, VERIFIED, INVALID }

    static AttestVerification of(Digest digest, CommandBinding commandBinding, Inclusion inclusion,
                                 Finality finality, int certSignatures, Anchor anchor,
                                 TrailHead trailHead, AttestTrust trust, List<String> failures) {
        boolean checksPass = digest != Digest.MISMATCH
                && commandBinding == CommandBinding.BOUND
                && inclusion == Inclusion.INCLUDED
                && finality == Finality.VALID
                && anchor != Anchor.INVALID
                && trailHead != TrailHead.INVALID;
        if (trust instanceof AttestTrust.IndependentAnchor
                && anchor != Anchor.INDEPENDENTLY_VERIFIED) {
            checksPass = false;
        }
        ProofLabVocabulary.TrustLevel level = trust.level();
        boolean accepted = checksPass
                && level != ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY;
        return new AttestVerification(digest, commandBinding, inclusion, finality,
                certSignatures, anchor, trailHead, level, accepted, checksPass, failures);
    }
}
