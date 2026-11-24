package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measured execution units per transaction (ADR-059 §10 "budgets are measured, not estimated"; M4 exit criterion).
 * Each figure is the sum over every script the transaction runs: the lead vault input, the other vault inputs (which
 * only compare redeemers) and the shard. The state trie holds {@value #STATE_LEAVES} leaves and each shard
 * {@value #SHARD_IDS} paid ids, so proofs have realistic depth; the printed fold counts give it. Results are printed as
 * {@code BUDGET|...} lines; {@code BUDGETS.md} records them. An Exit batch is also bounded by transaction size: its
 * redeemers must leave room in the 16,384-byte transaction for inputs, outputs and witnesses (14,000 bytes here).
 */
class GatewayBudgetTest extends GatewayTestBase {
    static final int STATE_LEAVES = 20_000;
    static final int SHARD_IDS = 4_000;
    private static StateTrie bigState;
    private static List<ClaimFx> exitClaims;

    record Cost(long cpu, long mem) {
        Cost plus(EvalResult result) {
            assertThat(result.isSuccess()).as("budget scenario succeeds: %s", result).isTrue();
            return new Cost(cpu + result.budgetConsumed().cpuSteps(), mem + result.budgetConsumed().memoryUnits());
        }

        boolean fits() {
            return cpu <= MAX_TX_CPU && mem <= MAX_TX_MEM;
        }
    }

    @BeforeAll
    static void bigTrie() {
        exitClaims = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            exitClaims.add(userClaim(0, 300 + i, TOKEN));
        }
        bigState = stateWith(STATE_LEAVES, statusLeaf(DomainStatusV1.Status.ACTIVE, NOW - 30 * DAY, EXIT_DELAY),
                TOKEN_VAULT, TOKEN, exitClaims);
    }

    private static void report(String name, int k, Cost cost, String note) {
        System.out.printf("BUDGET|%s|%d|%d|%d|%.1f%%|%.1f%%|%s%n", name, k, cost.cpu(), cost.mem(),
                100.0 * cost.cpu() / MAX_TX_CPU, 100.0 * cost.mem() / MAX_TX_MEM, note);
    }

    /** The CBOR size of the vault and shard redeemers, which the 16,384-byte transaction must carry. */
    private static int redeemerBytes(Scenario scenario) {
        int bytes = 0;
        for (Spent spent : scenario.tx().inputs) {
            if (spent.redeemer() != null) {
                bytes += org.julclang.core.cbor.PlutusDataCborEncoder.encode(spent.redeemer()).length;
            }
        }
        return bytes;
    }

    private Cost run(Program program, PlutusData context, Cost cost) {
        return cost.plus(evaluate(program, context));
    }

    @Test
    void settle() {
        for (int k : new int[] {1, 4, 8, 16}) {
            Payout payout = new Payout();
            payout.shardTrie = new ShardTrie(0).seed(SHARD_IDS);
            for (int i = 0; i < k; i++) {
                payout.claims.add(userClaim(0, i, TOKEN));
            }
            payout.vaultRedeemer = settleRedeemer(payout.claims, 0);
            payout.anchor = anchorDatum(2, bigState.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR);
            Scenario scenario = payout.build();
            Cost cost = run(shard(), scenario.shard(), run(vault(TOKEN), scenario.vault(), new Cost(0, 0)));
            report("Settle", k, cost, "redeemer bytes=" + redeemerBytes(scenario));
            assertThat(cost.fits()).as("Settle k=%d fits", k).isTrue();
        }
    }

    @Test
    void exitUpToTheLargestBatchThatFits() {
        PlutusData status = bigState.proof(STATUS_KEY);
        int largest = 0;
        for (int k = 1; k <= 16; k++) {
            List<ClaimFx> claims = exitClaims.subList(0, k);
            Payout payout = new Payout();
            payout.shardTrie = new ShardTrie(0).seed(SHARD_IDS);
            payout.claims.addAll(claims);
            payout.vaultRedeemer = exitRedeemer(claims, claimProofs(bigState, claims), status, 0);
            payout.anchor = anchorDatum(2, bigState.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR);
            payout.signers = 0;
            Scenario scenario = payout.build();
            Cost cost = run(shard(), scenario.shard(), run(vault(TOKEN), scenario.vault(), new Cost(0, 0)));
            int bytes = redeemerBytes(scenario);
            report("Exit", k, cost, "state folds=" + bigState.depth(claims.get(k - 1).key()) + " redeemer bytes="
                    + bytes + (cost.fits() ? "" : " EXCEEDS-UNITS") + (bytes < 14_000 ? "" : " EXCEEDS-SIZE"));
            if (!cost.fits() || bytes >= 14_000) {
                break;
            }
            largest = k;
        }
        System.out.println("BUDGET|ExitMax|" + largest);
        assertThat(largest).isGreaterThanOrEqualTo(1);
    }

    @Test
    void balanceExit() {
        byte[] holderId = filled(0xa0, 28);
        byte[] holderKey = filled(0xd1, 28);
        byte[] exitKey = GatewayFixtures.concat(GatewayFixtures.concat(EXIT_PREFIX, TOKEN_VAULT.bytes()), holderId);
        StateTrie state = new StateTrie().fill("ap/account/", STATE_LEAVES).put(exitKey,
                new ExitLeafV1(holderKey, TOKEN_VAULT, BigInteger.valueOf(250), BigInteger.valueOf(1_200_000))
                        .encode());
        byte[] escape = GatewayIds.escapeNullifier(TOKEN_VAULT, holderId).bytes();
        int shard = escape[31] & 0x0f;
        Inserts inserts = new ShardTrie(shard).seed(SHARD_IDS).plan(List.of(escape));
        byte[] recipient = GatewayFixtures.enterpriseKeyAddress(filled(0xd2, 28));
        PlutusData redeemer = PlutusData.constr(7, PlutusData.bytes(holderId), PlutusData.bytes(holderKey),
                PlutusData.integer(250), PlutusData.integer(1_200_000), PlutusData.bytes(recipient),
                state.proof(exitKey), PlutusData.integer(shard));
        Tx tx = new Tx();
        tx.spend(vaultInput(VAULT_REF, assetValue(TOKEN, 20_000_000, 5_000), continuationDatum(List.of())), redeemer);
        tx.spend(new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                new OutputDatum.OutputDatumInline(shardDatum(shard, inserts.before())), Optional.empty())),
                inserts.redeemer());
        tx.spend(keyInput(FEE_REF, 50_000_000L), null);
        tx.output(payTo(recipient, assetValue(TOKEN, 1_200_000, 250)));
        tx.output(out(vaultAddress(), assetValue(TOKEN, 18_800_000, 4_750), continuationDatum(List.of(escape))));
        tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                shardDatum(shard, inserts.after())));
        tx.reference(exitModeInput(exitMode(state.root())));
        tx.signer(new PubKeyHash(holderKey));
        Cost cost = run(shard(), tx.spending(shardRef(shard)), run(vault(TOKEN), tx.spending(VAULT_REF),
                new Cost(0, 0)));
        report("BalanceExit", 1, cost, "state folds=" + state.depth(exitKey));
        assertThat(cost.fits()).isTrue();
    }

    @Test
    void provedAccept() {
        for (int k : new int[] {1, 4, 8}) {
            StateTrie state = new StateTrie().fill("ap/account/", STATE_LEAVES);
            Tx tx = new Tx();
            List<PlutusData> proofs = new ArrayList<>();
            org.julclang.ledger.Value total = lovelace(0);
            List<byte[]> keys = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                Hash32 txId = Hash32.of(filled(0x70 + i, 32));
                byte[] key = GatewayFixtures.concat(DEPOSIT_PREFIX,
                        GatewayIds.depositId(TOKEN_VAULT, txId, i).bytes());
                state.put(key, GatewayFixtures.creditedRecord(TOKEN_VAULT, txId, i, 500 + i).encode());
                keys.add(key);
            }
            for (int i = 0; i < k; i++) {
                proofs.add(state.proof(keys.get(i)));
            }
            PlutusData redeemer = PlutusData.constr(4, PlutusData.list(proofs.toArray(new PlutusData[0])));
            for (int i = 0; i < k; i++) {
                org.julclang.ledger.Value value = assetValue(TOKEN, 2_000_000, 500 + i);
                tx.spend(vaultInput(ref(0x70 + i, i), value, depositDatum(
                        GatewayFixtures.enterpriseKeyAddress(filled(0xc1, 28)), filled(0xc1, 28))), redeemer);
                total = total.merge(value);
            }
            tx.spend(keyInput(FEE_REF, 5_000_000L), null);
            tx.output(out(vaultAddress(), total, continuationDatum(List.of())));
            tx.reference(anchorInput(anchorDatum(2, state.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR)));
            tx.reference(exitModeInput(normalMode()));
            Cost cost = new Cost(0, 0);
            for (int i = 0; i < k; i++) {
                cost = run(vault(TOKEN), tx.spending(ref(0x70 + i, i)), cost);
            }
            report("AcceptProved", k, cost, "state folds=" + state.depth(keys.get(0)) + " redeemer bytes="
                    + org.julclang.core.cbor.PlutusDataCborEncoder.encode(redeemer).length);
            assertThat(cost.fits()).isTrue();
        }
    }

    @Test
    void enterExitModeOnACensoredRequest() {
        byte[] requestId = GatewayIds.forcedRequestId(Hash32.of(filled(0x77, 32)), 0).bytes();
        StateTrie state = new StateTrie().fill("ap/account/", STATE_LEAVES);
        PlutusData redeemer = PlutusData.constr(1, PlutusData.bytes(requestId),
                state.absence(GatewayFixtures.concat(FORCED_PREFIX, requestId)));
        PlutusData request = PlutusData.constr(0, PlutusData.bytes(filled(0xa1, 28)),
                PlutusData.bytes(TOKEN_VAULT.bytes()), PlutusData.constr(1),
                PlutusData.bytes(GatewayFixtures.enterpriseKeyAddress(filled(0xa2, 28))),
                PlutusData.bytes(filled(0xa3, 28)), PlutusData.integer(NOW - FORCED_DEADLINE - HOUR));
        Tx tx = new Tx();
        tx.spend(new TxInInfo(ref(0x64, 0), new TxOut(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                new OutputDatum.OutputDatumInline(normalMode()), Optional.empty())), redeemer);
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.output(out(GatewayFixtures.scriptAddress(EXIT_HASH),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                PlutusData.constr(1, PlutusData.bytes(state.root()), PlutusData.integer(120),
                        PlutusData.integer(NOW - HOUR))));
        tx.reference(anchorInput(anchorDatum(2, state.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR)));
        tx.reference(new TxInInfo(ref(0x78, 0), new TxOut(GatewayFixtures.scriptAddress(INBOX_HASH),
                lovelace(2_000_000).merge(token(INBOX_HASH, requestId, 1)),
                new OutputDatum.OutputDatumInline(request), Optional.empty())));
        Cost cost = run(exitModeValidator(), tx.spending(ref(0x64, 0)), new Cost(0, 0));
        report("EnterExitMode(b)", 1, cost, "absence folds=" + state.depth(new byte[] {'a', 'p', '/', 'a', 'c',
                'c', 'o', 'u', 'n', 't', '/', '1'}) + " (filler depth)");
        assertThat(cost.fits()).isTrue();
    }

    @Test
    void scriptSizes() {
        for (Class<?> type : List.of(AssetVaultValidator.class, AssetShardValidator.class,
                AssetShardThreadPolicy.class, ExitModeThreadPolicy.class, ExitModeThreadValidator.class,
                ForcedInboxValidator.class)) {
            Program template = program(type);
            int size = org.julclang.clientlib.JulcScriptAdapter.fromProgram(template).getCborHex().length() / 2;
            System.out.printf("SIZE|%s|%d%n", type.getSimpleName(), size);
        }
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            int applied = org.julclang.clientlib.JulcScriptAdapter.fromProgram(vault(asset)).getCborHex().length() / 2;
            System.out.printf("SIZE|AssetVaultValidator(%s, applied)|%d%n", asset.isAda() ? "ADA" : "token", applied);
            // A reference script must fit in one output of a transaction of at most 16,384 bytes.
            assertThat(applied).isLessThan(15_500);
        }
    }
}
