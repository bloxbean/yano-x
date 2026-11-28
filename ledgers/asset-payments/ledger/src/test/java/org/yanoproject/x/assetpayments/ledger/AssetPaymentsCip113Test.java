package org.yanoproject.x.assetpayments.ledger;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.EXPIRY;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.OWNER;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.OBSERVER;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.T0;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.USDM_VAULT;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.envelope;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.filled;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.intent;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.owner;

/**
 * ADR-061 in the payment ledger: a CIP-113 vault in the registry with its custody pins and the
 * {@code ISSUER_CONTROLLED} label, wiring version 0 at genesis, wiring observations, origin-bound deposit credit and
 * {@code ISSUER_HOLD}.
 */
class AssetPaymentsCip113Test {
    static final String WIRING = "cip113-wiring";
    static final byte[] PLB = filled(28, 0x3a);
    static final byte[] D = filled(28, 0xd0);
    static final byte[] ISSUANCE = filled(28, 0xd1);
    static final byte[] T = filled(28, 0xd2);
    static final byte[] TP = filled(28, 0xd3);
    static final String PARAMS0 = "a0".repeat(32) + "#0";
    static final byte[] BLOCK = filled(32, 0xbb);

    final TestWallet alice = TestWallet.of(1);
    int nonce;

    static Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>(PaymentsHarness.settings());
        String c = "machines.asset-payments.vaults.1.";
        settings.put(c + "custody", "cip113");
        settings.put(c + "cip113.bootstrap-tx", "8e".repeat(32));
        settings.put(c + "cip113.params-policy", "1d".repeat(28));
        settings.put(c + "cip113.plb-hash", HexFormat.of().formatHex(PLB));
        settings.put(c + "cip113.registry-node-policy", "e5".repeat(28));
        settings.put(c + "cip113.exit-delegate-hash", "e0".repeat(28));
        settings.put(c + "cip113.substandard", "yano-x-test");
        settings.put(c + "cip113.params-outpoint", PARAMS0);
        settings.put(c + "cip113.dispatcher", HexFormat.of().formatHex(D));
        settings.put(c + "cip113.issuance-logic", HexFormat.of().formatHex(ISSUANCE));
        settings.put(c + "cip113.transfer", HexFormat.of().formatHex(T));
        settings.put(c + "cip113.third-party", HexFormat.of().formatHex(TP));
        settings.put(c + "cip113.transfer-delegate", HexFormat.of().formatHex(T));
        settings.put(c + "cip113.third-party-delegate", HexFormat.of().formatHex(TP));
        settings.put(c + "cip113.baseline-transfer-logic", "ab".repeat(28));
        settings.put("machines.asset-payments.cip113.wiring-observer-id", WIRING);
        settings.put("observers." + WIRING + ".type", "asset-cip113-wiring-v1");
        for (String key : List.of("domain.address-network-id", "domain.network-magic", "domain.chain-id",
                "domain.genesis-id")) {
            settings.put("observers." + WIRING + "." + key, settings.get("observers." + OBSERVER + "." + key));
        }
        return settings;
    }

    @Test
    void theRegistryPinsTheCustodyAndLabelsTheAsset() {
        AssetPaymentsSettings parsed = AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, settings());
        PaymentsRecords.RegistryEntryV1 entry = parsed.vaults().get(1);
        assertThat(entry.custody()).isPresent();
        assertThat(entry.trustLabels()).contains(PaymentsRecords.TrustLabel.ISSUER_CONTROLLED);
        assertThat(PaymentsRecords.RegistryEntryV1.decode(entry.encode())).isEqualTo(entry);
        // A native vault's entry keeps its ten-element bytes and carries only the domain label.
        PaymentsRecords.RegistryEntryV1 nativeEntry = parsed.vaults().get(0);
        assertThat(nativeEntry.custody()).isEmpty();
        assertThat(nativeEntry.trustLabels()).containsExactly(
                PaymentsRecords.TrustLabel.FEDERATED_COSIGNED_WITH_ESCAPE_HATCH);
        assertThat(nativeEntry.encode()[0] & 0xff).isEqualTo(0x8a);
        assertThat(PaymentsRecords.AssetStatus.ISSUER_HOLD.depositsEnabled()).isFalse();
        assertThat(PaymentsRecords.AssetStatus.ISSUER_HOLD.transfersEnabled()).isFalse();
        assertThatThrownBy(() -> nativeEntry.withStatus(PaymentsRecords.AssetStatus.ISSUER_HOLD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entry.withStatus(PaymentsRecords.AssetStatus.ISSUER_HOLD).status().held()).isTrue();
    }

    @Test
    void theWiringObserverMustSortBeforeTheDepositObserver() {
        Map<String, String> settings = settings();
        settings.put("machines.asset-payments.cip113.wiring-observer-id", "zz-wiring");
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, settings))
                .hasMessageContaining("I48");
        Map<String, String> missing = settings();
        missing.remove("machines.asset-payments.cip113.wiring-observer-id");
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, missing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOriginBoundTransferDepositIsCreditedOnceAndAReSpendIsNot() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        ledger.block(T0, register(alice));
        ProgrammableDepositDatumV1 datum = datum(0x11);
        ledger.block(T0 + 1_000, deposit(0x21, datum, List.of(outpoint(0x11)), 0L, List.of(outpoint(0xa0)), 100));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
        // A no-op third-party re-spend of the deposit in a later block: never credited again.
        ledger.block(T0 + 2_000, deposit(0x22, datum, List.of(outpoint(0x21)), 1L, List.of(outpoint(0xa0)), 100));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
        assertThat(ledger.depositRecord(datum.depositId(USDM_VAULT)).orElseThrow().status())
                .isEqualTo(DepositRecordV1.Status.CREDITED);
        ledger.checkInvariants();
    }

    @Test
    void aDepositAgainstAnUnapprovedWiringVersionIsUncredited() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        ledger.block(T0, register(alice));
        Cip113WiringClaimV1.Fields moved = new Cip113WiringClaimV1.Fields(D, filled(28, 0xe1), T, TP);
        ProgrammableDepositDatumV1 datum = datum(0x12);
        // The wiring observation sorts before the deposit in the same block (I48).
        ledger.block(T0 + 1_000, wiring(0xa1, moved),
                deposit(0x23, datum, List.of(outpoint(0x12)), 0L, List.of(outpoint(0xa1)), 50));
        assertThat(ledger.balance(USDM_VAULT, alice)).isZero();
        assertThat(ledger.depositRecord(datum.depositId(USDM_VAULT)).orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.WIRING_UNPINNED);
    }

    @Test
    void anAuthorityOnlyRolloverKeepsCrediting() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        ledger.block(T0, register(alice));
        ledger.block(T0 + 1_000, wiring(0xa2, new Cip113WiringClaimV1.Fields(D, ISSUANCE, T, TP)),
                deposit(0x24, datum(0x13), List.of(outpoint(0x13)), 0L, List.of(outpoint(0xa2)), 70));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(70));
    }

    // ------------------------------------------------------------------

    PaymentsHarness.Message register(TestWallet wallet) {
        return intent(owner(wallet, envelope(wallet.accountId(), WalletAuthorizer.REGISTER_ACCOUNT, OWNER, ++nonce,
                EXPIRY, PaymentsBodies.REGISTER_ACCOUNT)));
    }

    ProgrammableDepositDatumV1 datum(int originFill) {
        byte[] refund = new byte[57];
        refund[0] = 0x10;
        System.arraycopy(PLB, 0, refund, 1, 28);
        System.arraycopy(alice.keyHash(), 0, refund, 29, 28);
        return new ProgrammableDepositDatumV1(alice.accountId(), refund, alice.keyHash(),
                Hash32.of(filled(32, originFill)), 0, 0);
    }

    static ProgrammableDepositDatumV1.Outpoint outpoint(int fill) {
        return new ProgrammableDepositDatumV1.Outpoint(Hash32.of(filled(32, fill)), 0);
    }

    /** A deposit claim of transaction {@code fill}: a transfer when {@code ctor} is 0, a third-party action at 1. */
    static PaymentsHarness.Message deposit(int fill, ProgrammableDepositDatumV1 datum, List<ProgrammableDepositDatumV1.Outpoint> inputs,
                              long ctor, List<ProgrammableDepositDatumV1.Outpoint> references, long quantity) {
        List<ProgrammableDepositClaimV1.Withdrawal> withdrawals = ctor == 0
                ? List.of(new ProgrammableDepositClaimV1.Withdrawal(D, Optional.of(0L)),
                new ProgrammableDepositClaimV1.Withdrawal(T, Optional.of(0L)))
                : List.of(new ProgrammableDepositClaimV1.Withdrawal(D, Optional.of(1L)),
                new ProgrammableDepositClaimV1.Withdrawal(TP, Optional.of(0L)));
        Hash32 tx = Hash32.of(filled(32, fill));
        byte[] claim = new ProgrammableDepositClaimV1(USDM_VAULT, tx, 0, 1_000 + fill, Hash32.of(BLOCK),
                ProgrammableDepositClaimV1.Shape.DEPOSIT, Optional.of(new ProgrammableDepositClaimV1.Deposit(datum,
                datum.originSpent(inputs, 0))), BigInteger.valueOf(quantity), BigInteger.valueOf(2_000_000), 0,
                Hash32.of(filled(32, 0x5e)), Optional.of(withdrawals), false, Optional.of(references)).encode();
        return PaymentsHarness.deposit(new L1Observation(OBSERVER, new L1Observation.TransactionAnchor(tx.bytes()), 0,
                1_000 + fill, BLOCK, claim));
    }

    static PaymentsHarness.Message wiring(int fill, Cip113WiringClaimV1.Fields fields) {
        Hash32 tx = Hash32.of(filled(32, fill));
        byte[] claim = new Cip113WiringClaimV1(tx, 0, 900, 1, Hash32.of(BLOCK), Optional.of(fields)).encode();
        return new PaymentsHarness.Message("~l1/" + WIRING, new L1Observation(WIRING, new L1Observation.TransactionAnchor(
                tx.bytes()), 0, 900, BLOCK, claim).encode());
    }
}
