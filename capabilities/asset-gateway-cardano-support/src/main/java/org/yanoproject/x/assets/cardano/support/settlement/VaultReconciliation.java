package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p><b>Inputs.</b> Every output at the vault address at one L1 point (the scan must be complete); the vault's I4
 * counters at one committed height; the committed record of each deposit-shaped or junk output; the vault's
 * {@code RESERVED} claims; and the shards whose live nullifier root is ahead of the committed {@code PAID} inserts.
 *
 * <p><b>Classification</b> of each output at the vault address:
 * <ul>
 *   <li><b>accepted custody</b>: the one V10 rule ({@link PayoutTxBuilder#accepted}), a clean continuation or floor
 *       top-up. A continuation's datum names the claims the transaction that created it paid; any of them still
 *       {@code RESERVED} in committed state is a <b>payout in flight</b>: it has left L1 and is still a liability;</li>
 *   <li><b>credited, not yet accepted</b>: a deposit-shaped output whose record is {@code CREDITED}; it backs the
 *       liabilities its credit created until an {@code Accept} merges it (D8f);</li>
 *   <li><b>uncredited</b> (record {@code UNCREDITED}) and <b>unattributed</b> (record {@code UNATTRIBUTED}): value on
 *       L1 that is no liability (D6), leaving only through {@code Refund} in exit mode or a governed sweep;</li>
 *   <li><b>in flight</b>: an output with no record yet, observed on L1 but not finalized (D15
 *       {@code depositsInFlight}); a credit, when it comes, adds the same value to both sides.</li>
 * </ul>
 *
 * <p><b>Per unit</b> (the vault asset, and lovelace for a token vault; units never mix):
 * {@code backing = accepted + creditedUnaccepted}, {@code expected = liabilities − payoutsInFlight}, and
 * {@code difference = backing − expected}. Zero is {@link Verdict#BALANCED}; a surplus is
 * {@link Verdict#SURPLUS}, explained by the operator floor (D11), donations, or a confirmed payout that rolled back on
 * L1 and is payable again (D10); a shortfall while a shard is ahead of its committed inserts is
 * {@link Verdict#IN_FLIGHT} (a payout L1 has seen is not yet confirmed); any other shortfall is a
 * {@link Verdict#DISCREPANCY}.
 */
public final class VaultReconciliation {
    private VaultReconciliation() {
    }

    /** The vault under reconciliation. */
    public record Vault(Hash32 vaultId, AssetId asset, byte[] vaultAddress) {
        public Vault {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(asset, "asset");
            vaultAddress = Objects.requireNonNull(vaultAddress, "vault address").clone();
        }

        @Override
        public byte[] vaultAddress() {
            return vaultAddress.clone();
        }

        public static Vault of(VaultPins pins) {
            return new Vault(pins.vaultId(), pins.asset(), pins.vaultAddress());
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

    /** Every output at the vault address at one L1 point; {@code complete} only when no page was left unread. */
    public record L1Scan(OptionalLong slot, List<ResolvedOutput> outputs, boolean complete) {
        public L1Scan {
            Objects.requireNonNull(slot, "slot");
            outputs = List.copyOf(outputs);
        }
    }

    public enum Verdict {
        BALANCED, SURPLUS, IN_FLIGHT, DISCREPANCY, UNAVAILABLE;

        Verdict worst(Verdict other) {
            return other.ordinal() > ordinal() ? other : this;
        }
    }

    /** One unit's figures; every amount is in the unit's base quantity. */
    public record Unit(String unit, BigInteger liabilities, BigInteger accepted, BigInteger creditedUnaccepted,
                       BigInteger payoutsInFlight, BigInteger uncredited, BigInteger unattributed,
                       BigInteger inFlight, BigInteger l1Total, BigInteger difference, Verdict verdict,
                       String explanation) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("unit", unit);
            map.put("verdict", verdict.name());
            map.put("liabilities", liabilities.toString());
            map.put("acceptedCustody", accepted.toString());
            map.put("creditedUnaccepted", creditedUnaccepted.toString());
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
    public record Report(Hash32 vaultId, AssetId asset, long height, OptionalLong l1Slot, Verdict verdict,
                         List<Unit> units, int outputs, int reservedClaims, OptionalLong oldestReservedAtMs,
                         List<Hash32> claimsPaidOnL1, List<String> notes) {
        public Report {
            units = List.copyOf(units);
            claimsPaidOnL1 = List.copyOf(claimsPaidOnL1);
            notes = List.copyOf(notes);
        }

        public static Report unavailable(Hash32 vaultId, AssetId asset, String why) {
            return new Report(vaultId, asset, -1, OptionalLong.empty(), Verdict.UNAVAILABLE, List.of(), 0, 0,
                    OptionalLong.empty(), List.of(), List.of(why));
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("vaultId", vaultId.hex());
            map.put("asset", asset.isAda() ? "lovelace" : asset.toString());
            map.put("verdict", verdict.name());
            map.put("committedHeight", height);
            map.put("l1Slot", l1Slot.isPresent() ? l1Slot.getAsLong() : -1);
            map.put("outputs", outputs);
            map.put("reservedClaims", reservedClaims);
            map.put("oldestReservedAtMs", oldestReservedAtMs.isPresent() ? oldestReservedAtMs.getAsLong() : -1);
            map.put("claimsPaidOnL1", claimsPaidOnL1.stream().map(Hash32::hex).toList());
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
        BigInteger payouts = BigInteger.ZERO;
    }

    public static Report reconcile(Vault vault, Consensus consensus, L1Scan scan) {
        Objects.requireNonNull(vault, "vault");
        Objects.requireNonNull(consensus, "consensus");
        Objects.requireNonNull(scan, "scan");
        if (!scan.complete()) return Report.unavailable(vault.vaultId(), vault.asset(), "the L1 scan is incomplete");

        boolean ada = vault.asset().isAda();
        Sums asset = new Sums();
        Sums lovelace = new Sums();
        Map<Hash32, ClaimLeafV1> reserved = new LinkedHashMap<>();
        consensus.reserved().forEach(leaf -> reserved.put(leaf.claimId(), leaf));
        Set<Hash32> paidOnL1 = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        int foreign = 0;
        int counted = 0;
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
                out.inlineDatum().flatMap(GatewayDatums::continuationIds).ifPresent(ids -> ids.forEach(id -> {
                    if (reserved.containsKey(id)) paidOnL1.add(id);
                }));
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
        for (Hash32 id : paidOnL1) {
            ClaimLeafV1 leaf = reserved.get(id);
            if (ada) {
                lovelace.payouts = lovelace.payouts.add(leaf.value().quantityOf(AssetId.ADA));
            } else {
                asset.payouts = asset.payouts.add(leaf.value().quantityOf(vault.asset()));
                lovelace.payouts = lovelace.payouts.add(leaf.vaultLovelace());
            }
        }
        if (foreign > 0) notes.add(foreign + " output(s) carry assets other than the vault's (unattributed, D8d)");
        if (!consensus.shardsAhead().isEmpty()) {
            notes.add("shards " + consensus.shardsAhead() + " hold nullifier inserts not yet confirmed in committed state");
        }

        AssetAccounts.VaultCounters c = consensus.counters();
        boolean pending = !consensus.shardsAhead().isEmpty();
        List<Unit> units = new ArrayList<>();
        if (ada) {
            units.add(unit("lovelace", c.available().add(c.reserved()), lovelace, pending));
        } else {
            units.add(unit(vault.asset().toString(), c.available().add(c.reserved()), asset, pending));
            units.add(unit("lovelace", c.lovelaceAvailable().add(c.lovelaceReserved()), lovelace, pending));
        }
        Verdict verdict = Verdict.BALANCED;
        for (Unit unit : units) verdict = verdict.worst(unit.verdict());
        OptionalLong oldest = reserved.values().stream().mapToLong(ClaimLeafV1::reservedAtMs).min();
        return new Report(vault.vaultId(), vault.asset(), consensus.height(), scan.slot(), verdict, units, counted,
                reserved.size(), oldest, List.copyOf(paidOnL1), notes);
    }

    private static Unit unit(String name, BigInteger liabilities, Sums sums, boolean shardsAhead) {
        BigInteger backing = sums.accepted.add(sums.credited);
        BigInteger expected = liabilities.subtract(sums.payouts);
        BigInteger difference = backing.subtract(expected);
        BigInteger total = backing.add(sums.uncredited).add(sums.unattributed).add(sums.inFlight);
        Verdict verdict;
        String explanation;
        if (difference.signum() == 0) {
            verdict = Verdict.BALANCED;
            explanation = "custody equals liabilities net of payouts in flight";
        } else if (difference.signum() > 0) {
            verdict = Verdict.SURPLUS;
            explanation = "custody exceeds liabilities by " + difference + ": the operator floor (D11), donations, or "
                    + "a confirmed payout that rolled back on L1 and is payable again (D10)";
        } else if (shardsAhead) {
            verdict = Verdict.IN_FLIGHT;
            explanation = "custody is short by " + difference.negate() + " while a payout L1 has seen awaits its "
                    + "confirmation; re-run once its observation is final";
        } else {
            verdict = Verdict.DISCREPANCY;
            explanation = "custody is short of liabilities by " + difference.negate() + " with nothing in flight: "
                    + "raise the alert and submit the governed PAUSE (D15); no balance is changed";
        }
        return new Unit(name, liabilities, sums.accepted, sums.credited, sums.payouts, sums.uncredited,
                sums.unattributed, sums.inFlight, total, difference, verdict, explanation);
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
