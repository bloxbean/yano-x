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
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 D15 reconciliation over synthetic L1 scans: accepted custody, credited-but-unaccepted, uncredited,
 * unattributed and in-flight outputs; payouts L1 has seen but committed state has not confirmed; a deliberate
 * shortfall that is flagged; and units that never offset each other.
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
    static final VaultReconciliation.Vault VAULT = new VaultReconciliation.Vault(VAULT_ID, USDM, ADDRESS);

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
                id -> Optional.ofNullable(records.get(id)), reserved, ahead), new L1Scan(OptionalLong.of(900), l1,
                true));
    }

    static Unit unit(Report report, String name) {
        return report.units().stream().filter(unit -> unit.unit().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void custodyThatMatchesLiabilitiesBalancesAndTheFloorIsALovelaceSurplus() {
        custody(100, 3_500_000); // 100 USDM of liabilities, 1.5 ADA of min-ADA credit, a 2 ADA floor
        Report report = reconcile(counters(100, 0, 60, 40, 1_500_000, 0, 500_000, 1_000_000), List.of(), Set.of());
        assertThat(unit(report, USDM.toString()).verdict()).isEqualTo(Verdict.BALANCED);
        Unit lovelace = unit(report, "lovelace");
        assertThat(lovelace.verdict()).isEqualTo(Verdict.SURPLUS);
        assertThat(lovelace.difference()).isEqualTo(BigInteger.valueOf(2_000_000));
        assertThat(lovelace.explanation()).contains("floor");
        assertThat(report.verdict()).isEqualTo(Verdict.SURPLUS);
        assertThat(report.height()).isEqualTo(12);
        assertThat(report.l1Slot()).hasValue(900);
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
        assertThat(lovelace.difference()).isEqualTo(BigInteger.valueOf(2_000_000));
        assertThat(report.outputs()).isEqualTo(5);
        assertThat(report.notes()).anyMatch(note -> note.contains("other than the vault's"));
    }

    @Test
    void aPayoutL1HasSeenButCommittedStateHasNotConfirmedIsInFlight() {
        ClaimLeafV1 paid = claim(1, 40, 1_000_000, 5_000);
        ClaimLeafV1 waiting = claim(2, 10, 0, 4_000);
        custody(60, 2_500_000, paid.claimId()); // the continuation of the Settle that paid `paid`
        Report report = reconcile(counters(100, 0, 50, 50, 1_500_000, 0, 500_000, 1_000_000),
                List.of(paid, waiting), Set.of());
        assertThat(report.claimsPaidOnL1()).containsExactly(paid.claimId());
        assertThat(unit(report, USDM.toString()).payoutsInFlight()).isEqualTo(BigInteger.valueOf(40));
        assertThat(unit(report, USDM.toString()).verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(unit(report, "lovelace").payoutsInFlight()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertThat(report.reservedClaims()).isEqualTo(2);
        assertThat(report.oldestReservedAtMs()).hasValue(4_000);
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
        Report report = reconcile(counters(100, 0, 60, 40, 0, 0, 0, 0), List.of(claim(3, 40, 0, 1)), Set.of(3));
        assertThat(unit(report, USDM.toString()).verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(report.verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(report.notes()).anyMatch(note -> note.contains("[3]"));
    }

    @Test
    void anIncompleteScanIsUnavailableAndNeverAlerts() {
        custody(1, 2_000_000);
        Report report = VaultReconciliation.reconcile(VAULT, new Consensus(12,
                counters(100, 0, 100, 0, 0, 0, 0, 0), id -> Optional.empty(), List.of(), Set.of()),
                new L1Scan(OptionalLong.of(900), l1, false));
        assertThat(report.verdict()).isEqualTo(Verdict.UNAVAILABLE);
        assertThat(report.units()).isEmpty();
    }

    @Test
    void theAdaVaultHasOneUnitAndAFloorTopUpIsAcceptedCustody() {
        VaultReconciliation.Vault ada = new VaultReconciliation.Vault(ADA_VAULT_ID, AssetId.ADA, ADA_ADDRESS);
        output(ADA_ADDRESS, 30_000_000, Map.of(), continuation());
        output(ADA_ADDRESS, 2_000_000, Map.of(), VaultOutputShape.floorTopUpDatum());
        Report report = VaultReconciliation.reconcile(ada, new Consensus(3,
                counters(30_000_000, 0, 30_000_000, 0, 0, 0, 0, 0), id -> Optional.empty(), List.of(), Set.of()),
                new L1Scan(OptionalLong.of(1), l1, true));
        assertThat(report.units()).extracting(Unit::unit).containsExactly("lovelace");
        assertThat(report.units().getFirst().accepted()).isEqualTo(BigInteger.valueOf(32_000_000));
        assertThat(report.verdict()).isEqualTo(Verdict.SURPLUS);
        assertThat(report.toMap()).containsEntry("verdict", "SURPLUS").containsEntry("committedHeight", 3L);
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
