package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4: the permissionless {@code Consolidate} redeemer merges accepted custody in EXIT mode, so fragmented custody,
 * such as many small accepted outputs, never blocks a {@code BalanceExit} by transaction size. It is rejected in
 * NORMAL, where anyone could use it to invalidate in-flight payouts every round (and {@code Settle} merges inputs).
 * It spends only accepted, clean vault outputs, produces exactly one exact {@code VaultContinuationV1([])}, inserts no
 * nullifier and pays nothing out of the vault.
 */
class VaultConsolidateConformanceTest extends GatewayTestBase {
    static final PlutusData EXIT = exitMode(filled(0x55, 32));

    private static TxOutRef custodyRef(int i) {
        return ref(0x40 + i / 200, i % 200);
    }

    /** {@code count} accepted inputs (alternating continuation and top-up) merged into one continuation. */
    static Tx consolidation(AssetId asset, int count, PlutusData mode, boolean follow) {
        Tx tx = new Tx();
        Value total = lovelace(0);
        for (int i = 0; i < count; i++) {
            Value value = i % 3 == 2 ? lovelace(1_500_000) : assetValue(asset, 1_200_000, 1);
            PlutusData datum = i % 3 == 2 ? floorDatum() : continuationDatum(i % 3 == 0 ? List.of()
                    : List.of(shardId(i % 16, i)));
            tx.spend(vaultInput(custodyRef(i), value, datum), i > 0 && follow ? follow() : consolidate());
            total = total.merge(value);
        }
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.output(payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28)), lovelace(4_600_000L)));
        tx.output(out(vaultAddress(), total, continuationDatum(List.of())));
        tx.reference(exitModeInput(mode));
        return tx;
    }

    @Test
    void anyoneConsolidatesAcceptedCustodyInExitModeOnly() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            for (boolean follow : new boolean[] {true, false}) {
                Tx tx = consolidation(asset, 3, EXIT, follow);
                for (int i = 0; i < 3; i++) {
                    ok(vault(asset), tx.spending(custodyRef(i)));
                }
                rejected(vault(asset), consolidation(asset, 3, normalMode(), follow).spending(custodyRef(0)));
            }
        }
    }

    /** B5: a floor top-up is accepted custody only when it holds lovelace alone, as admission requires. */
    @Test
    void aFloorTopUpCarryingATokenIsNotAcceptedCustody() {
        Tx tx = consolidation(TOKEN, 3, EXIT, true);
        Spent third = tx.inputs.get(2);
        Value withToken = lovelace(1_500_000).merge(token(TOKEN.policyId(), TOKEN.assetName(), 1));
        tx.inputs.set(2, new Spent(vaultInput(custodyRef(2), withToken, floorDatum()), third.redeemer()));
        TxOut continuation = tx.outputs.get(1);
        tx.outputs.set(1, out(vaultAddress(), continuation.value().merge(token(TOKEN.policyId(), TOKEN.assetName(),
                1)), continuationDatum(List.of())));
        rejected(vault(TOKEN), tx.spending(custodyRef(0)));
    }

    /**
     * Fragmented custody: 20 one-unit outputs merge in one transaction, every script within the unit limits (the
     * lead checks every input; each follower only finds the lead). {@code GatewayBudgetTest} measures the largest
     * batch; repeated batches defragment any number of outputs.
     */
    @Test
    void twentyFragmentsMergeInOneTransaction() {
        Tx tx = consolidation(TOKEN, 20, EXIT, true);
        long cpu = 0;
        long mem = 0;
        for (int i = 0; i < 20; i++) {
            EvalResult result = run(vault(TOKEN), tx.spending(custodyRef(i)));
            assertThat(result.isSuccess()).as("input %d", i).isTrue();
            cpu += result.budgetConsumed().cpuSteps();
            mem += result.budgetConsumed().memoryUnits();
        }
        assertThat(cpu).isLessThan(MAX_TX_CPU);
        assertThat(mem).isLessThan(MAX_TX_MEM);
    }

    @Test
    void consolidationNeedsTwoAcceptedCleanInputs() {
        rejected(vault(TOKEN), consolidation(TOKEN, 1, EXIT, true).spending(custodyRef(0)));

        Tx deposit = consolidation(TOKEN, 2, EXIT, true);
        Spent second = deposit.inputs.get(1);
        deposit.inputs.set(1, new Spent(vaultInput(custodyRef(1), second.input().resolved().value(),
                depositDatum(GatewayFixtures.enterpriseKeyAddress(filled(0xc1, 28)), filled(0xc1, 28))),
                follow()));
        rejected(vault(TOKEN), deposit.spending(custodyRef(0)));

        Tx datumless = consolidation(TOKEN, 2, EXIT, true);
        datumless.inputs.set(1, new Spent(datumlessVaultInput(custodyRef(1), second.input().resolved().value()),
                follow()));
        rejected(vault(TOKEN), datumless.spending(custodyRef(0)));

        Tx dirty = consolidation(TOKEN, 2, EXIT, true);
        Value junk = second.input().resolved().value().merge(token(filled(0x7c, 28), new byte[] {1}, 1));
        dirty.inputs.set(1, new Spent(vaultInput(custodyRef(1), junk, continuationDatum(List.of())), follow()));
        dirty.outputs.set(1, out(vaultAddress(), dirty.outputs.get(1).value().merge(token(filled(0x7c, 28),
                new byte[] {1}, 1)), continuationDatum(List.of())));
        rejected(vault(TOKEN), dirty.spending(custodyRef(0)));
    }

    @Test
    void theContinuationIsOneExactEmptyContinuationAndNothingElseMoves() {
        Tx short_ = consolidation(TOKEN, 2, EXIT, true);
        TxOut continuation = short_.outputs.get(1);
        short_.outputs.set(1, out(vaultAddress(), continuation.value().merge(lovelace(-1)),
                continuationDatum(List.of())));
        rejected(vault(TOKEN), short_.spending(custodyRef(0)));

        Tx skimmed = consolidation(TOKEN, 2, EXIT, true);
        skimmed.outputs.set(1, out(vaultAddress(), continuation.value().merge(token(TOKEN.policyId(),
                TOKEN.assetName(), -1)), continuationDatum(List.of())));
        rejected(vault(TOKEN), skimmed.spending(custodyRef(0)));

        Tx ids = consolidation(TOKEN, 2, EXIT, true);
        ids.outputs.set(1, out(vaultAddress(), continuation.value(), continuationDatum(List.of(shardId(0, 1)))));
        rejected(vault(TOKEN), ids.spending(custodyRef(0)));

        Tx two = consolidation(TOKEN, 2, EXIT, true);
        two.output(out(vaultAddress(), lovelace(2_000_000), floorDatum()));
        rejected(vault(TOKEN), two.spending(custodyRef(0)));

        Tx script = consolidation(TOKEN, 2, EXIT, true);
        script.outputs.set(1, new TxOut(continuation.address(), continuation.value(), continuation.datum(),
                Optional.of(new org.julclang.ledger.ScriptHash(filled(0x5b, 28)))));
        rejected(vault(TOKEN), script.spending(custodyRef(0)));

        Tx staked = consolidation(TOKEN, 2, EXIT, true);
        staked.outputs.set(1, new TxOut(GatewayFixtures.scriptAddressWithStake(VAULT_HASH, filled(0x99, 28)),
                continuation.value(), continuation.datum(), Optional.empty()));
        rejected(vault(TOKEN), staked.spending(custodyRef(0)));

        // No nullifier: a shard input riding along is a foreign script input.
        Tx shard = consolidation(TOKEN, 2, EXIT, true);
        shard.spend(new TxInInfo(shardRef(0), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {0}, 1)),
                new OutputDatum.OutputDatumInline(shardDatum(0, new byte[32])), Optional.empty())),
                PlutusData.constr(0, PlutusData.list()));
        rejected(vault(TOKEN), shard.spending(custodyRef(0)));

        // The exit-mode thread must be referenced.
        Tx unmoded = consolidation(TOKEN, 2, EXIT, true);
        unmoded.references.clear();
        rejected(vault(TOKEN), unmoded.spending(custodyRef(0)));
    }
}
