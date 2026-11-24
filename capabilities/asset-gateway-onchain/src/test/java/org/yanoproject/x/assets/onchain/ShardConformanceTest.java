package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;

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
            mint = mint.merge(token(SHARD_POLICY, new byte[] {(byte) i}, quantity));
        }
        tx.mint = mint;
        tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), lovelace(2_000_000).merge(mint),
                shardDatum(0, new byte[32])));
        return tx;
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
