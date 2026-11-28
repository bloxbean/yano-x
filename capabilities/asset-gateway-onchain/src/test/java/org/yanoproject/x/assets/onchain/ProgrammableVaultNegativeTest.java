package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.SweepCommitment;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-061 §10 P3: the negatives of V14 to V17 and of the custody's other rules. Each runs the whole transaction
 * with the pinned CIP-113 scripts and requires the intended script to fail (and, where the CIP-113 scripts would
 * accept the transaction anyway, only that script).
 */
class ProgrammableVaultNegativeTest extends ProgrammableTestBase {
    private final ProgrammableVaultCompositionTest honest = new ProgrammableVaultCompositionTest();

    // ------------------------------------------------------------------
    // V14 vault inputs and V8 script inputs

    @Test
    void aSecondVaultInputLeftOutOfTheValueEquationFails() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        tx.spend(walletInput(W2, lovelace(FLOOR), floorDatum()), null);
        failsOnlyIn(tx, "h");
    }

    @Test
    void anotherOwnersPlbInputFails() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        byte[] owner = filled(0x91, 28);
        TxInInfo foreign = new TxInInfo(ref(0x59, 0), new TxOut(smartWalletOf(new Credential.PubKeyCredential(
                new PubKeyHash(owner))), lovelace(3_000_000), new OutputDatum.NoOutputDatum(), Optional.empty()));
        tx.spend(foreign, null);
        tx.signers.add(new PubKeyHash(owner));
        tx.output(payTo(smartWallet(owner), lovelace(3_000_000)));
        failsOnlyIn(tx, "h");
    }

    @Test
    void noVaultInputFails() {
        FullTx tx = frame(normalMode());
        tx.withdrawScript(custodyHash(), 0, PlutusData.constr(3));
        tx.output(out(vaultWallet(), assetValue(2_000_000, 1), continuation(List.of())));
        failsIn(tx, "h");
    }

    // ------------------------------------------------------------------
    // V15 vault address only on the continuation

    @Test
    void aSecondOutputAtTheVaultSmartWalletFails() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        tx.output(out(vaultWallet(), lovelace(2_000_000), floorDatum()));
        failsOnlyIn(tx, "h");
    }

    @Test
    void anOutputAtTheCustodyScriptsPaymentAddressesFails() {
        for (byte[] hash : List.of(custodyHash(), exitDelegateHash())) {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.output(out(GatewayFixtures.scriptAddress(hash), lovelace(2_000_000), null));
            failsOnlyIn(tx, "h");
        }
    }

    @Test
    void aKeyPaymentAddressWithTheCustodyStakePartIsAllowed() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        Address keyWithCustodyStake = new Address(new Credential.PubKeyCredential(new PubKeyHash(filled(0x92, 28))),
                Optional.of(new StakingCredential.StakingHash(new Credential.ScriptCredential(
                        new ScriptHash(custodyHash())))));
        tx.outputs.add(tx.outputs.size() - 1, out(keyWithCustodyStake, lovelace(2_000_000), null));
        passes(tx);
    }

    // ------------------------------------------------------------------
    // V16 continuation cap

    @Test
    void aContinuationOfThirtyIdsFailsAndOneOfTwentyNineIsAccepted() {
        int saved = settleCap;
        settleCap = 64;
        try {
            assertThat(custodyRuns(settle(claims(29), 4))).as("29 ids").isTrue();
            assertThat(custodyRuns(settle(claims(30), 4))).as("30 ids").isFalse();
        } finally {
            settleCap = saved;
        }
    }

    private boolean custodyRuns(FullTx tx) {
        Map<String, EvalResult> results = tx.finish().evaluate();
        return results.get("withdraw:h").isSuccess();
    }

    private static List<ClaimFx> claims(int k) {
        List<ClaimFx> claims = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            claims.add(walletClaim(4, 100 + i));
        }
        return claims;
    }

    // ------------------------------------------------------------------
    // V17 certificates and mint

    @Test
    void aCertificateInAVaultTransactionFails() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        tx.certificates.add(PlutusData.constr(0, Cip113Blueprint.keyCredential(filled(0x93, 28)),
                PlutusData.constr(1)));
        failsIn(tx, "h");
    }

    @Test
    void aMintInAVaultTransactionFails() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        tx.mint = token(filled(0x94, 28), "x".getBytes(), 1);
        failsIn(tx, "h");
    }

    // ------------------------------------------------------------------
    // Delegation to e (D2, I46)

    @Test
    void anExitOnlyActionWithoutTheDelegateFails() {
        FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
        tx.removeWithdrawal(exitDelegateHash());
        failsOnlyIn(tx, "h");
    }

    @Test
    void aMirrorNamingAnotherCustodyScriptOrActionFails() {
        FullTx other = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
        PlutusData action = other.redeemerOf(custodyHash());
        other.withdrawScript(exitDelegateHash(), 0, PlutusData.constr(0, PlutusData.bytes(filled(0x95, 28)), action));
        failsIn(other, "h");
        failsIn(other, "e");
        FullTx otherAction = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
        otherAction.withdrawScript(exitDelegateHash(), 0, PlutusData.constr(0, PlutusData.bytes(custodyHash()),
                consolidate()));
        failsIn(otherAction, "h");
    }

    @Test
    void theDelegateWithoutTheCustodyWithdrawalFails() {
        FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
        tx.removeWithdrawal(custodyHash());
        failsIn(tx, "e");
    }

    @Test
    void theDelegateInNormalModeFailsOnItsOwnModeCheck() {
        // A correct mirror, but the exit-mode thread is NORMAL: e refuses on its own V11 check.
        FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
        tx.references.removeIf(r -> hasPolicy(r.resolved().value(), EXIT_POLICY));
        tx.reference(exitModeInput(normalMode()));
        failsIn(tx, "e");
        failsIn(tx, "h");
    }

    @Test
    void followIsNotAnAction() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        tx.withdrawScript(custodyHash(), 0, follow());
        failsOnlyIn(tx, "h");
    }

    // ------------------------------------------------------------------
    // Deposits: the total predicate, origin identity, foreign tokens

    @Test
    void acceptOfAReCreatedDepositPassesByItsOrigin() {
        // A no-op third-party re-spend moved the deposit to a new outpoint with its datum: still accepted.
        TxInInfo moved = depositInput(ref(0x5f, 3), filled(0x40, 32), 1, assetValue(2_000_000L, 500));
        passes(acceptProved(List.of(moved), false));
        passes(acceptCosigned(List.of(moved)));
    }

    @Test
    void acceptOfACopiedDatumCarryingAForeignTokenFails() {
        Value mixed = assetValue(2_000_000L, 500).merge(token(ASSET_B.policyId(), ASSET_B.assetName(), 1));
        TxInInfo copy = depositInput(W1, filled(0x40, 32), 1, mixed);
        FullTx tx = acceptCosigned(List.of(copy));
        tx.outputs.set(1, out(vaultWallet(), mixed, continuation(List.of())));
        failsIn(tx, "h");
    }

    @Test
    void depositsThatAreNotDepositShapedAreNeverAccepted() {
        for (PlutusData datum : malformedDeposits()) {
            TxInInfo input = walletInput(W1, assetValue(2_000_000L, 500), datum);
            failsOnlyIn(acceptCosigned(List.of(input)), "h");
            TxInInfo refundable = walletInput(W1, ProgrammableVaultCompositionTest.mixedDeposit().resolved().value(),
                    datum);
            StateTrie state = new StateTrie().fill("ap/account/", 32);
            PlutusData uncredited = PlutusData.constr(6, state.proof(ascii("ap/account/1")));
            failsOnlyIn(delegated(honest.refund(refundable, uncredited, state.root()), uncredited), "e");
            PlutusData absent = PlutusData.constr(5, state.absence(ascii("ap/deposit/x")));
            failsOnlyIn(delegated(honest.refund(refundable, absent, state.root()), absent), "e");
        }
    }

    @Test
    void notDepositShapedOutputsAreSweptByOutpointWithoutFailingDecoding() {
        // The predicate is total: each malformed datum is classified, never crashes, and sweeps by outpoint.
        for (PlutusData datum : malformedDeposits()) {
            TxInInfo input = walletInput(W1, assetValue(2_500_000L, 300), datum);
            SweepCommitment byOutpoint = SweepCommitment.of(ASSET,
                    org.yanoproject.x.assets.contracts.Hash32.of(W1.txId().hash()), 0, BigInteger.valueOf(2_500_000L),
                    Map.of(ASSET, BigInteger.valueOf(300)));
            passes(honest.sweep(input, byOutpoint, false));
        }
    }

    /** Constructor-0 datums that are not {@code ProgrammableDepositDatumV1}: 3 and 5 fields, wrong types, bad refund. */
    private static List<PlutusData> malformedDeposits() {
        PlutusData origin = PlutusData.constr(0, PlutusData.bytes(filled(0x40, 32)), PlutusData.integer(0),
                PlutusData.integer(1));
        PlutusData beneficiary = PlutusData.bytes(filled(0xa1, 28));
        PlutusData refund = PlutusData.bytes(smartWallet(REFUND_KEY));
        PlutusData key = PlutusData.bytes(REFUND_KEY);
        byte[] scriptOwned = smartWallet(REFUND_KEY);
        scriptOwned[0] = 0x30;
        byte[] otherPlb = smartWallet(REFUND_KEY);
        otherPlb[1] ^= 1;
        return List.of(
                depositDatum(smartWallet(REFUND_KEY), REFUND_KEY),
                PlutusData.constr(0, beneficiary, refund, key, origin, PlutusData.integer(0)),
                PlutusData.constr(0, PlutusData.integer(1), refund, key, origin),
                PlutusData.constr(0, beneficiary, refund, key, PlutusData.constr(0, PlutusData.bytes(filled(0x40, 31)),
                        PlutusData.integer(0), PlutusData.integer(1))),
                PlutusData.constr(0, beneficiary, refund, key, PlutusData.constr(0, PlutusData.bytes(filled(0x40, 32)),
                        PlutusData.integer(-1), PlutusData.integer(1))),
                PlutusData.constr(0, beneficiary, refund, key, PlutusData.constr(0, PlutusData.bytes(filled(0x40, 32)),
                        PlutusData.integer(BigInteger.TWO.pow(64)), PlutusData.integer(1))),
                PlutusData.constr(0, beneficiary, refund, key, PlutusData.list(PlutusData.bytes(filled(0x40, 32)))),
                PlutusData.constr(0, beneficiary, PlutusData.bytes(GatewayFixtures.enterpriseKeyAddress(REFUND_KEY)),
                        key, origin),
                PlutusData.constr(0, beneficiary, PlutusData.bytes(scriptOwned), key, origin),
                PlutusData.constr(0, beneficiary, PlutusData.bytes(otherPlb), key, origin),
                PlutusData.constr(1, beneficiary, refund, key, origin),
                PlutusData.map(),
                PlutusData.bytes(filled(0x01, 4)));
    }

    @Test
    void aDepositShapedSweepMustMatchItsCommitment() {
        TxInInfo input = depositInput(W1, filled(0x45, 32), 2, assetValue(2_500_000L, 300));
        // The committed lovelace above the input's: fails.
        FullTx above = honest.sweep(input, new SweepCommitment(depositIdOf(input).bytes(),
                AssetValue.single(ASSET, BigInteger.valueOf(300)), BigInteger.valueOf(2_600_000L)), true);
        failsOnlyIn(above, "h");
        // Native assets that differ from the commitment: fails.
        FullTx other = honest.sweep(input, new SweepCommitment(depositIdOf(input).bytes(),
                AssetValue.single(ASSET, BigInteger.valueOf(299)), BigInteger.valueOf(2_000_000L)), true);
        failsOnlyIn(other, "h");
        // Keyed by its outpoint instead of its DepositId: fails.
        SweepCommitment byOutpoint = SweepCommitment.of(ASSET,
                org.yanoproject.x.assets.contracts.Hash32.of(W1.txId().hash()), 0, BigInteger.valueOf(2_500_000L),
                Map.of(ASSET, BigInteger.valueOf(300)));
        failsOnlyIn(honest.sweep(input, byOutpoint, false), "h");
        // After a no-op re-spend to a new outpoint: the DepositId-keyed reservation still pays.
        TxInInfo moved = depositInput(ref(0x5e, 1), filled(0x45, 32), 2, assetValue(2_500_000L, 300));
        passes(honest.sweep(moved, new SweepCommitment(depositIdOf(moved).bytes(),
                AssetValue.single(ASSET, BigInteger.valueOf(300)), BigInteger.valueOf(2_000_000L)), true));
    }

    @Test
    void refundOfACreditedOriginFails() {
        TxInInfo deposit = ProgrammableVaultCompositionTest.mixedDeposit();
        StateTrie state = new StateTrie().fill("ap/account/", 32).put(depositKey(deposit),
                creditedRecord(deposit).encode());
        PlutusData uncredited = PlutusData.constr(6, state.proof(depositKey(deposit)));
        failsOnlyIn(delegated(honest.refund(deposit, uncredited, state.root()), uncredited), "e");
        PlutusData absent = PlutusData.constr(5, state.absenceOfPresentKey(depositKey(deposit), 3));
        failsOnlyIn(delegated(honest.refund(deposit, absent, state.root()), absent), "e");
        StateTrie uncreditedState = new StateTrie().fill("ap/account/", 32).put(depositKey(deposit),
                record(deposit, DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.MIXED_ASSETS).encode());
        PlutusData wrongRefund = PlutusData.constr(6, uncreditedState.proof(depositKey(deposit)));
        FullTx stolen = delegated(honest.refund(deposit, wrongRefund, uncreditedState.root()), wrongRefund);
        stolen.outputs.set(0, out(ledger(smartWallet(filled(0x96, 28))), deposit.resolved().value(), null));
        failsOnlyIn(stolen, "e");
    }

    // ------------------------------------------------------------------
    // Recipients (D7, I37)

    @Test
    void tokenPayoutsOnlyGoToKeyOwnedSmartWallets() {
        byte[] key = filled(0x97, 28);
        byte[] scriptOwned = smartWallet(key);
        scriptOwned[0] = 0x30;
        byte[] pointer = GatewayFixtures.concat(GatewayFixtures.concat(new byte[] {0x50}, PLB), new byte[] {1, 2, 3});
        byte[] enterprise = GatewayFixtures.concat(new byte[] {0x70}, PLB);
        for (byte[] recipient : List.of(scriptOwned, pointer, enterprise, GatewayFixtures.enterpriseKeyAddress(key),
                GatewayFixtures.baseKeyAddress(key, key))) {
            ClaimFx claim = walletClaim(4, 1).withRecipient(recipient);
            failsIn(settle(List.of(claim), 4), "h");
        }
    }

    @Test
    void aLovelaceOnlyClaimMayPayAnOrdinaryKeyAddress() {
        ClaimFx claim = walletClaim(4, 1).withQuantity(0).withRecipient(GatewayFixtures.enterpriseKeyAddress(
                filled(0x98, 28)));
        passes(settle(List.of(claim), 4));
    }

    // ------------------------------------------------------------------
    // The shard variant, publish and spend

    @Test
    void theShardVariantWithoutTheCustodyWithdrawalFails() {
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        tx.removeWithdrawal(custodyHash());
        failsIn(tx, "shard");
    }

    @Test
    void publishAcceptsOnlyRegistration() {
        for (byte[] hash : List.of(custodyHash(), exitDelegateHash())) {
            PlutusData credential = Cip113Blueprint.scriptCredential(hash);
            PlutusData pool = PlutusData.constr(0, PlutusData.bytes(filled(0x99, 28)));
            for (PlutusData cert : List.of(
                    PlutusData.constr(1, credential, PlutusData.constr(1)),
                    PlutusData.constr(2, credential, pool),
                    PlutusData.constr(3, credential, pool, PlutusData.integer(2_000_000)))) {
                FullTx tx = frame(normalMode());
                tx.certificates.add(cert);
                Map<String, EvalResult> results = tx.evaluate();
                List<EvalResult> publish = results.entrySet().stream().filter(e -> e.getKey().startsWith("publish:"))
                        .map(Map.Entry::getValue).toList();
                assertThat(publish).as("certificate %s", cert).hasSize(1).allMatch(r -> !r.isSuccess());
            }
        }
    }

    @Test
    void spendingTheCustodyScriptsOwnAddressAlwaysFails() {
        for (String name : List.of("h", "e")) {
            byte[] hash = name.equals("h") ? custodyHash() : exitDelegateHash();
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.spend(new TxInInfo(ref(0x58, 0), new TxOut(GatewayFixtures.scriptAddress(hash), lovelace(5_000_000),
                    new OutputDatum.OutputDatumInline(PlutusData.constr(0)), Optional.empty())), PlutusData.constr(0));
            Map<String, EvalResult> results = tx.finish().evaluate();
            assertThat(results.entrySet()).anyMatch(e -> e.getKey().startsWith("spend:" + name + "#")
                    && !e.getValue().isSuccess());
        }
    }

    @Test
    void anIssuerFreezeOfTheVaultBlocksItsPayouts() {
        // The hook refuses the vault's own smart wallet: the vault is frozen (D11); the custody script still passes.
        FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
        Program frozen = hook(1, custodyHash());
        tx.references.removeIf(r -> nodeKeys(r).isPresent());
        registry(frozen, hookB()).forEach(tx::reference);
        tx.script(Cip113Blueprint.hash(frozen), frozen);
        tx.removeWithdrawal(Cip113Blueprint.hash(hookA()));
        tx.withdrawScript(Cip113Blueprint.hash(frozen), 0, PlutusData.constr(0));
        failsOnlyIn(tx, "hook-" + hex(Cip113Blueprint.hash(frozen)).substring(0, 6));
    }
}
