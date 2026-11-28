package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation;
import org.yanoproject.x.assets.cardano.support.settlement.VaultReconciliation.Verdict;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assets.cardano.gateway.AssetSettlementTasksTest.*;

/**
 * ADR-059 D15 node-local reconciliation through the executor factory, over the synthetic L1 view and committed state
 * of {@link AssetSettlementTasksTest}: its verdicts per unit, the task product's snapshot in the status and metrics,
 * the JSON report file, and a deliberate shortfall that is flagged and changes nothing.
 */
class AssetReconciliationTaskTest {
    @TempDir
    Path temp;

    final AssetSettlementTasksTest world = new AssetSettlementTasksTest();
    AssetSettlementTasksTest.Node node;

    @BeforeEach
    void seed() {
        world.temp = temp;
        node = world.new Node(MEMBER_SEED);
        world.seed(); // two accepted continuations of 100 000 USDM and 50 ADA each; 16 empty shards
        world.anchor();
    }

    static AssetAccounts.VaultCounters counters(long available, long reserved, long lovelaceAvailable,
                                                long lovelaceReserved) {
        return new AssetAccounts.VaultCounters(BigInteger.valueOf(available + reserved), BigInteger.ZERO,
                BigInteger.valueOf(available), BigInteger.valueOf(reserved),
                BigInteger.valueOf(lovelaceAvailable + lovelaceReserved), BigInteger.ZERO,
                BigInteger.valueOf(lovelaceAvailable), BigInteger.valueOf(lovelaceReserved));
    }

    /** A co-signing member's products, with the reconciliation writing its report under {@code temp}. */
    List<AppEffectExecutor> products(Map<String, String> extra) throws Exception {
        Map<String, String> config = world.config(false, false);
        config.put("reconciliation.report-file", temp.resolve("reports/reconciliation.json").toString());
        config.putAll(extra);
        return AssetSettlementExecutorFactory.create(config, node, () -> world.now);
    }

    ReconciliationTask task(List<AppEffectExecutor> products) {
        return ((AssetSettlementExecutorFactory.CosignOnly) products.getFirst()).reconciliation().orElseThrow();
    }

    static EffectExecutorOperationalSnapshot snapshot(List<AppEffectExecutor> products, String id) {
        return products.stream().filter(product -> product.id().equals(id)).findFirst().orElseThrow()
                .operationalSnapshot();
    }

    static VaultReconciliation.Unit unit(VaultReconciliation.Report report, String name) {
        return report.units().stream().filter(unit -> unit.unit().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void matchingCustodyIsBalancedAndTheReportReachesStatusAndItsFile() throws Exception {
        node.ledger.counters = counters(200_000, 0, 0, 0);
        List<AppEffectExecutor> products = products(Map.of());
        assertThat(products).extracting(AppEffectExecutor::id)
                .containsExactly(AssetSettlementExecutorFactory.SCHEME, TaskProduct.RECONCILIATION);
        ReconciliationTask task = task(products);
        String outcome = task.runOnce();
        VaultReconciliation.Report report = task.lastReports().getFirst();
        assertThat(outcome).contains("SURPLUS");
        assertThat(unit(report, USDM.toString()).verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(unit(report, "lovelace").difference()).isEqualTo(BigInteger.valueOf(100_000_000)); // the floor
        assertThat(report.height()).isEqualTo(1_000);
        assertThat(report.outputs()).isEqualTo(2);

        EffectExecutorOperationalSnapshot snapshot = snapshot(products, TaskProduct.RECONCILIATION);
        assertThat(snapshot.attempts()).isEqualTo(1);
        assertThat(snapshot.successes()).isEqualTo(1);
        assertThat(snapshot.readiness()).isEqualTo(EffectExecutorOperationalSnapshot.Readiness.READY);
        String json = Files.readString(temp.resolve("reports/reconciliation.json"));
        assertThat(json).startsWith("{\"schema\":\"yano-x-asset-reconciliation-v1\"")
                .contains("\"verdict\":\"BALANCED\"").contains("\"committedHeight\":1000");
    }

    @Test
    void aDeliberateShortfallIsFlaggedAsAConflictAndNothingIsWritten() throws Exception {
        node.ledger.counters = counters(250_000, 0, 0, 0); // 50 000 USDM more owed than the vault holds
        List<AppEffectExecutor> products = products(Map.of());
        ReconciliationTask task = task(products);
        task.runOnce();
        VaultReconciliation.Report report = task.lastReports().getFirst();
        assertThat(report.verdict()).isEqualTo(Verdict.DISCREPANCY);
        assertThat(unit(report, USDM.toString()).difference()).isEqualTo(BigInteger.valueOf(-50_000));
        EffectExecutorOperationalSnapshot snapshot = snapshot(products, TaskProduct.RECONCILIATION);
        assertThat(snapshot.terminalFailures()).isEqualTo(1);
        assertThat(snapshot.readiness()).isEqualTo(EffectExecutorOperationalSnapshot.Readiness.DEGRADED);
        assertThat(snapshot.failureCode()).isEqualTo(EffectExecutorOperationalSnapshot.FailureCode.CONFLICT);
        // Read-only: no transaction, no diffusion, the committed counters untouched.
        assertThat(world.submitted).isEmpty();
        assertThat(world.bus).isEmpty();
        assertThat(node.ledger.counters).isEqualTo(counters(250_000, 0, 0, 0));
        assertThat(Files.readString(temp.resolve("reports/reconciliation.json"))).contains("DISCREPANCY");

        // Back in line: the next pass is a success again.
        node.ledger.counters = counters(200_000, 0, 0, 0);
        task.runOnce();
        assertThat(snapshot(products, TaskProduct.RECONCILIATION).readiness())
                .isEqualTo(EffectExecutorOperationalSnapshot.Readiness.READY);
    }

    @Test
    void depositsAndReservedClaimsAreClassifiedAgainstCommittedRecords() throws Exception {
        world.deposit(0, 900, DepositAdmission.Reason.NONE, node); // credited: 500 USDM, 2 ADA of min-ADA credit
        world.deposit(1, 900, DepositAdmission.Reason.UNKNOWN_BENEFICIARY, node); // uncredited
        ClaimLeafV1 claim = world.reserve(3, 7, 900, NOW - DAY, node); // 107 USDM reserved, still in custody
        node.ledger.counters = counters(200_500 - 107, 107, 2_000_000 - 1_500_000, 1_500_000);
        ReconciliationTask task = task(products(Map.of()));
        task.runOnce();
        VaultReconciliation.Report report = task.lastReports().getFirst();
        VaultReconciliation.Unit usdm = unit(report, USDM.toString());
        assertThat(usdm.creditedUnaccepted()).isEqualTo(BigInteger.valueOf(500));
        assertThat(usdm.uncredited()).isEqualTo(BigInteger.valueOf(501));
        assertThat(usdm.verdict()).isEqualTo(Verdict.BALANCED);
        assertThat(report.reservedClaims()).isEqualTo(1);
        assertThat(report.oldestReservedAtMs()).hasValue(claim.reservedAtMs());
        assertThat(report.claimsPaidOnL1()).isEmpty();
    }

    @Test
    void aShardAheadOfCommittedInsertsMakesAShortfallInFlight() throws Exception {
        node.ledger.counters = counters(200_000, 0, 0, 0);
        world.reserve(3, 8, 900, NOW - DAY, node);
        node.ledger.counters = counters(200_000 - 108, 108, 0, 0);
        // A Settle of the claim landed on L1: its payout left the vault, and shard 3 holds the insert. The payout's
        // continuation was then spent by a later transaction, so only the shard tells.
        world.spend("61".repeat(32) + "#1");
        world.put(AssetSettlementTasksTest.utxo("7f".repeat(32), 0, enterprise(VAULT_HASH), 50_000_000,
                AssetSettlementTasksTest.asset("f0".repeat(28), "5553444d", 100_000 - 108),
                java.util.HexFormat.of().parseHex("d87a8180"), null));
        world.spend("62".repeat(32) + "#3");
        world.put(AssetSettlementTasksTest.utxo("7e".repeat(32), 0, enterprise(SHARD_HASH), 2_000_000,
                AssetSettlementTasksTest.asset(SHARD_POLICY, "03", 1),
                AssetSettlementTasksTest.serialize(ConstrPlutusData.of(0, BigIntPlutusData.of(1),
                        BigIntPlutusData.of(3), BytesPlutusData.of(filled(32, 0x99)))), null));
        ReconciliationTask task = task(products(Map.of()));
        task.runOnce();
        VaultReconciliation.Report report = task.lastReports().getFirst();
        assertThat(report.verdict()).isEqualTo(Verdict.IN_FLIGHT);
        assertThat(report.notes()).anyMatch(note -> note.contains("[3]"));
    }

    @Test
    void anUnreadableViewIsUnavailableNotAnAlertAndTheTaskCanBeTurnedOff() throws Exception {
        node.ledger.counters = counters(250_000, 0, 0, 0);
        world.brokenAddresses.add(enterprise(VAULT_HASH));
        List<AppEffectExecutor> products = products(Map.of());
        ReconciliationTask task = task(products);
        task.runOnce();
        assertThat(task.lastReports().getFirst().verdict()).isEqualTo(Verdict.UNAVAILABLE);
        EffectExecutorOperationalSnapshot snapshot = snapshot(products, TaskProduct.RECONCILIATION);
        assertThat(snapshot.retryableFailures()).isEqualTo(1);
        assertThat(snapshot.terminalFailures()).isZero();

        List<AppEffectExecutor> off = products(Map.of("reconciliation.interval-ms", "0"));
        assertThat(off).extracting(AppEffectExecutor::id).containsExactly(AssetSettlementExecutorFactory.SCHEME);
    }

    @Test
    void observersCountWhatTheyReportAndTheirLastSlot() {
        var deposits = new AssetVaultDepositObserver("deposits", depositObserverSettings());
        deposits.observe(9_000, SettlementFixtures.BLOCK_HASH, SettlementFixtures.block(List.of(),
                List.of(SettlementFixtures.tx(0x51, List.of(),
                        SettlementFixtures.output(SettlementFixtures.USDM_VAULT, null, SettlementFixtures.lovelace(2_000_000)),
                        SettlementFixtures.output(SettlementFixtures.USDM_VAULT, SettlementFixtures.continuation(),
                                SettlementFixtures.lovelace(2_000_000), SettlementFixtures.usdm(5)))),
                List.of(SettlementFixtures.spends())));
        @SuppressWarnings("unchecked")
        Map<String, Object> observed = (Map<String, Object>) deposits.status().get("observed");
        assertThat(observed).containsEntry("blocks", 1L).containsEntry("lastSlot", 9_000L);
        assertThat(observed.get("counts")).isEqualTo(Map.of("OTHER", 1L, "custody", 1L));
    }

    static Map<String, String> depositObserverSettings() {
        Map<String, String> settings = new java.util.LinkedHashMap<>(SettlementFixtures.shardSettings());
        settings.keySet().removeIf(key -> key.contains(".shard-"));
        return settings;
    }
}
