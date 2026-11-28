package org.yanoproject.x.assetpayments.ledger;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
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
        // The deposit observer pins the same custody (ADR-061 D3, r5).
        String o = "observers." + OBSERVER + ".vaults.1.";
        settings.put(o + "custody", "cip113");
        settings.put(o + "cip113.plb-hash", HexFormat.of().formatHex(PLB));
        settings.put(o + "cip113.exit-delegate-hash", "e0".repeat(28));
        settings.put(o + "cip113.params-policy", "1d".repeat(28));
        settings.put("observers." + WIRING + ".type", "asset-cip113-wiring-v1");
        settings.put("observers." + WIRING + ".params-policy", "1d".repeat(28));
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

    @Test
    void anAssetHoldStopsCreditAndTheRegistryQueryShowsTheLabel() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        ledger.block(T0, register(alice));
        AssetAccounts.Store store = new AssetAccounts.Store() {
            @Override
            public Optional<byte[]> get(byte[] key) {
                return ledger.get(key);
            }

            @Override
            public void put(byte[] key, byte[] value) {
                ledger.state.put(HexFormat.of().formatHex(key), value.clone());
            }

            @Override
            public void delete(byte[] key) {
                ledger.state.remove(HexFormat.of().formatHex(key));
            }
        };
        assertThat(Cip113Governance.hold(store, PaymentsHarness.ADA_VAULT))
                .isEqualTo(Cip113Governance.Result.NOT_CIP113);
        assertThat(Cip113Governance.hold(store, USDM_VAULT)).isEqualTo(Cip113Governance.Result.APPLIED);
        PaymentsRecords.RegistryEntryV1 held = PaymentsRecords.RegistryEntryV1.decode(ledger.query(
                org.yanoproject.x.assets.contracts.SettlementQueries.REGISTRY, USDM_VAULT.bytes()));
        assertThat(held.status()).isEqualTo(PaymentsRecords.AssetStatus.ISSUER_HOLD);
        assertThat(held.trustLabels()).contains(PaymentsRecords.TrustLabel.ISSUER_CONTROLLED);
        ProgrammableDepositDatumV1 datum = datum(0x14);
        ledger.block(T0 + 1_000, deposit(0x25, datum, List.of(outpoint(0x14)), 0L, List.of(outpoint(0xa0)), 30));
        assertThat(ledger.balance(USDM_VAULT, alice)).isZero();
        assertThat(ledger.depositRecord(datum.depositId(USDM_VAULT)).orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.ASSET_HELD);
        assertThat(Cip113Governance.resume(store, USDM_VAULT)).isEqualTo(Cip113Governance.Result.APPLIED);
        ledger.block(T0 + 2_000, deposit(0x26, datum(0x15), List.of(outpoint(0x15)), 0L, List.of(outpoint(0xa0)),
                30));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(30));
    }

    // ------------------------------------------------------------------ review fixes (r5)

    /** A second CIP-113 vault (EURC, vault 2) on the same deployment, with vault 1's pins except {@code overrides}. */
    static Map<String, String> twoVaults(Map<String, String> overrides) {
        Map<String, String> settings = settings();
        String one = "machines.asset-payments.vaults.1.";
        String two = "machines.asset-payments.vaults.2.";
        for (String key : List.copyOf(settings.keySet())) {
            if (key.startsWith(one + "c")) settings.put(two + key.substring(one.length()), settings.get(key));
        }
        String o = "observers." + OBSERVER + ".vaults.";
        for (String suffix : List.of("custody", "cip113.plb-hash", "cip113.exit-delegate-hash",
                "cip113.params-policy")) {
            settings.put(o + "2." + suffix, settings.get(o + "1." + suffix));
        }
        overrides.forEach((key, value) -> settings.put(two + key, value));
        return settings;
    }

    /** Codex P1-5, Fable P3-2: one deployment per domain; its vaults agree on wiring version 0. */
    @Test
    void theCip113VaultsOfADomainAreOnOneDeploymentWithOneVersionZero() {
        AssetPaymentsSettings parsed = AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, twoVaults(Map.of()));
        assertThat(parsed.cip113ParamsPolicy()).hasValueSatisfying(policy ->
                assertThat(HexFormat.of().formatHex(policy)).isEqualTo("1d".repeat(28)));
        new PaymentsHarness(twoVaults(Map.of())).block(T0);
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN,
                twoVaults(Map.of("cip113.params-policy", "2d".repeat(28))))).hasMessageContaining("one deployment");
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN,
                twoVaults(Map.of("cip113.params-outpoint", "a9".repeat(32) + "#0"))))
                .hasMessageContaining("version 0");
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN,
                twoVaults(Map.of("cip113.transfer-delegate", "d9".repeat(28))))).hasMessageContaining("version 0");
        // The wiring observer must watch this deployment's params policy.
        Map<String, String> other = settings();
        other.put("observers." + WIRING + ".params-policy", "2d".repeat(28));
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, other))
                .hasMessageContaining("params-policy");
        // And the deposit observer the same custody.
        Map<String, String> otherE = settings();
        otherE.put("observers." + OBSERVER + ".vaults.1.cip113.exit-delegate-hash", "e9".repeat(28));
        assertThatThrownBy(() -> AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, otherE))
                .hasMessageContaining("exit-delegate-hash");
    }

    /** Codex P2-8: the wiring observer id is part of a CIP-113 domain's settings digest. */
    @Test
    void theWiringObserverIdIsInTheSettingsDigest() {
        Hash32 digest = AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, settings()).digest();
        Map<String, String> renamed = settings();
        String id = "cip113-wiring-b";
        renamed.put("machines.asset-payments.cip113.wiring-observer-id", id);
        for (String key : List.copyOf(renamed.keySet())) {
            if (key.startsWith("observers." + WIRING + ".")) {
                renamed.put("observers." + id + key.substring(("observers." + WIRING).length()), renamed.get(key));
            }
        }
        assertThat(AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, renamed).digest()).isNotEqualTo(digest);
    }

    /** Codex P1-3: a key issuance logic at registration is version 0's kind, so its rollover is approved at once. */
    @Test
    void aKeyIssuanceLogicIsPinnedWithItsKind() {
        Map<String, String> keyed = settings();
        keyed.put("machines.asset-payments.vaults.1.cip113.issuance-logic-kind", "key");
        PaymentsRecords.Cip113Custody custody = AssetPaymentsSettings.parse(PaymentsHarness.CHAIN, keyed).vaults()
                .get(1).custody().orElseThrow();
        assertThat(custody.wiring0().script(1)).isFalse();
        PaymentsHarness ledger = new PaymentsHarness(keyed);
        ledger.block(T0, register(alice));
        ledger.block(T0 + 1_000, wiring(0xa3, new Cip113WiringClaimV1.Fields(D, ISSUANCE, T, TP, 0b0010)),
                deposit(0x27, datum(0x16), List.of(outpoint(0x16)), 0L, List.of(outpoint(0xa3)), 40));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(40));
    }

    /** Codex P1-6: ASSET_HOLD and ASSET_RESUME through the governed route, with threshold approval and history. */
    @Test
    void assetHoldAndResumeAreGovernedCommands() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        ledger.block(T0, register(alice));
        PaymentsGovernanceV1.Command hold = new PaymentsGovernanceV1.Command(0,
                new PaymentsGovernanceV1.AssetHold(USDM_VAULT, PaymentsGovernanceV1.HoldReason.SHORTFALL));
        assertThat(PaymentsGovernanceV1.Command.decode(hold.encode())).isEqualTo(hold);
        // One member is below the threshold of two.
        ledger.block(T0 + 1_000, PaymentsHarness.governance(0, hold));
        assertThat(status(ledger)).isEqualTo(PaymentsRecords.AssetStatus.ACTIVE);
        ledger.block(T0 + 2_000, PaymentsHarness.governance(1, hold));
        assertThat(status(ledger)).isEqualTo(PaymentsRecords.AssetStatus.ISSUER_HOLD);
        PaymentsGovernanceV1.DecisionV1 decided = decision(ledger, 0);
        assertThat(decided.outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(decided.to()).isEqualTo(PaymentsRecords.AssetStatus.ISSUER_HOLD.code());
        assertThat(SettlementQueries.decodeHold(ledger.query(SettlementQueries.VAULT_HOLD, USDM_VAULT.bytes())))
                .contains(true);
        // The hold keeps the custody pins, and the domain status leaf stays ACTIVE (I47).
        assertThat(registry(ledger).custody()).isPresent();
        // ASSET_STATUS never moves an asset out of ISSUER_HOLD.
        PaymentsGovernanceV1.Command status = new PaymentsGovernanceV1.Command(1,
                new PaymentsGovernanceV1.AssetStatusChange(USDM_VAULT, PaymentsRecords.AssetStatus.ACTIVE));
        ledger.block(T0 + 3_000, PaymentsHarness.governance(0, status), PaymentsHarness.governance(1, status));
        assertThat(decision(ledger, 1).reason()).isEqualTo("USE_ASSET_RESUME");
        PaymentsGovernanceV1.Command resume = new PaymentsGovernanceV1.Command(2,
                new PaymentsGovernanceV1.AssetResume(USDM_VAULT));
        ledger.block(T0 + 4_000, PaymentsHarness.governance(0, resume), PaymentsHarness.governance(2, resume));
        assertThat(status(ledger)).isEqualTo(PaymentsRecords.AssetStatus.ACTIVE);
        assertThat(decision(ledger, 2).outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        // A native vault cannot be held; ASSET_STATUS keeps a CIP-113 entry's custody.
        PaymentsGovernanceV1.Command nativeHold = new PaymentsGovernanceV1.Command(3,
                new PaymentsGovernanceV1.AssetHold(PaymentsHarness.ADA_VAULT, PaymentsGovernanceV1.HoldReason.OTHER));
        ledger.block(T0 + 5_000, PaymentsHarness.governance(0, nativeHold), PaymentsHarness.governance(1, nativeHold));
        assertThat(decision(ledger, 3).reason()).isEqualTo("NOT_CIP113");
        PaymentsGovernanceV1.Command pause = new PaymentsGovernanceV1.Command(4,
                new PaymentsGovernanceV1.AssetStatusChange(USDM_VAULT, PaymentsRecords.AssetStatus.DEPOSITS_PAUSED));
        ledger.block(T0 + 6_000, PaymentsHarness.governance(0, pause), PaymentsHarness.governance(1, pause));
        assertThat(status(ledger)).isEqualTo(PaymentsRecords.AssetStatus.DEPOSITS_PAUSED);
        assertThat(registry(ledger).custody()).isPresent();
    }

    /** Codex P1-6: ASSET_REWIRE through the governed route approves the head version only. */
    @Test
    void assetRewireIsAGovernedCommandThatApprovesTheHeadOnly() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        ledger.block(T0, register(alice));
        Cip113WiringClaimV1.Fields moved = new Cip113WiringClaimV1.Fields(filled(28, 0xd8), ISSUANCE, T, TP);
        ledger.block(T0 + 1_000, wiring(0xa4, moved));
        ProgrammableDepositDatumV1 before = datum(0x17);
        // The new dispatcher is unapproved: no tuple for it, and a deposit against it is uncredited.
        assertThat(ledger.query(SettlementQueries.CIP113_WIRING, filled(28, 0xd8))).isEmpty();
        assertThat(SettlementQueries.decodeWiring(ledger.query(SettlementQueries.CIP113_WIRING, D))).isPresent();
        byte[] policy = HexFormat.of().parseHex("1d".repeat(28));
        PaymentsGovernanceV1.Command stale = new PaymentsGovernanceV1.Command(0, new PaymentsGovernanceV1.AssetRewire(
                policy, Hash32.of(filled(32, 0xa0)), 0, T, TP, new byte[28]));
        ledger.block(T0 + 2_000, PaymentsHarness.governance(0, stale), PaymentsHarness.governance(1, stale));
        assertThat(decision(ledger, 0).reason()).isEqualTo("REJECTED_NOT_HEAD");
        PaymentsGovernanceV1.Command head = new PaymentsGovernanceV1.Command(1, new PaymentsGovernanceV1.AssetRewire(
                policy, Hash32.of(filled(32, 0xa4)), 0, T, TP, new byte[28]));
        ledger.block(T0 + 3_000, PaymentsHarness.governance(0, head), PaymentsHarness.governance(1, head));
        assertThat(decision(ledger, 1).outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(SettlementQueries.decodeWiring(ledger.query(SettlementQueries.CIP113_WIRING, filled(28, 0xd8))))
                .hasValueSatisfying(tuple -> assertThat(tuple.fields()).isEqualTo(moved));
        // A deposit that references the approved head is credited now (its dispatcher is the new one).
        List<ProgrammableDepositClaimV1.Withdrawal> transfer = List.of(
                new ProgrammableDepositClaimV1.Withdrawal(filled(28, 0xd8), Optional.of(0L)),
                new ProgrammableDepositClaimV1.Withdrawal(T, Optional.of(0L)));
        ledger.block(T0 + 4_000, deposit(0x28, before, List.of(outpoint(0x17)), transfer, List.of(outpoint(0xa4)),
                25));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(25));
        // Another deployment's policy is unknown.
        PaymentsGovernanceV1.Command unknown = new PaymentsGovernanceV1.Command(2,
                new PaymentsGovernanceV1.AssetRewire(filled(28, 0x2d), Hash32.of(filled(32, 0xa4)), 0, T, TP,
                        new byte[28]));
        ledger.block(T0 + 5_000, PaymentsHarness.governance(0, unknown), PaymentsHarness.governance(1, unknown));
        assertThat(decision(ledger, 2).reason()).isEqualTo("UNKNOWN_DEPLOYMENT");
    }

    static PaymentsRecords.RegistryEntryV1 registry(PaymentsHarness ledger) {
        return ledger.get(org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract.registryKey(USDM_VAULT))
                .map(PaymentsRecords.RegistryEntryV1::decode).orElseThrow();
    }

    static PaymentsRecords.AssetStatus status(PaymentsHarness ledger) {
        return registry(ledger).status();
    }

    static PaymentsGovernanceV1.DecisionV1 decision(PaymentsHarness ledger, long sequence) {
        return ledger.get(org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract
                .governanceHistoryKey(sequence)).map(PaymentsGovernanceV1.DecisionV1::decode).orElseThrow();
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

    /** A deposit claim of transaction {@code fill} with exactly {@code withdrawals}. */
    static PaymentsHarness.Message deposit(int fill, ProgrammableDepositDatumV1 datum,
                                           List<ProgrammableDepositDatumV1.Outpoint> inputs,
                                           List<ProgrammableDepositClaimV1.Withdrawal> withdrawals,
                                           List<ProgrammableDepositDatumV1.Outpoint> references, long quantity) {
        Hash32 tx = Hash32.of(filled(32, fill));
        byte[] claim = new ProgrammableDepositClaimV1(USDM_VAULT, tx, 0, 1_000 + fill, Hash32.of(BLOCK),
                ProgrammableDepositClaimV1.Shape.DEPOSIT, Optional.of(new ProgrammableDepositClaimV1.Deposit(datum,
                datum.originSpent(inputs, 0))), BigInteger.valueOf(quantity), BigInteger.valueOf(2_000_000), 0,
                Hash32.of(filled(32, 0x5e)), Optional.of(withdrawals), false, Optional.of(references)).encode();
        return PaymentsHarness.deposit(new L1Observation(OBSERVER, new L1Observation.TransactionAnchor(tx.bytes()), 0,
                1_000 + fill, BLOCK, claim));
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
