package org.yanoproject.x.assets.cardano.support.settlement;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Consensus;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.L1Scan;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Report;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Unit;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Verdict;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 D15 reconciliation over synthetic L1 scans: accepted custody, credited-but-unaccepted, uncredited,
 * unattributed and in-flight outputs; payouts in flight only from shards ahead of their committed inserts, and only up
 * to what they explain; the continuation floor expected in lovelace; a deliberate shortfall that is flagged; and
 * units that never offset each other.
 */
class VaultReconciliationTest {
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final AssetId JUNK = AssetId.ofHex("e1".repeat(28), "4a");
    static final Hash32 DOMAIN = Hash32.of(filled(32, 0x0d));
    static final Hash32 VAULT_ID = GatewayIds.vaultId(DOMAIN, USDM, 0);
    static final Hash32 ADA_VAULT_ID = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);
    static final byte[] ADDRESS = address(0x02);
    static final byte[] ADA_ADDRESS = address(0x01);
    static final byte[] RECIPIENT = concat(new byte[] {0x60}, filled(28, 0x77));
    static final BigInteger FLOOR = BigInteger.valueOf(2_000_000);
    static final VaultReconciliation.Vault VAULT = new VaultReconciliation.Vault(VAULT_ID, USDM, ADDRESS, FLOOR);
    static final VaultReconciliation.Vault ADA_VAULT = new VaultReconciliation.Vault(ADA_VAULT_ID, AssetId.ADA,
            ADA_ADDRESS, FLOOR);
    static final VaultReconciliation.L1Point POINT = new VaultReconciliation.L1Point(900, "ab".repeat(32));

    final Map<Hash32, DepositRecordV1> records = new HashMap<>();
    final List<ResolvedOutput> l1 = new ArrayList<>();
    int tx = 0x40;

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] address(int scriptByte) {
        return concat(new byte[] {0x70}, filled(28, scriptByte));
    }

    /** {@code VaultContinuationV1(ids)}: constructor 1 holding one list of 32-byte ids. */
    static byte[] continuation(Hash32... ids) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) (0x80 | ids.length)});
        for (Hash32 id : ids) {
            out.write(0x58);
            out.write(32);
            out.writeBytes(id.bytes());
        }
        return out.toByteArray();
    }

    static AssetAccounts.VaultCounters counters(long credited, long paid, long available, long reserved,
                                                long lovelaceCredited, long lovelacePaid, long lovelaceAvailable,
                                                long lovelaceReserved) {
        return new AssetAccounts.VaultCounters(BigInteger.valueOf(credited), BigInteger.valueOf(paid),
                BigInteger.valueOf(available), BigInteger.valueOf(reserved), BigInteger.valueOf(lovelaceCredited),
                BigInteger.valueOf(lovelacePaid), BigInteger.valueOf(lovelaceAvailable),
                BigInteger.valueOf(lovelaceReserved));
    }

    ResolvedOutput output(byte[] address, long lovelace, Map<AssetId, Long> assets, byte[] datum) {
        TreeMap<AssetId, BigInteger> values = new TreeMap<>();
        assets.forEach((asset, quantity) -> values.put(asset, BigInteger.valueOf(quantity)));
        ResolvedOutput out = new ResolvedOutput(new ResolvedOutput.Outpoint(Hash32.of(filled(32, tx++)), 0), address,
                BigInteger.valueOf(lovelace), values, Optional.ofNullable(datum), Optional.empty());
        l1.add(out);
        return out;
    }

    ResolvedOutput custody(long usdm, long lovelace, Hash32... paid) {
        return output(ADDRESS, lovelace, usdm == 0 ? Map.of() : Map.of(USDM, usdm), continuation(paid));
    }

    /** A deposit-shaped output, recorded with {@code status} (null: not recorded yet). */
    ResolvedOutput deposit(long usdm, long lovelace, DepositRecordV1.Status status) {
        byte[] datum = new DepositDatumV1(filled(28, 0xa1), RECIPIENT, filled(28, 0x77)).encode();
        ResolvedOutput out = output(ADDRESS, lovelace, Map.of(USDM, usdm), datum);
        if (status != null) record(out, VaultOutputShape.DEPOSIT, status, usdm, lovelace);
        return out;
    }

    void record(ResolvedOutput out, VaultOutputShape shape, DepositRecordV1.Status status, long usdm, long lovelace) {
        Hash32 id = GatewayIds.depositId(VAULT_ID, out.outpoint().txId(), out.outpoint().index());
        boolean credited = status == DepositRecordV1.Status.CREDITED;
        DepositAdmission.Reason reason = credited ? DepositAdmission.Reason.NONE
                : status == DepositRecordV1.Status.UNCREDITED ? DepositAdmission.Reason.UNKNOWN_BENEFICIARY
                : DepositAdmission.Reason.NOT_A_DEPOSIT;
        records.put(id, new DepositRecordV1(id, VAULT_ID, out.outpoint().txId(), 0, 10, 5, shape, status, reason,
                credited ? filled(28, 0xa1) : new byte[0], BigInteger.valueOf(usdm), BigInteger.valueOf(lovelace),
                0, Hash32.of(filled(32, 0x5e)), credited ? BigInteger.valueOf(usdm) : BigInteger.ZERO,
                credited ? BigInteger.valueOf(lovelace) : BigInteger.ZERO));
    }

    static ClaimLeafV1 claim(int seed, long usdm, long vaultLovelace, long reservedAtMs) {
        AssetValue value = AssetValue.single(USDM, BigInteger.valueOf(usdm));
        Hash32 id = GatewayIds.claimId(DOMAIN, VAULT_ID, ClaimKind.USER_WITHDRAWAL, filled(28, seed), RECIPIENT, value,
                BigInteger.valueOf(vaultLovelace), seed);
        return new ClaimLeafV1(id, VAULT_ID, ClaimKind.USER_WITHDRAWAL, value, BigInteger.valueOf(vaultLovelace),
                RECIPIENT, 7, reservedAtMs);
    }

    Report reconcile(AssetAccounts.VaultCounters counters, List<ClaimLeafV1> reserved, Set<Integer> ahead) {
        return VaultReconciliation.reconcile(VAULT, new Consensus(12, counters,
                id -> Optional.ofNullable(records.get(id)), reserved, ahead), new L1Scan(Optional.of(POINT), l1,
                true));
    }

    /** A claim whose id routes to {@code shard}: the seed is searched, so the claim's shard is known. */
    static ClaimLeafV1 claimIn(int shard, long usdm, long vaultLovelace) {
        for (int seed = 1; ; seed++) {
            ClaimLeafV1 leaf = claim(seed, usdm, vaultLovelace, 1_000 + seed);
            if (SettlementBatchV1.shardOf(leaf.claimId()) == shard) return leaf;
        }
    }

    static Unit unit(Report report, String name) {
        return report.units().stream().filter(unit -> unit.unit().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void custodyThatMatchesLiabilitiesAndTheFloorBalances() {
        custody(100, 3_500_000); // 100 USDM of liabilities, 1.5 ADA of min-ADA credit, a 2 ADA floor
        Report report = reconcile(counters(100, 0, 60, 40, 1_500_000, 0, 500_000, 1_000_000), List.of(), Set.of());
        assertThat(unit(report, USDM.toString()).verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(unit(report, USDM.toString()).floor()).isZero();
        Unit lovelace = unit(report, "lovelace");
        assertThat(lovelace.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(lovelace.floor()).isEqualTo(FLOOR);
        assertThat(lovelace.difference()).isZero();
        assertThat(report.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(report.height()).isEqualTo(12);
        assertThat(report.l1Point()).contains(POINT);
        assertThat(report.toMap()).containsEntry("l1Slot", 900L).containsEntry("l1BlockHash", "ab".repeat(32));
        assertThat(report.notes()).anyMatch(note -> note.contains("continuation floor of 2000000"));

        // A floor top-up above the profile floor is a lovelace surplus.
        output(ADDRESS, 1_000_000, Map.of(), VaultOutputShape.floorTopUpDatum());
        Unit topped = unit(reconcile(counters(100, 0, 60, 40, 1_500_000, 0, 500_000, 1_000_000), List.of(), Set.of()),
                "lovelace");
        assertThat(topped.verdict()).isEqualTo(Verdict.SURPLUS);
        assertThat(topped.explanation()).contains("floor top-ups");
    }

    @Test
    void everyOutputIsClassifiedAndOnlyBackingCounts() {
        custody(60, 2_000_000);
        deposit(40, 1_500_000, DepositRecordV1.Status.CREDITED); // credited, not yet accepted: backing
        deposit(7, 1_200_000, DepositRecordV1.Status.UNCREDITED); // no liability
        deposit(9, 1_300_000, null); // observed, not yet finalized
        ResolvedOutput junk = output(ADDRESS, 1_100_000, Map.of(JUNK, 5L), null);
        record(junk, VaultOutputShape.OTHER, DepositRecordV1.Status.UNATTRIBUTED, 0, 1_100_000);
        output(address(0x09), 50_000_000, Map.of(USDM, 1_000L), continuation()); // another address: not this vault

        Report report = reconcile(counters(100, 0, 100, 0, 1_500_000, 0, 1_500_000, 0), List.of(), Set.of());
        Unit usdm = unit(report, USDM.toString());
        assertThat(usdm.accepted()).isEqualTo(BigInteger.valueOf(60));
        assertThat(usdm.creditedUnaccepted()).isEqualTo(BigInteger.valueOf(40));
        assertThat(usdm.uncredited()).isEqualTo(BigInteger.valueOf(7));
        assertThat(usdm.inFlight()).isEqualTo(BigInteger.valueOf(9));
        assertThat(usdm.l1Total()).isEqualTo(BigInteger.valueOf(116));
        assertThat(usdm.verdict()).isEqualTo(Verdict.BALANCED);
        Unit lovelace = unit(report, "lovelace");
        assertThat(lovelace.unattributed()).isEqualTo(BigInteger.valueOf(1_100_000));
        assertThat(lovelace.difference()).isZero(); // 2 ADA of accepted custody is the floor
        assertThat(report.outputs()).isEqualTo(5);
        assertThat(report.notes()).anyMatch(note -> note.contains("other than the vault's"));
    }

    @Test
    void aPayoutL1HasSeenButCommittedStateHasNotConfirmedIsInFlight() {
        ClaimLeafV1 paid = claimIn(5, 40, 1_000_000);
        ClaimLeafV1 waiting = claimIn(6, 10, 0);
        custody(60, 2_500_000, paid.claimId()); // the continuation of the Settle that paid `paid`
        // Only the shard thread authenticates the payout: shard 5 moved past its committed inserts.
        Report report = reconcile(counters(100, 0, 50, 50, 1_500_000, 0, 500_000, 1_000_000),
                List.of(paid, waiting), Set.of(5));
        assertThat(report.claimsPendingOnL1()).containsExactly(paid.claimId());
        Unit usdm = unit(report, USDM.toString());
        assertThat(usdm.pendingPayouts()).isEqualTo(BigInteger.valueOf(40));
        assertThat(usdm.payoutsInFlight()).isEqualTo(BigInteger.valueOf(40));
        assertThat(usdm.verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(unit(report, "lovelace").payoutsInFlight()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertThat(unit(report, "lovelace").verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(report.reservedClaims()).isEqualTo(2);
        assertThat(report.oldestReservedAtMs()).hasValue(waiting.reservedAtMs() < paid.reservedAtMs()
                ? waiting.reservedAtMs() : paid.reservedAtMs());
        assertThat(report.notes()).anyMatch(note -> note.contains("may lag"));
    }

    @Test
    void aDonatedContinuationNamingAReservedClaimIsPlainCustodyNotAPayout() {
        // Codex probe: 40 USDM are missing; anyone can create a clean continuation naming the reserved claim.
        ClaimLeafV1 reserved = claimIn(5, 40, 0);
        custody(60, 2_000_000);
        custody(0, 1_000_000, reserved.claimId()); // donated: 1 ADA, naming the claim
        Report report = reconcile(counters(100, 0, 60, 40, 0, 0, 0, 0), List.of(reserved), Set.of());
        Unit usdm = unit(report, USDM.toString());
        assertThat(report.claimsPendingOnL1()).isEmpty();
        assertThat(usdm.payoutsInFlight()).isZero();
        assertThat(usdm.difference()).isEqualTo(BigInteger.valueOf(-40));
        assertThat(usdm.verdict()).isEqualTo(Verdict.DISCREPANCY);
        // The donation is custody: a lovelace surplus, never an explanation of the USDM shortfall.
        assertThat(unit(report, "lovelace").verdict()).isEqualTo(Verdict.SURPLUS);
        assertThat(report.verdict()).isEqualTo(Verdict.DISCREPANCY);
    }

    @Test
    void aShardAheadExplainsOnlyTheClaimsInItAndTheRemainderIsADiscrepancy() {
        // Fable probe: 99 of 100 USDM missing, one shard ahead holding a claim of 5.
        ClaimLeafV1 reserved = claimIn(7, 5, 0);
        custody(1, 2_000_000);
        Report report = reconcile(counters(100, 0, 95, 5, 0, 0, 0, 0), List.of(reserved), Set.of(7));
        Unit usdm = unit(report, USDM.toString());
        assertThat(usdm.difference()).isEqualTo(BigInteger.valueOf(-99));
        assertThat(usdm.pendingPayouts()).isEqualTo(BigInteger.valueOf(5));
        assertThat(usdm.payoutsInFlight()).isEqualTo(BigInteger.valueOf(5));
        assertThat(usdm.verdict()).isEqualTo(Verdict.DISCREPANCY);
        assertThat(usdm.explanation()).contains("explain 5 and 94 is unexplained");
        assertThat(report.claimsPendingOnL1()).containsExactly(reserved.claimId());
        // A claim in a shard that is not ahead explains nothing.
        Report elsewhere = reconcile(counters(100, 0, 95, 5, 0, 0, 0, 0), List.of(reserved), Set.of(8));
        assertThat(unit(elsewhere, USDM.toString()).payoutsInFlight()).isZero();
        assertThat(elsewhere.claimsPendingOnL1()).isEmpty();
    }

    @Test
    void aDeliberateShortfallIsADiscrepancyAndIsNeverOffsetByAnotherUnit() {
        custody(90, 50_000_000); // ten USDM missing; plenty of lovelace
        Report report = reconcile(counters(100, 0, 100, 0, 1_500_000, 0, 1_500_000, 0), List.of(), Set.of());
        Unit usdm = unit(report, USDM.toString());
        assertThat(usdm.verdict()).isEqualTo(Verdict.DISCREPANCY);
        assertThat(usdm.difference()).isEqualTo(BigInteger.valueOf(-10));
        assertThat(usdm.explanation()).contains("PAUSE").contains("no balance is changed");
        assertThat(unit(report, "lovelace").verdict()).isEqualTo(Verdict.SURPLUS);
        assertThat(report.verdict()).isEqualTo(Verdict.DISCREPANCY);
        // An uncredited deposit of the vault's asset never covers the shortfall either.
        deposit(10, 1_200_000, DepositRecordV1.Status.UNCREDITED);
        assertThat(reconcile(counters(100, 0, 100, 0, 1_500_000, 0, 1_500_000, 0), List.of(), Set.of()).verdict())
                .isEqualTo(Verdict.DISCREPANCY);
    }

    @Test
    void aShortfallWhileAShardIsAheadOfCommittedInsertsIsInFlightNotADiscrepancy() {
        custody(60, 2_000_000); // a payout's continuation already spent by a later one: not in the scan
        ClaimLeafV1 paid = claimIn(3, 40, 0);
        Report report = reconcile(counters(100, 0, 60, 40, 0, 0, 0, 0), List.of(paid), Set.of(3));
        assertThat(unit(report, USDM.toString()).verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(report.verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(report.notes()).anyMatch(note -> note.contains("[3]"));
    }

    @Test
    void anIncompleteScanOrAnUnknownL1PointIsUnavailableAndNeverAlerts() {
        custody(1, 2_000_000);
        Consensus owed = new Consensus(12, counters(100, 0, 100, 0, 0, 0, 0, 0), id -> Optional.empty(), List.of(),
                Set.of());
        Report incomplete = VaultReconciliation.reconcile(VAULT, owed, new L1Scan(Optional.of(POINT), l1, false));
        assertThat(incomplete.verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(incomplete.units()).isEmpty();
        // 99 USDM short, but the scan names no L1 point: never a DISCREPANCY.
        Report unnamed = VaultReconciliation.reconcile(VAULT, owed, new L1Scan(Optional.empty(), l1, true));
        assertThat(unnamed.verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(unnamed.notes()).anyMatch(note -> note.contains("slot and block hash"));
    }

    @Test
    void theAdaVaultHasOneUnitAndAFloorTopUpIsAcceptedCustody() {
        output(ADA_ADDRESS, 30_000_000, Map.of(), continuation());
        output(ADA_ADDRESS, 2_000_000, Map.of(), VaultOutputShape.floorTopUpDatum());
        Report report = VaultReconciliation.reconcile(ADA_VAULT, new Consensus(3,
                counters(30_000_000, 0, 30_000_000, 0, 0, 0, 0, 0), id -> Optional.empty(), List.of(), Set.of()),
                new L1Scan(Optional.of(new VaultReconciliation.L1Point(1, "cd".repeat(32))), l1, true));
        assertThat(report.units()).extracting(Unit::unit).containsExactly("lovelace");
        assertThat(report.units().getFirst().accepted()).isEqualTo(BigInteger.valueOf(32_000_000));
        assertThat(report.units().getFirst().floor()).isEqualTo(FLOOR);
        assertThat(report.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(report.toMap()).containsEntry("verdict", "BALANCED").containsEntry("committedHeight", 3L);
    }

    @Test
    void anAdaVaultShortOnlyByItsFloorIsADiscrepancy() {
        // Fable probe: the ADA vault holds 10 ADA and owes 10 ADA; its 2 ADA floor is missing.
        output(ADA_ADDRESS, 10_000_000, Map.of(), continuation());
        Report report = VaultReconciliation.reconcile(ADA_VAULT, new Consensus(3,
                counters(10_000_000, 0, 10_000_000, 0, 0, 0, 0, 0), id -> Optional.empty(), List.of(), Set.of()),
                new L1Scan(Optional.of(POINT), l1, true));
        Unit lovelace = report.units().getFirst();
        assertThat(lovelace.difference()).isEqualTo(BigInteger.valueOf(-2_000_000));
        assertThat(lovelace.verdict()).isEqualTo(Verdict.DISCREPANCY);
        assertThat(lovelace.explanation()).contains("plus the floor 2000000").contains("operator floor");
        assertThat(report.verdict()).isEqualTo(Verdict.DISCREPANCY);
    }

    @Test
    void theReportIsDeterministicWhateverTheScanOrder() {
        custody(60, 2_000_000);
        deposit(40, 1_500_000, DepositRecordV1.Status.CREDITED);
        deposit(9, 1_300_000, null);
        AssetAccounts.VaultCounters counters = counters(100, 0, 100, 0, 1_500_000, 0, 1_500_000, 0);
        Report forward = reconcile(counters, List.of(), Set.of());
        java.util.Collections.reverse(l1);
        l1.add(l1.getFirst()); // a page read twice
        assertThat(reconcile(counters, List.of(), Set.of()).toMap()).isEqualTo(forward.toMap());
    }
}
