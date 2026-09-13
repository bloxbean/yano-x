package org.yanoproject.x.feed.client;

import org.yanoproject.api.appchain.proof.ProofLabVocabulary;
import org.yanoproject.x.attest.client.AttestTrust;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.feed.profile.Aggregation;
import org.yanoproject.x.feed.profile.FeedDatum;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.client.StatusAnswer;
import org.yanoproject.x.trust.client.TrustRegistryClient;
import org.yanoproject.x.trust.client.TrustRegistryVerifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline verification of a {@code feed-round-v1} bundle (ADR-052 §2.2): every answer verifies
 * under the trust input and names the bundle's chain and genesis; the feed answer and one
 * observation per configured source sit at the observation height; the round is recomputed
 * from them; and when a record is present it sits at or after that height, declares that
 * height, binds the feed value it was computed under, equals the recomputation, carries the
 * candidate datum's hash, was never rewritten, and was applied through a proven approval
 * consumption. The lowest trust level reached is the bundle's.
 */
public final class FeedVerifier {
    public static final String FLAG_RECORD_DISAGREES = "RECORD_DISAGREES";
    public static final String FLAG_WRONG_POLICY = "WRONG_POLICY";
    public static final String FLAG_DATUM_MISMATCH = "DATUM_MISMATCH";
    public static final String FLAG_FEED_PAUSED = "FEED_PAUSED";
    public static final String FLAG_REWRITTEN = "REWRITTEN";
    public static final String FLAG_UNPROVEN_APPROVAL = "UNPROVEN_APPROVAL";
    public static final String FLAG_WRONG_HEIGHT = "WRONG_HEIGHT";
    public static final String FLAG_LATER_HEIGHT = "LATER_HEIGHT";
    public static final String FLAG_REVOKED = "RECORD_REVOKED";
    private static final HexFormat HEX = HexFormat.of();

    public record Verification(boolean consistent, ProofLabVocabulary.TrustLevel trustLevel,
                               int certSignatures, List<String> checks, List<String> failures,
                               List<String> flags, Map<String, TrustRegistryVerifier.Verification> answers,
                               String status, Aggregation.Result result, FeedValues.RoundValue record) {
        public Verification {
            checks = List.copyOf(checks);
            failures = List.copyOf(failures);
            flags = List.copyOf(flags);
            answers = Map.copyOf(answers);
        }
    }

    private FeedVerifier() {
    }

    public static Verification verify(RoundBundle bundle, AttestTrust trust) {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(trust, "trust");
        List<String> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        Map<String, TrustRegistryVerifier.Verification> results = new LinkedHashMap<>();
        ProofLabVocabulary.TrustLevel level = trust.level();
        int certSignatures = Integer.MAX_VALUE;

        for (StatusAnswer answer : bundle.answers()) {
            String label = answer.collection() + "/" + (answer.keyText() != null ? answer.keyText() : answer.keyHex());
            if (!answer.chainId().equals(bundle.chainId()) || !answer.profile().equals(bundle.profile())
                    || !answer.genesisIdHex().equals(bundle.genesisIdHex())) {
                failures.add(label + " does not name the bundle's chain, profile, and genesis");
            }
            TrustRegistryVerifier.Verification result = TrustRegistryVerifier.verify(answer, trust);
            results.put(label, result);
            if (!result.consistent()) {
                for (String failure : result.failures()) {
                    failures.add(label + ": " + failure);
                }
            }
            certSignatures = Math.min(certSignatures, result.certSignatures());
            if (result.trustLevel().ordinal() < level.ordinal()) {
                level = result.trustLevel();
            }
        }

        // The feed and the observations at the observation height, one per configured source.
        long h0 = bundle.observationHeight();
        StatusAnswer feed = bundle.feed();
        if (!FeedStarterProfile.FEEDS.equals(feed.collection())
                || !Arrays.equals(feed.key(), FeedStarterProfile.feedKey(bundle.feedId()))
                || feed.height() != h0) {
            failures.add("the feed answer is not feeds/" + bundle.feedId() + " at height " + h0);
        }
        for (StatusAnswer observation : bundle.observations()) {
            if (observation.height() != h0 || !observation.stateRootHex().equals(feed.stateRootHex())) {
                failures.add("observation " + observation.keyText() + " is not answered at height " + h0
                        + " under the feed's root");
            }
        }
        FeedValues.FeedValue feedValue = bundle.feedValue();
        Aggregation.Result result = null;
        if (feedValue == null) {
            failures.add("the feed is not active or not decodable at height " + h0);
        } else {
            List<String> expected = feedValue.sources();
            if (bundle.observations().size() != expected.size()) {
                failures.add("the bundle answers " + bundle.observations().size()
                        + " observation(s) for " + expected.size() + " configured source(s)");
            } else {
                for (int index = 0; index < expected.size(); index++) {
                    StatusAnswer observation = bundle.observations().get(index);
                    byte[] key = FeedStarterProfile.observationKey(bundle.feedId(), bundle.round(), expected.get(index));
                    if (!FeedStarterProfile.OBSERVATIONS.equals(observation.collection())
                            || !Arrays.equals(observation.key(), key)) {
                        failures.add("observation " + index + " is not " + FeedStarterProfile.text(key));
                    }
                }
                if (failures.isEmpty()) {
                    result = bundle.recompute();
                }
            }
        }
        if (result != null) {
            checks.add("round " + bundle.round() + " recomputed at height " + h0 + ": " + result.statusName()
                    + (result.closed() ? " " + FeedValues.decimal(result.aggregate(), result.scale())
                    + " " + feedValue.unit() + " from " + result.acceptedSources() : "")
                    + " (" + result.candidateCount() + " candidate(s) of " + feedValue.sources().size() + ")");
        }

        // The record.
        StatusAnswer record = bundle.record();
        if (!FeedStarterProfile.ROUNDS.equals(record.collection())
                || !Arrays.equals(record.key(), FeedStarterProfile.roundKey(bundle.feedId(), bundle.round()))) {
            failures.add("the record answer is not rounds/" + bundle.feedId() + "/" + bundle.round());
        }
        if (record.height() < h0) {
            failures.add("the record is answered before the observation height");
        }
        String status = bundle.status();
        FeedValues.RoundValue recordValue = bundle.recordValue();
        switch (record.presence()) {
            case ABSENT -> {
                if (record.height() != h0) {
                    failures.add("an open round must answer its record at the observation height");
                }
                checks.add("no record at height " + record.height() + ": the round is open");
            }
            case REVOKED -> {
                flags.add(FLAG_REVOKED);
                checks.add("the record was revoked; the round has no agreed result");
            }
            case ACTIVE -> {
                if (recordValue == null) {
                    failures.add("the record is not a canonical round value");
                } else if (result != null && feedValue != null) {
                    verifyRecord(bundle, record, recordValue, feedValue, result, checks, failures, flags);
                }
                if (!approvalConsumptionVerifies(record, trust, checks, failures)) {
                    flags.add(FLAG_UNPROVEN_APPROVAL);
                }
            }
        }
        checks.add(bundle.answers().size() + " answer(s) verified: observations at height " + h0
                + " under root " + bundle.stateRootHex() + ", record at height " + record.height());
        return new Verification(failures.isEmpty(), level,
                certSignatures == Integer.MAX_VALUE ? 0 : certSignatures, checks, failures, flags,
                results, status, result, recordValue);
    }

    private static void verifyRecord(RoundBundle bundle, StatusAnswer record, FeedValues.RoundValue value,
                                     FeedValues.FeedValue feed, Aggregation.Result result,
                                     List<String> checks, List<String> failures, List<String> flags) {
        long h0 = bundle.observationHeight();
        if (record.entry().revision() != 1) {
            flags.add(FLAG_REWRITTEN);
            failures.add("the record was rewritten (revision " + record.entry().revision() + ")");
        }
        if (!feed.active()) {
            flags.add(FLAG_FEED_PAUSED);
            failures.add("the feed was paused at height " + h0 + "; a paused feed closes no rounds");
        }
        if (value.closedAtHeight() != h0) {
            // A comparison bundle: the consumer re-answered the round at another height.
            flags.add(FLAG_LATER_HEIGHT);
            checks.add("the record declares closing height " + value.closedAtHeight() + "; this bundle recomputes at "
                    + h0 + " and the recomputation " + (agrees(value, result) ? "AGREES" : "DIFFERS"));
            return;
        }
        byte[] policySha256 = FeedValues.sha256(bundle.feed().entry().value());
        if (!Arrays.equals(policySha256, value.policySha256())) {
            flags.add(FLAG_WRONG_POLICY);
            failures.add("the record binds another feed policy than the one proven at height " + h0);
        }
        if (!agrees(value, result)) {
            flags.add(FLAG_RECORD_DISAGREES);
            failures.add("the record (" + value.statusName() + " " + FeedValues.decimal(value.aggregate(), value.scale())
                    + " from " + value.acceptedSources() + ") disagrees with the recomputation (" + result.statusName()
                    + " " + FeedValues.decimal(result.aggregate(), result.scale()) + " from " + result.acceptedSources() + ")");
        } else if (value.closed()) {
            byte[] datumSha256 = FeedDatum.hash(FeedClient.datumFields(bundle.chainId(), bundle.feedId(),
                    bundle.round(), feed, result, h0, bundle.feed().stateRootHex()));
            if (!Arrays.equals(datumSha256, value.datumSha256())) {
                flags.add(FLAG_DATUM_MISMATCH);
                failures.add("the record's datum hash is not the candidate datum of this result");
            } else {
                checks.add("the record agrees with the recomputation and binds candidate datum "
                        + HEX.formatHex(datumSha256));
            }
        } else {
            checks.add("the record agrees with the recomputation: NO_QUORUM");
        }
    }

    private static boolean agrees(FeedValues.RoundValue value, Aggregation.Result result) {
        return value.status() == result.status() && value.aggregate() == result.aggregate()
                && value.scale() == result.scale() && value.acceptedSources().equals(result.acceptedSources());
    }

    /**
     * The approval-consumption fact must verify against the certified root, name the proposal
     * the applied command referenced, and bind the same message and action commitment.
     */
    private static boolean approvalConsumptionVerifies(StatusAnswer answer, AttestTrust trust,
                                                       List<String> checks, List<String> failures) {
        String label = "rounds/" + answer.keyText();
        if (answer.entry() == null || answer.provenance().messageIdHex() == null) {
            failures.add(label + " carries no receipt provenance");
            return false;
        }
        StatusAnswer.Fact fact = answer.fact(FeedClient.APPROVAL_CONSUMPTION_FACT);
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
}
