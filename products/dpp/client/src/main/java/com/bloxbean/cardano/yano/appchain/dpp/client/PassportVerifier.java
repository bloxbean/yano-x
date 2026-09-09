package com.bloxbean.cardano.yano.appchain.dpp.client;

import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.appchain.attest.client.AttestTrust;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppStarterProfile;
import com.bloxbean.cardano.yano.appchain.dpp.profile.DppValues;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.StatusAnswer;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryClient;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistryVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline verification of a {@code dpp-passport-v1} bundle (ADR-051 §2.2): every answer verifies
 * under the trust input, all answers name the bundle's chain, genesis, height, root, and block,
 * every key belongs to the product, a certificate's approval consumption verifies and names the
 * applied message, and the timeline agrees with the answered entries. The lowest trust level
 * reached is the bundle's.
 */
public final class PassportVerifier {
    private static final HexFormat HEX = HexFormat.of();

    public record Verification(boolean consistent, ProofLabVocabulary.TrustLevel trustLevel,
                               int certSignatures, List<String> checks, List<String> failures,
                               Map<String, TrustRegistryVerifier.Verification> answers) {
        public Verification {
            checks = List.copyOf(checks);
            failures = List.copyOf(failures);
            answers = Map.copyOf(answers);
        }
    }

    private PassportVerifier() {
    }

    public static Verification verify(PassportBundle bundle, AttestTrust trust) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(trust, "trust");
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, TrustRegistryVerifier.Verification> results = new LinkedHashMap<>();
        ProofLabVocabulary.TrustLevel level = trust.level();
        int certSignatures = Integer.MAX_VALUE;
        boolean consistent = true;

        for (StatusAnswer answer : bundle.answers()) {
            String label = answer.collection() + "/" + (answer.keyText() != null ? answer.keyText() : answer.keyHex());
            if (!answer.chainId().equals(bundle.chainId()) || !answer.profile().equals(bundle.profile())
                    || !answer.genesisIdHex().equals(bundle.genesisIdHex())
                    || answer.height() != bundle.height()
                    || !answer.stateRootHex().equals(bundle.stateRootHex())
                    || !answer.blockHashHex().equals(bundle.blockHashHex())) {
                failures.add(label + " does not name the passport's chain, genesis, height, root, and block");
                consistent = false;
            }
            if (!belongs(bundle, answer)) {
                failures.add(label + " does not belong to product " + bundle.productId());
                consistent = false;
            }
            TrustRegistryVerifier.Verification result = TrustRegistryVerifier.verify(answer, trust);
            results.put(label, result);
            if (!result.consistent()) {
                consistent = false;
                for (String failure : result.failures()) {
                    failures.add(label + ": " + failure);
                }
            }
            certSignatures = Math.min(certSignatures, result.certSignatures());
            if (result.trustLevel().ordinal() < level.ordinal()) {
                level = result.trustLevel();
            }
            if (DppStarterProfile.CERTIFICATES.equals(answer.collection())
                    && !approvalConsumptionVerifies(answer, trust, label, checks, failures)) {
                consistent = false;
            }
        }
        checks.add(bundle.answers().size() + " answer(s) verified at height " + bundle.height()
                + " under root " + bundle.stateRootHex());

        if (!timelineAgrees(bundle, failures)) {
            consistent = false;
        } else {
            checks.add("timeline heights agree with the answered entries");
        }
        return new Verification(consistent && failures.isEmpty(), level,
                certSignatures == Integer.MAX_VALUE ? 0 : certSignatures, checks, failures, results);
    }

    private static boolean belongs(PassportBundle bundle, StatusAnswer answer) {
        String productId = DppStarterProfile.productIdOf(answer.collection(), answer.key());
        if (productId != null) {
            return productId.equals(bundle.productId());
        }
        if (!DppStarterProfile.CERTIFICATES.equals(answer.collection())) {
            return false;
        }
        if (answer.entry() != null && answer.presence() == StatusAnswer.Presence.ACTIVE) {
            try {
                return DppValues.CertificateValue.decode(answer.entry().value()).productId()
                        .equals(bundle.productId());
            } catch (RuntimeException malformed) {
                return false;
            }
        }
        // A revoked or absent certificate carries no value; the timeline attributes it.
        return bundle.timeline().stream().anyMatch(applied ->
                applied.collection().equals(answer.collection())
                        && Arrays.equals(applied.key(), answer.key()));
    }

    /**
     * The approval-consumption fact must verify against the certified root, name the proposal
     * the applied command referenced, and bind the same message and action commitment.
     */
    private static boolean approvalConsumptionVerifies(StatusAnswer answer, AttestTrust trust,
                                                       String label, List<String> checks,
                                                       List<String> failures) {
        if (answer.entry() == null || answer.provenance().messageIdHex() == null) {
            return true;
        }
        StatusAnswer.Fact fact = answer.fact(DppClient.APPROVAL_CONSUMPTION_FACT);
        if (fact == null) {
            failures.add(label + " carries no approval consumption fact");
            return false;
        }
        AppChainClient.Proof proof;
        AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1 consumption;
        try {
            proof = AppChainClient.decodeProofEnvelope(fact.proofJson());
            consumption = AuthenticatedMapAuthorizationContract.ApprovalConsumptionV1
                    .decode(fact.expectedValue());
        } catch (RuntimeException malformed) {
            failures.add(label + ": approval consumption fact is malformed");
            return false;
        }
        byte[] expectedKey = TrustRegistryClient.componentKey(TrustRegistryClient.COMPONENT_MAP,
                AuthenticatedMapContract.approvalConsumptionKey(consumption.proposalId()));
        if (!Arrays.equals(expectedKey, fact.expectedKey())
                || !HEX.formatHex(expectedKey).equals(proof.keyHex())
                || proof.valueHex() == null
                || !Arrays.equals(HEX.parseHex(proof.valueHex()), fact.expectedValue())) {
            failures.add(label + ": approval consumption fact proves a different key or value");
            return false;
        }
        if (!HEX.formatHex(consumption.messageId()).equals(answer.provenance().messageIdHex())
                || !Arrays.equals(consumption.actionCommitment(), answer.actionCommitment())) {
            failures.add(label + ": approval consumption names another message or action");
            return false;
        }
        ProofVerifier.TrustedStateRoot root = new ProofVerifier.TrustedStateRoot(
                answer.chainId(), answer.profile(), answer.genesisIdHex(), answer.height(),
                answer.stateRootHex(),
                trust instanceof AttestTrust.IndependentAnchor
                        ? ProofVerifier.TrustedRootSource.CARDANO_ANCHOR
                        : ProofVerifier.TrustedRootSource.FINALITY_CERTIFICATE,
                answer.blockHashHex());
        if (!ProofVerifier.verify(proof, root)) {
            failures.add(label + ": approval consumption proof does not verify against the certified root");
            return false;
        }
        checks.add(label + ": proposal " + consumption.proposalId()
                + " consumed once under policy " + consumption.policyId()
                + " revision " + consumption.policyRevision());
        return true;
    }

    /** Each answered entry's creation and last-mutation heights appear in the timeline for its key. */
    private static boolean timelineAgrees(PassportBundle bundle, List<String> failures) {
        boolean agrees = true;
        for (StatusAnswer answer : bundle.answers()) {
            if (answer.entry() == null || answer.entry().lastMutationHeight() == 0) continue;
            long created = answer.entry().createdHeight();
            long last = answer.entry().lastMutationHeight();
            boolean createdSeen = false;
            boolean lastSeen = false;
            for (PassportProjection.Applied applied : bundle.timeline()) {
                if (!applied.collection().equals(answer.collection())
                        || !Arrays.equals(applied.key(), answer.key())) continue;
                createdSeen |= applied.height() == created;
                lastSeen |= applied.height() == last;
            }
            if (!createdSeen || !lastSeen) {
                failures.add(answer.collection() + "/" + answer.keyHex()
                        + ": the timeline does not show the entry's creation and last mutation heights");
                agrees = false;
            }
        }
        for (PassportProjection.Applied applied : bundle.timeline()) {
            if (applied.height() > bundle.height()) {
                failures.add("the timeline reaches beyond the passport height");
                agrees = false;
            }
        }
        return agrees;
    }
}
