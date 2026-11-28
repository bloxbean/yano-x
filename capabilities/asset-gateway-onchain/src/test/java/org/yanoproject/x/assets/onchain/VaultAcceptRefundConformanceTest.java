package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADR-059 D8f: co-signed and proved {@code Accept}, and {@code Refund} in exit mode. {@code DepositId} is computed on
 * chain from each spent outpoint; a credited deposit can never be refunded and an uncredited one never accepted.
 */
class VaultAcceptRefundConformanceTest extends GatewayTestBase {
    private static final byte[] REFUND_KEY = filled(0xc1, 28);
    private static final byte[] REFUND_ADDRESS = GatewayFixtures.enterpriseKeyAddress(REFUND_KEY);

    private static TxOutRef depositRef(int i) {
        return ref(0x70 + i, i);
    }

    private static Value depositValue(AssetId asset, int i) {
        return asset.isAda() ? lovelace(10_000_000L + i) : assetValue(asset, 2_000_000, 500 + i);
    }

    private static Hash32 vaultIdOf(AssetId asset) {
        return asset.isAda() ? ADA_VAULT : TOKEN_VAULT;
    }

    private static byte[] depositKey(AssetId asset, int i) {
        Hash32 depositId = GatewayIds.depositId(vaultIdOf(asset), Hash32.of(filled(0x70 + i, 32)), i);
        return GatewayFixtures.concat(DEPOSIT_PREFIX, depositId.bytes());
    }

    /** A state trie with every deposit recorded CREDITED (or, for {@code uncredited}, that one UNCREDITED). */
    private static StateTrie depositState(AssetId asset, int count, int uncredited, int missing) {
        StateTrie state = new StateTrie().fill("ap/account/", 64);
        Hash32 vault = vaultIdOf(asset);
        for (int i = 0; i < count; i++) {
            if (i == missing) {
                continue;
            }
            Hash32 tx = Hash32.of(filled(0x70 + i, 32));
            byte[] record = i == uncredited
                    ? GatewayFixtures.uncreditedRecord(vault, tx, i, DepositAdmission.Reason.DEPOSITS_PAUSED).encode()
                    : GatewayFixtures.creditedRecord(vault, tx, i, 500 + i).encode();
            state.put(depositKey(asset, i), record);
        }
        return state;
    }

    /** {@code count} deposits merged into one continuation. */
    private static Tx accept(AssetId asset, int count, PlutusData redeemer, PlutusData mode, PlutusData anchor) {
        Tx tx = new Tx();
        Value total = lovelace(0);
        for (int i = 0; i < count; i++) {
            tx.spend(vaultInput(depositRef(i), depositValue(asset, i), depositDatum(REFUND_ADDRESS, REFUND_KEY)),
                    redeemer);
            total = total.merge(depositValue(asset, i));
        }
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.output(out(vaultAddress(), total, continuationDatum(List.of())));
        tx.output(payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28)), lovelace(4_600_000L)));
        if (anchor != null) {
            tx.reference(anchorInput(anchor));
        }
        tx.reference(exitModeInput(mode));
        return tx;
    }

    private static PlutusData acceptCosigned() {
        return PlutusData.constr(3);
    }

    private static PlutusData acceptProved(StateTrie state, AssetId asset, int count) {
        List<PlutusData> proofs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            proofs.add(state.proof(depositKey(asset, i)));
        }
        return PlutusData.constr(4, PlutusData.list(proofs.toArray(new PlutusData[0])));
    }

    private static PlutusData liveAnchor(byte[] root) {
        return anchorDatum(2, root, 120, MEMBERS, THRESHOLD, NOW - HOUR);
    }

    // ---- Accept, co-signed

    @Test
    void cosignedAcceptMergesDepositsIntoOneExactContinuation() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            for (int count : new int[] {1, 3}) {
                Tx tx = accept(asset, count, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                        .signedByMembers(THRESHOLD);
                ok(vault(asset), tx.spending(depositRef(0)));
                if (count > 1) {
                    ok(vault(asset), tx.spending(depositRef(1)));
                }
            }
        }
    }

    @Test
    void cosignedAcceptBelowThresholdOrInExitModeFails() {
        rejected(vault(TOKEN), accept(TOKEN, 2, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                .signedByMembers(1).spending(depositRef(0)));
        rejected(vault(TOKEN), accept(TOKEN, 2, acceptCosigned(), exitMode(filled(0x55, 32)),
                liveAnchor(filled(0x55, 32))).signedByMembers(3).spending(depositRef(0)));
    }

    @Test
    void acceptKeepsTheValueExactAndTheContinuationEmpty() {
        Tx short_ = accept(TOKEN, 2, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                .signedByMembers(THRESHOLD);
        TxOut continuation = short_.outputs.get(0);
        short_.outputs.set(0, out(vaultAddress(), continuation.value().merge(lovelace(-1)),
                continuationDatum(List.of())));
        rejected(vault(TOKEN), short_.spending(depositRef(0)));

        Tx ids = accept(TOKEN, 1, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                .signedByMembers(THRESHOLD);
        ids.outputs.set(0, out(vaultAddress(), ids.outputs.get(0).value(),
                continuationDatum(List.of(shardId(0, 1)))));
        rejected(vault(TOKEN), ids.spending(depositRef(0)));

        Tx deposit = accept(TOKEN, 1, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                .signedByMembers(THRESHOLD);
        deposit.outputs.set(0, out(vaultAddress(), deposit.outputs.get(0).value(),
                depositDatum(REFUND_ADDRESS, REFUND_KEY)));
        rejected(vault(TOKEN), deposit.spending(depositRef(0)));
    }

    @Test
    void acceptTakesOnlyCleanDepositShapedInputsAndNoOtherScript() {
        // An accepted continuation is not a deposit.
        Tx continuation = accept(TOKEN, 1, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                .signedByMembers(THRESHOLD);
        continuation.spend(vaultInput(ref(0x61, 0), assetValue(TOKEN, 3_000_000, 10),
                continuationDatum(List.of())), acceptCosigned());
        continuation.outputs.set(0, out(vaultAddress(), continuation.outputs.get(0).value()
                .merge(assetValue(TOKEN, 3_000_000, 10)), continuationDatum(List.of())));
        // The continuation sorts first, so it is the input that runs the full check.
        rejected(vault(TOKEN), continuation.spending(ref(0x61, 0)));

        // A deposit with a foreign token stays unattributed.
        Tx foreign = new Tx();
        Value junk = depositValue(TOKEN, 0).merge(token(filled(0x7c, 28), new byte[] {1}, 5));
        foreign.spend(vaultInput(depositRef(0), junk, depositDatum(REFUND_ADDRESS, REFUND_KEY)), acceptCosigned())
                .spend(keyInput(FEE_REF, 5_000_000L), null)
                .output(out(vaultAddress(), junk, continuationDatum(List.of())))
                .reference(anchorInput(liveAnchor(filled(0x55, 32)))).reference(exitModeInput(normalMode()))
                .signedByMembers(THRESHOLD);
        rejected(vault(TOKEN), foreign.spending(depositRef(0)));

        // Another script input rides along.
        Tx other = accept(TOKEN, 1, acceptCosigned(), normalMode(), liveAnchor(filled(0x55, 32)))
                .signedByMembers(THRESHOLD);
        other.spend(new TxInInfo(ref(0x5f, 0), new TxOut(GatewayFixtures.scriptAddress(OTHER_SCRIPT),
                lovelace(5_000_000), new OutputDatum.OutputDatumInline(PlutusData.UNIT), Optional.empty())),
                PlutusData.UNIT);
        rejected(vault(TOKEN), other.spending(depositRef(0)));
    }

    // ---- Accept, proved

    @Test
    void provedAcceptWorksAgainstTheAnchorRootAndTheFrozenRoot() {
        for (AssetId asset : List.of(TOKEN, AssetId.ADA)) {
            StateTrie state = depositState(asset, 3, -1, -1);
            ok(vault(asset), accept(asset, 3, acceptProved(state, asset, 3), normalMode(), liveAnchor(state.root()))
                    .spending(depositRef(0)));
            ok(vault(asset), accept(asset, 3, acceptProved(state, asset, 3), exitMode(state.root()), null)
                    .spending(depositRef(0)));
        }
    }

    @Test
    void provedAcceptOfAnUncreditedDepositFails() {
        StateTrie state = depositState(TOKEN, 2, 1, -1);
        rejected(vault(TOKEN), accept(TOKEN, 2, acceptProved(state, TOKEN, 2), normalMode(),
                liveAnchor(state.root())).spending(depositRef(0)));
    }

    @Test
    void provedAcceptBindsEachProofToItsOwnOutpoint() {
        StateTrie state = depositState(TOKEN, 2, -1, -1);
        List<PlutusData> swapped = List.of(state.proof(depositKey(TOKEN, 1)), state.proof(depositKey(TOKEN, 0)));
        rejected(vault(TOKEN), accept(TOKEN, 2, PlutusData.constr(4, PlutusData.list(swapped.toArray(
                new PlutusData[0]))), normalMode(), liveAnchor(state.root())).spending(depositRef(0)));
        List<PlutusData> missing = List.of(state.proof(depositKey(TOKEN, 0)));
        rejected(vault(TOKEN), accept(TOKEN, 2, PlutusData.constr(4, PlutusData.list(missing.toArray(
                new PlutusData[0]))), normalMode(), liveAnchor(state.root())).spending(depositRef(0)));
        // A record of the same outpoint under another vault id.
        StateTrie otherVault = depositState(AssetId.ADA, 1, -1, -1);
        rejected(vault(TOKEN), accept(TOKEN, 1, PlutusData.constr(4, PlutusData.list(otherVault.proof(
                depositKey(AssetId.ADA, 0)))), normalMode(), liveAnchor(otherVault.root())).spending(depositRef(0)));
    }

    // ---- Refund

    private static Tx refund(AssetId asset, PlutusData redeemer, PlutusData mode) {
        Tx tx = new Tx();
        tx.spend(vaultInput(depositRef(0), depositValue(asset, 0), depositDatum(REFUND_ADDRESS, REFUND_KEY)),
                redeemer);
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.output(payTo(REFUND_ADDRESS, depositValue(asset, 0)));
        tx.output(payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28)), lovelace(4_600_000L)));
        tx.reference(exitModeInput(mode));
        tx.signer(new org.julclang.ledger.PubKeyHash(REFUND_KEY));
        return tx;
    }

    @Test
    void refundOfAnAbsentOrUncreditedDepositInExitMode() {
        StateTrie absent = depositState(TOKEN, 1, -1, 0);
        ok(vault(TOKEN), refund(TOKEN, PlutusData.constr(5, absent.absence(depositKey(TOKEN, 0))),
                exitMode(absent.root())).spending(depositRef(0)));
        StateTrie uncredited = depositState(TOKEN, 1, 0, -1);
        ok(vault(TOKEN), refund(TOKEN, PlutusData.constr(6, uncredited.proof(depositKey(TOKEN, 0))),
                exitMode(uncredited.root())).spending(depositRef(0)));
        StateTrie ada = depositState(AssetId.ADA, 1, -1, 0);
        ok(vault(AssetId.ADA), refund(AssetId.ADA, PlutusData.constr(5, ada.absence(depositKey(AssetId.ADA, 0))),
                exitMode(ada.root())).spending(depositRef(0)));
    }

    @Test
    void anUncreditedMixedAssetDepositIsRefundedWhole() {
        StateTrie state = new StateTrie().fill("ap/account/", 64).put(depositKey(TOKEN, 0),
                GatewayFixtures.uncreditedRecord(TOKEN_VAULT, Hash32.of(filled(0x70, 32)), 0,
                        DepositAdmission.Reason.MIXED_ASSETS).encode());
        Value junk = depositValue(TOKEN, 0).merge(token(filled(0x7c, 28), new byte[] {1}, 5));
        Tx tx = new Tx();
        tx.spend(vaultInput(depositRef(0), junk, depositDatum(REFUND_ADDRESS, REFUND_KEY)),
                PlutusData.constr(6, state.proof(depositKey(TOKEN, 0))));
        tx.spend(keyInput(FEE_REF, 5_000_000L), null);
        tx.output(payTo(REFUND_ADDRESS, junk));
        tx.reference(exitModeInput(exitMode(state.root())));
        tx.signer(new org.julclang.ledger.PubKeyHash(REFUND_KEY));
        ok(vault(TOKEN), tx.spending(depositRef(0)));
        // Without its foreign token the refund is short.
        tx.outputs.set(0, payTo(REFUND_ADDRESS, depositValue(TOKEN, 0)));
        rejected(vault(TOKEN), tx.spending(depositRef(0)));
    }

    @Test
    void refundOfACreditedDepositFails() {
        StateTrie credited = depositState(TOKEN, 1, -1, -1);
        rejected(vault(TOKEN), refund(TOKEN, PlutusData.constr(6, credited.proof(depositKey(TOKEN, 0))),
                exitMode(credited.root())).spending(depositRef(0)));
        // An absence proof from a trie without the record, against the frozen root that has it.
        StateTrie without = depositState(TOKEN, 1, -1, 0);
        rejected(vault(TOKEN), refund(TOKEN, PlutusData.constr(5, without.absence(depositKey(TOKEN, 0))),
                exitMode(credited.root())).spending(depositRef(0)));
    }

    /**
     * P0-1: a credited deposit's own leaf, named through a conflicting key hash that differs from the deposit key's
     * hash in one nibble above the leaf, does not prove the deposit absent, wherever the flipped nibble is.
     */
    @Test
    void refundAbsentWithAnAbsenceProofOfACreditedDepositFails() {
        StateTrie credited = depositState(TOKEN, 1, -1, -1);
        byte[] key = depositKey(TOKEN, 0);
        int cursor = (int) leafCursor(credited.inclusion(key).leafSuffix());
        for (int flip : new int[] {0, cursor / 2, cursor - 1}) {
            rejected(vault(TOKEN), refund(TOKEN, PlutusData.constr(5, credited.absenceOfPresentKey(key, flip)),
                    exitMode(credited.root())).spending(depositRef(0)));
        }
    }

    /**
     * P1-2: a deposit whose key leaves a branch's compressed prefix in the frozen trie is provably absent, so it is
     * refundable after the freeze like any other uncredited deposit.
     */
    @Test
    void aDepositWhosePathLeavesABranchPrefixIsRefundable() {
        StateTrie frozen = branchPrefixTrie(depositKey(TOKEN, 0));
        ok(vault(TOKEN), refund(TOKEN, PlutusData.constr(5, frozen.absence(depositKey(TOKEN, 0))),
                exitMode(frozen.root())).spending(depositRef(0)));
    }

    @Test
    void refundOnlyInExitModeWithTheRefundKeyToTheRefundAddressForTheExactValue() {
        StateTrie absent = depositState(TOKEN, 1, -1, 0);
        PlutusData redeemer = PlutusData.constr(5, absent.absence(depositKey(TOKEN, 0)));
        rejected(vault(TOKEN), refund(TOKEN, redeemer, normalMode()).spending(depositRef(0)));

        Tx unsigned = refund(TOKEN, redeemer, exitMode(absent.root()));
        unsigned.signers.clear();
        rejected(vault(TOKEN), unsigned.spending(depositRef(0)));

        Tx elsewhere = refund(TOKEN, redeemer, exitMode(absent.root()));
        elsewhere.outputs.set(0, payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xbb, 28)),
                depositValue(TOKEN, 0)));
        rejected(vault(TOKEN), elsewhere.spending(depositRef(0)));

        Tx short_ = refund(TOKEN, redeemer, exitMode(absent.root()));
        short_.outputs.set(0, payTo(REFUND_ADDRESS, depositValue(TOKEN, 0).merge(lovelace(-1))));
        rejected(vault(TOKEN), short_.spending(depositRef(0)));

        // The absence proof must be for this outpoint's DepositId.
        Tx other = refund(TOKEN, PlutusData.constr(5, absent.absence(depositKey(TOKEN, 1))),
                exitMode(absent.root()));
        rejected(vault(TOKEN), other.spending(depositRef(0)));
    }

    @Test
    void refundIsTheTransactionsOnlyVaultSpend() {
        StateTrie absent = depositState(TOKEN, 1, -1, 0);
        PlutusData redeemer = PlutusData.constr(5, absent.absence(depositKey(TOKEN, 0)));
        Tx two = refund(TOKEN, redeemer, exitMode(absent.root()));
        two.spend(vaultInput(depositRef(1), depositValue(TOKEN, 1), depositDatum(REFUND_ADDRESS, REFUND_KEY)),
                redeemer);
        rejected(vault(TOKEN), two.spending(depositRef(0)));

        Tx continuation = refund(TOKEN, redeemer, exitMode(absent.root()));
        continuation.output(out(vaultAddress(), lovelace(2_000_000), continuationDatum(List.of())));
        rejected(vault(TOKEN), continuation.spending(depositRef(0)));
    }
}
