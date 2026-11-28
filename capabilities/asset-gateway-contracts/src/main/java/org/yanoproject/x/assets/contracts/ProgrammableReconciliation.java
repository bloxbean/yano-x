package org.yanoproject.x.assets.contracts;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * ADR-061 D6 and D14(a): the L1 side of a CIP-113 vault's reconciliation. It counts as backing only the live outputs
 * that a vault action can spend: accepted custody (a continuation holding only the asset and lovelace, or a floor
 * top-up holding only lovelace, the custody scripts' V10 predicate) and deposit-shaped outputs
 * that hold only the asset and lovelace and whose {@code DepositId} is {@code CREDITED}. Everything else is reported
 * apart and never counts as backing:
 *
 * <ul>
 *   <li><b>unavailable</b>: deposit-shaped copies that also hold a foreign token, which cannot be accepted, swept or
 *       refunded while their id is credited, even when the id is {@code CREDITED}, so a shortfall hidden in them
 *       surfaces;</li>
 *   <li><b>unattributed</b>: each {@code UNCREDITED} id, once, at its recorded value, whatever outpoint its UTxO now
 *       has;</li>
 *   <li><b>donor dust</b>: {@code RECREATED} outputs whose id is absent.</li>
 * </ul>
 *
 * Reward balances of the custody credentials are not custody and are never counted (D2). Liabilities never change
 * to match custody (I36): a shortfall is only reported.
 */
public final class ProgrammableReconciliation {
    private ProgrammableReconciliation() {
    }

    /** One live output at the vault smart wallet: its shape, deposit id when deposit-shaped, and its value summary. */
    public record LiveOutput(ProgrammableDepositDatumV1.Outpoint outpoint, ProgrammableDepositClaimV1.Shape shape,
                             Optional<Hash32> depositId, BigInteger asset, BigInteger lovelace, long otherAssets) {
        public LiveOutput {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(depositId, "deposit id");
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(lovelace, "lovelace");
        }

        boolean clean() {
            return otherAssets == 0;
        }
    }

    public record Report(BigInteger backing, BigInteger unavailable, BigInteger unattributed, BigInteger donorDust,
                         BigInteger liability, BigInteger shortfall) {
        public boolean shortfallFlagged() {
            return shortfall.signum() > 0;
        }
    }

    /**
     * @param outputs  the vault smart wallet's live outputs (the reconciler's L1 view)
     * @param records  {@code deposit/<DepositId>} by id, from consensus state
     * @param liability the asset the ledger owes for this vault (credited less paid)
     */
    public static Report reconcile(List<LiveOutput> outputs, Function<Hash32, Optional<DepositRecordV1>> records,
                                   BigInteger liability) {
        BigInteger backing = BigInteger.ZERO;
        BigInteger unavailable = BigInteger.ZERO;
        BigInteger unattributed = BigInteger.ZERO;
        BigInteger dust = BigInteger.ZERO;
        Set<Hash32> counted = new HashSet<>();
        for (LiveOutput out : outputs) {
            switch (out.shape()) {
                // V10, as the custody scripts read accepted custody: a continuation holding only the asset and
                // lovelace, or a floor top-up holding lovelace alone. Anything else of either shape cannot be spent as
                // custody, so what it holds of the asset is unavailable, never backing.
                case CONTINUATION -> {
                    if (out.clean()) backing = backing.add(out.asset());
                    else unavailable = unavailable.add(out.asset());
                }
                case TOP_UP -> {
                    if (!out.clean() || out.asset().signum() != 0) unavailable = unavailable.add(out.asset());
                }
                case DEPOSIT -> {
                    Hash32 id = out.depositId().orElseThrow();
                    Optional<DepositRecordV1> record = records.apply(id);
                    if (record.isEmpty()) {
                        dust = dust.add(out.asset());
                    } else if (!out.clean()) {
                        unavailable = unavailable.add(out.asset());
                    } else if (record.get().status() == DepositRecordV1.Status.CREDITED) {
                        backing = backing.add(out.asset());
                    } else if (counted.add(id)) {
                        unattributed = unattributed.add(record.get().registeredQuantity());
                    }
                }
                default -> unavailable = unavailable.add(out.asset());
            }
        }
        BigInteger shortfall = liability.subtract(backing).max(BigInteger.ZERO);
        return new Report(backing, unavailable, unattributed, dust, liability, shortfall);
    }
}
