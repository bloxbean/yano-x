package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import org.yanoproject.x.assets.contracts.ClaimEligibility;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * The permissionless exit relayer of ADR-059 D9: it pays, by proof {@code Exit}, claims that are
 * {@code EXIT_ELIGIBLE} (D7): anchored, past {@code exitDelayMs} in L1 time since their reservation, and either the
 * anchored domain status is {@code ACTIVE} or the user claim is past its maximum age. It needs no custody key and has
 * liveness power only: the script checks every field against the anchored leaf (V1-V3), so a relayer cannot pay the
 * wrong amount, the wrong recipient, or twice (I5-I7).
 *
 * <p>Steps, as D9 lists them: select eligible claims ({@link #plan}), dropping any whose nullifier the shard mirror
 * already holds (paid by someone else, who can win that race); group them by shard, at most {@code maxExitBatch} per
 * transaction, oldest reservation first; then {@link #build} each with the claim and status proofs under the
 * referenced anchor's root. Submission and polling belong to the caller. Nothing here changes a claim's status (I12).
 */
public final class ExitRelayer {
    private ExitRelayer() {
    }

    /** A committed claim the relayer may consider. */
    public record Candidate(ClaimLeafV1 leaf, ClaimStatus status) {
        public Candidate {
            Objects.requireNonNull(leaf, "leaf");
            Objects.requireNonNull(status, "status");
        }
    }

    /** One Exit to build: claims of one shard, in payment order. */
    public record Batch(int shardIndex, List<ClaimLeafV1> claims) {
        public Batch {
            claims = List.copyOf(claims);
        }
    }

    /**
     * The eligible claims of this vault, grouped by shard and capped at {@code maxExitBatch}.
     *
     * @param anchorHeight   the referenced anchor's height
     * @param anchoredStatus the domain status leaf under that anchor's root (the one the Exit proves)
     * @param l1TimeMs       the L1 time of the transaction's validity lower bound
     * @param mirrors        the shard mirrors, by shard index
     */
    public static List<Batch> plan(List<Candidate> candidates, VaultPins pins, long anchorHeight,
                                   DomainStatusV1 anchoredStatus, long l1TimeMs, IntFunction<ShardMirror> mirrors) {
        Map<Integer, List<ClaimLeafV1>> byShard = new TreeMap<>();
        for (Candidate candidate : candidates) {
            ClaimLeafV1 leaf = candidate.leaf();
            if (!leaf.vaultId().equals(pins.vaultId())) continue;
            if (ClaimEligibility.view(leaf, candidate.status(), anchorHeight, anchoredStatus, l1TimeMs,
                    pins.maxPauseMs()) != ClaimEligibility.View.EXIT_ELIGIBLE) {
                continue;
            }
            int shard = SettlementBatchV1.shardOf(leaf.claimId());
            if (mirrors.apply(shard).contains(leaf.claimId())) continue; // its nullifier is on L1 already
            byShard.computeIfAbsent(shard, ignored -> new ArrayList<>()).add(leaf);
        }
        List<Batch> batches = new ArrayList<>();
        byShard.forEach((shard, leaves) -> {
            leaves.sort(Comparator.comparingLong(ClaimLeafV1::reservedAtMs).thenComparing(ClaimLeafV1::claimId));
            for (int from = 0; from < leaves.size(); from += pins.maxExitBatch()) {
                batches.add(new Batch(shard, leaves.subList(from, Math.min(leaves.size(), from + pins.maxExitBatch()))));
            }
        });
        return batches;
    }

    /**
     * Builds one {@code Exit}. Every proof must be of the script-computed key and carry the exact leaf bytes, or the
     * batch is refused here rather than failing on chain.
     */
    public static PayoutTxBuilder.Built build(PayoutTxBuilder.Context context, Batch batch, StateProof.Source proofs,
                                              ShardMirror mirror, TransactionEvaluator evaluator) {
        VaultPins pins = context.pins();
        List<StateProof> claimProofs = new ArrayList<>();
        for (ClaimLeafV1 leaf : batch.claims()) {
            byte[] key = concat(pins.claimKeyPrefix(), leaf.claimId().bytes());
            StateProof proof = proofs.inclusion(key)
                    .orElseThrow(() -> new PayoutTxBuilder.BuildException("no proof for claim " + leaf.claimId()));
            if (!Arrays.equals(proof.key(), key) || !Arrays.equals(proof.value(), leaf.encode())) {
                throw new PayoutTxBuilder.BuildException("the proof of claim " + leaf.claimId()
                        + " is not of its committed leaf");
            }
            claimProofs.add(proof);
        }
        StateProof status = proofs.inclusion(pins.domainStatusKey())
                .filter(proof -> Arrays.equals(proof.key(), pins.domainStatusKey()))
                .orElseThrow(() -> new PayoutTxBuilder.BuildException("no proof of the domain status leaf"));
        return PayoutTxBuilder.exit(context, batch.claims(), claimProofs, status, mirror, evaluator);
    }

    /** The ids of a batch, for journaling. */
    public static List<Hash32> ids(Batch batch) {
        return batch.claims().stream().map(ClaimLeafV1::claimId).toList();
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
