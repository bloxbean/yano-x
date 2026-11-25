package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measured execution units and transaction sizes (ADR-059 §10 "budgets are measured, not estimated"; M4 exit
 * criterion). Each row is one whole transaction: the units are the sum over every script it runs (the lead vault
 * input, the follower vault inputs, which only find the lead, and the shard), and the size is the full serialized
 * transaction ({@link TxSizes}: body, inline datums, reference inputs, collateral, witnesses and per-input
 * redeemers with their units). The state trie holds {@value #STATE_LEAVES} leaves and each shard {@value #SHARD_IDS}
 * paid ids, so proofs have realistic depth. A batch cap is the largest batch whose units AND size fit. Results are
 * printed as {@code BUDGET|...} and {@code CAP|...} lines; {@code BUDGETS.md} records them.
 */
class GatewayBudgetTest extends GatewayTestBase {
    static final int STATE_LEAVES = 20_000;
    static final int SHARD_IDS = 4_000;
    static final int MAX_TX_BYTES = 16_384;
    private static StateTrie bigState;
    private static List<ClaimFx> exitClaims;

    record Measured(long cpu, long mem, int bytes) {
        boolean fits() {
            return cpu <= MAX_TX_CPU && mem <= MAX_TX_MEM && bytes <= MAX_TX_BYTES;
        }

        String verdict() {
            return (cpu <= MAX_TX_CPU && mem <= MAX_TX_MEM ? "" : " EXCEEDS-UNITS")
                    + (bytes <= MAX_TX_BYTES ? "" : " EXCEEDS-SIZE");
        }
    }

    @BeforeAll
    static void bigTrie() {
        exitClaims = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            exitClaims.add(userClaim(0, 300 + i, TOKEN));
        }
        bigState = stateWith(STATE_LEAVES, statusLeaf(DomainStatusV1.Status.ACTIVE, NOW - 30 * DAY, EXIT_DELAY),
                TOKEN_VAULT, TOKEN, exitClaims);
    }

    /** Runs every script input of {@code tx} (vault or shard, by address) and sizes the whole transaction. */
    private Measured measure(Tx tx, Program vault, int scriptRefs) {
        long cpu = 0;
        long mem = 0;
        Map<TxOutRef, long[]> units = new HashMap<>();
        for (Spent spent : tx.inputs) {
            if (spent.redeemer() == null) {
                continue;
            }
            Program program = spent.input().resolved().address().equals(vaultAddress()) ? vault : shard();
            EvalResult result = evaluate(program, tx.spending(spent.input().outRef()));
            assertThat(result.isSuccess()).as("budget scenario succeeds: %s", result).isTrue();
            long c = result.budgetConsumed().cpuSteps();
            long m = result.budgetConsumed().memoryUnits();
            units.put(spent.input().outRef(), new long[] {c, m});
            cpu += c;
            mem += m;
        }
        return new Measured(cpu, mem, TxSizes.bytes(tx, units, scriptRefs));
    }

    private static void report(String name, int k, int vaultInputs, Measured m, String note) {
        System.out.printf("BUDGET|%s|%d|%d|%d|%d|%.1f%%|%.1f%%|%d|%s%s%n", name, k, vaultInputs, m.cpu(), m.mem(),
                100.0 * m.cpu() / MAX_TX_CPU, 100.0 * m.mem() / MAX_TX_MEM, m.bytes(), note, m.verdict());
    }

    private static int redeemerBytes(Tx tx) {
        int bytes = 0;
        for (Spent spent : tx.inputs) {
            if (spent.redeemer() != null) {
                bytes += TxSizes.dataBytes(spent.redeemer());
            }
        }
        return bytes;
    }

    // ------------------------------------------------------------------
    // Settle and Exit

    private static Payout settlePayout(int k, int followers) {
        Payout payout = new Payout();
        payout.shardTrie = new ShardTrie(0).seed(SHARD_IDS);
        for (int i = 0; i < k; i++) {
            payout.claims.add(userClaim(0, i, TOKEN));
        }
        payout.vaultRedeemer = settleRedeemer(payout.claims, 0);
        payout.anchor = anchorDatum(2, bigState.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR);
        payout.followerInputs = followers;
        return payout;
    }

    private static Payout exitPayout(int k, int followers) {
        List<ClaimFx> claims = exitClaims.subList(0, k);
        Payout payout = new Payout();
        payout.shardTrie = new ShardTrie(0).seed(SHARD_IDS);
        payout.claims.addAll(claims);
        payout.vaultRedeemer = exitRedeemer(claims, claimProofs(bigState, claims), bigState.proof(STATUS_KEY), 0);
        payout.anchor = anchorDatum(2, bigState.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR);
        payout.signers = 0;
        payout.followerInputs = followers;
        return payout;
    }

    /** The token vault with the profile's batch caps at the on-chain maximum, 64, to find the physical caps. */
    private static Program uncappedVault() {
        PlutusData[] params = vaultParams(TOKEN);
        params[19] = PlutusData.integer(64);
        params[20] = PlutusData.integer(64);
        return program(AssetVaultValidator.class, params);
    }

    @Test
    void settleCapByUnitsAndSize() {
        for (int followers : new int[] {0, 3, 7}) {
            int largest = 0;
            for (int k = 1; k <= 64; k = k < 16 ? k * 2 : k + 1) {
                Tx tx = settlePayout(k, followers).build().tx();
                Measured m = measure(tx, uncappedVault(), 2);
                report("Settle", k, followers + 1, m, "redeemer bytes=" + redeemerBytes(tx));
                if (!m.fits()) {
                    break;
                }
                largest = k;
            }
            System.out.printf("CAP|Settle|%d|%d%n", followers + 1, largest);
            assertThat(largest).as("Settle cap with %d vault inputs", followers + 1).isGreaterThanOrEqualTo(16);
        }
    }

    @Test
    void exitCapByUnitsAndSize() {
        for (int followers : new int[] {0, 3}) {
            int largest = 0;
            for (int k = 1; k <= 16; k++) {
                Tx tx = exitPayout(k, followers).build().tx();
                Measured m = measure(tx, uncappedVault(), 2);
                report("Exit", k, followers + 1, m, "state folds=" + bigState.depth(exitClaims.get(k - 1).key())
                        + " redeemer bytes=" + redeemerBytes(tx));
                if (!m.fits()) {
                    break;
                }
                largest = k;
            }
            System.out.printf("CAP|Exit|%d|%d%n", followers + 1, largest);
            assertThat(largest).isGreaterThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // BalanceExit

    @Test
    void balanceExitWithSeveralVaultInputs() {
        byte[] holderId = filled(0xa0, 28);
        byte[] holderKey = filled(0xd1, 28);
        byte[] exitKey = GatewayFixtures.concat(GatewayFixtures.concat(EXIT_PREFIX, TOKEN_VAULT.bytes()), holderId);
        StateTrie state = new StateTrie().fill("ap/account/", STATE_LEAVES).put(exitKey,
                new ExitLeafV1(holderKey, TOKEN_VAULT, BigInteger.valueOf(250), BigInteger.valueOf(1_200_000))
                        .encode());
        byte[] escape = GatewayIds.escapeNullifier(TOKEN_VAULT, holderId).bytes();
        int shard = escape[31] & 0x0f;
        byte[] recipient = GatewayFixtures.enterpriseKeyAddress(filled(0xd2, 28));
        PlutusData redeemer = PlutusData.constr(7, PlutusData.bytes(holderId), PlutusData.bytes(holderKey),
                PlutusData.integer(250), PlutusData.integer(1_200_000), PlutusData.bytes(recipient),
                state.proof(exitKey), PlutusData.integer(shard));
        for (int vaultInputs : new int[] {1, 4, 8, 16, 24}) {
            Inserts inserts = new ShardTrie(shard).seed(SHARD_IDS).plan(List.of(escape));
            Tx tx = new Tx();
            long lovelace = 20_000_000;
            long quantity = 5_000;
            tx.spend(vaultInput(VAULT_REF, assetValue(TOKEN, 20_000_000, 5_000), continuationDatum(List.of())),
                    redeemer);
            for (int i = 1; i < vaultInputs; i++) {
                tx.spend(vaultInput(ref(0x61, i), assetValue(TOKEN, 1_200_000, 1), continuationDatum(List.of())),
                        follow());
                lovelace += 1_200_000;
                quantity += 1;
            }
            tx.spend(new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                    lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                    new OutputDatum.OutputDatumInline(shardDatum(shard, inserts.before())), Optional.empty())),
                    inserts.redeemer());
            tx.spend(keyInput(FEE_REF, 50_000_000L), null);
            tx.output(payTo(recipient, assetValue(TOKEN, 1_200_000, 250)));
            tx.output(out(vaultAddress(), assetValue(TOKEN, lovelace - 1_200_000, quantity - 250),
                    continuationDatum(List.of(escape))));
            tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH),
                    lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                    shardDatum(shard, inserts.after())));
            tx.reference(exitModeInput(exitMode(state.root())));
            tx.signer(new PubKeyHash(holderKey));
            Measured m = measure(tx, vault(TOKEN), 2);
            report("BalanceExit", 1, vaultInputs, m, "state folds=" + state.depth(exitKey));
            if (vaultInputs <= 16) {
                assertThat(m.fits()).as("BalanceExit with %d vault inputs", vaultInputs).isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // AcceptProved and Consolidate

    @Test
    void provedAcceptCapByUnitsAndSize() {
        int largest = 0;
        for (int k = 1; k <= 32; k = k < 8 ? k + 1 : k + 2) {
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
                        GatewayFixtures.enterpriseKeyAddress(filled(0xc1, 28)), filled(0xc1, 28))),
                        i == 0 ? redeemer : follow());
                total = total.merge(value);
            }
            tx.spend(keyInput(FEE_REF, 5_000_000L), null);
            tx.output(out(vaultAddress(), total, continuationDatum(List.of())));
            tx.reference(anchorInput(anchorDatum(2, state.root(), 120, MEMBERS, THRESHOLD, NOW - HOUR)));
            tx.reference(exitModeInput(normalMode()));
            Measured m = measure(tx, vault(TOKEN), 1);
            report("AcceptProved", k, k, m, "state folds=" + state.depth(keys.get(0)) + " redeemer bytes="
                    + redeemerBytes(tx));
            if (!m.fits()) {
                break;
            }
            largest = k;
        }
        System.out.println("CAP|AcceptProved|" + largest);
        assertThat(largest).isGreaterThanOrEqualTo(8);
    }

    @Test
    void consolidateCapByUnitsAndSize() {
        int largest = 0;
        for (int v = 2; v <= 40; v = v < 16 ? v * 2 : v + 2) {
            Tx tx = VaultConsolidateConformanceTest.consolidation(TOKEN, v, normalMode(), true);
            Measured m = measure(tx, vault(TOKEN), 1);
            report("Consolidate", v, v, m, "redeemer bytes=" + redeemerBytes(tx));
            if (!m.fits()) {
                break;
            }
            largest = v;
        }
        System.out.println("CAP|Consolidate|" + largest);
        assertThat(largest).isGreaterThanOrEqualTo(16);
    }

    // ------------------------------------------------------------------
    // Exit mode

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
        EvalResult result = evaluate(exitModeValidator(), tx.spending(ref(0x64, 0)));
        assertThat(result.isSuccess()).isTrue();
        Measured m = new Measured(result.budgetConsumed().cpuSteps(), result.budgetConsumed().memoryUnits(),
                TxSizes.bytes(tx, Map.of(ref(0x64, 0), new long[] {result.budgetConsumed().cpuSteps(),
                        result.budgetConsumed().memoryUnits()}), 1));
        report("EnterExitMode(b)", 1, 0, m, "absence folds=" + state.absenceProof(
                GatewayFixtures.concat(FORCED_PREFIX, requestId)).folds().size());
        assertThat(m.fits()).isTrue();
    }

    // ------------------------------------------------------------------
    // Script sizes

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
