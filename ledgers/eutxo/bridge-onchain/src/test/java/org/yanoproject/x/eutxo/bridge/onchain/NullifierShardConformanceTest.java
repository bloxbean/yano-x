package org.yanoproject.x.eutxo.bridge.onchain;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.stdlib.lib.CryptoLib;
import org.julclang.testkit.ContractTest;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.eutxo.bridge.onchain.mpf.MerklePatriciaForestry;
import org.yanoproject.x.eutxo.bridge.onchain.mpf.Neighbor;
import org.yanoproject.x.eutxo.bridge.onchain.mpf.ProofStep;
import org.yanoproject.x.eutxo.client.NullifierShardMirror;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * ADR-UTXO-009 SP-M2: nullifier shard — chained non-membership + insert
 * proofs against a real off-chain trie, paired with a vault spend.
 */
class NullifierShardConformanceTest extends ContractTest {
    private static final byte[] SHARD_POLICY = filled(0x71, 28);
    private static final byte[] VAULT_SCRIPT = filled(0x54, 28);
    private static final byte[] SHARD_SCRIPT = filled(0x53, 28);
    private static final byte[] CHAIN_ID = "payments".getBytes(StandardCharsets.UTF_8);
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(NullifierShardValidator.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(SHARD_POLICY),
                            PlutusData.bytes(VAULT_SCRIPT));
        }
        return program;
    }

    @Test
    void chainedInsertsAdvanceTheShardRoot() {
        for (int n : new int[] {1, 2, 3}) {
            var result = evaluate(program(), context(n, false, false));
            assertSuccess(result);
            System.out.println("[NullifierShard] inserts=" + n
                    + " cpu=" + result.budgetConsumed().cpuSteps()
                    + " mem=" + result.budgetConsumed().memoryUnits());
        }
    }

    @Test
    void adversarialCasesFail() {
        // Continuing datum carries a root that does not match the computed one.
        assertFailure(evaluate(program(), context(2, true, false)));
        // No paired vault spend.
        assertFailure(evaluate(program(), context(2, false, true)));
    }

    /**
     * A shard's first settlement starts from the empty root (32 zero bytes). The proofs are the mirror's wire
     * proofs, put in the redeemer the way QuickTxSettlePipeline does.
     */
    @Test
    void emptyShardAcceptsFirstInserts() {
        for (int n : new int[] {1, 4}) {
            List<byte[]> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ids.add(shardZeroClaimId(0x20 + i));
            }
            var plan = new NullifierShardMirror().planInserts(0, ids);
            assertArrayEquals(NullifierShardMirror.emptyRoot(), plan.priorRoot());
            assertSuccess(evaluate(program(),
                    context(plan.priorRoot(), redeemerInserts(plan), plan.nextRoot(), false)));
        }
    }

    /**
     * A settled claim id must not settle again. Each forged proof rebuilds the running root as if the id were
     * absent, so only miss()'s proof shape check rejects it. The next root is the one an unchecked walk computes,
     * which a shard validator without the check accepts.
     */
    @Test
    void forgedReinsertOfASettledIdFails() {
        // One settled id: a Leaf step that rebuilds the id's own leaf.
        byte[] settled = shardZeroClaimId(0x30);
        var mirror = new NullifierShardMirror();
        mirror.insert(settled);
        byte[] path = CryptoLib.blake2b_256(settled);
        byte[] ownLeaf = MerklePatriciaForestry.combine(
                MerklePatriciaForestry.suffix(path, 1), CryptoLib.blake2b_256(settled));
        assertForgedReinsertFails(mirror.root(0), settled,
                new ProofStep.Leaf(0, path, CryptoLib.blake2b_256(settled)), ownLeaf);

        // Two settled ids under one path nibble: a Fork whose neighbor is that branch, on the path's own nibble.
        byte[] first = shardZeroClaimId(0x31);
        byte[] firstPath = CryptoLib.blake2b_256(first);
        byte[] second = null;
        for (int fill = 0x32; second == null; fill++) {
            byte[] candidate = CryptoLib.blake2b_256(shardZeroClaimId(fill));
            if (MerklePatriciaForestry.nibble(candidate, 0) == MerklePatriciaForestry.nibble(firstPath, 0)
                    && MerklePatriciaForestry.nibble(candidate, 1) != MerklePatriciaForestry.nibble(firstPath, 1)) {
                second = shardZeroClaimId(fill);
            }
        }
        var pair = new NullifierShardMirror();
        pair.insert(first);
        pair.insert(second);
        byte[] secondPath = CryptoLib.blake2b_256(second);
        byte[] children = MerklePatriciaForestry.sparseMerkle16(
                MerklePatriciaForestry.nibble(firstPath, 1), leafAt(first, 2),
                MerklePatriciaForestry.nibble(secondPath, 1), leafAt(second, 2));
        assertForgedReinsertFails(pair.root(0), first,
                new ProofStep.Fork(0, new Neighbor(MerklePatriciaForestry.nibble(firstPath, 0), new byte[0], children)),
                MerklePatriciaForestry.combine(new byte[0], children));
    }

    /** The second copy of an id is checked against the root that already holds it. */
    @Test
    void sameIdTwiceInOneBatchFails() {
        var mirror = new NullifierShardMirror();
        for (int i = 0; i < 4; i++) {
            mirror.insert(shardKey(0x10 + i));
        }
        var plan = mirror.planInserts(0, List.of(shardZeroClaimId(0x50)));
        List<PlutusData> once = redeemerInserts(plan);
        assertSuccess(evaluate(program(), context(plan.priorRoot(), once, plan.nextRoot(), false)));
        assertFailure(evaluate(program(),
                context(plan.priorRoot(), List.of(once.get(0), once.get(0)), plan.nextRoot(), false)));
    }

    private void assertForgedReinsertFails(
            byte[] root, byte[] claimId, ProofStep forged, byte[] neighborHash) {
        assertArrayEquals(root, MerklePatriciaForestry.excluding(claimId, JulcList.of(forged)));
        // What doFork computes for the one forged step when it does not reject the neighbor's nibble.
        int branch = MerklePatriciaForestry.nibble(CryptoLib.blake2b_256(claimId), 0);
        byte[] nextRoot = MerklePatriciaForestry.combine(new byte[0],
                MerklePatriciaForestry.sparseMerkle16(branch, leafAt(claimId, 1), branch, neighborHash));
        PlutusData insert = PlutusData.constr(0, PlutusData.bytes(claimId), PlutusData.list(stepData(forged)));
        assertFailure(evaluate(program(), context(root, List.of(insert), nextRoot, false)));
    }

    /** Hash of a claim id's leaf (the id is its own value) at the given cursor. */
    private static byte[] leafAt(byte[] claimId, int cursor) {
        return MerklePatriciaForestry.combine(
                MerklePatriciaForestry.suffix(CryptoLib.blake2b_256(claimId), cursor),
                CryptoLib.blake2b_256(claimId));
    }

    private static PlutusData stepData(ProofStep step) {
        if (step instanceof ProofStep.Fork fork) {
            Neighbor n = fork.neighbor();
            return PlutusData.constr(1, PlutusData.integer(fork.skip()), PlutusData.constr(0,
                    PlutusData.integer(n.nibble()), PlutusData.bytes(n.prefix()), PlutusData.bytes(n.root())));
        }
        ProofStep.Leaf leaf = (ProofStep.Leaf) step;
        return PlutusData.constr(2, PlutusData.integer(leaf.skip()),
                PlutusData.bytes(leaf.key()), PlutusData.bytes(leaf.value()));
    }

    /** Insert items exactly as QuickTxSettlePipeline builds them, converted to the testkit's Data. */
    private static List<PlutusData> redeemerInserts(NullifierShardMirror.InsertPlan plan) {
        List<PlutusData> items = new ArrayList<>();
        try {
            for (NullifierShardMirror.PlannedInsert insert : plan.inserts()) {
                items.add(PlutusDataAdapter.fromClientLib(ConstrPlutusData.of(0,
                        BytesPlutusData.of(insert.claimId()),
                        com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(insert.proofWire()))));
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return items;
    }

    private PlutusData context(int inserts, boolean wrongNextRoot, boolean noVault) {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        // Seed the shard trie with some settled ids.
        for (int i = 0; i < 4; i++) {
            trie.put(shardKey(0x10 + i), shardKey(0x10 + i));
        }
        byte[] currentRoot = trie.getRootHash();
        List<PlutusData> insertItems = new ArrayList<>();
        for (int i = 0; i < inserts; i++) {
            byte[] claimId = shardZeroClaimId(0x40 + i);
            byte[] proofWire = trie.getProofWire(claimId).orElseThrow();
            insertItems.add(PlutusData.constr(0,
                    PlutusData.bytes(claimId), convertProofSteps(proofWire)));
            trie.put(claimId, claimId);
        }
        byte[] finalRoot = wrongNextRoot ? filled(0x00, 32) : trie.getRootHash();
        return context(currentRoot, insertItems, finalRoot, noVault);
    }

    private PlutusData context(
            byte[] currentRoot, List<PlutusData> insertItems, byte[] finalRoot, boolean noVault) {
        PlutusData currentDatum = shardDatum(currentRoot);
        PlutusData nextDatum = shardDatum(finalRoot);
        Address shardAddress = scriptAddress(SHARD_SCRIPT);
        Address vaultAddress = scriptAddress(VAULT_SCRIPT);
        TxOutRef shardRef = new TxOutRef(new TxId(filled(0x62, 32)), BigInteger.ZERO);
        TxOutRef vaultRef = new TxOutRef(new TxId(filled(0x61, 32)), BigInteger.ZERO);
        Value shardValue = threadedValue(BigInteger.valueOf(2_000_000),
                SHARD_POLICY, new byte[] {0x00});

        var builder = spendingContext(shardRef, currentDatum)
                .redeemer(PlutusData.constr(0, PlutusData.list(insertItems.toArray(new PlutusData[0]))))
                .fee(BigInteger.valueOf(300_000))
                .input(new TxInInfo(shardRef, new TxOut(
                        shardAddress, shardValue,
                        new OutputDatum.OutputDatumInline(currentDatum),
                        Optional.empty())))
                .output(new TxOut(
                        shardAddress, shardValue,
                        new OutputDatum.OutputDatumInline(nextDatum),
                        Optional.empty()));
        if (!noVault) {
            builder.input(new TxInInfo(vaultRef, new TxOut(
                    vaultAddress, Value.lovelace(BigInteger.valueOf(20_000_000)),
                    new OutputDatum.OutputDatumInline(PlutusData.UNIT),
                    Optional.empty())));
        }
        return builder.buildPlutusData();
    }

    private static PlutusData shardDatum(byte[] root) {
        return PlutusData.constr(0,
                PlutusData.integer(1),
                PlutusData.bytes(CHAIN_ID),
                PlutusData.integer(7),
                PlutusData.integer(0),
                PlutusData.bytes(root));
    }

    private static byte[] shardZeroClaimId(int fill) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) fill);
        id[31] = (byte) (id[31] & 0xF0);
        return id;
    }

    private static byte[] shardKey(int fill) {
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) fill);
        id[31] = (byte) (id[31] & 0xF0);
        return id;
    }

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
                    steps.add(PlutusData.constr(0, intData(f.get(0)), bytesData(f.get(1))));
                } else if (tag == 1) {
                    var nc = (com.bloxbean.cardano.client.plutus.spec
                            .ConstrPlutusData) f.get(1);
                    var nf = nc.getData().getPlutusDataList();
                    steps.add(PlutusData.constr(1, intData(f.get(0)),
                            PlutusData.constr(0, intData(nf.get(0)),
                                    bytesData(nf.get(1)), bytesData(nf.get(2)))));
                } else {
                    steps.add(PlutusData.constr(2, intData(f.get(0)),
                            bytesData(f.get(1)), bytesData(f.get(2))));
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

    private static byte[] filled(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        @Override public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }
        @Override public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }
        @Override public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
