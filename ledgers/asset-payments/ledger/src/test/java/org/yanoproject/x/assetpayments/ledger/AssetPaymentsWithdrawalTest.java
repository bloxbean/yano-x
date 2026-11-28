package org.yanoproject.x.assetpayments.ledger;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.CardanoAddresses;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.framework.api.appchain.effects.EffectId;
import org.yanoproject.x.framework.api.appchain.effects.EffectIntent;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcome;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.effects.ResultPolicy;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * ADR-059 D7 claim reservation through the {@code asset-payments} state machine: feasibility (the M4 exit criterion:
 * the reservation calls {@link CardanoAddresses#payableRecipient}, so pointer limits, script, reward and foreign
 * network recipients are infeasible), the minimum ADA rules of D11, the balance move into reserved, the immutable
 * {@code ClaimLeafV1} at {@code claim/<ClaimId>} with a {@code RESERVED} status, replay, and the batch effects of D7
 * step 4, whose results never change claim status (I12).
 */
class AssetPaymentsWithdrawalTest {
    static final IntentEnvelopeV1.Owner OWNER = new IntentEnvelopeV1.Owner();
    static final long EXPIRY = T0 + 600_000;
    static final PayoutConfirmation RECORDS = new PayoutConfirmation(AssetPaymentsContract.PREFIX,
            AssetPaymentsPayoutTest.PAYOUTS, DOMAIN, 86_400_000L, id -> Optional.empty());

    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    int nonce = 1_000;

    static Map<String, String> settings(String... overrides) {
        Map<String, String> settings = new LinkedHashMap<>(AssetPaymentsPayoutTest.settings());
        for (int i = 0; i < overrides.length; i += 2) {
            settings.put("machines.asset-payments." + overrides[i], overrides[i + 1]);
        }
        return settings;
    }

    IntentEnvelopeV1 env(TestWallet wallet, String action, byte[] body) {
        return envelope(wallet.accountId(), action, OWNER, ++nonce, EXPIRY, body);
    }

    IntentEnvelopeV1 withdrawal(TestWallet wallet, Hash32 vault, byte[] recipient, long amount, long vaultLovelace) {
        return env(wallet, AssetPaymentsContract.REQUEST_WITHDRAWAL, new PaymentsBodies.RequestWithdrawal(vault,
                recipient, BigInteger.valueOf(amount), BigInteger.valueOf(vaultLovelace)).encode());
    }

    /** Registers alice and bob and credits alice 10 ADA, and 100 USDM with 1.5 ADA of min-ADA credit. */
    void fund(PaymentsHarness ledger) {
        ledger.block(T0, intent(owner(alice, env(alice, WalletAuthorizer.REGISTER_ACCOUNT,
                PaymentsBodies.REGISTER_ACCOUNT))), intent(owner(bob, env(bob, WalletAuthorizer.REGISTER_ACCOUNT,
                PaymentsBodies.REGISTER_ACCOUNT))));
        // A fresh observer per ledger, so every ledger sees the same deposit transaction ids.
        ledger.block(T0 + 1_000, new AssetPaymentsStateMachineTest().l1(
                AssetPaymentsStateMachineTest.output(ADA_ADDRESS, alice, AssetPaymentsStateMachineTest.lovelace(10_000_000)),
                AssetPaymentsStateMachineTest.output(USDM_ADDRESS, alice, AssetPaymentsStateMachineTest.lovelace(1_500_000),
                        AssetPaymentsStateMachineTest.token(USDM, 100))));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
    }

    static PaymentsRecords.ReceiptV1 receipt(PaymentsHarness ledger, IntentEnvelopeV1 envelope) {
        return ledger.receipt(envelope).orElseThrow();
    }

    static Optional<ClaimLeafV1> leaf(PaymentsHarness ledger, Hash32 claimId) {
        return ledger.get(RECORDS.claimLeafKey(claimId)).map(ClaimLeafV1::decode);
    }

    static Optional<SettlementRecords.ClaimStatusV1> status(PaymentsHarness ledger, Hash32 claimId) {
        return ledger.get(RECORDS.claimStatusKey(claimId)).map(SettlementRecords.ClaimStatusV1::decode);
    }

    static Hash32 expectedClaimId(Hash32 vault, AssetId asset, TestWallet wallet, byte[] recipient, long amount,
                                  long vaultLovelace, long sequence) {
        AssetValue value = amount == 0 ? AssetValue.EMPTY : AssetValue.single(asset, BigInteger.valueOf(amount));
        return GatewayIds.claimId(DOMAIN, vault, ClaimKind.USER_WITHDRAWAL, wallet.accountId(), recipient, value,
                BigInteger.valueOf(vaultLovelace), sequence);
    }

    static byte[] pointer(int header, byte[] keyHash, long slot, long tx, long cert) {
        return TestWallet.cat(new byte[] {(byte) header}, keyHash, CardanoAddresses.varNat(slot),
                CardanoAddresses.varNat(tx), CardanoAddresses.varNat(cert));
    }

    // ------------------------------------------------------------------ reservation

    @Test
    void aTokenWithdrawalReservesAndWritesTheImmutableLeafAndAReservedStatus() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        fund(ledger);
        IntentEnvelopeV1 request = withdrawal(alice, USDM_VAULT, alice.address(), 40, 1_200_000);
        ledger.block(T0 + 2_000, intent(owner(alice, request)));

        assertThat(receipt(ledger, request).outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        Hash32 claimId = expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 40, 1_200_000, 0);
        ClaimLeafV1 leaf = leaf(ledger, claimId).orElseThrow();
        assertThat(leaf).isEqualTo(new ClaimLeafV1(claimId, USDM_VAULT, ClaimKind.USER_WITHDRAWAL,
                AssetValue.single(USDM, BigInteger.valueOf(40)), BigInteger.valueOf(1_200_000), alice.address(),
                ledger.height, T0 + 2_000));
        assertThat(ledger.get(RECORDS.claimLeafKey(claimId)).orElseThrow())
                .as("the leaf sits at the vault script's claimKeyPrefix ‖ ClaimId (V2)")
                .isEqualTo(leaf.encode());
        assertThat(RECORDS.claimKeyPrefix()).isEqualTo("ap/claim/".getBytes());
        SettlementRecords.ClaimStatusV1 status = status(ledger, claimId).orElseThrow();
        assertThat(status.status()).isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        assertThat(status.reservedHeight()).isEqualTo(ledger.height);

        // Available moved to reserved, for the asset and the min-ADA credit (I4 holds).
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(60));
        assertThat(ledger.minAda(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(300_000));
        AssetAccounts.VaultCounters counters = ledger.counters(USDM_VAULT);
        assertThat(counters.reserved()).isEqualTo(BigInteger.valueOf(40));
        assertThat(counters.lovelaceReserved()).isEqualTo(BigInteger.valueOf(1_200_000));
        assertThat(counters.available()).isEqualTo(BigInteger.valueOf(60));
        ledger.checkInvariants();

        // The committed queries the executor reads (D9).
        SettlementQueries.ClaimView view = SettlementQueries.decodeClaim(claimId,
                ledger.query(SettlementQueries.CLAIM, claimId.bytes())).orElseThrow();
        assertThat(view.leaf()).isEqualTo(leaf);
        assertThat(view.status().status()).isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        assertThat(SettlementQueries.decodeCount(ledger.query(SettlementQueries.CLAIM_COUNT, new byte[0])))
                .isEqualTo(1);
        assertThat(ledger.query(SettlementQueries.CLAIM_BY_SEQUENCE, SettlementQueries.encodeSequence(0)))
                .isEqualTo(claimId.bytes());
        assertThat(SettlementQueries.decodeDomainStatus(ledger.query(SettlementQueries.DOMAIN_STATUS, new byte[0])))
                .isPresent();
        assertThat(SettlementQueries.decodeClaim(claimId,
                ledger.query(SettlementQueries.CLAIM, filled(32, 9)))).isEmpty();
    }

    @Test
    void adaAndCreditOnlyClaimsMeetTheMinimumAndTheLovelaceCap() {
        PaymentsHarness ledger = new PaymentsHarness(settings("payout-lovelace-cap", "1000000"));
        fund(ledger);
        IntentEnvelopeV1 smallAda = withdrawal(alice, ADA_VAULT, alice.address(), 999_999, 0);
        IntentEnvelopeV1 adaWithLovelace = withdrawal(alice, ADA_VAULT, alice.address(), 2_000_000, 1);
        IntentEnvelopeV1 smallCredit = withdrawal(alice, USDM_VAULT, alice.address(), 0, 999_999);
        IntentEnvelopeV1 overCap = withdrawal(alice, USDM_VAULT, alice.address(), 10, 1_000_001);
        IntentEnvelopeV1 ada = withdrawal(alice, ADA_VAULT, alice.address(), 1_000_000, 0);
        IntentEnvelopeV1 credit = withdrawal(alice, USDM_VAULT, alice.address(), 0, 1_200_000);
        ledger.block(T0 + 2_000, intent(owner(alice, smallAda)), intent(owner(alice, adaWithLovelace)),
                intent(owner(alice, smallCredit)), intent(owner(alice, overCap)), intent(owner(alice, ada)),
                intent(owner(alice, credit)));
        assertThat(receipt(ledger, smallAda).reason()).isEqualTo("BELOW_MINIMUM");
        assertThat(receipt(ledger, adaWithLovelace).reason()).isEqualTo("BAD_BODY");
        assertThat(receipt(ledger, smallCredit).reason()).isEqualTo("BELOW_MINIMUM");
        assertThat(receipt(ledger, overCap).reason()).isEqualTo("VAULT_LOVELACE_CAP");
        assertThat(receipt(ledger, ada).outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        assertThat(receipt(ledger, credit).outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);

        // The ADA claim carries no vaultLovelace; the credit-only claim has an empty AssetValue (D7 V5).
        ClaimLeafV1 adaLeaf = leaf(ledger, expectedClaimId(ADA_VAULT, AssetId.ADA, alice, alice.address(),
                1_000_000, 0, 0)).orElseThrow();
        assertThat(adaLeaf.value()).isEqualTo(AssetValue.lovelace(BigInteger.valueOf(1_000_000)));
        ClaimLeafV1 creditLeaf = leaf(ledger, expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 0,
                1_200_000, 1)).orElseThrow();
        assertThat(creditLeaf.value().isEmpty()).isTrue();
        assertThat(ledger.balance(ADA_VAULT, alice)).isEqualTo(BigInteger.valueOf(9_000_000));
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
        assertThat(ledger.minAda(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(300_000));
        ledger.checkInvariants();
    }

    @Test
    void insufficientFundsRejectWithoutTouchingBalances() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        fund(ledger);
        Map<String, byte[]> balances = snapshot(ledger, "ap/x/", "ap/c/", "ap/claim");
        IntentEnvelopeV1 tooMuch = withdrawal(alice, USDM_VAULT, alice.address(), 101, 0);
        IntentEnvelopeV1 creditShort = withdrawal(alice, USDM_VAULT, alice.address(), 10, 1_500_001);
        IntentEnvelopeV1 noBalance = withdrawal(bob, USDM_VAULT, bob.address(), 1, 0);
        IntentEnvelopeV1 unknownVault = withdrawal(alice, filled32(0x77), alice.address(), 1, 0);
        ledger.block(T0 + 2_000, intent(owner(alice, tooMuch)), intent(owner(alice, creditShort)),
                intent(owner(bob, noBalance)), intent(owner(alice, unknownVault)));
        assertThat(receipt(ledger, tooMuch).reason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(receipt(ledger, creditShort).reason()).isEqualTo("INSUFFICIENT_MIN_ADA_CREDIT");
        assertThat(receipt(ledger, noBalance).reason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(receipt(ledger, unknownVault).reason()).isEqualTo("UNKNOWN_VAULT");
        assertThat(snapshot(ledger, "ap/x/", "ap/c/", "ap/claim")).containsExactlyEntriesOf(balances);
        ledger.checkInvariants();
    }

    /**
     * The M4 exit criterion (r16, I19): the reservation's feasibility check is {@link CardanoAddresses#payableRecipient}
     * on the domain's network, so only a recipient the vault script can pay is ever reserved.
     */
    @Test
    void theReservationCallsPayableRecipientSoUnpayableRecipientsAreInfeasible() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        fund(ledger);
        byte[] key = alice.keyHash();
        Map<String, byte[]> unpayable = new LinkedHashMap<>();
        unpayable.put("script enterprise", TestWallet.cat(new byte[] {0x70}, key));
        unpayable.put("script base", TestWallet.cat(new byte[] {0x10}, key, key));
        unpayable.put("reward", TestWallet.cat(new byte[] {(byte) 0xe0}, key));
        unpayable.put("other network", TestWallet.cat(new byte[] {0x61}, key));
        unpayable.put("truncated enterprise", TestWallet.cat(new byte[] {0x60}, filled(27, 1)));
        unpayable.put("slot at 2^32", pointer(0x40, key, 1L << 32, 0, 0));
        unpayable.put("tx index at 2^16", pointer(0x40, key, 5, 1 << 16, 0));
        unpayable.put("cert index at 2^16", pointer(0x40, key, 5, 0, 1 << 16));
        unpayable.put("padded pointer", TestWallet.cat(new byte[] {0x40}, key, new byte[] {(byte) 0x80, 0x05, 0, 0}));
        unpayable.put("pointer trailing byte", TestWallet.cat(pointer(0x40, key, 5, 1, 2), new byte[] {0}));
        unpayable.put("script pointer", pointer(0x50, key, 5, 1, 2));
        unpayable.put("byron", TestWallet.cat(new byte[] {(byte) 0x82}, key));
        List<IntentEnvelopeV1> requests = new ArrayList<>();
        Map<String, byte[]> before = snapshot(ledger, "ap/x/", "ap/c/", "ap/claim");
        for (byte[] recipient : unpayable.values()) {
            assertThat(CardanoAddresses.payableRecipient(recipient, 0)).isFalse();
            requests.add(withdrawal(alice, USDM_VAULT, recipient, 1, 0));
        }
        ledger.block(T0 + 2_000, requests.stream().map(r -> intent(owner(alice, r))).toArray(Message[]::new));
        int i = 0;
        for (String name : unpayable.keySet()) {
            assertThat(receipt(ledger, requests.get(i++)).reason()).as(name).isEqualTo("INFEASIBLE_RECIPIENT");
        }
        assertThat(snapshot(ledger, "ap/x/", "ap/c/", "ap/claim")).containsExactlyEntriesOf(before);

        // Payable: a canonical pointer at the Conway limits, a base key address, an enterprise key address.
        byte[] canonical = pointer(0x40, key, (1L << 32) - 1, (1 << 16) - 1, (1 << 16) - 1);
        byte[] base = TestWallet.cat(new byte[] {0x00}, key, bob.keyHash());
        IntentEnvelopeV1 toPointer = withdrawal(alice, USDM_VAULT, canonical, 1, 0);
        IntentEnvelopeV1 toBase = withdrawal(alice, USDM_VAULT, base, 1, 0);
        IntentEnvelopeV1 toBob = withdrawal(alice, USDM_VAULT, bob.address(), 1, 0);
        ledger.block(T0 + 3_000, intent(owner(alice, toPointer)), intent(owner(alice, toBase)),
                intent(owner(alice, toBob)));
        for (IntentEnvelopeV1 accepted : List.of(toPointer, toBase, toBob)) {
            assertThat(receipt(ledger, accepted).outcome()).isEqualTo(PaymentsRecords.Outcome.ACCEPTED);
        }
        assertThat(leaf(ledger, expectedClaimId(USDM_VAULT, USDM, alice, canonical, 1, 0, 0))).isPresent();
        ledger.checkInvariants();
    }

    @Test
    void aReplayedWithdrawalReservesOnceAndReservationIsDeterministic() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        PaymentsHarness replica = new PaymentsHarness(settings());
        fund(ledger);
        nonce = 1_000;
        fund(replica);
        IntentEnvelopeV1 request = withdrawal(alice, USDM_VAULT, alice.address(), 25, 0);
        Message message = intent(owner(alice, request));
        ledger.block(T0 + 2_000, message, message);
        ledger.block(T0 + 3_000, message);
        replica.block(T0 + 2_000, message, message);
        replica.block(T0 + 3_000, message);

        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(75));
        assertThat(ledger.counters(USDM_VAULT).reserved()).isEqualTo(BigInteger.valueOf(25));
        assertThat(SettlementQueries.decodeCount(ledger.query(SettlementQueries.CLAIM_COUNT, new byte[0])))
                .isEqualTo(1);
        assertThat(ledger.state).as("identical inputs give identical state (I13)")
                .containsExactlyEntriesOf(replica.state);
        ledger.checkInvariants();
    }

    @Test
    void withoutAPayoutObserverWithdrawalsAreDisabled() {
        PaymentsHarness ledger = new PaymentsHarness(PaymentsHarness.settings());
        fund(ledger);
        IntentEnvelopeV1 request = withdrawal(alice, USDM_VAULT, alice.address(), 1, 0);
        ledger.block(T0 + 2_000, intent(owner(alice, request)));
        assertThat(receipt(ledger, request).reason()).isEqualTo("WITHDRAWALS_DISABLED");
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(100));
    }

    // ------------------------------------------------------------------ settlement batches (D7 step 4)

    @Test
    void reservedClaimsAreBatchedPerShardAndResultsNeverChangeClaimStatus() {
        PaymentsHarness ledger = new PaymentsHarness(settings("settlement.batch-size", "2",
                "settlement.batch-wait-ms", "10000", "settlement.min-queued-vault-lovelace", "0"), true);
        fund(ledger);
        assertThat(ledger.emitted).isEmpty();
        // Reserve claims until one shard holds two of them: that shard's batch is due by size.
        Map<Integer, List<Hash32>> byShard = new TreeMap<>();
        int shard = -1;
        long time = T0 + 2_000;
        for (int sequence = 0; shard < 0; sequence++) {
            IntentEnvelopeV1 request = withdrawal(alice, USDM_VAULT, alice.address(), 1, 0);
            ledger.block(time += 100, intent(owner(alice, request)));
            Hash32 claimId = expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 1, 0, sequence);
            assertThat(status(ledger, claimId)).isPresent();
            List<Hash32> ids = byShard.computeIfAbsent(SettlementBatchV1.shardOf(claimId), s -> new ArrayList<>());
            ids.add(claimId);
            if (ids.size() == 2) shard = SettlementBatchV1.shardOf(claimId);
        }
        assertThat(ledger.emitted).hasSize(1);
        Map.Entry<EffectId, EffectIntent> first = ledger.emitted.getFirst();
        EffectIntent intent = first.getValue();
        assertThat(intent.type()).isEqualTo(SettlementBatchV1.EFFECT_TYPE);
        assertThat(intent.gate()).isEqualTo(FinalityGate.APP_FINAL);
        assertThat(intent.result()).isEqualTo(ResultPolicy.CHAIN);
        assertThat(intent.scope()).isEqualTo(SettlementBatchV1.scope(0));
        SettlementBatchV1 batch = SettlementBatchV1.decode(intent.payload());
        assertThat(batch).isEqualTo(new SettlementBatchV1(USDM_VAULT, shard, byShard.get(shard)));

        // The other shards' single claims are due by age once batch-wait-ms has passed.
        int singles = (int) byShard.values().stream().filter(ids -> ids.size() == 1).count();
        ledger.block(time + 10_000);
        assertThat(ledger.emitted).hasSize(1 + singles);

        // A CONFIRMED result closes the batch and leaves every claim RESERVED: only the payout observer pays (I12).
        // The claims are still RESERVED in committed state, so they are queued and batched again (review P1: a
        // Settle can be included and then roll back).
        ledger.effectResult(time + 11_000, first.getKey(), intent, EffectOutcome.CONFIRMED);
        for (Hash32 claimId : batch.claimIds()) {
            assertThat(status(ledger, claimId).orElseThrow().status())
                    .isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        }
        assertThat(ledger.get(AssetPaymentsContract.settleBatchKey(0))).isEmpty();
        assertThat(ledger.emitted).hasSize(2 + singles);
        assertThat(SettlementBatchV1.decode(ledger.emitted.getLast().getValue().payload()).claimIds())
                .isEqualTo(batch.claimIds());

        // A FAILED result re-queues its still-RESERVED claims, which are batched again.
        Map.Entry<EffectId, EffectIntent> second = ledger.emitted.get(1);
        SettlementBatchV1 secondBatch = SettlementBatchV1.decode(second.getValue().payload());
        ledger.effectResult(time + 12_000, second.getKey(), second.getValue(), EffectOutcome.FAILED);
        ledger.block(time + 30_000);
        Map.Entry<EffectId, EffectIntent> rebatched = ledger.emitted.getLast();
        assertThat(SettlementBatchV1.decode(rebatched.getValue().payload()).claimIds())
                .isEqualTo(secondBatch.claimIds());
        assertThat(rebatched.getValue().scope()).isNotEqualTo(second.getValue().scope());
        for (Hash32 claimId : secondBatch.claimIds()) {
            assertThat(status(ledger, claimId).orElseThrow().status())
                    .isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        }
        ledger.checkInvariants();
    }

    /** Review P1 (rolled-back Settle): any result re-queues exactly the batch's claims still RESERVED. */
    @Test
    void anyResultRequeuesOnlyTheClaimsStillReserved() {
        PaymentsHarness ledger = new PaymentsHarness(settings("settlement.batch-wait-ms", "60000",
                "settlement.min-queued-vault-lovelace", "0"), true);
        fund(ledger);
        Map<Integer, List<Hash32>> byShard = reserveUntilAShardHolds(ledger, 2, T0 + 2_000);
        int shard = byShard.entrySet().stream().filter(e -> e.getValue().size() == 2).findFirst().orElseThrow()
                .getKey();
        List<Hash32> ids = byShard.get(shard);
        ledger.block(T0 + 70_000);
        Map.Entry<EffectId, EffectIntent> batch = ledger.emitted.stream().filter(e ->
                SettlementBatchV1.decode(e.getValue().payload()).shardIndex() == shard).findFirst().orElseThrow();
        assertThat(SettlementBatchV1.decode(batch.getValue().payload()).claimIds()).isEqualTo(ids);
        // The payout observer confirmed the first claim; the second is still RESERVED.
        ledger.writer().put(RECORDS.claimStatusKey(ids.getFirst()), status(ledger, ids.getFirst()).orElseThrow()
                .resolve(SettlementRecords.ClaimStatus.PAID, Hash32.of(filled(32, 0x44)), ledger.height).encode());
        int before = ledger.emitted.size();
        ledger.effectResult(T0 + 80_000, batch.getKey(), batch.getValue(), EffectOutcome.CONFIRMED);
        ledger.block(T0 + 150_000);
        assertThat(ledger.emitted).hasSize(before + 1);
        assertThat(SettlementBatchV1.decode(ledger.emitted.getLast().getValue().payload()).claimIds())
                .containsExactly(ids.get(1));
    }

    /** Review P2-7 (idle chain): with the default wait of 0, a claim is batched in the block that reserves it. */
    @Test
    void byDefaultAClaimIsBatchedInItsReservingBlock() {
        PaymentsHarness ledger = new PaymentsHarness(settings(), true);
        fund(ledger);
        ledger.block(T0 + 2_000, intent(owner(alice, withdrawal(alice, USDM_VAULT, alice.address(), 1, 1_300_000))));
        Hash32 claimId = expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 1, 1_300_000, 0);
        assertThat(ledger.emitted).singleElement().satisfies(entry -> {
            assertThat(entry.getKey().height()).isEqualTo(ledger.height);
            assertThat(SettlementBatchV1.decode(entry.getValue().payload()).claimIds()).containsExactly(claimId);
        });
    }

    /**
     * Re-review (Fable P3-1): a token claim whose vaultLovelace cannot carry its payout without a supplement is never
     * batched, so it stays payable by Exit and churns no effects; one that can is batched.
     */
    @Test
    void aTokenClaimTooPoorForItsMinUtxoIsNeverBatched() {
        PaymentsHarness ledger = new PaymentsHarness(settings(), true);
        fund(ledger);
        IntentEnvelopeV1 poor = withdrawal(alice, USDM_VAULT, alice.address(), 1, 0);
        ledger.block(T0 + 2_000, intent(owner(alice, poor)));
        Hash32 poorId = expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 1, 0, 0);
        assertThat(status(ledger, poorId).orElseThrow().status()).isEqualTo(SettlementRecords.ClaimStatus.RESERVED);
        assertThat(ledger.emitted).isEmpty();
        assertThat(snapshot(ledger, "ap/settle")).isEmpty();
        // A token claim whose vaultLovelace covers the payout queues.
        ledger.block(T0 + 3_000, intent(owner(alice, withdrawal(alice, USDM_VAULT, alice.address(), 2, 1_300_000))));
        assertThat(ledger.emitted).singleElement().satisfies(entry ->
                assertThat(SettlementBatchV1.decode(entry.getValue().payload()).claimIds()).containsExactly(
                        expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 2, 1_300_000, 1)));
    }

    /** Review P2-6: one shard's refused emission does not stop the other shards' batches. */
    @Test
    void aRefusedEmissionHoldsOnlyItsOwnShard() {
        PaymentsHarness ledger = new PaymentsHarness(settings("settlement.batch-wait-ms", "60000",
                "settlement.min-queued-vault-lovelace", "0"), true);
        fund(ledger);
        Map<Integer, List<Hash32>> byShard = reserveUntil(ledger, T0 + 2_000, shards -> shards.size() >= 3);
        int refused = byShard.keySet().iterator().next();
        ledger.refuse = intent -> SettlementBatchV1.decode(intent.payload()).shardIndex() == refused;
        ledger.block(T0 + 70_000);
        assertThat(ledger.emitted).extracting(e -> SettlementBatchV1.decode(e.getValue().payload()).shardIndex())
                .containsExactlyInAnyOrderElementsOf(byShard.keySet().stream().filter(s -> s != refused).toList());
        // The refused shard's queue is intact and emits once the runtime accepts it.
        ledger.refuse = intent -> false;
        ledger.block(T0 + 71_000);
        assertThat(SettlementBatchV1.decode(ledger.emitted.getLast().getValue().payload()).claimIds())
                .isEqualTo(byShard.get(refused));
    }

    /** Review P2-6: the batch cap is what the chain's effect payload limit carries, and the expiry fits the window. */
    @Test
    void theBatchCapFollowsTheEffectPayloadLimitAndTheExpiryIsChecked() {
        PaymentsHarness ledger = new PaymentsHarness(settings("settlement.batch-wait-ms", "3600000",
                "settlement.min-queued-vault-lovelace", "0"),
                PaymentsHarness.effectsProfile(SettlementBatchV1.encodedSize(2), 1_000));
        fund(ledger);
        Map<Integer, List<Hash32>> byShard = reserveUntilAShardHolds(ledger, 2, T0 + 2_000);
        assertThat(ledger.emitted).singleElement().satisfies(entry -> {
            assertThat(entry.getValue().payload()).hasSizeLessThanOrEqualTo(SettlementBatchV1.encodedSize(2));
            assertThat(byShard).containsValue(SettlementBatchV1.decode(entry.getValue().payload()).claimIds());
        });
        assertThatThrownBy(() -> new PaymentsHarness(settings("settlement.expiry-blocks", "1001"), true))
                .hasMessageContaining("effect expiry window");
        assertThatThrownBy(() -> new PaymentsHarness(settings(), PaymentsHarness.effectsProfile(70, 1_000)))
                .hasMessageContaining("cannot carry one asset.settlement.batch");
    }

    /** Reserves one-unit USDM claims, one per block, until some shard holds {@code count}; returns ids by shard. */
    Map<Integer, List<Hash32>> reserveUntilAShardHolds(PaymentsHarness ledger, int count, long from) {
        return reserveUntil(ledger, from, shards -> shards.values().stream().anyMatch(ids -> ids.size() == count));
    }

    Map<Integer, List<Hash32>> reserveUntil(PaymentsHarness ledger, long from,
                                            java.util.function.Predicate<Map<Integer, List<Hash32>>> done) {
        Map<Integer, List<Hash32>> byShard = new TreeMap<>();
        long time = from;
        long sequence = SettlementQueries.decodeCount(ledger.query(SettlementQueries.CLAIM_COUNT, new byte[0]));
        while (!done.test(byShard)) {
            ledger.block(time += 100, intent(owner(alice, withdrawal(alice, USDM_VAULT, alice.address(), 1, 0))));
            Hash32 claimId = expectedClaimId(USDM_VAULT, USDM, alice, alice.address(), 1, 0, sequence++);
            byShard.computeIfAbsent(SettlementBatchV1.shardOf(claimId), s -> new ArrayList<>()).add(claimId);
        }
        return byShard;
    }

    @Test
    void withoutEffectsNoBatchIsQueued() {
        PaymentsHarness ledger = new PaymentsHarness(settings());
        fund(ledger);
        ledger.block(T0 + 2_000, intent(owner(alice, withdrawal(alice, USDM_VAULT, alice.address(), 1, 0))));
        ledger.block(T0 + 200_000);
        assertThat(snapshot(ledger, "ap/settle")).isEmpty();
    }

    static Hash32 filled32(int value) {
        return Hash32.of(filled(32, value));
    }

    static Map<String, byte[]> snapshot(PaymentsHarness ledger, String... prefixes) {
        Map<String, byte[]> out = new TreeMap<>();
        ledger.state.forEach((key, value) -> {
            String text = new String(java.util.HexFormat.of().parseHex(key), java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String prefix : prefixes) {
                if (text.startsWith(prefix)) out.put(key, value);
            }
        });
        return out;
    }
}
