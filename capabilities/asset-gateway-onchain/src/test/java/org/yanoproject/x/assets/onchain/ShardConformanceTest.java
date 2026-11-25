package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.core.types.JulcList;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.onchain.mpf.MerklePatriciaForestry;
import org.yanoproject.x.assets.onchain.mpf.Neighbor;
import org.yanoproject.x.assets.onchain.mpf.ProofStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADR-059 D8c: the per-vault nullifier shard validator {@code (shardThreadPolicyId, vaultScriptHash)} and the per-vault
 * one-shot {@code ShardThreadPolicy}.
 */
class ShardConformanceTest extends GatewayTestBase {
    private static final int SHARD = 6;

    /** A shard spend paired with a vault spend, inserting {@code count} fresh ids. */
    private static Tx shardSpend(ShardTrie trie, int count, byte[] vaultHash) {
        List<byte[]> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(shardId(SHARD, 500 + i));
        }
        Inserts inserts = trie.plan(ids);
        Tx tx = new Tx();
        tx.spend(new TxInInfo(shardRef(SHARD), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                threadValue(), new OutputDatum.OutputDatumInline(shardDatum(SHARD, inserts.before())),
                Optional.empty())), inserts.redeemer());
        tx.spend(new TxInInfo(VAULT_REF, new TxOut(GatewayFixtures.scriptAddress(vaultHash), lovelace(9_000_000),
                new OutputDatum.OutputDatumInline(continuationDatum(List.of())), Optional.empty())),
                PlutusData.constr(0));
        tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(), shardDatum(SHARD, inserts.after())));
        return tx;
    }

    private static Value threadValue() {
        return lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {SHARD}, 1));
    }

    @Test
    void aShardInsertsFreshIdsFromTheEmptyRootAndFromASeededRoot() {
        for (int count : new int[] {1, 4, 16}) {
            ok(shard(), shardSpend(new ShardTrie(SHARD), count, VAULT_HASH).spending(shardRef(SHARD)));
            ok(shard(), shardSpend(new ShardTrie(SHARD).seed(40), count, VAULT_HASH).spending(shardRef(SHARD)));
        }
    }

    /**
     * M4-F1 (reviewer3): a real branch on a paid id's path at odd cursor {@code c}, whose {@code 2 + (63 - c) / 2}
     * nibble prefix starts with 0, has the shape of an odd leaf. A terminal {@code Leaf} step whose key carries that
     * prefix's nibbles as raw bytes is well formed and excludes to the shard root that holds the id, so before the
     * fix {@code miss()} passed and {@code including()} re-inserted the paid id (a double payment) and overwrote the
     * branch, erasing the other nullifiers under it. The shard now rejects it, at every odd cursor tried.
     */
    @Test
    void aPaidIdIsNeverReinsertedThroughABranchReadAsALeaf() {
        for (int c : new int[] {1, 3, 5, 9, 13}) {
            int length = 2 + (63 - c) / 2;
            byte[] id = null;
            byte[] path = null;
            for (int seed = 0; ; seed++) {
                id = shardId(SHARD, 70_000 + 1_000 * c + seed);
                path = GatewayFixtures.blake2b256(id);
                if (MerklePatriciaForestry.nibble(path, c) == 0 && MerklePatriciaForestry.nibble(path, c + 1) != 0) {
                    break;
                }
            }
            byte[] d1 = GatewayFixtures.blake2b256(new byte[] {1});
            byte[] d2 = GatewayFixtures.blake2b256(new byte[] {2});
            int lower = (MerklePatriciaForestry.nibble(path, c + length) + 1) % 16;
            int upper = (MerklePatriciaForestry.nibble(path, c - 1) + 1) % 16;
            byte[] leaf = MerklePatriciaForestry.combine(MerklePatriciaForestry.suffix(path, c + length + 1),
                    GatewayFixtures.blake2b256(id));
            byte[] branchMerkle = MerklePatriciaForestry.sparseMerkle16(MerklePatriciaForestry.nibble(path, c + length),
                    leaf, lower, MerklePatriciaForestry.combine(new byte[0], d1));
            byte[] prefix = MerklePatriciaForestry.nibbles(path, c, c + length);
            byte[] branch = MerklePatriciaForestry.combine(prefix, branchMerkle);
            byte[] root = MerklePatriciaForestry.doFork(path, 0, c - 1, branch, upper, new byte[0], d2);
            ProofStep upperStep = new ProofStep.Fork(c - 1, new Neighbor(upper, new byte[0], d2));
            JulcList<ProofStep> inclusion = JulcList.of(upperStep,
                    new ProofStep.Fork(length, new Neighbor(lower, new byte[0], d1)));
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.has(root, id, id, inclusion))
                    .as("cursor %d: the id is paid", c).isTrue();

            byte[] conflicting = new byte[32];
            System.arraycopy(path, 0, conflicting, 0, (c - 1) / 2);
            conflicting[(c - 1) / 2] = (byte) ((MerklePatriciaForestry.nibble(path, c - 1) << 4) | prefix[1]);
            System.arraycopy(prefix, 2, conflicting, (c + 1) / 2, length - 2);
            JulcList<ProofStep> forged = JulcList.of(upperStep, new ProofStep.Leaf(0, conflicting, branchMerkle));
            // The forgery is well formed and excludes to the paid root; only the branch-as-leaf rule stops it.
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.isWellFormed(path, 0, forged)).isTrue();
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.doExcluding(path, 0, forged))
                    .isEqualTo(root);
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.miss(root, id, forged)).isFalse();

            PlutusData insert = PlutusData.constr(0, PlutusData.list(PlutusData.constr(0, PlutusData.bytes(id),
                    PlutusData.list(PlutusData.constr(1, PlutusData.integer(c - 1), PlutusData.constr(0,
                                    PlutusData.integer(upper), PlutusData.bytes(new byte[0]), PlutusData.bytes(d2))),
                            PlutusData.constr(2, PlutusData.integer(0), PlutusData.bytes(conflicting),
                                    PlutusData.bytes(branchMerkle))))));
            byte[] next = MerklePatriciaForestry.including(id, id, forged);
            Tx tx = new Tx();
            tx.spend(new TxInInfo(shardRef(SHARD), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                    threadValue(), new OutputDatum.OutputDatumInline(shardDatum(SHARD, root)), Optional.empty())),
                    insert);
            tx.spend(new TxInInfo(VAULT_REF, new TxOut(vaultAddress(), lovelace(9_000_000),
                    new OutputDatum.OutputDatumInline(continuationDatum(List.of())), Optional.empty())),
                    PlutusData.constr(0));
            tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(), shardDatum(SHARD, next)));
            rejected(shard(), tx.spending(shardRef(SHARD)));
        }
    }

    /**
     * The converse forgery (PR #113 fcd107c0): a paid id's own leaf at odd cursor {@code c}, whose raw path bytes are
     * all below 16, has the shape of a branch. A terminal {@code Fork} step at {@code c} (skip 0, neighbor nibble 0,
     * neighbor prefix the rest of the leaf suffix, neighbor root the value hash) reconstructs exactly that node, so
     * the forged miss is well formed and excludes to the shard root that holds the id; {@code including()} would then
     * re-insert the paid id. The shard rejects it ({@code excludedTerminalIsUnambiguous}) at every cursor tried.
     */
    @Test
    void aPaidIdIsNeverReinsertedThroughItsOwnLeafReadAsABranch() {
        byte[] d = GatewayFixtures.blake2b256(new byte[] {3});
        for (int c : new int[] {55, 57, 59}) {
            byte[] id = null;
            byte[] path = null;
            for (int seed = 0; ; seed++) {
                id = shardId(SHARD, 90_000 + 100_000 * c + seed);
                path = GatewayFixtures.blake2b256(id);
                if (MerklePatriciaForestry.nibble(path, c) != 0 && MerklePatriciaForestry.allNibbles(path, (c + 1) / 2)) {
                    break;
                }
            }
            byte[] valueHash = GatewayFixtures.blake2b256(id);
            byte[] suffix = MerklePatriciaForestry.suffix(path, c);
            int other = (MerklePatriciaForestry.nibble(path, c - 1) + 1) % 16;
            byte[] root = MerklePatriciaForestry.doFork(path, 0, c - 1,
                    MerklePatriciaForestry.combine(suffix, valueHash), other, new byte[0], d);
            ProofStep upper = new ProofStep.Fork(c - 1, new Neighbor(other, new byte[0], d));
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.has(root, id, id, JulcList.of(upper)))
                    .as("cursor %d: the id is paid", c).isTrue();

            byte[] neighborPrefix = java.util.Arrays.copyOfRange(suffix, 1, suffix.length);
            JulcList<ProofStep> forged = JulcList.of(upper,
                    new ProofStep.Fork(0, new Neighbor(0, neighborPrefix, valueHash)));
            // The forgery is well formed and excludes to the paid root; only the terminal-fork rule stops it.
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.isWellFormed(path, 0, forged)).isTrue();
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.doExcluding(path, 0, forged))
                    .isEqualTo(root);

            PlutusData insert = PlutusData.constr(0, PlutusData.list(PlutusData.constr(0, PlutusData.bytes(id),
                    PlutusData.list(PlutusData.constr(1, PlutusData.integer(c - 1), PlutusData.constr(0,
                                    PlutusData.integer(other), PlutusData.bytes(new byte[0]), PlutusData.bytes(d))),
                            PlutusData.constr(1, PlutusData.integer(0), PlutusData.constr(0, PlutusData.integer(0),
                                    PlutusData.bytes(neighborPrefix), PlutusData.bytes(valueHash)))))));
            byte[] next = MerklePatriciaForestry.including(id, id, forged);
            Tx tx = new Tx();
            tx.spend(new TxInInfo(shardRef(SHARD), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                    threadValue(), new OutputDatum.OutputDatumInline(shardDatum(SHARD, root)), Optional.empty())),
                    insert);
            tx.spend(new TxInInfo(VAULT_REF, new TxOut(vaultAddress(), lovelace(9_000_000),
                    new OutputDatum.OutputDatumInline(continuationDatum(List.of())), Optional.empty())),
                    PlutusData.constr(0));
            tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(), shardDatum(SHARD, next)));
            rejected(shard(), tx.spending(shardRef(SHARD)));
            org.assertj.core.api.Assertions.assertThat(MerklePatriciaForestry.miss(root, id, forged)).isFalse();
        }
    }

    @Test
    void theShardOfAnotherVaultFails() {
        rejected(shard(), shardSpend(new ShardTrie(SHARD), 1, OTHER_VAULT_HASH).spending(shardRef(SHARD)));
    }

    @Test
    void aWrongShardIdOrWrongNextRootFails() {
        Tx wrongShard = new Tx();
        ShardTrie trie = new ShardTrie(SHARD);
        Inserts inserts = trie.plan(List.of(shardId(SHARD + 1, 1)));
        wrongShard.spend(new TxInInfo(shardRef(SHARD), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                threadValue(), new OutputDatum.OutputDatumInline(shardDatum(SHARD, inserts.before())),
                Optional.empty())), inserts.redeemer());
        wrongShard.spend(new TxInInfo(VAULT_REF, new TxOut(vaultAddress(), lovelace(9_000_000),
                new OutputDatum.OutputDatumInline(continuationDatum(List.of())), Optional.empty())),
                PlutusData.constr(0));
        wrongShard.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(),
                shardDatum(SHARD, inserts.after())));
        rejected(shard(), wrongShard.spending(shardRef(SHARD)));

        Tx wrongRoot = shardSpend(new ShardTrie(SHARD), 2, VAULT_HASH);
        wrongRoot.outputs.set(0, out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(),
                shardDatum(SHARD, filled(0x01, 32))));
        rejected(shard(), wrongRoot.spending(shardRef(SHARD)));
    }

    @Test
    void theContinuationKeepsTheExactAddressTokenAndIndex() {
        Tx staked = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        TxOut next = staked.outputs.get(0);
        staked.outputs.set(0, new TxOut(GatewayFixtures.scriptAddressWithStake(SHARD_HASH, filled(0x99, 28)),
                next.value(), next.datum(), Optional.empty()));
        rejected(shard(), staked.spending(shardRef(SHARD)));

        Tx junk = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        next = junk.outputs.get(0);
        junk.outputs.set(0, new TxOut(next.address(), next.value().merge(token(filled(0x7c, 28), new byte[] {1}, 1)),
                next.datum(), Optional.empty()));
        rejected(shard(), junk.spending(shardRef(SHARD)));

        Tx tokenless = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        next = tokenless.outputs.get(0);
        tokenless.outputs.set(0, new TxOut(next.address(), lovelace(2_000_000), next.datum(), Optional.empty()));
        rejected(shard(), tokenless.spending(shardRef(SHARD)));

        Tx index = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        PlutusData datum = ((OutputDatum.OutputDatumInline) index.outputs.get(0).datum()).datum();
        PlutusData root = ((PlutusData.ConstrData) datum).fields().get(2);
        index.outputs.set(0, out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(SHARD + 1), root)));
        rejected(shard(), index.spending(shardRef(SHARD)));

        Tx second = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        second.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), lovelace(2_000_000), PlutusData.UNIT));
        rejected(shard(), second.spending(shardRef(SHARD)));
    }

    @Test
    void dustAtTheShardAddressOrAMalformedDatumIsNotAShard() {
        Tx dust = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        Spent spent = dust.inputs.get(0);
        TxOut out = spent.input().resolved();
        dust.inputs.set(0, new Spent(new TxInInfo(spent.input().outRef(), new TxOut(out.address(),
                lovelace(2_000_000), out.datum(), Optional.empty())), spent.redeemer()));
        rejected(shard(), dust.spending(shardRef(SHARD)));

        Tx version = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        spent = version.inputs.get(0);
        out = spent.input().resolved();
        PlutusData root = ((PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) out.datum()).datum())
                .fields().get(2);
        version.inputs.set(0, new Spent(new TxInInfo(spent.input().outRef(), new TxOut(out.address(), out.value(),
                new OutputDatum.OutputDatumInline(PlutusData.constr(0, PlutusData.integer(2),
                        PlutusData.integer(SHARD), root)), Optional.empty())), spent.redeemer()));
        rejected(shard(), version.spending(shardRef(SHARD)));

        Tx empty = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        empty.replaceRedeemer(empty.inputs.get(0).redeemer(), PlutusData.constr(0, PlutusData.list()));
        rejected(shard(), empty.spending(shardRef(SHARD)));
    }

    @Test
    void aMintOfTheThreadPolicyAlongsideAShardSpendFails() {
        Tx tx = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        tx.mint = token(SHARD_POLICY, new byte[] {9}, 1);
        rejected(shard(), tx.spending(shardRef(SHARD)));
    }

    // ---- ShardThreadPolicy

    private static Program threadPolicy() {
        return program(AssetShardThreadPolicy.class, PlutusData.bytes(filled(0x6e, 32)), PlutusData.integer(1));
    }

    private static Tx bootstrap(int names, long quantity, boolean seed) {
        Tx tx = new Tx();
        tx.spend(keyInput(seed ? ref(0x6e, 1) : ref(0x6e, 2), 30_000_000L), null);
        Value mint = Value.zero();
        for (int i = 0; i < names; i++) {
            Value one = token(SHARD_POLICY, new byte[] {(byte) i}, quantity);
            mint = mint.merge(one);
            tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), lovelace(2_000_000).merge(one),
                    shardDatum(i, new byte[32])));
        }
        tx.mint = mint;
        return tx;
    }

    /** P3-14: each minted token sits alone in its output, with {@code ShardDatumV1(1, i, emptyRoot)}. */
    @Test
    void eachMintedTokenGetsItsOwnOutputWithAnEmptyShardDatum() {
        Tx together = bootstrap(16, 1, true);
        Value merged = lovelace(2_000_000);
        for (TxOut o : together.outputs.subList(0, 2)) {
            merged = merged.merge(o.value()).merge(lovelace(-2_000_000));
        }
        together.outputs.set(0, out(GatewayFixtures.scriptAddress(SHARD_HASH), merged, shardDatum(0, new byte[32])));
        together.outputs.remove(1);
        rejected(threadPolicy(), together.minting(SHARD_POLICY, PlutusData.integer(0)));

        Tx wrongIndex = bootstrap(16, 1, true);
        TxOut third = wrongIndex.outputs.get(3);
        wrongIndex.outputs.set(3, out(third.address(), third.value(), shardDatum(4, new byte[32])));
        rejected(threadPolicy(), wrongIndex.minting(SHARD_POLICY, PlutusData.integer(0)));

        Tx seeded = bootstrap(16, 1, true);
        TxOut first = seeded.outputs.get(0);
        seeded.outputs.set(0, out(first.address(), first.value(), shardDatum(0, filled(0x01, 32))));
        rejected(threadPolicy(), seeded.minting(SHARD_POLICY, PlutusData.integer(0)));

        Tx datumless = bootstrap(16, 1, true);
        datumless.outputs.set(0, out(first.address(), first.value(), null));
        rejected(threadPolicy(), datumless.minting(SHARD_POLICY, PlutusData.integer(0)));
    }

    /** P2-10, P3-13: the continuation keeps at least the spent lovelace and carries no reference script. */
    @Test
    void theContinuationKeepsItsLovelaceAndCarriesNoReferenceScript() {
        Tx more = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        TxOut next = more.outputs.get(0);
        more.outputs.set(0, new TxOut(next.address(), next.value().merge(lovelace(1)), next.datum(),
                Optional.empty()));
        ok(shard(), more.spending(shardRef(SHARD)));

        Tx drained = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        drained.outputs.set(0, new TxOut(next.address(), next.value().merge(lovelace(-1)), next.datum(),
                Optional.empty()));
        rejected(shard(), drained.spending(shardRef(SHARD)));

        Tx script = shardSpend(new ShardTrie(SHARD), 1, VAULT_HASH);
        script.outputs.set(0, new TxOut(next.address(), next.value(), next.datum(),
                Optional.of(new org.julclang.ledger.ScriptHash(filled(0x5b, 28)))));
        rejected(shard(), script.spending(shardRef(SHARD)));
    }

    @Test
    void theThreadPolicyMintsExactlySixteenTokensOnceFromItsSeed() {
        ok(threadPolicy(), bootstrap(16, 1, true).minting(SHARD_POLICY, PlutusData.integer(0)));
        rejected(threadPolicy(), bootstrap(16, 1, false).minting(SHARD_POLICY, PlutusData.integer(0)));
        rejected(threadPolicy(), bootstrap(15, 1, true).minting(SHARD_POLICY, PlutusData.integer(0)));
        rejected(threadPolicy(), bootstrap(17, 1, true).minting(SHARD_POLICY, PlutusData.integer(0)));
        rejected(threadPolicy(), bootstrap(16, 2, true).minting(SHARD_POLICY, PlutusData.integer(0)));
        Tx burn = bootstrap(0, 1, true);
        burn.mint = token(SHARD_POLICY, new byte[] {0}, -1);
        rejected(threadPolicy(), burn.minting(SHARD_POLICY, PlutusData.integer(0)));
    }
}
