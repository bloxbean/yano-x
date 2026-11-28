package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.Value;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SweepCommitment;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-061 §10 P2: an honest transaction of every action, each evaluated with every script it runs: the pinned PLB
 * (per vault input), dispatcher and transfer delegate (cip113-programmable-tokens {@code 6b75ba32}), the test
 * transfer hooks, the custody script {@code h}, the exit delegate {@code e} and the shard binding variant. The same
 * runs measure the whole transaction's execution units (P6): {@code BUDGET|} lines.
 */
class ProgrammableVaultCompositionTest extends ProgrammableTestBase {
    @Test
    void theBlueprintIsThePinnedOne() {
        for (String title : List.of("programmable_logic_base.programmable_logic_base.spend",
                "programmable_logic_global.programmable_logic_global.withdraw", "transfer.transfer.withdraw")) {
            assertThat(hex(Cip113Blueprint.hash(Cip113Blueprint.template(title))))
                    .as(title).isEqualTo(Cip113Blueprint.blueprintHash(title));
        }
    }

    @Test
    void settle() {
        for (int k : new int[] {1, 4, 8, 16}) {
            List<ClaimFx> claims = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                claims.add(walletClaim(4, 10 + i));
            }
            report("Settle", k, settle(claims, 4));
        }
    }

    @Test
    void exitAgainstTheAnchor() {
        for (int k : new int[] {1, 4, 8}) {
            List<ClaimFx> claims = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                claims.add(walletClaim(6, 30 + i));
            }
            report("Exit", k, exit(claims, 6, false));
        }
    }

    @Test
    void exitInExitMode() {
        report("Exit(EXIT)", 2, exit(List.of(walletClaim(6, 40), walletClaim(6, 41)), 6, true));
    }

    @Test
    void acceptCosigned() {
        for (int k : new int[] {1, 4, 8}) {
            report("AcceptCosigned", k, acceptCosigned(deposits(k)));
        }
    }

    @Test
    void acceptProved() {
        for (int k : new int[] {1, 4, 8}) {
            report("AcceptProved", k, acceptProved(deposits(k), false));
        }
        report("AcceptProved(EXIT)", 2, acceptProved(deposits(2), true));
    }

    @Test
    void sweepByOutpoint() {
        // A non-deposit-shaped output holding the vault asset and an unregistered token: swept whole, by outpoint.
        Value junk = assetValue(3_000_000L, 7).merge(token(UNREGISTERED.policyId(), UNREGISTERED.assetName(), 9));
        TxInInfo input = walletInput(W1, junk, PlutusData.integer(5));
        SweepCommitment commitment = SweepCommitment.of(ASSET, Hash32.of(W1.txId().hash()), 0,
                BigInteger.valueOf(3_000_000L), Map.of(ASSET, BigInteger.valueOf(7), UNREGISTERED,
                        BigInteger.valueOf(9)));
        report("SweepExit(outpoint)", 1, sweep(input, commitment, false));
    }

    @Test
    void sweepByDepositId() {
        // An uncredited deposit-shaped output: keyed by its origin DepositId, committing the recorded lovelace.
        TxInInfo input = depositInput(W1, filled(0x45, 32), 2, assetValue(2_500_000L, 300));
        SweepCommitment commitment = new SweepCommitment(depositIdOf(input).bytes(),
                AssetValue.single(ASSET, BigInteger.valueOf(300)), BigInteger.valueOf(2_000_000L));
        report("SweepExit(DepositId)", 1, sweep(input, commitment, true));
    }

    @Test
    void refundUncredited() {
        report("RefundUncredited", 1, refundUncredited(mixedDeposit(), 0));
    }

    @Test
    void refundAbsent() {
        report("RefundAbsent", 1, refundAbsent(mixedDeposit()));
    }

    @Test
    void balanceExit() {
        report("BalanceExit", 1, balanceExit(1, 0));
        report("BalanceExit", 4, balanceExit(4, 0));
    }

    @Test
    void consolidateMerges() {
        for (int k : new int[] {2, 4, 8}) {
            List<TxInInfo> inputs = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                inputs.add(i % 2 == 0
                        ? walletInput(ref(0x61, i), assetValue(3_000_000L, 100 + i), continuation(List.of()))
                        : walletInput(ref(0x61, i), lovelace(FLOOR), floorDatum()));
            }
            report("Consolidate", k, delegated(merge(inputs, consolidate(), exitMode(filled(0x47, 32))), consolidate()));
        }
    }

    @Test
    void positiveRewardBalancesAreWithdrawnInFull() {
        // D2, I43: h, e and the dispatcher carry reward balances; each withdrawal takes the full balance to change.
        FullTx settle = settle(List.of(walletClaim(4, 1)), 4);
        settle.withdrawScript(custodyHash(), 3_000_000, settle.redeemerOf(custodyHash()));
        settle.withdrawScript(CIP.dispatcherHash(), 1_000_000, transferAct());
        passes(settle);
        passes(refundUncredited(mixedDeposit(), 2_000_000));
        passes(balanceExit(1, 2_500_000));
        FullTx sweep = sweepOfDeposit();
        sweep.withdrawScript(custodyHash(), 4_000_000, sweep.redeemerOf(custodyHash()));
        passes(sweep);
    }

    @Test
    void theCustodyCredentialsRegister() {
        FullTx tx = frame(normalMode());
        tx.certificates.add(PlutusData.constr(0, Cip113Blueprint.scriptCredential(custodyHash()), PlutusData.constr(1)));
        tx.certificates.add(PlutusData.constr(0, Cip113Blueprint.scriptCredential(exitDelegateHash()),
                PlutusData.constr(1)));
        Map<String, EvalResult> results = tx.evaluate();
        assertThat(results).containsKeys("publish:h#0", "publish:e#1");
        results.values().forEach(r -> assertThat(r.isSuccess()).isTrue());
    }

    // ------------------------------------------------------------------

    List<TxInInfo> deposits(int k) {
        List<TxInInfo> deposits = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            deposits.add(depositInput(ref(0x61, i), filled(0x40 + i, 32), 1, assetValue(2_000_000L, 500)));
        }
        return deposits;
    }

    /** An uncredited deposit holding the vault asset, a second registered policy and an unregistered token. */
    static TxInInfo mixedDeposit() {
        Value value = assetValue(2_000_000L, 500).merge(token(ASSET_B.policyId(), ASSET_B.assetName(), 40))
                .merge(token(UNREGISTERED.policyId(), UNREGISTERED.assetName(), 3));
        return depositInput(W1, filled(0x46, 32), 0, value);
    }

    FullTx sweepOfDeposit() {
        TxInInfo input = depositInput(W1, filled(0x45, 32), 2, assetValue(2_500_000L, 300));
        return sweep(input, new SweepCommitment(depositIdOf(input).bytes(),
                AssetValue.single(ASSET, BigInteger.valueOf(300)), BigInteger.valueOf(2_000_000L)), true);
    }

    /** {@code SweepExit} of {@code input} under {@code commitment}, reserved and matured, paying a smart wallet. */
    FullTx sweep(TxInInfo input, SweepCommitment commitment, boolean depositShaped) {
        byte[] recipient = smartWallet(filled(0x8a, 28));
        long sequence = 7;
        Hash32 claimId = shardedClaimId(commitment, recipient, sequence);
        int shard = claimId.bytes()[31] & 0x0f;
        long reservedAtMs = NOW - 2 * DAY;
        StateTrie state = new StateTrie().fill("ap/account/", 64)
                .put(STATUS_KEY, statusLeaf(DomainStatusV1.Status.ACTIVE, 0, EXIT_DELAY))
                .put(GatewayFixtures.concat(CLAIM_PREFIX, claimId.bytes()),
                        commitment.leaf(claimId, VAULT_ID, recipient, 90, reservedAtMs).encode());
        List<PlutusData> assets = new ArrayList<>();
        for (Map.Entry<AssetId, BigInteger> entry : commitment.value().entries().entrySet()) {
            assets.add(PlutusData.constr(0, PlutusData.bytes(entry.getKey().policyId()),
                    PlutusData.bytes(entry.getKey().assetName()), PlutusData.integer(entry.getValue())));
        }
        if (depositShaped) {
            assets.add(PlutusData.constr(0, PlutusData.bytes(new byte[0]), PlutusData.bytes(new byte[0]),
                    PlutusData.integer(commitment.vaultLovelace())));
        }
        PlutusData action = PlutusData.constr(2, PlutusData.bytes(claimId.bytes()), PlutusData.bytes(recipient),
                PlutusData.integer(90), PlutusData.integer(reservedAtMs),
                PlutusData.list(assets.toArray(new PlutusData[0])),
                state.proof(GatewayFixtures.concat(CLAIM_PREFIX, claimId.bytes())), state.proof(STATUS_KEY),
                PlutusData.integer(sequence), PlutusData.integer(shard));
        FullTx tx = frame(normalMode());
        tx.spend(input, null);
        Inserts inserts = new ShardTrie(shard).seed(5).plan(List.of(claimId.bytes()));
        tx.spend(shardInput(shard, inserts.before()), inserts.redeemer());
        tx.output(out(ledger(recipient), input.resolved().value(),
                PlutusData.constr(4, PlutusData.bytes(claimId.bytes()))));
        tx.output(shardOutput(shard, inserts.after()));
        tx.output(payTo(CHANGE, lovelace(40_000_000L)));
        tx.reference(anchorInput(anchorDatum(2, state.root(), 77, MEMBERS, THRESHOLD, NOW)));
        tx.withdrawScript(custodyHash(), 0, action);
        return withHooks(tx);
    }

    /** The sweep claim's id; the sequence is chosen by the caller, so the shard follows from the id. */
    static Hash32 shardedClaimId(SweepCommitment commitment, byte[] recipient, long sequence) {
        return commitment.claimId(DOMAIN, VAULT_ID, recipient, sequence);
    }

    /** {@code RefundUncredited}: the deposit's UNCREDITED record proved under {@code frozenRoot}, through {@code e}. */
    FullTx refundUncredited(TxInInfo deposit, long delegateBalance) {
        StateTrie state = new StateTrie().fill("ap/account/", 32)
                .put(depositKey(deposit), record(deposit, DepositRecordV1.Status.UNCREDITED,
                        DepositAdmission.Reason.MIXED_ASSETS).encode());
        PlutusData action = PlutusData.constr(6, state.proof(depositKey(deposit)));
        FullTx tx = refund(deposit, action, state.root());
        tx.withdrawScript(exitDelegateHash(), delegateBalance, PlutusData.constr(0, PlutusData.bytes(custodyHash()),
                action));
        return tx;
    }

    /** {@code RefundAbsent}: the deposit's id is absent under {@code frozenRoot}, through {@code e}. */
    FullTx refundAbsent(TxInInfo deposit) {
        StateTrie state = new StateTrie().fill("ap/account/", 32);
        PlutusData action = PlutusData.constr(5, state.absence(depositKey(deposit)));
        return delegated(refund(deposit, action, state.root()), action);
    }

    FullTx refund(TxInInfo deposit, PlutusData action, byte[] frozenRoot) {
        FullTx tx = frame(exitMode(frozenRoot));
        tx.spend(deposit, null);
        tx.output(out(ledger(smartWallet(REFUND_KEY)), deposit.resolved().value(), null));
        tx.output(payTo(CHANGE, lovelace(40_000_000L)));
        tx.signers.add(new org.julclang.ledger.PubKeyHash(REFUND_KEY));
        tx.withdrawScript(custodyHash(), 0, action);
        return withHooks(tx);
    }

    /** {@code BalanceExit} of one exit leaf from {@code inputs} accepted vault inputs, through {@code e}. */
    FullTx balanceExit(int inputs, long delegateBalance) {
        byte[] holderId = filled(0xb1, 20);
        long amount = 250;
        long vaultLovelace = 1_200_000L;
        StateTrie state = new StateTrie().fill("ap/account/", 64);
        byte[] exitKey = GatewayFixtures.concat(GatewayFixtures.concat(EXIT_PREFIX, VAULT_ID.bytes()), holderId);
        state.put(exitKey, new ExitLeafV1(HOLDER_KEY, VAULT_ID, BigInteger.valueOf(amount),
                BigInteger.valueOf(vaultLovelace)).encode());
        byte[] escape = GatewayIds.escapeNullifier(VAULT_ID, holderId).bytes();
        int shard = escape[31] & 0x0f;
        byte[] recipient = smartWallet(filled(0x8b, 28));
        PlutusData action = PlutusData.constr(7, PlutusData.bytes(holderId), PlutusData.bytes(HOLDER_KEY),
                PlutusData.integer(amount), PlutusData.integer(vaultLovelace), PlutusData.bytes(recipient),
                state.proof(exitKey), PlutusData.integer(shard));
        FullTx tx = frame(exitMode(state.root()));
        long inLovelace = 20_000_000L;
        long inQuantity = 5_000L;
        tx.spend(walletInput(W1, assetValue(inLovelace - (inputs - 1) * FLOOR, inQuantity),
                continuation(List.of(shardId(3, 1)))), null);
        for (int i = 1; i < inputs; i++) {
            tx.spend(walletInput(ref(0x61, i), lovelace(FLOOR), floorDatum()), null);
        }
        Inserts inserts = new ShardTrie(shard).seed(5).plan(List.of(escape));
        tx.spend(shardInput(shard, inserts.before()), inserts.redeemer());
        tx.output(payTo(recipient, assetValue(vaultLovelace, amount)));
        tx.output(out(vaultWallet(), assetValue(inLovelace - vaultLovelace, inQuantity - amount),
                continuation(List.of(escape))));
        tx.output(shardOutput(shard, inserts.after()));
        tx.output(payTo(CHANGE, lovelace(40_000_000L)));
        tx.signers.add(new org.julclang.ledger.PubKeyHash(HOLDER_KEY));
        tx.withdrawScript(custodyHash(), 0, action);
        tx.withdrawScript(exitDelegateHash(), delegateBalance, PlutusData.constr(0, PlutusData.bytes(custodyHash()),
                action));
        return withHooks(tx);
    }

    /** Runs an honest transaction and prints its units per script and in total. */
    void report(String action, int k, FullTx tx) {
        Map<String, EvalResult> results = measure(tx);
        long cpu = 0;
        long mem = 0;
        Map<String, long[]> perScript = new TreeMap<>();
        for (Map.Entry<String, EvalResult> entry : results.entrySet()) {
            String script = entry.getKey().replaceAll("#.*", "");
            long[] units = perScript.computeIfAbsent(script, ignored -> new long[2]);
            units[0] += entry.getValue().budgetConsumed().cpuSteps();
            units[1] += entry.getValue().budgetConsumed().memoryUnits();
            cpu += entry.getValue().budgetConsumed().cpuSteps();
            mem += entry.getValue().budgetConsumed().memoryUnits();
        }
        StringBuilder detail = new StringBuilder();
        perScript.forEach((script, units) -> detail.append(script).append('=').append(units[1]).append(' '));
        System.out.printf("BUDGET|%s|%d|%d|%d|%.1f|%.1f|%s%n", action, k, cpu, mem, 100.0 * cpu / MAX_TX_CPU,
                100.0 * mem / MAX_TX_MEM, detail.toString().trim());
        assertThat(cpu).isLessThan(MAX_TX_CPU);
        assertThat(mem).isLessThan(MAX_TX_MEM);
    }
}
