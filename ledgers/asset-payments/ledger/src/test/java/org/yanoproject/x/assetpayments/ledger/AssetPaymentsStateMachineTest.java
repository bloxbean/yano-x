package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultDepositObserverProvider;
import org.yanoproject.x.assets.contracts.AssetDepositClaimV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

class AssetPaymentsStateMachineTest {
    static final IntentEnvelopeV1.Owner OWNER = new IntentEnvelopeV1.Owner();
    static final long EXPIRY = T0 + 60_000;

    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final PaymentsHarness ledger = new PaymentsHarness();
    final L1Observer observer = new AssetVaultDepositObserverProvider().create(OBSERVER, observerSettings());
    int nonce;
    int txByte = 0x40;

    IntentEnvelopeV1 env(TestWallet wallet, String action, byte[] body) {
        return envelope(wallet.accountId(), action, OWNER, ++nonce, EXPIRY, body);
    }

    Message register(TestWallet wallet) {
        return intent(owner(wallet, env(wallet, WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT)));
    }

    static byte[] transferBody(AssetId asset, TestWallet to, long amount) {
        return new PaymentsBodies.Transfer(asset, to.accountId(), BigInteger.valueOf(amount)).encode();
    }

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount token(AssetId asset, long quantity) {
        String policy = HexFormat.of().formatHex(asset.policyId());
        return Amount.builder().unit(policy + "." + HexFormat.of().formatHex(asset.assetName())).policyId(policy)
                .assetNameBytes(asset.assetName()).quantity(BigInteger.valueOf(quantity)).build();
    }

    static TransactionOutput output(String address, TestWallet beneficiary, Amount... amounts) {
        String datum = beneficiary == null ? null : HexFormat.of().formatHex(new DepositDatumV1(
                beneficiary.accountId(), beneficiary.address(), beneficiary.keyHash()).encode());
        return TransactionOutput.builder().address(address).amounts(List.of(amounts)).inlineDatum(datum).build();
    }

    /** Observes one L1 transaction with the real deposit observer and returns its ~l1 messages. */
    Message[] l1(TransactionOutput... outputs) {
        TransactionBody tx = TransactionBody.builder().txHash(HexFormat.of().formatHex(filled(32, txByte++)))
                .outputs(List.of(outputs)).build();
        List<L1Observation> observations = observer.observe(1_000, filled(32, 0xb1),
                Block.builder().transactionBodies(List.of(tx)).build());
        return observations.stream().map(PaymentsHarness::deposit).toArray(Message[]::new);
    }

    void fund() {
        ledger.block(T0, register(alice), register(bob));
        ledger.block(T0 + 1_000, l1(output(ADA_ADDRESS, alice, lovelace(10_000_000)),
                output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100))));
    }

    @Test
    void depositsCreditTokenAndMinAdaOnceAndKeepI4() {
        fund();
        assertThat(ledger.balance(ADA_VAULT, alice)).isEqualTo(BigInteger.valueOf(10_000_000));
        assertThat(ledger.minAda(ADA_VAULT, alice)).isZero();
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
        assertThat(ledger.minAda(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(1_500_000));
        assertThat(ledger.leaf(USDM_VAULT, alice).orElseThrow().holderKeyHash()).isEqualTo(alice.keyHash());
        // Re-delivery of the same observations (rescan) is a no-op.
        txByte--;
        Message[] again = l1(output(ADA_ADDRESS, alice, lovelace(10_000_000)),
                output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100)));
        ledger.block(T0 + 2_000, again);
        ledger.block(T0 + 3_000, again);
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
        assertThat(ledger.counters(USDM_VAULT).credited()).isEqualTo(BigInteger.valueOf(100));
        assertThat(ledger.counters(USDM_VAULT).lovelaceCredited()).isEqualTo(BigInteger.valueOf(1_500_000));
        ledger.checkInvariants();
    }

    @Test
    void depositsToUnknownAccountsOrPausedVaultsAreRecordedButNotCredited() {
        Map<String, String> settings = new HashMap<>(PaymentsHarness.settings());
        settings.put("machines.asset-payments.vaults.2.status", "DEPOSITS_PAUSED");
        PaymentsHarness paused = new PaymentsHarness(settings);
        paused.block(T0, register(alice));
        Message[] messages = l1(output(USDM_ADDRESS, bob, lovelace(1_500_000), token(USDM, 5)),
                output(EURC_ADDRESS, alice, lovelace(1_500_000), token(EURC, 5)));
        paused.block(T0 + 1_000, messages);
        List<DepositRecordV1> records = java.util.Arrays.stream(messages)
                .map(message -> AssetDepositClaimV1.decode(L1Observation.decode(message.body()).claim()).depositId())
                .map(id -> paused.depositRecord(id).orElseThrow()).toList();
        assertThat(records).extracting(DepositRecordV1::reason).containsExactly(
                DepositAdmission.Reason.UNKNOWN_BENEFICIARY, DepositAdmission.Reason.DEPOSITS_PAUSED);
        assertThat(paused.balance(EURC_VAULT, alice)).isZero();
        assertThat(paused.counters(USDM_VAULT).credited()).isZero();
    }

    @Test
    void ownerTransfersMoveBalanceAndWriteOneReceiptPerIntent() {
        fund();
        IntentEnvelopeV1 pay = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 30));
        IntentEnvelopeV1 tooMuch = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 71));
        IntentEnvelopeV1 self = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, alice, 1));
        IntentEnvelopeV1 unknown = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, TestWallet.of(9), 1));
        ledger.block(T0 + 2_000, intent(owner(alice, pay)), intent(owner(alice, tooMuch)), intent(owner(alice, self)),
                intent(owner(alice, unknown)));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(70));
        assertThat(ledger.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.valueOf(30));
        assertThat(ledger.minAda(USDM_VAULT, bob)).isZero(); // min-ADA credit is never transferable
        assertThat(ledger.leaf(USDM_VAULT, bob).orElseThrow().holderKeyHash()).isEqualTo(bob.keyHash());
        assertThat(ledger.receipt(pay).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.receipt(pay).orElseThrow().asset()).contains(USDM);
        assertThat(ledger.receipt(tooMuch).orElseThrow().reason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(ledger.receipt(self).orElseThrow().reason()).isEqualTo("SELF_TRANSFER");
        assertThat(ledger.receipt(unknown).orElseThrow().reason()).isEqualTo("UNKNOWN_RECIPIENT");

        // A replay changes nothing and never rewrites the receipt.
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        ledger.block(T0 + 3_000, intent(owner(alice, pay)), intent(owner(alice, tooMuch)));
        assertThat(ledger.state).containsExactlyEntriesOf(before);
        ledger.checkInvariants();
    }

    @Test
    void unauthenticatedExpiredOrMalformedIntentsWriteNothing() {
        fund();
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        IntentEnvelopeV1 forged = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 1));
        IntentEnvelopeV1 expired = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, OWNER, ++nonce,
                T0 + 1_500, transferBody(USDM, bob, 1));
        IntentEnvelopeV1 tooLong = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, OWNER, ++nonce,
                T0 + 10_000_000, transferBody(USDM, bob, 1));
        IntentEnvelopeV1 withdrawal = env(alice, "REQUEST_WITHDRAWAL", PaymentsBodies.REGISTER_ACCOUNT);
        ledger.block(T0 + 2_000, intent(owner(bob, forged)), intent(owner(alice, expired)),
                intent(owner(alice, tooLong)), intent(owner(alice, withdrawal)),
                new Message(AssetPaymentsContract.INTENT_TOPIC, new byte[] {1, 2, 3}),
                new Message(AssetPaymentsContract.INTENT_TOPIC, new byte[0]),
                new Message("~l1/" + OBSERVER, new L1Observation(OBSERVER,
                        new L1Observation.EpochAnchor(3), 0, 1, filled(32, 1), new byte[] {9}).encode()),
                new Message("other.topic", new byte[] {1}));
        assertThat(ledger.state).containsExactlyEntriesOf(before);
    }

    @Test
    void sessionLimitsHoldWithinOneBlockAndRevocationIsImmediate() {
        fund();
        byte[] sessionSeed = filled(32, 0x33);
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(sessionSeed);
        IntentEnvelopeV1 grantIntent = env(alice, WalletAuthorizer.AUTHORIZE_SESSION,
                new PaymentsBodies.AuthorizeSession(sessionKey, List.of(AssetPaymentsContract.TRANSFER),
                        List.of(new SessionGrantV1.Limit(USDM_VAULT, BigInteger.valueOf(6), BigInteger.TEN)),
                        List.of(), T0 + 50_000).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, grantIntent)));
        Hash32 grantId = intentId(grantIntent);
        assertThat(ledger.receipt(grantIntent).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);

        IntentEnvelopeV1.Session who = new IntentEnvelopeV1.Session(grantId);
        IntentEnvelopeV1 first = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, who, ++nonce, EXPIRY,
                transferBody(USDM, bob, 6));
        IntentEnvelopeV1 second = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, who, ++nonce, EXPIRY,
                transferBody(USDM, bob, 6));
        ledger.block(T0 + 3_000, intent(session(sessionSeed, first)), intent(session(sessionSeed, second)));
        assertThat(ledger.receipt(first).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.receipt(second).orElseThrow().reason()).isEqualTo("SESSION_LIMIT");
        assertThat(ledger.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.valueOf(6));

        // A session cannot authorize a new session (OWNER_ONLY) and is not owner-signable by others.
        IntentEnvelopeV1 delegate = envelope(alice.accountId(), WalletAuthorizer.AUTHORIZE_SESSION, who, ++nonce,
                EXPIRY, PaymentsBodies.REGISTER_ACCOUNT);
        IntentEnvelopeV1 revoke = env(alice, WalletAuthorizer.REVOKE_SESSION,
                new PaymentsBodies.RevokeSession(grantId).encode());
        IntentEnvelopeV1 afterRevoke = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, who, ++nonce,
                EXPIRY, transferBody(USDM, bob, 1));
        ledger.block(T0 + 4_000, intent(session(sessionSeed, delegate)), intent(owner(alice, revoke)),
                intent(session(sessionSeed, afterRevoke)));
        assertThat(ledger.receipt(delegate)).isEmpty();
        assertThat(ledger.receipt(revoke).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.receipt(afterRevoke)).isEmpty();
        assertThat(ledger.get(AssetPaymentsContract.grantKey(grantId))).isEmpty();
        assertThat(ledger.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.valueOf(6));

        // A grant cannot delegate owner-only actions or withdrawals.
        IntentEnvelopeV1 bad = env(alice, WalletAuthorizer.AUTHORIZE_SESSION, new PaymentsBodies.AuthorizeSession(
                sessionKey, List.of("REQUEST_WITHDRAWAL"), List.of(), List.of(), T0 + 50_000).encode());
        ledger.block(T0 + 5_000, intent(owner(alice, bad)));
        assertThat(ledger.receipt(bad).orElseThrow().reason()).isEqualTo("ACTION_NOT_DELEGABLE");
        ledger.checkInvariants();
    }

    @Test
    void rotationNeedsBothSignaturesRewritesEveryLeafAndRevokesGrants() {
        fund();
        TestWallet next = TestWallet.of(7);
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(filled(32, 0x44));
        IntentEnvelopeV1 grantIntent = env(alice, WalletAuthorizer.AUTHORIZE_SESSION,
                new PaymentsBodies.AuthorizeSession(sessionKey, List.of(AssetPaymentsContract.TRANSFER),
                        List.of(new SessionGrantV1.Limit(USDM_VAULT, BigInteger.TEN, BigInteger.TEN)), List.of(),
                        T0 + 50_000).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, grantIntent)));

        IntentEnvelopeV1 rotate = env(alice, WalletAuthorizer.ROTATE_OWNER, WalletAuthorizer.rotationBody(next.credential()));
        IntentEnvelopeV1 forgedRotate = env(alice, WalletAuthorizer.ROTATE_OWNER,
                WalletAuthorizer.rotationBody(bob.credential()));
        ledger.block(T0 + 3_000, intent(rotation(alice, next, forgedRotate)));
        assertThat(ledger.receipt(forgedRotate)).isEmpty(); // second signer is not the named credential
        ledger.block(T0 + 4_000, intent(rotation(alice, next, rotate)));
        assertThat(ledger.receipt(rotate).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.leaf(USDM_VAULT, alice).orElseThrow().holderKeyHash()).isEqualTo(next.keyHash());
        assertThat(ledger.leaf(ADA_VAULT, alice).orElseThrow().holderKeyHash()).isEqualTo(next.keyHash());
        assertThat(ledger.get(AssetPaymentsContract.grantKey(intentId(grantIntent)))).isEmpty();

        // The old owner is locked out; the new owner signs for the same AccountId.
        IntentEnvelopeV1 old = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 1));
        IntentEnvelopeV1 fresh = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, OWNER, ++nonce, EXPIRY,
                transferBody(USDM, bob, 2));
        ledger.block(T0 + 5_000, intent(owner(alice, old)), intent(owner(next, fresh)));
        assertThat(ledger.receipt(old)).isEmpty();
        assertThat(ledger.receipt(fresh).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.balance(USDM_VAULT, bob)).isEqualTo(BigInteger.TWO);

        // Later deposits to the account carry the new key.
        ledger.block(T0 + 6_000, l1(output(EURC_ADDRESS, alice, lovelace(1_200_000), token(EURC, 3))));
        assertThat(ledger.leaf(EURC_VAULT, alice).orElseThrow().holderKeyHash()).isEqualTo(next.keyHash());
        ledger.checkInvariants();
    }

    @Test
    void usedIntentsArePrunedAfterExpiryPlusSkewAndStayUnreplayable() {
        fund();
        IntentEnvelopeV1 pay = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 1));
        ledger.block(T0 + 2_000, intent(owner(alice, pay)));
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(pay)))).isPresent();
        ledger.block(EXPIRY + 5_000); // notAfter + skew not yet passed
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(pay)))).isPresent();
        ledger.block(EXPIRY + 5_001);
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(pay)))).isEmpty();
        assertThat(ledger.receipt(pay)).isPresent();
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        ledger.block(EXPIRY + 6_000, intent(owner(alice, pay)));
        assertThat(ledger.state).containsExactlyEntriesOf(before);
    }

    @Test
    void startupFailsClosedOnChangedSettingsOrMismatchedObserver() {
        fund();
        Map<String, String> changed = new HashMap<>(PaymentsHarness.settings());
        changed.put("machines.asset-payments.vaults.1.ticker", "USDX");
        changed.put("observers." + OBSERVER + ".vaults.1.asset", changed.get("machines.asset-payments.vaults.1.asset"));
        AppStateMachine restarted = PaymentsHarness.machine(changed);
        assertThatThrownBy(() -> restarted.init(ledger.writer(), null)).isInstanceOf(IllegalStateException.class);
        PaymentsHarness.machine(PaymentsHarness.settings()).init(ledger.writer(), null);

        Map<String, String> mismatch = new HashMap<>(PaymentsHarness.settings());
        mismatch.put("observers." + OBSERVER + ".domain.chain-id", "payments-9");
        assertThatThrownBy(() -> PaymentsHarness.machine(mismatch)).hasMessageContaining("domain.chain-id");
        Map<String, String> wrongType = new HashMap<>(PaymentsHarness.settings());
        wrongType.put("observers." + OBSERVER + ".type", "eutxo-vault-deposit-v1");
        assertThatThrownBy(() -> PaymentsHarness.machine(wrongType)).hasMessageContaining("type");
        // Fable P3: the observer's depth must be the ledger's, and the chain must meet it.
        Map<String, String> deeperObserver = new HashMap<>(PaymentsHarness.settings());
        deeperObserver.put("observers." + OBSERVER + ".min-stability-depth", "50");
        assertThatThrownBy(() -> PaymentsHarness.machine(deeperObserver)).hasMessageContaining("min-stability-depth");
        Map<String, String> defaultObserver = new HashMap<>(PaymentsHarness.settings());
        defaultObserver.put("machines.asset-payments.min-stability-depth", "2");
        defaultObserver.remove("observers." + OBSERVER + ".min-stability-depth"); // the observer's default is 1
        assertThatThrownBy(() -> PaymentsHarness.machine(defaultObserver)).hasMessageContaining("min-stability-depth");
        Map<String, String> deep = new HashMap<>(PaymentsHarness.settings());
        deep.put("observers." + OBSERVER + ".min-stability-depth", "50");
        deep.put("machines.asset-payments.min-stability-depth", "50");
        assertThatThrownBy(() -> PaymentsHarness.machine(deep, java.util.Optional.of(PaymentsHarness.profile(49))))
                .hasMessageContaining("l1.stability-depth");
        assertThat(PaymentsHarness.machine(deep, java.util.Optional.of(PaymentsHarness.profile(50)))).isNotNull();
        assertThatThrownBy(() -> PaymentsHarness.machine(PaymentsHarness.settings(), java.util.Optional.empty()))
                .hasMessageContaining("consensus profile");
        Map<String, String> duplicate = new HashMap<>(PaymentsHarness.settings());
        duplicate.put("machines.asset-payments.vaults.2.asset", duplicate.get("machines.asset-payments.vaults.1.asset"));
        assertThatThrownBy(() -> PaymentsHarness.machine(duplicate)).hasMessageContaining("I29");
    }

    IntentEnvelopeV1 grantIntent(byte[] sessionKey, long notAfter, SessionGrantV1.Limit... limits) {
        return env(alice, WalletAuthorizer.AUTHORIZE_SESSION, new PaymentsBodies.AuthorizeSession(sessionKey,
                List.of(AssetPaymentsContract.TRANSFER), List.of(limits), List.of(), notAfter).encode());
    }

    PaymentsRecords.AccountRecordV1 account(TestWallet wallet) {
        return ledger.get(AssetPaymentsContract.accountKey(wallet.accountId()))
                .map(PaymentsRecords.AccountRecordV1::decode).orElseThrow();
    }

    @Test
    void expiredGrantsArePrunedBeforeTheGrantLimitIsCounted() {
        fund();
        byte[] sessionSeed = filled(32, 0x55);
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(sessionSeed);
        SessionGrantV1.Limit limit = new SessionGrantV1.Limit(USDM_VAULT, BigInteger.TEN, BigInteger.TEN);
        // 15 grants expire at T0 + 50 000; the 16th lives exactly until T0 + 50 001.
        List<IntentEnvelopeV1> grants = new java.util.ArrayList<>();
        for (int i = 0; i < AssetPaymentsContract.MAX_GRANTS_PER_ACCOUNT - 1; i++) {
            grants.add(grantIntent(sessionKey, T0 + 50_000, limit));
        }
        IntentEnvelopeV1 lasting = grantIntent(sessionKey, T0 + 50_001, limit);
        grants.add(lasting);
        ledger.block(T0 + 2_000, grants.stream().map(g -> intent(owner(alice, g))).toArray(Message[]::new));
        assertThat(account(alice).grants()).hasSize(AssetPaymentsContract.MAX_GRANTS_PER_ACCOUNT);

        // One spend leaves a usage counter behind for the first (soon expired) grant.
        Hash32 firstGrant = intentId(grants.getFirst());
        IntentEnvelopeV1 spend = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER,
                new IntentEnvelopeV1.Session(firstGrant), ++nonce, EXPIRY, transferBody(USDM, bob, 3));
        ledger.block(T0 + 3_000, intent(session(sessionSeed, spend)));
        assertThat(ledger.receipt(spend).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(ledger.get(AssetPaymentsContract.usageKey(firstGrant, USDM_VAULT))).isPresent();

        // While all 16 are live, a 17th is refused and nothing is pruned.
        IntentEnvelopeV1 refused = grantIntent(sessionKey, T0 + 100_000, limit);
        ledger.block(T0 + 4_000, intent(owner(alice, refused)));
        assertThat(ledger.receipt(refused).orElseThrow().reason()).isEqualTo("TOO_MANY_GRANTS");

        // After 15 expired, a new grant takes a slot: the expired ones and their usage are deleted,
        // the one that is still valid at this block time stays.
        IntentEnvelopeV1 fresh = grantIntent(sessionKey, T0 + 100_000, limit);
        ledger.block(T0 + 50_001, intent(owner(alice, fresh)));
        assertThat(ledger.receipt(fresh).orElseThrow().outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(account(alice).grants()).containsExactly(intentId(lasting), intentId(fresh));
        for (IntentEnvelopeV1 expired : grants.subList(0, grants.size() - 1)) {
            assertThat(ledger.get(AssetPaymentsContract.grantKey(intentId(expired)))).isEmpty();
        }
        assertThat(ledger.get(AssetPaymentsContract.usageKey(firstGrant, USDM_VAULT))).isEmpty();
        assertThat(ledger.get(AssetPaymentsContract.grantKey(intentId(lasting)))).isPresent();
        assertThat(ledger.get(AssetPaymentsContract.grantKey(intentId(fresh)))).isPresent();
        ledger.checkInvariants();
    }

    @Test
    void duplicateVaultLimitsInASessionBodyAreBadBody() {
        fund();
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(filled(32, 0x66));
        byte[] distinct = new PaymentsBodies.AuthorizeSession(sessionKey, List.of(AssetPaymentsContract.TRANSFER),
                List.of(new SessionGrantV1.Limit(USDM_VAULT, BigInteger.TEN, BigInteger.TEN),
                        new SessionGrantV1.Limit(EURC_VAULT, BigInteger.TEN, BigInteger.TEN)),
                List.of(), T0 + 50_000).encode();
        byte[] duplicated = HexFormat.of().parseHex(HexFormat.of().formatHex(distinct)
                .replace(EURC_VAULT.hex(), USDM_VAULT.hex()));
        IntentEnvelopeV1 grant = env(alice, WalletAuthorizer.AUTHORIZE_SESSION, duplicated);
        ledger.block(T0 + 2_000, intent(owner(alice, grant)));
        assertThat(ledger.receipt(grant).orElseThrow().reason()).isEqualTo("BAD_BODY");
        assertThat(account(alice).grants()).isEmpty();
    }

    @Test
    void usedIntentQueueIsFifoAndAMarkerWaitsAtMostTheMaxTtlBehindALongerOne() {
        fund();
        long skew = 5_000;
        long maxTtl = 600_000;
        IntentEnvelopeV1 longLived = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER, OWNER, ++nonce,
                T0 + 2_000 + maxTtl, transferBody(USDM, bob, 1));
        ledger.block(T0 + 2_000, intent(owner(alice, longLived)));
        IntentEnvelopeV1 shortLived = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 1));
        ledger.block(T0 + 3_000, intent(owner(alice, shortLived)));
        // The short marker has expired but waits behind the head of the queue.
        ledger.block(EXPIRY + skew + 1);
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(shortLived)))).isPresent();
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(longLived)))).isPresent();
        // Still held while the head is unexpired; the documented bound is the short marker's own block
        // time + max-intent-ttl-ms + skew, by which every entry ahead of it has expired too.
        ledger.block(T0 + 2_000 + maxTtl + skew);
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(shortLived)))).isPresent();
        ledger.block(T0 + 3_000 + maxTtl + skew + 1);
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(shortLived)))).isEmpty();
        assertThat(ledger.get(AssetPaymentsContract.usedKey(intentId(longLived)))).isEmpty();
        assertThat(ledger.receipt(shortLived)).isPresent();
    }

    @Test
    void settingsRejectDepthZeroDuplicateAddressesAndUnknownOrGappedVaultKeys() {
        String m = "machines.asset-payments.";
        String o = "observers." + OBSERVER + ".";
        Map<String, String> depthZero = new HashMap<>(PaymentsHarness.settings());
        depthZero.put(m + "min-stability-depth", "0");
        depthZero.remove(o + "type"); // the ledger's own bound, not the observer cross-check
        assertThatThrownBy(() -> PaymentsHarness.machine(depthZero, java.util.Optional.of(PaymentsHarness.profile(0))))
                .hasMessageContaining("min-stability-depth");

        Map<String, String> sameAddress = new HashMap<>(PaymentsHarness.settings());
        sameAddress.put(m + "vaults.2.address", USDM_ADDRESS);
        assertThatThrownBy(() -> PaymentsHarness.machine(sameAddress)).hasMessageContaining("distinct");
        Map<String, String> sameHash = new HashMap<>(PaymentsHarness.settings());
        sameHash.put(m + "vaults.2.script-hash", HexFormat.of().formatHex(USDM_HASH));
        assertThatThrownBy(() -> PaymentsHarness.machine(sameHash)).hasMessageContaining("distinct");

        Map<String, String> gapped = new HashMap<>(PaymentsHarness.settings());
        gapped.put(m + "vaults.4.asset", POLICY + ".4142");
        gapped.put(m + "vaults.4.script-hash", "04".repeat(28));
        gapped.put(m + "vaults.4.address", enterprise(filled(28, 4)));
        assertThatThrownBy(() -> PaymentsHarness.machine(gapped)).hasMessageContaining("unknown vault setting");
        for (String key : List.of("vaults.0.tickr", "vaults.x.asset", "vaults.0", "vaults.0.ordinal")) {
            Map<String, String> unknown = new HashMap<>(PaymentsHarness.settings());
            unknown.put(m + key, "1");
            assertThatThrownBy(() -> PaymentsHarness.machine(unknown)).as(key)
                    .hasMessageContaining("unknown vault setting");
        }
        assertThat(PaymentsHarness.machine(PaymentsHarness.settings())).isNotNull();
    }

    @Test
    void admissionIsStructuralAndStateAware() {
        fund();
        IntentEnvelopeV1 pay = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 1));
        var message = appMessage(AssetPaymentsContract.INTENT_TOPIC, owner(alice, pay).encode());
        assertThat(ledger.machine.validate(message).isAccepted()).isTrue();
        assertThat(ledger.machine.validateForBlock(message, 3, ledger.writer()).isAccepted()).isTrue();
        ledger.block(T0 + 2_000, intent(owner(alice, pay)));
        assertThat(ledger.machine.validateForBlock(message, 4, ledger.writer()).reason()).isEqualTo("REPLAYED");
        var forged = appMessage(AssetPaymentsContract.INTENT_TOPIC, owner(bob, pay).encode());
        assertThat(ledger.machine.validateForBlock(forged, 4, ledger.writer()).reason()).isEqualTo("UNAUTHENTICATED");
        var junk = appMessage(AssetPaymentsContract.INTENT_TOPIC, new byte[] {1});
        assertThat(ledger.machine.validate(junk).isAccepted()).isFalse();
        var other = appMessage("x", owner(alice, pay).encode());
        assertThat(ledger.machine.validate(other).isAccepted()).isFalse();
    }

    static com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage appMessage(String topic, byte[] body) {
        return com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage.builder()
                .messageId(new byte[32]).chainId(CHAIN).topic(topic).sender(new byte[32])
                .senderSeq(1).expiresAt(1).body(body).authScheme(1).authProof(new byte[64]).build();
    }
}
