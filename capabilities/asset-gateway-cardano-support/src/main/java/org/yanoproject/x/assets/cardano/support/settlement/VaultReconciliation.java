package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableReconciliation;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The L1 reconciliation of one vault (ADR-059 D15): a deterministic, read-only comparison of the vault's consensus
 * liabilities with what its address holds on L1, per unit, with every difference explained. It is node-local
 * evidence, never consensus state, and nothing here writes a balance: an unexplained shortfall is reported as a
 * {@link Verdict#DISCREPANCY} so operators raise the alert and the members submit the governed {@code PAUSE} the
 * runbook prescribes (D15 "Incidents").
 *
 * <p><b>Inputs.</b> Every output at the vault address at one named L1 point, slot and block hash (the scan must be
 * complete, and without a point the report is {@link Verdict#UNAVAILABLE}); the vault's I4 counters at one committed
 * height; the committed record of each deposit-shaped or junk output; the vault's {@code RESERVED} claims; and the
 * shards whose live nullifier root is ahead of the committed {@code PAID} inserts.
 *
 * <p><b>Classification</b> of each output at the vault address:
 * <ul>
 *   <li><b>accepted custody</b>: the one V10 rule ({@link PayoutTxBuilder#accepted}), a clean continuation or floor
 *       top-up. It is plain custody: anyone can create one at the vault address, so the claim ids a continuation's
 *       datum names are <b>not</b> evidence that those claims were paid;</li>
 *   <li><b>credited, not yet accepted</b>: a deposit-shaped output whose record is {@code CREDITED}; it backs the
 *       liabilities its credit created until an {@code Accept} merges it (D8f);</li>
 *   <li><b>uncredited</b> (record {@code UNCREDITED}) and <b>unattributed</b> (record {@code UNATTRIBUTED}): value on
 *       L1 that is no liability (D6), leaving only through {@code Refund} in exit mode or a governed sweep;</li>
 *   <li><b>in flight</b>: an output with no record yet, observed on L1 but not finalized (D15
 *       {@code depositsInFlight}); a credit, when it comes, adds the same value to both sides.</li>
 * </ul>
 *
 * <p><b>Payouts in flight</b> come only from thread-authenticated evidence (D10): a {@code RESERVED} claim whose shard
 * is ahead of the committed inserts may have been paid on L1, since only a spend of the shard thread (a {@code Settle}
 * or {@code Exit}) moves its nullifier root. The sum of those claims, per unit, is the most a shortfall can be
 * explained by.
 *
 * <p><b>Per unit</b> (the vault asset, and lovelace; units never mix): {@code backing = accepted + creditedUnaccepted},
 * {@code expected = liabilities + floor} and {@code difference = backing − expected}. The <b>floor</b> is the profile's
 * {@code continuationFloor} (D11): operator-owned operational lovelace that every continuation keeps, never a user
 * liability, counted only in the lovelace unit and only once the vault holds accepted custody (it is bootstrapped). So
 * a lovelace shortfall smaller than the floor is still a shortfall; it is never absorbed by the floor. Zero is
 * {@link Verdict#BALANCED}; a surplus is {@link Verdict#SURPLUS}, explained by floor top-ups, donations, or a
 * confirmed payout that rolled back on L1 and is payable again (D10); a shortfall no larger than the pending payouts
 * is {@link Verdict#IN_FLIGHT}; a lovelace remainder no larger than the floor is {@link Verdict#FLOOR_SHORT}: the
 * users' liabilities are covered and the operator tops up its floor; any shortfall beyond the floor is a
 * {@link Verdict#DISCREPANCY}, with the uncovered remainder named.
 */
public final class VaultReconciliation {
    private VaultReconciliation() {
    }

    /**
     * The vault under reconciliation.
     *
     * @param continuationFloor the profile's {@code continuationFloor} (D11, V6), expected in lovelace on top of the
     *                          lovelace liabilities
     */
    public record Vault(Hash32 vaultId, AssetId asset, byte[] vaultAddress, BigInteger continuationFloor) {
        public Vault {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(asset, "asset");
            vaultAddress = Objects.requireNonNull(vaultAddress, "vault address").clone();
            if (continuationFloor == null || continuationFloor.signum() < 0) {
                throw new IllegalArgumentException("continuation floor is non-negative");
            }
        }

        @Override
        public byte[] vaultAddress() {
            return vaultAddress.clone();
        }

        public static Vault of(VaultPins pins) {
            return new Vault(pins.vaultId(), pins.asset(), pins.vaultAddress(), pins.continuationFloor());
        }
    }

    /**
     * The consensus side at one committed height.
     *
     * @param deposits     the committed record of a deposit id; empty when there is none
     * @param reserved     the vault's {@code RESERVED} claims
     * @param shardsAhead  shards whose live L1 nullifier root is not the root of the committed {@code PAID} inserts
     */
    public record Consensus(long height, AssetAccounts.VaultCounters counters,
                            Function<Hash32, Optional<DepositRecordV1>> deposits, List<ClaimLeafV1> reserved,
                            Set<Integer> shardsAhead) {
        public Consensus {
            Objects.requireNonNull(counters, "counters");
            Objects.requireNonNull(deposits, "deposits");
            reserved = List.copyOf(reserved);
            shardsAhead = Set.copyOf(shardsAhead);
        }
    }

    /** A named L1 point: the slot and hash of the last block applied to the view that was scanned. */
    public record L1Point(long slot, String blockHash) {
        public L1Point {
            if (slot < 0) throw new IllegalArgumentException("slot is non-negative");
            Objects.requireNonNull(blockHash, "block hash");
            blockHash = blockHash.toLowerCase(java.util.Locale.ROOT);
            if (!blockHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("block hash is 32-byte hex");
        }
    }

    /**
     * Every output at the vault address at one L1 point; {@code complete} only when no page was left unread. Without
     * a point the scan names no L1 state, and the report is {@link Verdict#UNAVAILABLE}.
     */
    public record L1Scan(Optional<L1Point> point, List<ResolvedOutput> outputs, boolean complete) {
        public L1Scan {
            Objects.requireNonNull(point, "point");
            outputs = List.copyOf(outputs);
        }
    }

    public enum Verdict {
        BALANCED, SURPLUS, IN_FLIGHT, FLOOR_SHORT, DISCREPANCY, UNAVAILABLE;

        Verdict worst(Verdict other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    /**
     * One unit's figures; every amount is in the unit's base quantity. {@code pendingPayouts} is the value of the
     * {@code RESERVED} claims in shards ahead of the committed inserts; {@code payoutsInFlight} is the part of a
     * shortfall they explain; {@code floor} is the continuation floor expected on top of the liabilities (lovelace
     * only).
     */
    public record Unit(String unit, BigInteger liabilities, BigInteger floor, BigInteger accepted,
                       BigInteger creditedUnaccepted, BigInteger pendingPayouts, BigInteger payoutsInFlight,
                       BigInteger uncredited, BigInteger unattributed, BigInteger inFlight, BigInteger l1Total,
                       BigInteger difference, Verdict verdict, String explanation) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unit", unit);
            map.put("verdict", verdict.name());
            map.put("liabilities", liabilities.toString());
            map.put("floor", floor.toString());
            map.put("acceptedCustody", accepted.toString());
            map.put("creditedUnaccepted", creditedUnaccepted.toString());
            map.put("pendingPayouts", pendingPayouts.toString());
            map.put("payoutsInFlight", payoutsInFlight.toString());
            map.put("uncreditedDeposits", uncredited.toString());
            map.put("unattributed", unattributed.toString());
            map.put("depositsInFlight", inFlight.toString());
            map.put("l1Total", l1Total.toString());
            map.put("difference", difference.toString());
            map.put("explanation", explanation);
            return map;
        }
    }

    /** The report of one vault. */
    public record Report(Hash32 vaultId, AssetId asset, long height, Optional<L1Point> l1Point, Verdict verdict,
                         List<Unit> units, int outputs, int reservedClaims, OptionalLong oldestReservedAtMs,
                         List<Hash32> claimsPendingOnL1, List<String> notes) {
        public Report {
            Objects.requireNonNull(l1Point, "L1 point");
            units = List.copyOf(units);
            claimsPendingOnL1 = List.copyOf(claimsPendingOnL1);
            notes = List.copyOf(notes);
        }

        public static Report unavailable(Hash32 vaultId, AssetId asset, String why) {
            return new Report(vaultId, asset, -1, Optional.empty(), Verdict.UNAVAILABLE, List.of(), 0, 0,
                    OptionalLong.empty(), List.of(), List.of(why));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("vaultId", vaultId.hex());
            map.put("asset", asset.isAda() ? "lovelace" : asset.toString());
            map.put("verdict", verdict.name());
            map.put("committedHeight", height);
            map.put("l1Slot", l1Point.map(L1Point::slot).orElse(-1L));
            map.put("l1BlockHash", l1Point.map(L1Point::blockHash).orElse(""));
            map.put("outputs", outputs);
            map.put("reservedClaims", reservedClaims);
            map.put("oldestReservedAtMs", oldestReservedAtMs.isPresent() ? oldestReservedAtMs.getAsLong() : -1);
            map.put("claimsPendingOnL1", claimsPendingOnL1.stream().map(Hash32::hex).toList());
            map.put("units", units.stream().map(Unit::toMap).toList());
            map.put("notes", notes);
            return map;
        }
    }

    /** Accumulates one unit. */
    private static final class Sums {
        BigInteger accepted = BigInteger.ZERO;
        BigInteger credited = BigInteger.ZERO;
        BigInteger uncredited = BigInteger.ZERO;
        BigInteger unattributed = BigInteger.ZERO;
        BigInteger inFlight = BigInteger.ZERO;
        BigInteger pending = BigInteger.ZERO;
    }

    public static Report reconcile(Vault vault, Consensus consensus, L1Scan scan) {
        Objects.requireNonNull(vault, "vault");
        Objects.requireNonNull(consensus, "consensus");
        Objects.requireNonNull(scan, "scan");
        if (!scan.complete()) return Report.unavailable(vault.vaultId(), vault.asset(), "the L1 scan is incomplete");
        if (scan.point().isEmpty()) {
            return Report.unavailable(vault.vaultId(), vault.asset(),
                    "the L1 view names no applied point (slot and block hash)");
        }

        boolean ada = vault.asset().isAda();
        Sums asset = new Sums();
        Sums lovelace = new Sums();
        Map<Hash32, ClaimLeafV1> reserved = new LinkedHashMap<>();
        consensus.reserved().forEach(leaf -> reserved.put(leaf.claimId(), leaf));
        List<String> notes = new ArrayList<>();
        int foreign = 0;
        int counted = 0;
        int acceptedOutputs = 0;
        TreeSet<ResolvedOutput.Outpoint> seen = new TreeSet<>();
        List<ResolvedOutput> ordered = new ArrayList<>(scan.outputs());
        ordered.sort((a, b) -> a.outpoint().compareTo(b.outpoint()));
        for (ResolvedOutput out : ordered) {
            if (!out.sameAddress(vault.vaultAddress()) || !seen.add(out.outpoint())) continue;
            counted++;
            BigInteger quantity = ada ? BigInteger.ZERO : out.quantityOf(vault.asset());
            BigInteger coins = out.lovelace();
            if (out.assets().keySet().stream().anyMatch(id -> !id.equals(vault.asset()))) foreign++;
            if (PayoutTxBuilder.accepted(out, vault.vaultAddress(), vault.asset())) {
                asset.accepted = asset.accepted.add(quantity);
                lovelace.accepted = lovelace.accepted.add(coins);
                acceptedOutputs++;
                continue;
            }
            Hash32 depositId = GatewayIds.depositId(vault.vaultId(), out.outpoint().txId(), out.outpoint().index());
            Optional<DepositRecordV1> record = consensus.deposits().apply(depositId);
            boolean depositShaped = out.inlineDatum().map(VaultOutputShape::classify)
                    .filter(shape -> shape == VaultOutputShape.DEPOSIT).isPresent();
            if (record.isEmpty()) {
                asset.inFlight = asset.inFlight.add(quantity);
                lovelace.inFlight = lovelace.inFlight.add(coins);
            } else if (record.get().status() == DepositRecordV1.Status.CREDITED && depositShaped) {
                asset.credited = asset.credited.add(quantity);
                lovelace.credited = lovelace.credited.add(coins);
            } else if (record.get().status() == DepositRecordV1.Status.UNCREDITED) {
                asset.uncredited = asset.uncredited.add(quantity);
                lovelace.uncredited = lovelace.uncredited.add(coins);
            } else {
                // UNATTRIBUTED, or a record whose status this output's shape cannot carry: never backing.
                asset.unattributed = asset.unattributed.add(quantity);
                lovelace.unattributed = lovelace.unattributed.add(coins);
            }
        }
        // Thread-authenticated evidence only (D10): a claim may have been paid on L1 only if its shard moved.
        List<Hash32> pending = new ArrayList<>();
        for (ClaimLeafV1 leaf : reserved.values()) {
            if (!consensus.shardsAhead().contains(SettlementBatchV1.shardOf(leaf.claimId()))) continue;
            pending.add(leaf.claimId());
            if (ada) {
                lovelace.pending = lovelace.pending.add(leaf.value().quantityOf(AssetId.ADA));
            } else {
                asset.pending = asset.pending.add(leaf.value().quantityOf(vault.asset()));
                lovelace.pending = lovelace.pending.add(leaf.vaultLovelace());
            }
        }
        if (foreign > 0) notes.add(foreign + " output(s) carry assets other than the vault's (unattributed, D8d)");
        if (!consensus.shardsAhead().isEmpty()) {
            notes.add("shards " + new TreeSet<>(consensus.shardsAhead())
                    + " hold nullifier inserts not yet confirmed in committed state; " + pending.size()
                    + " reserved claim(s) there may be paid on L1");
        }
        BigInteger floor = acceptedOutputs > 0 ? vault.continuationFloor() : BigInteger.ZERO;
        notes.add(acceptedOutputs > 0
                ? "lovelace expected includes the operator continuation floor of " + floor + " (D11): operator-owned, "
                        + "never a liability, so a lovelace shortfall smaller than the floor is still a shortfall"
                : "no accepted custody yet (not bootstrapped), so no continuation floor is expected");

        AssetAccounts.VaultCounters c = consensus.counters();
        List<Unit> units = new ArrayList<>();
        if (ada) {
            units.add(unit("lovelace", c.available().add(c.reserved()), floor, lovelace));
        } else {
            units.add(unit(vault.asset().toString(), c.available().add(c.reserved()), BigInteger.ZERO, asset));
            units.add(unit("lovelace", c.lovelaceAvailable().add(c.lovelaceReserved()), floor, lovelace));
        }
        Verdict verdict = Verdict.BALANCED;
        for (Unit unit : units) verdict = verdict.worst(unit.verdict());
        if (verdict == Verdict.IN_FLIGHT || verdict == Verdict.FLOOR_SHORT || verdict == Verdict.DISCREPANCY) {
            L1Point point = scan.point().get();
            notes.add("this node's L1 view (slot " + point.slot() + ", block " + point.blockHash() + ") may lag the "
                    + "L1 observations committed height " + consensus.height() + " was built from: a deposit credited "
                    + "from a block this view has not applied yet reads as a shortfall here; compare another "
                    + "member's report or an independent L1 view before acting");
        }
        OptionalLong oldest = reserved.values().stream().mapToLong(ClaimLeafV1::reservedAtMs).min();
        return new Report(vault.vaultId(), vault.asset(), consensus.height(), scan.point(), verdict, units, counted,
                reserved.size(), oldest, pending, notes);
    }

    /**
     * ADR-061 D6, D14(a): the reconciliation of a CIP-113 vault's asset. Its L1 side counts as backing only what a vault
     * action can spend ({@link ProgrammableReconciliation}): accepted custody by the V10 predicate, and deposit-shaped
     * outputs holding only the asset whose origin-keyed {@code DepositId} is {@code CREDITED}. Copies holding a
     * foreign token, a top-up holding the asset, and other shapes are <b>unavailable</b>, never backing; an
     * {@code UNCREDITED} id counts once, at its recorded value; a re-created output whose id is absent is donor dust.
     * {@code consensus.deposits()} answers by origin-keyed id. A shortfall within the claims of shards ahead of their
     * committed inserts is {@link Verdict#IN_FLIGHT}; any other shortfall is a {@link Verdict#DISCREPANCY}. Reward
     * balances are never counted, and the lovelace unit is not reconciled for a CIP-113 vault in this release.
     */
    public static Report reconcileProgrammable(Vault vault, Consensus consensus, L1Scan scan, byte[] plbHash,
                                               int networkId) {
        Objects.requireNonNull(vault, "vault");
        if (!scan.complete()) return Report.unavailable(vault.vaultId(), vault.asset(), "the L1 scan is incomplete");
        if (scan.point().isEmpty()) {
            return Report.unavailable(vault.vaultId(), vault.asset(),
                    "the L1 view names no applied point (slot and block hash)");
        }
        List<ProgrammableReconciliation.LiveOutput> live = new ArrayList<>();
        TreeSet<ResolvedOutput.Outpoint> seen = new TreeSet<>();
        for (ResolvedOutput out : scan.outputs()) {
            if (!out.sameAddress(vault.vaultAddress()) || !seen.add(out.outpoint())) continue;
            byte[] datum = out.inlineDatum().orElse(null);
            ProgrammableDepositClaimV1.Shape shape = ProgrammableDepositClaimV1.Shape.of(datum, plbHash, networkId);
            Optional<Hash32> depositId = shape == ProgrammableDepositClaimV1.Shape.DEPOSIT
                    ? ProgrammableDepositDatumV1.depositShaped(datum, plbHash, networkId)
                    .map(d -> d.depositId(vault.vaultId())) : Optional.empty();
            long others = out.assets().keySet().stream().filter(id -> !id.equals(vault.asset())).count();
            live.add(new ProgrammableReconciliation.LiveOutput(new ProgrammableDepositDatumV1.Outpoint(
                    out.outpoint().txId(), out.outpoint().index()), shape, depositId, out.quantityOf(vault.asset()),
                    out.lovelace(), others));
        }
        AssetAccounts.VaultCounters c = consensus.counters();
        BigInteger liabilities = c.available().add(c.reserved());
        ProgrammableReconciliation.Report programmable = ProgrammableReconciliation.reconcile(live,
                consensus.deposits(), liabilities);
        List<Hash32> pending = new ArrayList<>();
        BigInteger pendingAsset = BigInteger.ZERO;
        for (ClaimLeafV1 leaf : consensus.reserved()) {
            if (!consensus.shardsAhead().contains(SettlementBatchV1.shardOf(leaf.claimId()))) continue;
            pending.add(leaf.claimId());
            pendingAsset = pendingAsset.add(leaf.value().quantityOf(vault.asset()));
        }
        BigInteger difference = programmable.backing().subtract(liabilities);
        BigInteger explained = difference.signum() < 0 ? difference.negate().min(pendingAsset) : BigInteger.ZERO;
        Verdict verdict;
        String explanation;
        if (difference.signum() == 0) {
            verdict = Verdict.BALANCED;
            explanation = "spendable custody equals the liabilities";
        } else if (difference.signum() > 0) {
            verdict = Verdict.SURPLUS;
            explanation = "spendable custody exceeds the liabilities by " + difference;
        } else if (explained.equals(difference.negate())) {
            verdict = Verdict.IN_FLIGHT;
            explanation = "spendable custody is short by " + difference.negate() + ", within the " + pendingAsset
                    + " reserved in shards ahead of their committed inserts";
        } else {
            verdict = Verdict.DISCREPANCY;
            explanation = "spendable custody is short by " + difference.negate() + "; payouts in flight explain "
                    + explained + "; " + programmable.unavailable() + " is unavailable (not spendable as custody)"
                    + ": attribute it (third-party action or burn: ASSET_HOLD) or raise the alert (D14)";
        }
        List<Unit> units = List.of(new Unit(vault.asset().toString(), liabilities, BigInteger.ZERO,
                programmable.backing(), BigInteger.ZERO, pendingAsset, explained, programmable.unattributed(),
                programmable.unavailable(), programmable.donorDust(), programmable.backing()
                .add(programmable.unavailable()).add(programmable.unattributed()).add(programmable.donorDust()),
                difference, verdict, explanation));
        List<String> notes = new ArrayList<>();
        notes.add("CIP-113 vault (ADR-061): unavailable " + programmable.unavailable() + ", unattributed "
                + programmable.unattributed() + ", donor dust " + programmable.donorDust()
                + "; reward balances are never counted; the lovelace unit is not reconciled in this release");
        OptionalLong oldest = consensus.reserved().stream().mapToLong(ClaimLeafV1::reservedAtMs).min();
        return new Report(vault.vaultId(), vault.asset(), consensus.height(), scan.point(), verdict, units,
                seen.size(), consensus.reserved().size(), oldest, pending, notes);
    }

    private static Unit unit(String name, BigInteger liabilities, BigInteger floor, Sums sums) {
        BigInteger backing = sums.accepted.add(sums.credited);
        BigInteger expected = liabilities.add(floor);
        BigInteger difference = backing.subtract(expected);
        BigInteger total = backing.add(sums.uncredited).add(sums.unattributed).add(sums.inFlight);
        BigInteger explained = BigInteger.ZERO;
        Verdict verdict;
        String explanation;
        String floorPart = floor.signum() == 0 ? "" : " (liabilities " + liabilities + " plus the floor " + floor + ")";
        if (difference.signum() == 0) {
            verdict = Verdict.BALANCED;
            explanation = "custody equals what is expected" + floorPart;
        } else if (difference.signum() > 0) {
            verdict = Verdict.SURPLUS;
            explanation = "custody exceeds what is expected" + floorPart + " by " + difference + ": floor top-ups, "
                    + "donations, or a confirmed payout that rolled back on L1 and is payable again (D10)";
        } else {
            BigInteger shortfall = difference.negate();
            explained = shortfall.min(sums.pending);
            BigInteger uncovered = shortfall.subtract(explained);
            if (uncovered.signum() == 0) {
                verdict = Verdict.IN_FLIGHT;
                explanation = "custody is short of what is expected" + floorPart + " by " + shortfall + ", within the "
                        + sums.pending + " reserved in shards ahead of their committed inserts (payouts L1 has "
                        + "seen, not yet confirmed); re-run once their observations are final";
            } else if (floor.signum() > 0 && uncovered.compareTo(floor) <= 0) {
                verdict = Verdict.FLOOR_SHORT;
                explanation = "custody is short of what is expected" + floorPart + " by " + shortfall + "; payouts in "
                        + "flight explain " + explained + " and the remaining " + uncovered + " is no more than the "
                        + "operator continuation floor (D11), so the liabilities are covered: top up the floor at the "
                        + "vault address; no balance is changed";
            } else {
                verdict = Verdict.DISCREPANCY;
                explanation = "custody is short of what is expected" + floorPart + " by " + shortfall + "; payouts in "
                        + "flight explain " + explained + " and " + uncovered + " is unexplained"
                        + ": raise the alert and submit the governed PAUSE (D15); no balance is changed";
            }
        }
        return new Unit(name, liabilities, floor, sums.accepted, sums.credited, sums.pending, explained,
                sums.uncredited, sums.unattributed, sums.inFlight, total, difference, verdict, explanation);
    }

    /** Whether two scans name the same outputs, so a caller can tell that the L1 view moved under it. */
    public static boolean sameOutputs(List<ResolvedOutput> a, List<ResolvedOutput> b) {
        TreeSet<ResolvedOutput.Outpoint> left = new TreeSet<>();
        a.forEach(out -> left.add(out.outpoint()));
        TreeSet<ResolvedOutput.Outpoint> right = new TreeSet<>();
        b.forEach(out -> right.add(out.outpoint()));
        return left.equals(right);
    }
}
