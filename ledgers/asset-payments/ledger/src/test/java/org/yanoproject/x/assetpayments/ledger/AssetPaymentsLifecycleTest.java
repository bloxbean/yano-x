package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.AssetStatusChange;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Command;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.DecisionV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AssetStatus;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultDepositObserverProvider;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultPayoutObserverProvider;
import org.yanoproject.x.assets.contracts.AssetDepositClaimV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.lovelace;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.output;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.token;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.transferBody;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * The D14 asset lifecycle at runtime, one governed status at a time, through the {@code asset-payments} state machine
 * with the real deposit and payout observers: what each status lets through ({@code DEPOSITS_PAUSED},
 * {@code TRANSFERS_PAUSED}, {@code WIND_DOWN}: withdrawals and settlement only, {@code RETIRED} only once nothing is
 * owed), plus the ledger-level refusals #109 names ({@code BALANCE_OVERFLOW}, {@code UNKNOWN_ASSET}, an expired
 * session grant) and a single-asset chain end to end.
 */
class AssetPaymentsLifecycleTest {
    static final IntentEnvelopeV1.Owner OWNER = new IntentEnvelopeV1.Owner();
    static final long EXPIRY = T0 + 600_000;
    /** The shortest retirement window depth 1 allows (one mean L1 block interval), so intents stay unexpired. */
    static final long RETIRE_WINDOW = 20_000;

    static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>(AssetPaymentsPayoutTest.settings());
        settings.put("machines.asset-payments.retire-after-wind-down-ms", String.valueOf(RETIRE_WINDOW));
        return settings;
    }

    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final PaymentsHarness ledger = new PaymentsHarness(settings());
    final AssetPaymentsStateMachineTest l1 = new AssetPaymentsStateMachineTest();
    final AssetPaymentsPayoutTest settlement = new AssetPaymentsPayoutTest();
    long governed;
    int nonce = 7_000;

    IntentEnvelopeV1 env(TestWallet wallet, String action, byte[] body) {
        return envelope(wallet.accountId(), action, OWNER, ++nonce, EXPIRY, body);
    }

    Message register(TestWallet wallet) {
        return intent(owner(wallet, env(wallet, WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT)));
    }

    /** Alice and bob registered; alice holds 10 ADA, and 100 USDM with 1.5 ADA of min-ADA credit. */
    void fund(PaymentsHarness chain) {
        chain.block(T0, register(alice), register(bob));
        chain.block(T0 + 1_000, l1.l1(output(ADA_ADDRESS, alice, lovelace(10_000_000)),
                output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100))));
    }

    /** Two of the three members decide {@code vault → status}; returns the decision. */
    DecisionV1 govern(long timestamp, Hash32 vault, AssetStatus status) {
        Command command = new Command(governed, new AssetStatusChange(vault, status));
        ledger.block(timestamp, governance(0, command), governance(1, command));
        return ledger.get(AssetPaymentsContract.governanceHistoryKey(governed++)).map(DecisionV1::decode)
                .orElseThrow();
    }

    AssetStatus status(Hash32 vault) {
        return ledger.get(AssetPaymentsContract.registryKey(vault)).map(PaymentsRecords.RegistryEntryV1::decode)
                .orElseThrow().status();
    }

    String reason(IntentEnvelopeV1 envelope) {
        return ledger.receipt(envelope).orElseThrow().reason();
    }

    IntentEnvelopeV1 transfer(TestWallet from, AssetId asset, TestWallet to, long amount) {
        return env(from, AssetPaymentsContract.TRANSFER, transferBody(asset, to, amount));
    }

    IntentEnvelopeV1 withdrawal(Hash32 vault, long amount, long vaultLovelace) {
        return env(alice, AssetPaymentsContract.REQUEST_WITHDRAWAL, new PaymentsBodies.RequestWithdrawal(vault,
                alice.address(), BigInteger.valueOf(amount), BigInteger.valueOf(vaultLovelace)).encode());
    }

    ClaimLeafV1 claim(long sequence) {
        Hash32 claimId = Hash32.of(ledger.get(AssetPaymentsContract.claimIndexKey(sequence)).orElseThrow());
        return ledger.get(AssetPaymentsWithdrawalTest.RECORDS.claimLeafKey(claimId)).map(ClaimLeafV1::decode)
                .orElseThrow();
    }

    SettlementRecords.ClaimStatus claimStatus(ClaimLeafV1 leaf) {
        return AssetPaymentsWithdrawalTest.status(ledger, leaf.claimId()).orElseThrow().status();
    }

    DepositRecordV1 record(Message deposit) {
        Hash32 id = AssetDepositClaimV1.decode(L1Observation.decode(deposit.body()).claim()).depositId();
        return ledger.depositRecord(id).orElseThrow();
    }

    // ------------------------------------------------------------------ one status at a time

    @Test
    void transfersPausedRefusesTransfersOnlyOfThatAsset() {
        fund(ledger);
        assertThat(govern(T0 + 2_000, USDM_VAULT, AssetStatus.TRANSFERS_PAUSED).outcome())
                .isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        IntentEnvelopeV1 paused = transfer(alice, USDM, bob, 10);
        IntentEnvelopeV1 other = transfer(alice, AssetId.ADA, bob, 1_000_000);
        IntentEnvelopeV1 withdraw = withdrawal(USDM_VAULT, 10, 1_200_000);
        Message[] deposit = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_200_000), token(USDM, 5)));
        ledger.block(T0 + 3_000, intent(owner(alice, paused)), intent(owner(alice, other)),
                intent(owner(alice, withdraw)), deposit[0]);
        assertThat(reason(paused)).isEqualTo("TRANSFERS_PAUSED");
        assertThat(ledger.receipt(other).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.receipt(withdraw).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(record(deposit[0]).status()).isEqualTo(DepositRecordV1.Status.CREDITED);
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(95));
        assertThat(ledger.balance(USDM_VAULT, bob)).isZero();

        // Back to ACTIVE: transfers flow again.
        govern(T0 + 4_000, USDM_VAULT, AssetStatus.ACTIVE);
        IntentEnvelopeV1 resumed = transfer(alice, USDM, bob, 10);
        ledger.block(T0 + 5_000, intent(owner(alice, resumed)));
        assertThat(ledger.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.TEN);
        ledger.checkInvariants();
    }

    @Test
    void depositsPausedRecordsButNeverCreditsAndResumingCreditsOnlyLaterDeposits() {
        fund(ledger);
        govern(T0 + 2_000, USDM_VAULT, AssetStatus.DEPOSITS_PAUSED);
        Message[] whilePaused = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_200_000), token(USDM, 7)));
        IntentEnvelopeV1 pay = transfer(alice, USDM, bob, 10);
        ledger.block(T0 + 3_000, whilePaused[0], intent(owner(alice, pay)));
        DepositRecordV1 uncredited = record(whilePaused[0]);
        assertThat(uncredited.status()).isEqualTo(DepositRecordV1.Status.UNCREDITED);
        assertThat(uncredited.reason()).isEqualTo(DepositAdmission.Reason.DEPOSITS_PAUSED);
        assertThat(ledger.receipt(pay).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);

        govern(T0 + 4_000, USDM_VAULT, AssetStatus.ACTIVE);
        ledger.block(T0 + 5_000, whilePaused); // redelivery after the resume stays a no-op (I1)
        Message[] after = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_200_000), token(USDM, 3)));
        ledger.block(T0 + 6_000, after);
        assertThat(record(whilePaused[0]).status()).isEqualTo(DepositRecordV1.Status.UNCREDITED);
        assertThat(record(after[0]).status()).isEqualTo(DepositRecordV1.Status.CREDITED);
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(93));
        assertThat(ledger.counters(USDM_VAULT).credited()).isEqualTo(BigInteger.valueOf(103));
        ledger.checkInvariants();
    }

    @Test
    void windDownAllowsWithdrawalAndSettlementButNoNewCreditOrTransfer() {
        fund(ledger);
        govern(T0 + 2_000, USDM_VAULT, AssetStatus.WIND_DOWN);
        Message[] late = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_200_000), token(USDM, 9)));
        IntentEnvelopeV1 pay = transfer(alice, USDM, bob, 10);
        IntentEnvelopeV1 withdraw = withdrawal(USDM_VAULT, 60, 1_000_000);
        ledger.block(T0 + 3_000, late[0], intent(owner(alice, pay)), intent(owner(alice, withdraw)));
        assertThat(record(late[0]).reason()).isEqualTo(DepositAdmission.Reason.DEPOSITS_PAUSED);
        assertThat(reason(pay)).isEqualTo("TRANSFERS_PAUSED");
        assertThat(ledger.receipt(withdraw).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);

        ClaimLeafV1 leaf = claim(0);
        ledger.block(T0 + 4_000, settlement.settle(leaf, alice.address(), true));
        assertThat(claimStatus(leaf)).isEqualTo(SettlementRecords.ClaimStatus.PAID);
        assertThat(ledger.counters(USDM_VAULT).paid()).isEqualTo(BigInteger.valueOf(60));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(40));
        ledger.checkInvariants();
    }

    @Test
    void retiredIsRefusedWhileAnythingIsOwedAndReachedOnceEverythingIsPaid() {
        fund(ledger);
        DecisionV1 active = govern(T0 + 2_000, USDM_VAULT, AssetStatus.RETIRED);
        assertThat(active.outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.REFUSED);
        assertThat(active.reason()).isEqualTo("NOT_WOUND_DOWN");
        assertThat(active.to()).isEqualTo(AssetStatus.ACTIVE.code());

        // Wind down first and wait out the window; then everything owed still blocks it.
        govern(T0 + 3_000, USDM_VAULT, AssetStatus.WIND_DOWN);
        long woundDown = T0 + 3_000;
        assertThat(govern(woundDown + RETIRE_WINDOW, USDM_VAULT, AssetStatus.RETIRED).reason())
                .isEqualTo("OUTSTANDING_LIABILITIES");
        // Withdraw everything, the min-ADA credit included.
        IntentEnvelopeV1 all = withdrawal(USDM_VAULT, 100, 1_500_000);
        ledger.block(woundDown + RETIRE_WINDOW + 1_000, intent(owner(alice, all)));
        assertThat(ledger.counters(USDM_VAULT).available()).isZero();
        assertThat(govern(woundDown + RETIRE_WINDOW + 2_000, USDM_VAULT, AssetStatus.RETIRED).reason())
                .isEqualTo("UNPAID_CLAIMS");
        assertThat(status(USDM_VAULT)).isEqualTo(AssetStatus.WIND_DOWN);

        ClaimLeafV1 leaf = claim(0);
        ledger.block(woundDown + RETIRE_WINDOW + 3_000, settlement.settle(leaf, alice.address(), true));
        assertThat(claimStatus(leaf)).isEqualTo(SettlementRecords.ClaimStatus.PAID);
        DecisionV1 retired = govern(woundDown + RETIRE_WINDOW + 4_000, USDM_VAULT, AssetStatus.RETIRED);
        assertThat(retired.outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(retired.from()).isEqualTo(AssetStatus.WIND_DOWN.code());
        assertThat(status(USDM_VAULT)).isEqualTo(AssetStatus.RETIRED);

        // A retired vault credits nothing and reserves nothing; the other assets are untouched.
        Message[] late = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_200_000), token(USDM, 4)));
        IntentEnvelopeV1 again = withdrawal(USDM_VAULT, 1, 0);
        IntentEnvelopeV1 ada = transfer(alice, AssetId.ADA, bob, 1_000_000);
        ledger.block(woundDown + RETIRE_WINDOW + 5_000, late[0], intent(owner(alice, again)),
                intent(owner(alice, ada)));
        assertThat(record(late[0]).reason()).isEqualTo(DepositAdmission.Reason.DEPOSITS_PAUSED);
        assertThat(reason(again)).isEqualTo("ASSET_RETIRED");
        assertThat(ledger.receipt(ada).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.counters(USDM_VAULT).credited()).isEqualTo(ledger.counters(USDM_VAULT).paid());
        ledger.checkInvariants();
    }

    @Test
    void aPaidMismatchKeepsItsValueReservedAndBlocksRetirement() {
        fund(ledger);
        IntentEnvelopeV1 withdraw = withdrawal(USDM_VAULT, 100, 1_500_000);
        ledger.block(T0 + 2_000, intent(owner(alice, withdraw)));
        ClaimLeafV1 leaf = claim(0);
        ledger.block(T0 + 3_000, settlement.settle(leaf, bob.address(), true));
        assertThat(claimStatus(leaf)).isEqualTo(SettlementRecords.ClaimStatus.PAID_MISMATCH);
        govern(T0 + 4_000, USDM_VAULT, AssetStatus.WIND_DOWN);
        assertThat(govern(T0 + 4_000 + RETIRE_WINDOW, USDM_VAULT, AssetStatus.RETIRED).reason())
                .isEqualTo("UNPAID_CLAIMS");
    }

    // ------------------------------------------------------------------ ledger-level refusals

    static Amount tokenOf(AssetId asset, BigInteger quantity) {
        String policy = HexFormat.of().formatHex(asset.policyId());
        return Amount.builder().unit(policy + "." + HexFormat.of().formatHex(asset.assetName())).policyId(policy)
                .assetNameBytes(asset.assetName()).quantity(quantity).build();
    }

    @Test
    void overflowingCreditsAndTransfersAreRefusedWithoutPartialMutation() {
        fund(ledger);
        BigInteger max = Quantities.MAX_U64;
        Message[] big = l1.l1(output(EURC_ADDRESS, bob, lovelace(1_200_000), tokenOf(EURC, max.subtract(BigInteger.TWO))),
                output(EURC_ADDRESS, alice, lovelace(1_200_000), tokenOf(EURC, BigInteger.TEN)));
        ledger.block(T0 + 2_000, big);
        assertThat(ledger.balance(EURC_VAULT, bob)).isEqualTo(max.subtract(BigInteger.TWO));
        // A second deposit that would overflow bob's u64 balance is recorded, never credited.
        Message[] over = l1.l1(output(EURC_ADDRESS, bob, lovelace(1_200_000), tokenOf(EURC, BigInteger.TEN)));
        ledger.block(T0 + 3_000, over);
        assertThat(record(over[0]).reason()).isEqualTo(DepositAdmission.Reason.BALANCE_OVERFLOW);
        assertThat(ledger.balance(EURC_VAULT, bob)).isEqualTo(max.subtract(BigInteger.TWO));

        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        IntentEnvelopeV1 overflow = transfer(alice, EURC, bob, 3);
        ledger.block(T0 + 4_000, intent(owner(alice, overflow)));
        assertThat(reason(overflow)).isEqualTo("BALANCE_OVERFLOW");
        assertThat(ledger.balance(EURC_VAULT, alice)).isEqualTo(BigInteger.TEN);
        assertThat(ledger.balance(EURC_VAULT, bob)).isEqualTo(max.subtract(BigInteger.TWO));
        assertThat(changedKeys(before)).allMatch(this::receiptOrUsedKey); // only the receipt and the replay marker
        ledger.checkInvariants();
    }

    @Test
    void anUnregisteredAssetNeverBecomesABalanceOrMoves() {
        fund(ledger);
        AssetId foreign = AssetId.ofHex("e1".repeat(28), "464f4f");
        Message[] junk = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_200_000), token(foreign, 50)));
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        IntentEnvelopeV1 send = transfer(alice, foreign, bob, 1);
        IntentEnvelopeV1 out = env(alice, AssetPaymentsContract.REQUEST_WITHDRAWAL, new PaymentsBodies.RequestWithdrawal(
                Hash32.of(filled(32, 0x0f)), alice.address(), BigInteger.ONE, BigInteger.ZERO).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, send)), intent(owner(alice, out)), junk[0]);
        assertThat(reason(send)).isEqualTo("UNKNOWN_ASSET");
        assertThat(ledger.receipt(send).orElseThrow().asset()).contains(foreign);
        assertThat(reason(out)).isEqualTo("UNKNOWN_VAULT");
        assertThat(record(junk[0]).reason()).isEqualTo(DepositAdmission.Reason.UNREGISTERED_ASSET);
        assertThat(ledger.counters(USDM_VAULT).credited()).isEqualTo(BigInteger.valueOf(100));
        assertThat(changedKeys(before)).allMatch(key -> receiptOrUsedKey(key) || depositKey(key));
        ledger.checkInvariants();
    }

    @Test
    void aTransferUnderAnExpiredSessionGrantWritesNothing() {
        fund(ledger);
        byte[] seed = filled(32, 0x77);
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        IntentEnvelopeV1 grant = env(alice, WalletAuthorizer.AUTHORIZE_SESSION, new PaymentsBodies.AuthorizeSession(
                sessionKey, List.of(AssetPaymentsContract.TRANSFER),
                List.of(new SessionGrantV1.Limit(USDM_VAULT, BigInteger.TEN, BigInteger.valueOf(50))), List.of(),
                T0 + 10_000).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, grant)));
        IntentEnvelopeV1.Session who = new IntentEnvelopeV1.Session(intentId(grant));
        IntentEnvelopeV1 inTime = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, who, ++nonce, EXPIRY,
                transferBody(USDM, bob, 1));
        ledger.block(T0 + 10_000, intent(session(seed, inTime)));
        assertThat(ledger.receipt(inTime).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);

        IntentEnvelopeV1 late = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, who, ++nonce, EXPIRY,
                transferBody(USDM, bob, 1));
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        ledger.block(T0 + 10_001, intent(session(seed, late)));
        assertThat(ledger.state).containsExactlyEntriesOf(before); // unauthenticated: no receipt, no marker (D14)
        assertThat(ledger.receipt(late)).isEmpty();
        assertThat(ledger.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.ONE);
        assertThat(ledger.machine.validateForBlock(AssetPaymentsStateMachineTest.appMessage(
                AssetPaymentsContract.INTENT_TOPIC, session(seed, late).encode()), ledger.height + 1,
                ledger.writer()).isAccepted()).isTrue(); // advisory only: the clock is apply()'s
    }

    // ------------------------------------------------------------------ single-asset configuration

    /** Only USDM, at ordinal 0: the ledger, the deposit observer and the payout observer agree on one vault. */
    static Map<String, String> singleAsset() {
        Map<String, String> settings = new LinkedHashMap<>();
        AssetPaymentsPayoutTest.settings().forEach((key, value) -> {
            if (!key.contains(".vaults.")) settings.put(key, value);
        });
        String[] prefixes = {"machines.asset-payments.", "observers." + OBSERVER + ".",
                "observers." + AssetPaymentsPayoutTest.PAYOUTS + "."};
        for (String prefix : prefixes) {
            settings.put(prefix + "vaults.0.asset", POLICY + ".5553444d");
            settings.put(prefix + "vaults.0.script-hash", HexFormat.of().formatHex(USDM_HASH));
            settings.put(prefix + "vaults.0.address", USDM_ADDRESS);
        }
        settings.put("machines.asset-payments.vaults.0.ticker", "USDM");
        settings.put("machines.asset-payments.vaults.0.decimals", "6");
        String p = "observers." + AssetPaymentsPayoutTest.PAYOUTS + ".";
        settings.put(p + "vaults.0.shard-policy-id", AssetPaymentsPayoutTest.SHARD_POLICY);
        settings.put(p + "vaults.0.shard-script-hash", HexFormat.of().formatHex(AssetPaymentsPayoutTest.SHARD_HASH));
        settings.put(p + "vaults.0.shard-address", AssetPaymentsPayoutTest.SHARD_ADDRESS);
        return settings;
    }

    static Map<String, String> suffixes(Map<String, String> settings, String prefix) {
        Map<String, String> out = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (key.startsWith(prefix)) out.put(key.substring(prefix.length()), value);
        });
        out.remove("type");
        return out;
    }

    @Test
    void aSingleAssetChainDepositsTransfersWithdrawsAndSettlesOneVault() {
        Map<String, String> settings = singleAsset();
        PaymentsHarness single = new PaymentsHarness(settings);
        L1Observer deposits = new AssetVaultDepositObserverProvider().create(OBSERVER,
                suffixes(settings, "observers." + OBSERVER + "."));
        L1Observer payouts = new AssetVaultPayoutObserverProvider().create(AssetPaymentsPayoutTest.PAYOUTS,
                suffixes(settings, "observers." + AssetPaymentsPayoutTest.PAYOUTS + "."));
        single.block(T0, register(alice), register(bob));
        // ADA sent to the (unconfigured) ADA vault address is not this chain's: the observer reports nothing.
        com.bloxbean.cardano.yaci.core.model.TransactionBody tx = com.bloxbean.cardano.yaci.core.model.TransactionBody
                .builder().txHash("9a".repeat(32)).outputs(List.of(
                        output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100)),
                        output(ADA_ADDRESS, alice, lovelace(10_000_000)))).build();
        List<L1Observation> seen = deposits.observe(1_000, filled(32, 0xb1),
                com.bloxbean.cardano.yaci.core.model.Block.builder().transactionBodies(List.of(tx)).build());
        assertThat(seen).hasSize(1);
        single.block(T0 + 1_000, seen.stream().map(PaymentsHarness::deposit).toArray(Message[]::new));
        assertThat(single.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));

        IntentEnvelopeV1 pay = transfer(alice, USDM, bob, 30);
        IntentEnvelopeV1 ada = transfer(alice, AssetId.ADA, bob, 1);
        IntentEnvelopeV1 withdraw = withdrawal(USDM_VAULT, 50, 1_200_000);
        single.block(T0 + 2_000, intent(owner(alice, pay)), intent(owner(alice, ada)), intent(owner(alice, withdraw)));
        assertThat(single.receipt(ada).orElseThrow().reason()).isEqualTo("UNKNOWN_ASSET");
        assertThat(single.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.valueOf(30));
        Hash32 claimId = Hash32.of(single.get(AssetPaymentsContract.claimIndexKey(0)).orElseThrow());
        ClaimLeafV1 leaf = single.get(AssetPaymentsWithdrawalTest.RECORDS.claimLeafKey(claimId))
                .map(ClaimLeafV1::decode).orElseThrow();
        single.block(T0 + 3_000, settlement.settle(payouts, leaf, alice.address(), true));
        assertThat(AssetPaymentsWithdrawalTest.status(single, claimId).orElseThrow().status())
                .isEqualTo(SettlementRecords.ClaimStatus.PAID);
        var counters = single.counters(USDM_VAULT);
        assertThat(counters.credited()).isEqualTo(BigInteger.valueOf(100));
        assertThat(counters.paid()).isEqualTo(BigInteger.valueOf(50));
        assertThat(counters.available()).isEqualTo(BigInteger.valueOf(50));
        assertThat(counters.reserved()).isZero();
        assertThat(counters.lovelaceCredited().subtract(counters.lovelacePaid()))
                .isEqualTo(counters.lovelaceAvailable().add(counters.lovelaceReserved()));
        assertThat(single.get(AssetPaymentsContract.registryKey(ADA_VAULT))).isEmpty();
        assertThat(single.get(AssetPaymentsContract.registryKey(EURC_VAULT))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    List<String> changedKeys(TreeMap<String, byte[]> before) {
        TreeMap<String, byte[]> after = ledger.state;
        java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream().filter(key -> !Arrays.equals(before.get(key), after.get(key))).toList();
    }

    boolean receiptOrUsedKey(String hexKey) {
        String text = new String(HexFormat.of().parseHex(hexKey), java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.startsWith("ap/receipt/") || text.startsWith("ap/used");
    }

    boolean depositKey(String hexKey) {
        return new String(HexFormat.of().parseHex(hexKey), java.nio.charset.StandardCharsets.ISO_8859_1)
                .startsWith("ap/deposit/");
    }
}
