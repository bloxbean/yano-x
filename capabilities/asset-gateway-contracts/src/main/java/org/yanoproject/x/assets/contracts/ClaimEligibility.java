package org.yanoproject.x.assets.contracts;

import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.util.Objects;

/**
 * The derived claim views of ADR-059 D7, computed off chain by the executor, the relayer and the domain API. They
 * are views only: nothing here changes a claim's consensus status (I12).
 *
 * <ul>
 *   <li>{@link View#EXIT_ELIGIBLE}: the claim is {@code RESERVED} and anchored ({@code reservedHeight <=} the anchor
 *       height), {@code reservedAtMs + exitDelayMs <= l1TimeMs}, and either the domain status leaf under that anchor
 *       is {@code ACTIVE}, or the claim is a user claim past its maximum age
 *       ({@code reservedAtMs + exitDelayMs + maxPauseMs <= l1TimeMs}; V3, D8a). {@code exitDelayMs} is the anchored
 *       status leaf's.</li>
 *   <li>{@link View#SETTLEMENT_PENDING}: {@code RESERVED} and not (yet) exit eligible: waiting for a co-signed
 *       batch, or for an anchor to cover it.</li>
 *   <li>{@link View#RESOLVED}: {@code PAID} or {@code PAID_MISMATCH}; nothing is payable.</li>
 * </ul>
 */
public final class ClaimEligibility {
    private ClaimEligibility() {
    }

    public enum View {
        EXIT_ELIGIBLE,
        SETTLEMENT_PENDING,
        RESOLVED
    }

    /**
     * @param anchorHeight   the referenced (live, L1-stable) anchor's app height
     * @param anchoredStatus the domain status leaf as committed at or below that height (the one an {@code Exit}
     *                       proves)
     * @param l1TimeMs       the L1 time the transaction would use as its validity lower bound
     * @param maxPauseMs     the vault script's {@code maxPauseMs} parameter
     */
    public static View view(ClaimLeafV1 leaf, ClaimStatus status, long anchorHeight, DomainStatusV1 anchoredStatus,
                            long l1TimeMs, long maxPauseMs) {
        Objects.requireNonNull(leaf, "leaf");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(anchoredStatus, "anchored status");
        if (status != ClaimStatus.RESERVED) return View.RESOLVED;
        if (leaf.reservedHeight() > anchorHeight) return View.SETTLEMENT_PENDING;
        long matured = saturatedAdd(leaf.reservedAtMs(), anchoredStatus.exitDelayMs());
        if (matured > l1TimeMs) return View.SETTLEMENT_PENDING;
        boolean active = anchoredStatus.status() == DomainStatusV1.Status.ACTIVE;
        boolean aged = leaf.kind() == ClaimKind.USER_WITHDRAWAL && saturatedAdd(matured, maxPauseMs) <= l1TimeMs;
        if (leaf.kind() != ClaimKind.USER_WITHDRAWAL && leaf.kind() != ClaimKind.SWEEP_OPERATIONAL) {
            // SWEEP_UNATTRIBUTED pays through SweepExit and MIGRATE is M4b: neither is a proof Exit claim.
            return View.SETTLEMENT_PENDING;
        }
        return active || aged ? View.EXIT_ELIGIBLE : View.SETTLEMENT_PENDING;
    }

    private static long saturatedAdd(long left, long right) {
        long sum = left + right;
        return ((left ^ sum) & (right ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
