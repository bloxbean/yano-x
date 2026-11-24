package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADR-059 {@code Settle} (V9) and the rules shared with {@code Exit} (V4-V8, V10, V11, V13), on the token vault and
 * the ADA vault, with one negative case per script-enforced rule and the applicable §10 V6 cases.
 */
class VaultSettleConformanceTest extends GatewayTestBase {

    private Payout settle(AssetId asset, int count) {
        Payout payout = new Payout();
        payout.asset = asset;
        payout.shard = 0;
        payout.shardTrie = new ShardTrie(0).seed(8);
        for (int i = 0; i < count; i++) {
            payout.claims.add(userClaim(0, i, asset));
        }
        payout.vaultRedeemer = settleRedeemer(payout.claims, 0);
        payout.anchor = anchorDatum(2, filled(0x55, 32), 120, MEMBERS, THRESHOLD, NOW - HOUR);
        return payout;
    }

    @Test
    void settlePaysABatchOnBothVaults() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            for (int count : new int[] {1, 4}) {
                Scenario scenario = settle(asset, count).build();
                ok(vault(asset), scenario.vault());
                ok(shard(), scenario.shard());
            }
        }
    }

    @Test
    void aFloorTopUpInputIsAcceptedCustody() {
        Payout payout = settle(TOKEN, 2);
        payout.floorTopUpInput = true;
        Scenario scenario = payout.build();
        ok(vault(TOKEN), scenario.vault());
        // V13: the second vault input carries the same redeemer and only checks that.
        ok(vault(TOKEN), scenario.tx().spending(VAULT_REF_2));
    }

    @Test
    void aTokenPayoutSupplementedByTheBuilderSucceeds() {
        Payout payout = settle(TOKEN, 2);
        payout.payoutSupplement = 3_000_000L;
        Scenario scenario = payout.build();
        // The supplement comes from the fee input, not the vault.
        ok(vault(TOKEN), scenario.vault());
    }

    // ---- V9

    @Test
    void v9SettleBelowThresholdFails() {
        Payout payout = settle(TOKEN, 2);
        payout.signers = 1;
        rejected(vault(TOKEN), payout.build().vault());
    }

    @Test
    void v9SettleSignedByMembersOfAnOlderAnchorDatumFails() {
        Payout payout = settle(TOKEN, 2);
        // The live anchor's members are new keys; the old members' signatures do not count.
        payout.anchor = anchorDatum(2, filled(0x55, 32), 120, List.of(filled(0x44, 32), filled(0x45, 32)), 2,
                NOW - HOUR);
        rejected(vault(TOKEN), payout.build().vault());
    }

    @Test
    void v9SettleNeedsTheV2AnchorAtThePinnedIdentity() {
        Payout v1 = settle(TOKEN, 1);
        v1.anchor = anchorDatum(1, filled(0x55, 32), 120, MEMBERS, THRESHOLD, 0);
        rejected(vault(TOKEN), v1.build().vault());

        Payout none = settle(TOKEN, 1);
        none.anchor = null;
        rejected(vault(TOKEN), none.build().vault());

        // An anchor-looking reference at another script address.
        Scenario elsewhere = settle(TOKEN, 1).build();
        TxInInfo real = elsewhere.tx().references.get(0);
        elsewhere.tx().references.set(0, new TxInInfo(real.outRef(), new TxOut(
                GatewayFixtures.scriptAddress(OTHER_SCRIPT), real.resolved().value(), real.resolved().datum(),
                Optional.empty())));
        rejected(vault(TOKEN), elsewhere.vault());
    }

    @Test
    void governedClaimViaSettleFails() {
        Payout payout = settle(TOKEN, 2);
        payout.claims.set(1, payout.claims.get(1).withKind(2));
        payout.vaultRedeemer = settleRedeemer(payout.claims, 0);
        rejected(vault(TOKEN), payout.build().vault());
    }

    @Test
    void settleRejectsAnEmptyOrOversizedBatch() {
        Payout empty = settle(TOKEN, 1);
        empty.vaultRedeemer = settleRedeemer(List.of(), 0);
        rejected(vault(TOKEN), empty.build().vault());

        Payout big = settle(TOKEN, 17);
        rejected(vault(TOKEN), big.build().vault());
    }

    // ---- V4

    @Test
    void v4MissingExtraOrReorderedInsertsFail() {
        List<List<Integer>> wrong = List.of(List.of(0), List.of(0, 1, 2), List.of(1, 0), List.of(0, 2));
        for (List<Integer> order : wrong) {
            Payout payout = settle(TOKEN, 2);
            List<byte[]> inserted = new ArrayList<>();
            for (int index : order) {
                inserted.add(index < 2 ? payout.claims.get(index).id() : shardId(0, 77));
            }
            payout.inserted = inserted;
            Scenario scenario = payout.build();
            rejected(vault(TOKEN), scenario.vault());
            // The shard itself accepts any fresh ids: the pairing is the vault's rule.
            ok(shard(), scenario.shard());
        }
    }

    @Test
    void v4MixedShardBatchFails() {
        Payout payout = settle(TOKEN, 2);
        payout.claims.set(1, new ClaimFx(shardId(5, 1), 0, 1001, 1_500_000, payout.claims.get(1).recipient(),
                101, NOW - 2 * DAY));
        payout.vaultRedeemer = settleRedeemer(payout.claims, 0);
        rejected(vault(TOKEN), payout.build().vault());
    }

    @Test
    void v4ShardOfAnotherVaultOrATokenlessShardFails() {
        // The "shard" carries another vault's thread policy: it is an unknown script input, and no shard is spent.
        Scenario other = settle(TOKEN, 1).build();
        replaceShardToken(other, filled(0x7f, 28));
        rejected(vault(TOKEN), other.vault());

        // Dust at the shard address, with no thread token, as the "shard".
        Scenario dust = settle(TOKEN, 1).build();
        replaceShardToken(dust, null);
        rejected(vault(TOKEN), dust.vault());
    }

    @Test
    void v4ASecondShardInputFails() {
        Scenario scenario = settle(TOKEN, 1).build();
        Inserts extra = new ShardTrie(4).seed(2).plan(List.of(shardId(4, 50)));
        scenario.tx().spend(new TxInInfo(shardRef(4), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {4}, 1)),
                new OutputDatum.OutputDatumInline(shardDatum(4, extra.before())), Optional.empty())),
                extra.redeemer());
        rejected(vault(TOKEN), scenario.vault());
    }

    @Test
    void doublePayWithinOneTransactionFailsInTheShard() {
        Payout payout = settle(TOKEN, 1);
        payout.claims.add(payout.claims.get(0));
        payout.vaultRedeemer = settleRedeemer(payout.claims, 0);
        Scenario scenario = payout.build();
        // The second insert of the same id has no exclusion proof at the running root.
        rejected(shard(), scenario.shard());
    }

    @Test
    void doublePayAcrossTransactionsFailsInTheShard() {
        Payout first = settle(TOKEN, 1);
        ok(shard(), first.build().shard());
        // The same claim again, on the shard that now holds it.
        Payout again = settle(TOKEN, 1);
        again.shardTrie = first.shardTrie;
        Scenario scenario = again.build();
        rejected(shard(), scenario.shard());
    }

    // ---- V5

    @Test
    void v5WrongRecipientQuantityOrAssetFails() {
        byte[] attacker = GatewayFixtures.enterpriseKeyAddress(filled(0xbb, 28));
        Scenario recipient = settle(TOKEN, 2).build();
        TxOut first = recipient.tx().outputs.get(0);
        recipient.tx().outputs.set(0, payTo(attacker, first.value()));
        rejected(vault(TOKEN), recipient.vault());

        Payout quantity = settle(TOKEN, 2);
        quantity.paidAs = List.of(quantity.claims.get(0).withQuantity(quantity.claims.get(0).quantity() + 1),
                quantity.claims.get(1));
        rejected(vault(TOKEN), quantity.build().vault());

        Scenario asset = settle(TOKEN, 1).build();
        ClaimFx claim = userClaim(0, 0, TOKEN);
        asset.tx().outputs.set(0, payTo(claim.recipient(),
                lovelace(1_500_000).merge(token(filled(0x7b, 28), TOKEN.assetName(), claim.quantity()))));
        rejected(vault(TOKEN), asset.vault());
    }

    @Test
    void v5RecipientsAreNeverScriptsAndTheVaultLovelaceIsAFloor() {
        // A claim whose recipient is a script address cannot be paid by Settle.
        Payout script = settle(TOKEN, 1);
        byte[] scriptRecipient = GatewayFixtures.concat(new byte[] {0x70}, filled(0x5d, 28));
        script.claims.set(0, script.claims.get(0).withRecipient(scriptRecipient));
        script.vaultRedeemer = settleRedeemer(script.claims, 0);
        rejected(vault(TOKEN), script.build().vault());

        // The payout carries less lovelace than vaultLovelace.
        Scenario low = settle(TOKEN, 1).build();
        ClaimFx claim = userClaim(0, 0, TOKEN);
        low.tx().outputs.set(0, payTo(claim.recipient(), assetValue(TOKEN, claim.vaultLovelace() - 1,
                claim.quantity())));
        rejected(vault(TOKEN), low.vault());
    }

    // ---- V6

    @Test
    void v6LovelaceDivertedBelowOrAboveVaultLovelaceFails() {
        for (long delta : new long[] {-1, 1}) {
            Scenario scenario = settle(TOKEN, 2).build();
            adjustContinuation(scenario, delta, 0);
            rejected(vault(TOKEN), scenario.vault());
        }
        for (long delta : new long[] {-1, 1}) {
            Scenario scenario = settle(AssetId.ADA, 2).build();
            adjustContinuation(scenario, delta, 0);
            rejected(vault(AssetId.ADA), scenario.vault());
        }
    }

    @Test
    void v6AssetSkimmedFromTheContinuationFails() {
        Scenario scenario = settle(TOKEN, 2).build();
        adjustContinuation(scenario, 0, -1);
        rejected(vault(TOKEN), scenario.vault());
    }

    @Test
    void v6FeeTakenFromTheVaultFails() {
        // The fee is paid out of vault lovelace: the continuation is short by the fee.
        Scenario scenario = settle(TOKEN, 1).build();
        adjustContinuation(scenario, -400_000, 0);
        rejected(vault(TOKEN), scenario.vault());
    }

    @Test
    void v6ContinuationBelowTheFloorFails() {
        Payout payout = settle(AssetId.ADA, 1);
        payout.extraLovelace = -1;
        rejected(vault(AssetId.ADA), payout.build().vault());
    }

    // ---- V7

    @Test
    void v7ASecondVaultOutputFails() {
        Scenario scenario = settle(TOKEN, 1).build();
        scenario.tx().output(out(vaultAddress(), lovelace(2_000_000), continuationDatum(List.of())));
        rejected(vault(TOKEN), scenario.vault());

        // An output with the vault's payment credential but a stake part also counts.
        Scenario staked = settle(TOKEN, 1).build();
        staked.tx().output(out(GatewayFixtures.scriptAddressWithStake(VAULT_HASH, filled(0x99, 28)),
                lovelace(2_000_000), null));
        rejected(vault(TOKEN), staked.vault());
    }

    @Test
    void v7DepositShapedOrStakeSwappedContinuationFails() {
        Scenario deposit = settle(TOKEN, 1).build();
        replaceContinuation(deposit, vaultAddress(), depositDatum(GatewayFixtures.enterpriseKeyAddress(
                filled(0xcc, 28)), filled(0xcc, 28)));
        rejected(vault(TOKEN), deposit.vault());

        Scenario staked = settle(TOKEN, 1).build();
        replaceContinuation(staked, GatewayFixtures.scriptAddressWithStake(VAULT_HASH, filled(0x99, 28)),
                continuationDatum(List.of(userClaim(0, 0, TOKEN).id())));
        rejected(vault(TOKEN), staked.vault());

        Scenario ids = settle(TOKEN, 2).build();
        replaceContinuation(ids, vaultAddress(), continuationDatum(List.of(userClaim(0, 1, TOKEN).id(),
                userClaim(0, 0, TOKEN).id())));
        rejected(vault(TOKEN), ids.vault());
    }

    // ---- V8

    @Test
    void v8AnotherScriptInputFails() {
        Scenario scenario = settle(TOKEN, 1).build();
        scenario.tx().spend(new TxInInfo(ref(0x5f, 0), new TxOut(GatewayFixtures.scriptAddress(OTHER_SCRIPT),
                lovelace(5_000_000), new OutputDatum.OutputDatumInline(PlutusData.UNIT), Optional.empty())),
                PlutusData.UNIT);
        rejected(vault(TOKEN), scenario.vault());

        // The anchor UTxO spent rather than referenced (the cross-protocol case).
        Scenario anchor = settle(TOKEN, 1).build();
        anchor.tx().spend(anchorInput(anchorDatum(2, filled(0x55, 32), 120, MEMBERS, THRESHOLD, NOW)),
                PlutusData.integer(0));
        rejected(vault(TOKEN), anchor.vault());
    }

    @Test
    void v8WithdrawalsAndShardPolicyMintsFail() {
        Scenario withdrawal = settle(TOKEN, 1).build();
        withdrawal.tx().withdrawal = true;
        rejected(vault(TOKEN), withdrawal.vault());

        // A spoofed payout inside a shard bootstrap mint.
        Scenario bootstrap = settle(TOKEN, 1).build();
        bootstrap.tx().mint = token(SHARD_POLICY, new byte[] {9}, 1);
        rejected(vault(TOKEN), bootstrap.vault());
        rejected(shard(), bootstrap.shard());
    }

    // ---- V10

    @Test
    void v10SettleSpendingAnUnacceptedDepositFails() {
        Scenario scenario = settle(TOKEN, 1).build();
        Spent first = scenario.tx().inputs.get(0);
        TxOut out = first.input().resolved();
        scenario.tx().inputs.set(0, new Spent(new TxInInfo(first.input().outRef(), new TxOut(out.address(),
                out.value(), new OutputDatum.OutputDatumInline(depositDatum(
                GatewayFixtures.enterpriseKeyAddress(filled(0xcc, 28)), filled(0xcc, 28))), Optional.empty())),
                first.redeemer()));
        rejected(vault(TOKEN), scenario.vault());
    }

    @Test
    void v10AContinuationCarryingAForeignTokenIsNotSpendable() {
        Scenario scenario = settle(TOKEN, 1).build();
        Spent first = scenario.tx().inputs.get(0);
        TxOut out = first.input().resolved();
        scenario.tx().inputs.set(0, new Spent(new TxInInfo(first.input().outRef(), new TxOut(out.address(),
                out.value().merge(token(filled(0x7c, 28), new byte[] {1}, 5)), out.datum(), Optional.empty())),
                first.redeemer()));
        rejected(vault(TOKEN), scenario.vault());
    }

    // ---- V11

    @Test
    void v11TheExitModeThreadMustBeReferencedAuthenticAndNormalForSettle() {
        Scenario missing = settle(TOKEN, 1).build();
        missing.tx().references.removeIf(r -> r.outRef().equals(ref(0x64, 0)));
        rejected(vault(TOKEN), missing.vault());

        Scenario fake = settle(TOKEN, 1).build();
        fake.tx().references.removeIf(r -> r.outRef().equals(ref(0x64, 0)));
        fake.tx().reference(new TxInInfo(ref(0x64, 0), new TxOut(GatewayFixtures.scriptAddress(OTHER_SCRIPT),
                lovelace(2_000_000).merge(token(EXIT_POLICY, new byte[0], 1)),
                new OutputDatum.OutputDatumInline(normalMode()), Optional.empty())));
        rejected(vault(TOKEN), fake.vault());

        Payout exit = settle(TOKEN, 1);
        exit.mode = exitMode(filled(0x55, 32));
        rejected(vault(TOKEN), exit.build().vault());

        Payout malformed = settle(TOKEN, 1);
        malformed.mode = PlutusData.constr(0, PlutusData.integer(1));
        rejected(vault(TOKEN), malformed.build().vault());
    }

    // ---- V13

    @Test
    void v13AVaultInputWithADifferentRedeemerFails() {
        Payout payout = settle(TOKEN, 2);
        payout.floorTopUpInput = true;
        Scenario scenario = payout.build();
        Spent second = scenario.tx().inputs.get(1);
        PlutusData other = settleRedeemer(List.of(payout.claims.get(0)), 0);
        scenario.tx().inputs.set(1, new Spent(second.input(), other));
        rejected(vault(TOKEN), scenario.vault());
        rejected(vault(TOKEN), scenario.tx().spending(VAULT_REF_2));
    }

    @Test
    void v13AVaultInputAtAStakedVaultAddressFails() {
        Payout payout = settle(TOKEN, 1);
        payout.floorTopUpInput = true;
        Scenario scenario = payout.build();
        Spent second = scenario.tx().inputs.get(1);
        TxOut out = second.input().resolved();
        scenario.tx().inputs.set(1, new Spent(new TxInInfo(second.input().outRef(), new TxOut(
                GatewayFixtures.scriptAddressWithStake(VAULT_HASH, filled(0x99, 28)), out.value(), out.datum(),
                Optional.empty())), second.redeemer()));
        rejected(vault(TOKEN), scenario.vault());
    }

    // ------------------------------------------------------------------

    static void adjustContinuation(Scenario scenario, long lovelaceDelta, long quantityDelta) {
        int index = continuationIndex(scenario);
        TxOut out = scenario.tx().outputs.get(index);
        org.julclang.ledger.Value value = out.value().merge(lovelace(lovelaceDelta));
        if (quantityDelta != 0) {
            value = value.merge(token(TOKEN.policyId(), TOKEN.assetName(), quantityDelta));
        }
        scenario.tx().outputs.set(index, new TxOut(out.address(), value, out.datum(), Optional.empty()));
    }

    static void replaceContinuation(Scenario scenario, org.julclang.ledger.Address address, PlutusData datum) {
        int index = continuationIndex(scenario);
        TxOut out = scenario.tx().outputs.get(index);
        scenario.tx().outputs.set(index, new TxOut(address, out.value(), new OutputDatum.OutputDatumInline(datum),
                Optional.empty()));
    }

    static int continuationIndex(Scenario scenario) {
        List<TxOut> outputs = scenario.tx().outputs;
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i).address().equals(vaultAddress())) {
                return i;
            }
        }
        throw new IllegalStateException("no continuation");
    }

    /** Replace the shard input's thread token with one of {@code policy}, or remove it when null. */
    static void replaceShardToken(Scenario scenario, byte[] policy) {
        List<Spent> inputs = scenario.tx().inputs;
        for (int i = 0; i < inputs.size(); i++) {
            Spent spent = inputs.get(i);
            if (spent.input().outRef().equals(scenario.shardRef())) {
                TxOut out = spent.input().resolved();
                org.julclang.ledger.Value value = policy == null ? lovelace(2_000_000)
                        : lovelace(2_000_000).merge(token(policy, new byte[] {0}, 1));
                inputs.set(i, new Spent(new TxInInfo(spent.input().outRef(), new TxOut(out.address(), value,
                        out.datum(), Optional.empty())), spent.redeemer()));
            }
        }
    }
}
