package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * ADR-059 D8g §4 {@code BalanceExit}: one exit leaf proved against {@code frozenRoot}, the holder's signature, and a
 * shard insert list that is exactly the script-derived escape id (I17), so one exit never burns another holder's
 * entitlement and a balance exits once.
 */
class VaultBalanceExitConformanceTest extends GatewayTestBase {
    private static final byte[] HOLDER_KEY = filled(0xd1, 28);
    private static final byte[] RECIPIENT = GatewayFixtures.enterpriseKeyAddress(filled(0xd2, 28));

    record Holder(byte[] holderId, long amount, long vaultLovelace) {
    }

    private static Hash32 vaultIdOf(AssetId asset) {
        return asset.isAda() ? ADA_VAULT : TOKEN_VAULT;
    }

    private static byte[] exitKey(AssetId asset, byte[] holderId) {
        return GatewayFixtures.concat(GatewayFixtures.concat(EXIT_PREFIX, vaultIdOf(asset).bytes()), holderId);
    }

    private static byte[] escapeId(AssetId asset, byte[] holderId) {
        return GatewayIds.escapeNullifier(vaultIdOf(asset), holderId).bytes();
    }

    private static Holder holder(AssetId asset, int seed) {
        return new Holder(filled(0xa0 + seed, 28), asset.isAda() ? 7_000_000L : 250, asset.isAda() ? 0 : 1_200_000L);
    }

    private static StateTrie exitState(AssetId asset, List<Holder> holders) {
        StateTrie state = new StateTrie().fill("ap/account/", 64);
        for (Holder h : holders) {
            state.put(exitKey(asset, h.holderId()), new ExitLeafV1(HOLDER_KEY, vaultIdOf(asset),
                    BigInteger.valueOf(h.amount()), BigInteger.valueOf(h.vaultLovelace())).encode());
        }
        return state;
    }

    private static PlutusData redeemer(Holder h, PlutusData proof, int shard, byte[] recipient) {
        return PlutusData.constr(7, PlutusData.bytes(h.holderId()), PlutusData.bytes(HOLDER_KEY),
                PlutusData.integer(h.amount()), PlutusData.integer(h.vaultLovelace()), PlutusData.bytes(recipient),
                proof, PlutusData.integer(shard));
    }

    /** A BalanceExit transaction in exit mode with {@code frozenRoot}. */
    private static Scenario balanceExit(AssetId asset, Holder h, StateTrie proofs, byte[] frozenRoot,
                                        ShardTrie shardTrie, List<byte[]> inserted) {
        byte[] escape = escapeId(asset, h.holderId());
        int shard = escape[31] & 0x0f;
        PlutusData vaultRedeemer = redeemer(h, proofs.proof(exitKey(asset, h.holderId())), shard, RECIPIENT);
        long inLovelace = 20_000_000L + (asset.isAda() ? h.amount() : 0);
        long inQuantity = asset.isAda() ? 0 : 5_000L;
        Inserts inserts = shardTrie.plan(inserted == null ? List.of(escape) : inserted);
        Tx tx = new Tx();
        tx.spend(vaultInput(VAULT_REF, asset.isAda() ? lovelace(inLovelace) : assetValue(asset, inLovelace,
                inQuantity), continuationDatum(List.of())), vaultRedeemer);
        tx.spend(new TxInInfo(shardRef(shard), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                new OutputDatum.OutputDatumInline(shardDatum(shard, inserts.before())), Optional.empty())),
                inserts.redeemer());
        tx.spend(keyInput(FEE_REF, 50_000_000L), null);
        tx.output(payTo(RECIPIENT, asset.isAda() ? lovelace(h.amount())
                : assetValue(asset, h.vaultLovelace(), h.amount())));
        tx.output(out(vaultAddress(), asset.isAda() ? lovelace(inLovelace - h.amount())
                        : assetValue(asset, inLovelace - h.vaultLovelace(), inQuantity - h.amount()),
                continuationDatum(List.of(escape))));
        tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {(byte) shard}, 1)),
                shardDatum(shard, inserts.after())));
        tx.reference(exitModeInput(exitMode(frozenRoot)));
        tx.signer(new PubKeyHash(HOLDER_KEY));
        return new Scenario(tx, VAULT_REF, shardRef(shard), inserts);
    }

    private static Scenario balanceExit(AssetId asset, Holder h) {
        StateTrie state = exitState(asset, List.of(h, holder(asset, 1)));
        int shard = escapeId(asset, h.holderId())[31] & 0x0f;
        return balanceExit(asset, h, state, state.root(), new ShardTrie(shard).seed(6), null);
    }

    @Test
    void aHolderExitsItsFrozenBalanceOnBothVaults() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            Scenario scenario = balanceExit(asset, holder(asset, 0));
            ok(vault(asset), scenario.vault());
            ok(shard(), scenario.shard());
        }
    }

    @Test
    void balanceExitWithoutTheOwnersSignatureFails() {
        Scenario scenario = balanceExit(TOKEN, holder(TOKEN, 0));
        scenario.tx().signers.clear();
        rejected(vault(TOKEN), scenario.vault());
    }

    @Test
    void aSecondBalanceExitFailsInTheShard() {
        Holder h = holder(TOKEN, 0);
        StateTrie state = exitState(TOKEN, List.of(h));
        int shard = escapeId(TOKEN, h.holderId())[31] & 0x0f;
        ShardTrie shardTrie = new ShardTrie(shard).seed(6);
        Scenario first = balanceExit(TOKEN, h, state, state.root(), shardTrie, null);
        ok(shard(), first.shard());
        Scenario second = balanceExit(TOKEN, h, state, state.root(), shardTrie, null);
        ok(vault(TOKEN), second.vault());
        rejected(shard(), second.shard());
    }

    @Test
    void balanceExitAgainstANonFrozenRootOrInNormalModeFails() {
        Holder h = holder(TOKEN, 0);
        StateTrie frozen = exitState(TOKEN, List.of(holder(TOKEN, 1)));
        StateTrie later = exitState(TOKEN, List.of(h, holder(TOKEN, 1)));
        int shard = escapeId(TOKEN, h.holderId())[31] & 0x0f;
        rejected(vault(TOKEN), balanceExit(TOKEN, h, later, frozen.root(), new ShardTrie(shard).seed(6), null)
                .vault());

        Scenario normal = balanceExit(TOKEN, h);
        normal.tx().references.removeIf(r -> r.outRef().equals(ref(0x64, 0)));
        normal.tx().reference(exitModeInput(normalMode()));
        rejected(vault(TOKEN), normal.vault());
    }

    @Test
    void theInsertListIsExactlyTheScriptDerivedEscapeId() {
        Holder h = holder(TOKEN, 0);
        StateTrie state = exitState(TOKEN, List.of(h, holder(TOKEN, 1)));
        byte[] escape = escapeId(TOKEN, h.holderId());
        int shard = escape[31] & 0x0f;
        byte[] another = shardId(shard, 4242);
        for (List<byte[]> inserted : List.of(List.of(another), List.of(escape, another), List.<byte[]>of(another,
                escape))) {
            rejected(vault(TOKEN), balanceExit(TOKEN, h, state, state.root(), new ShardTrie(shard).seed(6),
                    inserted).vault());
        }
    }

    @Test
    void balanceExitPaysExactlyTheLeafToTheNamedNonScriptRecipient() {
        Holder h = holder(TOKEN, 0);
        StateTrie state = exitState(TOKEN, List.of(h));
        int shard = escapeId(TOKEN, h.holderId())[31] & 0x0f;
        // The redeemer claims more than the leaf.
        Holder greedy = new Holder(h.holderId(), h.amount() + 1, h.vaultLovelace());
        Scenario more = balanceExit(TOKEN, h, state, state.root(), new ShardTrie(shard).seed(6), null);
        more.tx().replaceRedeemer(more.tx().inputs.get(0).redeemer(),
                redeemer(greedy, state.proof(exitKey(TOKEN, h.holderId())), shard, RECIPIENT));
        rejected(vault(TOKEN), more.vault());

        Scenario elsewhere = balanceExit(TOKEN, h);
        elsewhere.tx().outputs.set(0, payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xbb, 28)),
                elsewhere.tx().outputs.get(0).value()));
        rejected(vault(TOKEN), elsewhere.vault());

        byte[] script = GatewayFixtures.concat(new byte[] {0x70}, filled(0x5d, 28));
        Scenario toScript = balanceExit(TOKEN, h);
        toScript.tx().replaceRedeemer(toScript.tx().inputs.get(0).redeemer(),
                redeemer(h, state.proof(exitKey(TOKEN, h.holderId())), shard, script));
        toScript.tx().outputs.set(0, payTo(script, toScript.tx().outputs.get(0).value()));
        rejected(vault(TOKEN), toScript.vault());
    }

    @Test
    void anotherHoldersLeafCannotBeExitedUnderThisHolderId() {
        Holder h = holder(TOKEN, 0);
        Holder other = holder(TOKEN, 1);
        StateTrie state = exitState(TOKEN, List.of(h, other));
        int shard = escapeId(TOKEN, h.holderId())[31] & 0x0f;
        Scenario scenario = balanceExit(TOKEN, h, state, state.root(), new ShardTrie(shard).seed(6), null);
        // The proof is the other holder's leaf; the key the script computes is this holder's.
        scenario.tx().replaceRedeemer(scenario.tx().inputs.get(0).redeemer(),
                redeemer(h, state.proof(exitKey(TOKEN, other.holderId())), shard, RECIPIENT));
        rejected(vault(TOKEN), scenario.vault());
    }
}
