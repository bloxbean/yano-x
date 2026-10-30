package org.yanoproject.x.eutxo.bridge.onchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.testkit.ContractTest;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M2: batched Settle (A2) and permissionless Exit (A3) on the
 * V1 settlement vault, driven against a real off-chain MPF state trie on the
 * julc VM, with per-batch-size budget measurement and adversarial cases.
 */
class SettlementVaultConformanceTest extends ContractTest {
    private static final long MAX_TX_CPU = 10_000_000_000L;
    private static final long MAX_TX_MEM = 14_000_000L;
    private static final byte[] ROOT_POLICY = filled(0x51, 28);
    private static final byte[] ROOT_SCRIPT = filled(0x52, 28);
    private static final byte[] SHARD_SCRIPT = filled(0x53, 28);
    private static final byte[] SHARD_POLICY = filled(0x71, 28);
    private static final byte[] VAULT_SCRIPT = filled(0x54, 28);
    private static final byte[] CHAIN_ID = "payments".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLAIM_DOMAIN =
            "yano-eutxo-withdrawal-v2".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_PREFIX = new byte[] {0x01, 0x03};
    private static final BigInteger EPOCH = BigInteger.valueOf(7);
    private static final BigInteger THRESHOLD = BigInteger.valueOf(2);
    /** POSIX milliseconds, as the bootstrap writes it: 86,400 one-second slots. */
    private static final long FALLBACK_DELAY_MILLIS = 86_400_000L;
    private static Program program;
    private static final List<byte[]> MEMBER_KEYS = sortedMembers();

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(SettlementVaultValidator.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(ROOT_POLICY),
                            PlutusData.bytes(new byte[0]),
                            PlutusData.bytes(SHARD_POLICY),
                            PlutusData.bytes(KEY_PREFIX),
                            PlutusData.bytes(CLAIM_DOMAIN),
                            PlutusData.bytes(new byte[] {(byte) 16}),
                            PlutusData.bytes(new byte[] {(byte) 6}));
        }
        return program;
    }

    @Test
    void batchedSettleSweepReportsBudgetPerSize() {
        for (int n : new int[] {1, 2, 3, 4, 6, 8}) {
            Batch batch = batch(n);
            var result = evaluate(program(), settleContext(batch, true, false));
            assertSuccess(result);
            assertThat(result.budgetConsumed().cpuSteps()).isLessThan(MAX_TX_CPU);
            assertThat(result.budgetConsumed().memoryUnits()).isLessThan(MAX_TX_MEM);
            System.out.println("[SettlementVault A2] claims=" + n
                    + " cpu=" + result.budgetConsumed().cpuSteps()
                    + " mem=" + result.budgetConsumed().memoryUnits());
        }
    }

    @Test
    void permissionlessExitVerifiesInclusionAndReportsBudget() {
        for (int n : new int[] {1, 2, 3}) {
            Batch batch = batch(n);
            var result = evaluate(program(), exitContext(batch, true));
            assertSuccess(result);
            assertThat(result.budgetConsumed().cpuSteps()).isLessThan(MAX_TX_CPU);
            System.out.println("[SettlementVault A3] claims=" + n
                    + " cpu=" + result.budgetConsumed().cpuSteps()
                    + " mem=" + result.budgetConsumed().memoryUnits());
        }
    }

    @Test
    void adversarialSettleAndExitCasesFail() {
        Batch batch = batch(3);
        // No federation threshold signatures.
        assertFailure(evaluate(program(), settleContext(batch, false, false)));
        // Continuing vault skims part of the remainder to itself.
        assertFailure(evaluate(program(), settleContext(batch, true, true)));
        // Exit before the fallback delay is armed.
        assertFailure(evaluate(program(), exitContext(batch, false)));
        // Exit with a payout reordered against the claim order.
        assertFailure(evaluate(program(), exitReorderedContext(batch)));
        // Fully signed settle whose "shard" input carries NO thread token —
        // the paired-shard requirement is token-based (deploy ordering fix).
        assertFailure(evaluate(program(), tokenlessShardContext(batch)));
    }

    /**
     * The shard must insert exactly the paid claim ids, in claim order. Before this check the vault only required
     * some shard input, so an Exit could pay a claim again while the shard inserted an unrelated fresh id, and any
     * spend could nullify claims it never paid.
     */
    @Test
    void shardInsertsMustBeThePaidClaims() {
        Batch batch = batch(2);
        byte[] first = batch.claims().get(0).claimId;
        byte[] second = batch.claims().get(1).claimId;
        byte[] unrelated = filled(0x7a, 32);
        unrelated[31] = 0;
        long armed = batch.updatedAtMillis + FALLBACK_DELAY_MILLIS + 10;

        assertSuccess(evaluate(program(), signed(shardVariant(batch, 0, armed, List.of(first, second), 1, true))));
        assertSuccess(evaluate(program(), shardVariant(batch, 1, armed, List.of(first, second), 1, true)
                .buildPlutusData()));

        for (int mode : new int[] {0, 1}) {
            List<List<byte[]>> wrong = List.of(
                    List.of(first, unrelated),
                    List.of(unrelated, unrelated),
                    List.of(second, first),
                    List.of(first),
                    List.of(first, second, unrelated),
                    List.of());
            for (List<byte[]> inserted : wrong) {
                var builder = shardVariant(batch, mode, armed, inserted, 1, true);
                assertFailure(evaluate(program(), mode == 0 ? signed(builder) : builder.buildPlutusData()));
            }
            // A second shard input could nullify unpaid claims of its own shard. It inserts a real id, and it comes
            // both before and after the paired shard, so the guard is tested whichever input is found first.
            for (int extraShards : new int[] {2, -2}) {
                var twoShards = shardVariant(batch, mode, armed, List.of(first, second), extraShards, true);
                assertFailure(evaluate(program(), mode == 0 ? signed(twoShards) : twoShards.buildPlutusData()));
            }
            // The shard input has no spend redeemer to read.
            var noRedeemer = shardVariant(batch, mode, armed, List.of(first, second), 1, false);
            assertFailure(evaluate(program(), mode == 0 ? signed(noRedeemer) : noRedeemer.buildPlutusData()));
        }
    }

    /** The Exit delay is POSIX milliseconds: one millisecond short of the delay stays closed. */
    @Test
    void exitArmsOnlyAfterTheDelayInMilliseconds() {
        Batch batch = batch(1);
        long boundary = batch.updatedAtMillis + FALLBACK_DELAY_MILLIS;
        assertFailure(evaluate(program(), baseBuilder(batch, 1, false, boundary).buildPlutusData()));
        assertSuccess(evaluate(program(), baseBuilder(batch, 1, false, boundary + 1).buildPlutusData()));
    }

    /**
     * The vault counts each federation key once and requires the root's keys strictly ascending. Julc before
     * 0.1.0-pre17 discarded the sortedness {@code return} in {@code countSigners}, so a root datum listing one key
     * twice let that key alone meet a threshold of two.
     */
    @Test
    void settleCountsEachFederationKeyOnce() {
        Batch batch = batch(1);
        byte[] first = MEMBER_KEYS.get(0);
        byte[] second = MEMBER_KEYS.get(1);
        assertSuccess(evaluate(program(), settleContext(batch, true, false)));

        var repeated = baseBuilder(batch.withMembers(List.of(first, first)), 0, false, 0L)
                .signer(new PubKeyHash(Blake2bUtil.blake2bHash224(first)));
        assertFailure(evaluate(program(), repeated.buildPlutusData()));

        var descending = baseBuilder(batch.withMembers(List.of(second, first)), 0, false, 0L)
                .signer(new PubKeyHash(Blake2bUtil.blake2bHash224(first)))
                .signer(new PubKeyHash(Blake2bUtil.blake2bHash224(second)));
        assertFailure(evaluate(program(), descending.buildPlutusData()));
    }

    // ------------------------------------------------------------------

    private PlutusData settleContext(
            Batch batch, boolean signed, boolean skim) {
        var builder = baseBuilder(batch, 0, skim, 0L);
        return signed ? signed(builder) : builder.buildPlutusData();
    }

    private static PlutusData signed(org.julclang.testkit.ScriptContextTestBuilder builder) {
        for (byte[] key : MEMBER_KEYS) {
            builder.signer(new PubKeyHash(Blake2bUtil.blake2bHash224(key)));
        }
        return builder.buildPlutusData();
    }

    private org.julclang.testkit.ScriptContextTestBuilder shardVariant(
            Batch batch, int mode, long now, List<byte[]> inserted, int shardInputs, boolean withRedeemer) {
        return baseBuilder(batch, mode, false, now, false, false, inserted, shardInputs, withRedeemer);
    }

    private PlutusData tokenlessShardContext(Batch batch) {
        var builder = baseBuilder(batch, 0, false, 0L, false, true);
        for (byte[] key : MEMBER_KEYS) {
            builder.signer(new PubKeyHash(Blake2bUtil.blake2bHash224(key)));
        }
        return builder.buildPlutusData();
    }

    private PlutusData exitContext(Batch batch, boolean armed) {
        long now = armed ? batch.updatedAtMillis + FALLBACK_DELAY_MILLIS + 10
                : batch.updatedAtMillis + 10;
        return baseBuilder(batch, 1, false, now).buildPlutusData();
    }

    private PlutusData exitReorderedContext(Batch batch) {
        long now = batch.updatedAtMillis + FALLBACK_DELAY_MILLIS + 10;
        return baseBuilder(batch, 1, false, now, true).buildPlutusData();
    }

    private org.julclang.testkit.ScriptContextTestBuilder baseBuilder(
            Batch batch, int mode, boolean skim, long now) {
        return baseBuilder(batch, mode, skim, now, false, false);
    }

    private org.julclang.testkit.ScriptContextTestBuilder baseBuilder(
            Batch batch, int mode, boolean skim, long now, boolean reorderPayouts) {
        return baseBuilder(batch, mode, skim, now, reorderPayouts, false);
    }

    private org.julclang.testkit.ScriptContextTestBuilder baseBuilder(
            Batch batch, int mode, boolean skim, long now, boolean reorderPayouts,
            boolean tokenlessShard) {
        List<byte[]> ids = batch.claims().stream().map(Claim::claimId).toList();
        return baseBuilder(batch, mode, skim, now, reorderPayouts, tokenlessShard, ids, 1, true);
    }

    private org.julclang.testkit.ScriptContextTestBuilder baseBuilder(
            Batch batch, int mode, boolean skim, long now, boolean reorderPayouts,
            boolean tokenlessShard, List<byte[]> insertedIds, int shardInputs, boolean withShardRedeemer) {
        Address vaultAddress = scriptAddress(VAULT_SCRIPT);
        Address rootAddress = scriptAddress(ROOT_SCRIPT);
        Address shardAddress = scriptAddress(SHARD_SCRIPT);
        TxOutRef vaultRef = new TxOutRef(new TxId(filled(0x61, 32)), BigInteger.ZERO);
        TxOutRef shardRef = new TxOutRef(new TxId(filled(0x62, 32)), BigInteger.ZERO);
        TxOutRef rootRef = new TxOutRef(new TxId(filled(0x63, 32)), BigInteger.ZERO);

        PlutusData redeemer = mode == 0
                ? PlutusData.constr(0, PlutusData.list(batch.settleClaims()))
                : PlutusData.constr(1, PlutusData.list(batch.exitClaims()));

        List<TxInInfo> paired = List.of(
                new TxInInfo(vaultRef, new TxOut(
                        vaultAddress, Value.lovelace(batch.vaultInput),
                        new OutputDatum.OutputDatumInline(PlutusData.UNIT),
                        Optional.empty())),
                new TxInInfo(shardRef, new TxOut(
                        shardAddress,
                        tokenlessShard
                                ? Value.lovelace(BigInteger.valueOf(2_000_000))
                                : threadedValue(BigInteger.valueOf(2_000_000),
                                SHARD_POLICY, new byte[] {0x00}),
                        new OutputDatum.OutputDatumInline(PlutusData.UNIT),
                        Optional.empty())));
        var builder = spendingContext(vaultRef, PlutusData.UNIT)
                .redeemer(redeemer)
                .fee(BigInteger.valueOf(300_000))
                .redeemerEntry(new ScriptPurpose.Spending(vaultRef), redeemer)
                .referenceInput(new TxInInfo(rootRef, new TxOut(
                        rootAddress,
                        threadedValue(BigInteger.valueOf(2_000_000), ROOT_POLICY,
                                new byte[0]),
                        new OutputDatum.OutputDatumInline(batch.rootDatum()),
                        Optional.empty())));

        if (withShardRedeemer) {
            builder.redeemerEntry(new ScriptPurpose.Spending(shardRef), shardInsertBatch(insertedIds));
        }
        // shardInputs < 0 adds the extra shards ahead of the paired one in input order.
        List<TxInInfo> extras = new ArrayList<>();
        for (int extra = 1; extra < Math.abs(shardInputs); extra++) {
            TxOutRef extraRef = new TxOutRef(
                    new TxId(filled(shardInputs < 0 ? 0x5f : 0x63, 32)), BigInteger.valueOf(extra));
            extras.add(new TxInInfo(extraRef, new TxOut(
                    shardAddress,
                    threadedValue(BigInteger.valueOf(2_000_000), SHARD_POLICY, new byte[] {(byte) extra}),
                    new OutputDatum.OutputDatumInline(PlutusData.UNIT),
                    Optional.empty())));
            // An unpaid claim id of the extra shard's own nibble.
            byte[] unpaid = filled(0x90 + extra, 32);
            unpaid[31] = (byte) extra;
            builder.redeemerEntry(new ScriptPurpose.Spending(extraRef), shardInsertBatch(List.of(unpaid)));
        }
        List<TxInInfo> inputs = new ArrayList<>(paired);
        inputs.addAll(shardInputs < 0 ? 0 : inputs.size(), extras);
        for (TxInInfo input : inputs) {
            builder.input(input);
        }

        List<TxOut> payouts = new ArrayList<>(batch.payoutOutputs());
        if (reorderPayouts && payouts.size() >= 2) {
            TxOut first = payouts.get(0);
            payouts.set(0, payouts.get(1));
            payouts.set(1, first);
        }
        for (TxOut payout : payouts) {
            builder.output(payout);
        }
        BigInteger continuing = batch.vaultInput.subtract(batch.total)
                .subtract(skim ? BigInteger.valueOf(500_000) : BigInteger.ZERO);
        builder.output(new TxOut(
                vaultAddress, Value.lovelace(continuing),
                new OutputDatum.OutputDatumInline(batch.marker()),
                Optional.empty()));
        if (mode == 1) {
            builder.validRange(new Interval(
                    new IntervalBound(new IntervalBoundType.Finite(
                            BigInteger.valueOf(now)), true),
                    new IntervalBound(new IntervalBoundType.Finite(
                            BigInteger.valueOf(now + 1_000)), true)));
        }
        return builder;
    }

    /** NullifierShardValidator's InsertBatch{[Insert{claimId, proof}]}; the vault reads only the ids. */
    private static PlutusData shardInsertBatch(List<byte[]> claimIds) {
        List<PlutusData> inserts = new ArrayList<>();
        for (byte[] claimId : claimIds) {
            inserts.add(PlutusData.constr(0, PlutusData.bytes(claimId), PlutusData.list()));
        }
        return PlutusData.constr(0, PlutusData.list(inserts.toArray(new PlutusData[0])));
    }

    private Batch batch(int claimCount) {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        List<Claim> claims = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < claimCount; i++) {
            Claim claim = claim(i);
            claims.add(claim);
            byte[] key = concat(KEY_PREFIX, claim.claimId);
            trie.put(key, claim.digest);
            total = total.add(claim.payout).add(claim.bounty);
        }
        byte[] stateRoot = trie.getRootHash();
        List<PlutusData> settle = new ArrayList<>();
        List<PlutusData> exit = new ArrayList<>();
        List<TxOut> payoutOutputs = new ArrayList<>();
        byte[] joined = new byte[0];
        for (Claim claim : claims) {
            settle.add(claim.claimData());
            byte[] proofWire = trie.getProofWire(
                    concat(KEY_PREFIX, claim.claimId)).orElseThrow();
            exit.add(PlutusData.constr(0, claim.claimData(),
                    convertProofSteps(proofWire)));
            payoutOutputs.add(new TxOut(
                    claim.destination, Value.lovelace(claim.payout),
                    new OutputDatum.NoOutputDatum(), Optional.empty()));
            joined = concat(joined, claim.claimId);
        }
        return new Batch(claims, total,
                total.add(BigInteger.valueOf(2_000_000)),
                stateRoot, 5_000L,
                settle.toArray(new PlutusData[0]),
                exit.toArray(new PlutusData[0]),
                payoutOutputs, joined, MEMBER_KEYS);
    }

    private static Claim claim(int index) {
        byte[] destHash = filled(0x80 + index, 28);
        Address destination = new Address(
                new Credential.PubKeyCredential(new PubKeyHash(destHash)),
                Optional.empty());
        long payout = 6_000_000L + index * 1_000_000L;
        long bounty = 2_000_000L;
        // Deterministic claim id whose last nibble is 0 (shard 0).
        byte[] claimId = new byte[32];
        Arrays.fill(claimId, (byte) (0x30 + index));
        claimId[31] = (byte) (claimId[31] & 0xF0);
        byte[] digest = claimDigest(EPOCH.longValue(), index, claimId, destHash,
                payout, bounty);
        return new Claim(claimId, destination, destination.toPlutusData(),
                BigInteger.valueOf(index), BigInteger.valueOf(payout),
                BigInteger.valueOf(bounty), digest);
    }

    private static byte[] claimDigest(
            long epoch, long sequence, byte[] claimId, byte[] destHash,
            long payout, long bounty) {
        // Enterprise-address fingerprint: blake2b(cred(0x00||hash)||0x00).
        byte[] cred = concat(new byte[] {0x00}, destHash);
        byte[] destFp = Blake2bUtil.blake2bHash256(concat(cred, new byte[] {0x00}));
        byte[] buf = concat(CLAIM_DOMAIN, Blake2bUtil.blake2bHash256(CHAIN_ID));
        buf = concat(buf, int64(epoch));
        buf = concat(buf, int64(sequence));
        buf = concat(buf, claimId);
        buf = concat(buf, destFp);
        buf = concat(buf, int64(payout));
        buf = concat(buf, int64(bounty));
        return Blake2bUtil.blake2bHash256(buf);
    }

    private record Claim(
            byte[] claimId, Address destination, PlutusData destinationData,
            BigInteger sequence, BigInteger payout, BigInteger bounty,
            byte[] digest) {
        PlutusData claimData() {
            return PlutusData.constr(0,
                    PlutusData.integer(EPOCH),
                    PlutusData.integer(sequence),
                    PlutusData.bytes(claimId),
                    destinationData,
                    PlutusData.integer(payout),
                    PlutusData.integer(bounty));
        }
    }

    private record Batch(
            List<Claim> claims, BigInteger total, BigInteger vaultInput,
            byte[] stateRoot, long updatedAtMillis,
            PlutusData[] settleClaims, PlutusData[] exitClaims,
            List<TxOut> payoutOutputs, byte[] joinedIds, List<byte[]> members) {
        Batch withMembers(List<byte[]> replacement) {
            return new Batch(claims, total, vaultInput, stateRoot, updatedAtMillis, settleClaims, exitClaims,
                    payoutOutputs, joinedIds, replacement);
        }

        PlutusData rootDatum() {
            List<PlutusData> keys = new ArrayList<>();
            for (byte[] key : members) {
                keys.add(PlutusData.bytes(key));
            }
            return PlutusData.constr(0,
                    PlutusData.integer(1),
                    PlutusData.bytes(CHAIN_ID),
                    PlutusData.integer(EPOCH),
                    PlutusData.integer(1),
                    PlutusData.bytes(stateRoot),
                    PlutusData.list(keys.toArray(new PlutusData[0])),
                    PlutusData.integer(THRESHOLD),
                    PlutusData.integer(1),
                    PlutusData.integer(updatedAtMillis),
                    PlutusData.integer(FALLBACK_DELAY_MILLIS));
        }

        PlutusData marker() {
            return PlutusData.constr(0,
                    PlutusData.integer(1),
                    PlutusData.integer(claims.size()),
                    PlutusData.bytes(joinedIds));
        }
    }

    // ------------------------------------------------------------------

    private static List<byte[]> sortedMembers() {
        List<byte[]> keys = new ArrayList<>();
        keys.add(filled(0x11, 32));
        keys.add(filled(0x22, 32));
        keys.add(filled(0x33, 32));
        keys.sort((a, b) -> Arrays.compareUnsigned(a, b));
        return keys;
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static Value threadedValue(
            BigInteger lovelace, byte[] policy, byte[] tokenName) {
        return Value.lovelace(lovelace).merge(Value.singleton(
                new PolicyId(policy), new TokenName(tokenName), BigInteger.ONE));
    }

    /** MPF wire proof (tags 121/122/123) -> julc List of ProofStep constrs
     * Branch=Constr0[skip, neighbors], Fork=Constr1[skip, Neighbor],
     * Leaf=Constr2[skip, key, value]; Neighbor=Constr0[nibble, prefix, root]. */
    private static PlutusData convertProofSteps(byte[] proofWire) {
        try {
            var data = com.bloxbean.cardano.client.plutus.spec.PlutusData
                    .deserialize(proofWire);
            var list = (com.bloxbean.cardano.client.plutus.spec.ListPlutusData) data;
            var steps = new ArrayList<PlutusData>();
            for (var item : list.getPlutusDataList()) {
                var c = (com.bloxbean.cardano.client.plutus.spec
                        .ConstrPlutusData) item;
                var f = c.getData().getPlutusDataList();
                long tag = c.getAlternative();
                if (tag == 0) {
                    steps.add(PlutusData.constr(0,
                            intData(f.get(0)), bytesData(f.get(1))));
                } else if (tag == 1) {
                    var nc = (com.bloxbean.cardano.client.plutus.spec
                            .ConstrPlutusData) f.get(1);
                    var nf = nc.getData().getPlutusDataList();
                    steps.add(PlutusData.constr(1, intData(f.get(0)),
                            PlutusData.constr(0, intData(nf.get(0)),
                                    bytesData(nf.get(1)), bytesData(nf.get(2)))));
                } else if (tag == 2) {
                    steps.add(PlutusData.constr(2, intData(f.get(0)),
                            bytesData(f.get(1)), bytesData(f.get(2))));
                } else {
                    throw new IllegalStateException("unexpected MPF step tag " + tag);
                }
            }
            return PlutusData.list(steps.toArray(new PlutusData[0]));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static PlutusData intData(
            com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        return PlutusData.integer(((com.bloxbean.cardano.client.plutus.spec
                .BigIntPlutusData) data).getValue());
    }

    private static PlutusData bytesData(
            com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        return PlutusData.bytes(((com.bloxbean.cardano.client.plutus.spec
                .BytesPlutusData) data).getValue());
    }

    private static byte[] int64(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static byte[] filled(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
