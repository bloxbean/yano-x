package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.PayoutClaimV1.Continuation;
import org.yanoproject.x.assets.contracts.PayoutClaimV1.Payout;
import org.yanoproject.x.assets.contracts.PayoutConfirmation.Disposition;
import org.yanoproject.x.assets.contracts.PayoutConfirmation.Outcome;
import org.yanoproject.x.assets.contracts.PayoutConfirmation.Reason;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;
import org.yanoproject.x.assets.contracts.SettlementRecords.PayoutRecordV1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.contracts.DepositAdmissionTest.filled;

/**
 * ADR-059 D10 confirmation: {@code RESERVED → PAID} on matching script-enforced fields, a recorded no-op for an
 * already-paid claim (replay or re-payment after a rollback), {@code PAID_MISMATCH} plus a {@code HALTED} domain on a
 * disagreement, the whole observation validated before any write, and balance exits that never touch a claim (I2,
 * I12, I18).
 */
class PayoutConfirmationTest {
    static final Hash32 DOMAIN = DepositAdmissionTest.DOMAIN;
    static final AssetId USDM = DepositAdmissionTest.USDM;
    static final Hash32 ADA_VAULT = DepositAdmissionTest.ADA_VAULT;
    static final Hash32 USDM_VAULT = DepositAdmissionTest.USDM_VAULT;
    static final byte[] PREFIX = "ap/".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ALICE = filled(28, 0xa1);
    static final byte[] ALICE_KEY = filled(28, 0xa2);
    static final byte[] RECIPIENT = cat(new byte[] {0x60}, filled(28, 0x77));
    static final byte[] OTHER_RECIPIENT = cat(new byte[] {0x60}, filled(28, 0x78));
    static final Hash32 BLOCK = Hash32.of(filled(32, 0xb1));
    static final long DEFAULT_DELAY = 86_400_000L;

    final DepositAdmissionTest.MapStore store = new DepositAdmissionTest.MapStore();
    final Map<Hash32, PayoutConfirmation.VaultBinding> registry = new TreeMap<>(Map.of(
            ADA_VAULT, new PayoutConfirmation.VaultBinding(ADA_VAULT, AssetId.ADA),
            USDM_VAULT, new PayoutConfirmation.VaultBinding(USDM_VAULT, USDM)));
    final PayoutConfirmation confirmation = new PayoutConfirmation(PREFIX, "payouts", DOMAIN, DEFAULT_DELAY,
            id -> Optional.ofNullable(registry.get(id)));
    final AssetAccounts accounts = new AssetAccounts(PREFIX, store);
    int sequence;

    static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    /** Credits Alice, reserves a claim and writes its leaf and RESERVED status, as the reserving transition will. */
    ClaimLeafV1 reserve(Hash32 vault, ClaimKind kind, long amount, long vaultLovelace) {
        AssetId asset = vault.equals(ADA_VAULT) ? AssetId.ADA : USDM;
        AssetValue value = amount == 0 ? AssetValue.EMPTY : AssetValue.single(asset, BigInteger.valueOf(amount));
        Hash32 claimId = GatewayIds.claimId(DOMAIN, vault, kind, new byte[] {(byte) ++sequence}, RECIPIENT, value,
                BigInteger.valueOf(vaultLovelace), sequence);
        ClaimLeafV1 leaf = new ClaimLeafV1(claimId, vault, kind, value, BigInteger.valueOf(vaultLovelace), RECIPIENT,
                7, 1_000);
        if (!kind.governed()) {
            accounts.credit(vault, ALICE, ALICE_KEY, BigInteger.valueOf(amount), BigInteger.valueOf(vaultLovelace));
            accounts.reserve(vault, ALICE, BigInteger.valueOf(amount), BigInteger.valueOf(vaultLovelace));
        }
        confirmation.writeReserved(store, leaf);
        return leaf;
    }

    static Payout pays(byte[] recipient, long quantity, long lovelace) {
        return new Payout(recipient, BigInteger.valueOf(quantity), BigInteger.valueOf(lovelace));
    }

    static PayoutObservationV1 payout(Hash32 vault, int txByte, List<Hash32> ids, List<Payout> payouts) {
        return new PayoutObservationV1(DOMAIN, vault, Hash32.of(filled(32, txByte)), 900, BLOCK, 3, ids.size() + 1,
                PayoutObservationV1.Source.CONTINUATION, ids, payouts, Optional.of(new Continuation(ids.size(),
                BigInteger.valueOf(50), BigInteger.valueOf(2_000_000), 0, Hash32.of(filled(32, 0xcc)))));
    }

    static PayoutConfirmation.ObservedPayout observed(PayoutClaimV1 claim) {
        return new PayoutConfirmation.ObservedPayout("payouts", claim.txId().bytes(), claim.shardOutputIndex(),
                claim.slot(), claim.blockHash().bytes(), claim.encode());
    }

    PayoutConfirmation.Result apply(PayoutClaimV1 claim) {
        return confirmation.apply(store, observed(claim), 20, 5_000_000);
    }

    ClaimStatus status(Hash32 claimId) {
        return confirmation.claimStatus(store, claimId).orElseThrow().status();
    }

    Optional<DomainStatusV1> domain() {
        return confirmation.domainStatus(store);
    }

    @Test
    void aMatchingTokenPayoutMarksTheClaimPaidAndMovesReservedToPaidByTheClaimAmount() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 100, 1_500_000);
        // The builder supplemented the min-ADA: the output carries more lovelace than vaultLovelace.
        PayoutObservationV1 observation = payout(USDM_VAULT, 0x51, List.of(leaf.claimId()),
                List.of(pays(RECIPIENT, 100, 1_800_000)));

        PayoutConfirmation.Result result = apply(observation);

        assertThat(result.disposition()).isEqualTo(Disposition.RECORDED);
        assertThat(result.halted()).isFalse();
        assertThat(result.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.outcome()).isEqualTo(Outcome.PAID);
            assertThat(claim.reason()).isEqualTo(Reason.NONE);
        });
        assertThat(status(leaf.claimId())).isEqualTo(ClaimStatus.PAID);
        assertThat(confirmation.claimStatus(store, leaf.claimId()).orElseThrow().resolvedTxId())
                .contains(observation.txId());
        AssetAccounts.VaultCounters counters = accounts.counters(USDM_VAULT);
        assertThat(counters.paid()).isEqualTo(BigInteger.valueOf(100));
        assertThat(counters.reserved()).isZero();
        assertThat(counters.lovelacePaid()).isEqualTo(BigInteger.valueOf(1_500_000)); // never the output's lovelace
        assertThat(counters.lovelaceReserved()).isZero();
        accounts.checkInvariant(USDM_VAULT);
        assertThat(store.get(confirmation.payoutRecordKey(observation.txId(), leaf.claimId()))
                .map(PayoutRecordV1::decode)).contains(new PayoutRecordV1(Outcome.PAID, Reason.NONE, 20));
        assertThat(domain()).isEmpty();
    }

    @Test
    void anAdaPayoutMayCarryMoreLovelaceButPaidGrowsByTheClaim() {
        ClaimLeafV1 leaf = reserve(ADA_VAULT, ClaimKind.USER_WITHDRAWAL, 5_000_000, 0);
        PayoutConfirmation.Result result = apply(payout(ADA_VAULT, 0x52, List.of(leaf.claimId()),
                List.of(pays(RECIPIENT, 5_200_000, 5_200_000))));
        assertThat(result.claims()).singleElement().extracting(PayoutConfirmation.ClaimResult::outcome)
                .isEqualTo(Outcome.PAID);
        assertThat(accounts.counters(ADA_VAULT).paid()).isEqualTo(BigInteger.valueOf(5_000_000));
        accounts.checkInvariant(ADA_VAULT);
    }

    @Test
    void aRedeliveredObservationIsADuplicateAndWritesNothing() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        PayoutObservationV1 observation = payout(USDM_VAULT, 0x53, List.of(leaf.claimId()),
                List.of(pays(RECIPIENT, 10, 1_000_000)));
        apply(observation);
        TreeMap<String, byte[]> before = new TreeMap<>(store.values);

        PayoutConfirmation.Result again = apply(observation);

        assertThat(again.disposition()).isEqualTo(Disposition.DUPLICATE);
        assertThat(store.values.keySet()).isEqualTo(before.keySet());
    }

    @Test
    void aRePaymentAfterARolledBackPayoutIsARecordedNoOp() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        apply(payout(USDM_VAULT, 0x54, List.of(leaf.claimId()), List.of(pays(RECIPIENT, 10, 1_000_000))));
        AssetAccounts.VaultCounters paidOnce = accounts.counters(USDM_VAULT);

        // L1 rolled the first payout back; anyone paid the immutable, anchored leaf again in another transaction.
        PayoutObservationV1 second = payout(USDM_VAULT, 0x55, List.of(leaf.claimId()),
                List.of(pays(RECIPIENT, 10, 1_100_000)));
        PayoutConfirmation.Result result = apply(second);

        assertThat(result.disposition()).isEqualTo(Disposition.RECORDED);
        assertThat(result.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.outcome()).isEqualTo(Outcome.NO_OP);
            assertThat(claim.reason()).isEqualTo(Reason.ALREADY_PAID);
        });
        assertThat(accounts.counters(USDM_VAULT)).isEqualTo(paidOnce);
        assertThat(status(leaf.claimId())).isEqualTo(ClaimStatus.PAID);
        assertThat(store.get(confirmation.payoutRecordKey(second.txId(), leaf.claimId()))).isPresent();
        assertThat(domain()).isEmpty();
        assertThat(apply(second).disposition()).isEqualTo(Disposition.DUPLICATE);
    }

    @Test
    void aWrongRecipientIsPaidMismatchAndHaltsTheDomainWithoutTouchingCounters() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        AssetAccounts.VaultCounters before = accounts.counters(USDM_VAULT);

        PayoutConfirmation.Result result = apply(payout(USDM_VAULT, 0x56, List.of(leaf.claimId()),
                List.of(pays(OTHER_RECIPIENT, 10, 1_000_000))));

        assertThat(result.halted()).isTrue();
        assertThat(result.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.outcome()).isEqualTo(Outcome.PAID_MISMATCH);
            assertThat(claim.reason()).isEqualTo(Reason.RECIPIENT);
        });
        assertThat(status(leaf.claimId())).isEqualTo(ClaimStatus.PAID_MISMATCH);
        assertThat(accounts.counters(USDM_VAULT)).isEqualTo(before); // the reserved value stays reserved (D15)
        assertThat(domain()).contains(new DomainStatusV1(DomainStatusV1.Status.HALTED, 20, 5_000_000, DEFAULT_DELAY));
    }

    @Test
    void everyScriptEnforcedFieldIsCompared() {
        ClaimLeafV1 quantity = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 1_000_000);
        ClaimLeafV1 lovelace = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 1_000_000);
        ClaimLeafV1 ada = reserve(ADA_VAULT, ClaimKind.USER_WITHDRAWAL, 3_000_000, 0);
        ClaimLeafV1 otherVault = reserve(ADA_VAULT, ClaimKind.USER_WITHDRAWAL, 3_000_000, 0);

        assertThat(apply(payout(USDM_VAULT, 0x57, List.of(quantity.claimId()),
                List.of(pays(RECIPIENT, 11, 1_000_000)))).claims().getFirst().reason()).isEqualTo(Reason.QUANTITY);
        assertThat(apply(payout(USDM_VAULT, 0x58, List.of(lovelace.claimId()),
                List.of(pays(RECIPIENT, 10, 999_999)))).claims().getFirst().reason()).isEqualTo(Reason.LOVELACE);
        assertThat(apply(payout(ADA_VAULT, 0x59, List.of(ada.claimId()),
                List.of(pays(RECIPIENT, 2_999_999, 2_999_999)))).claims().getFirst().reason())
                .isEqualTo(Reason.LOVELACE);
        assertThat(apply(payout(USDM_VAULT, 0x5a, List.of(otherVault.claimId()),
                List.of(pays(RECIPIENT, 3_000_000, 3_000_000)))).claims().getFirst().reason())
                .isEqualTo(Reason.WRONG_VAULT);
        assertThat(domain().orElseThrow().status()).isEqualTo(DomainStatusV1.Status.HALTED);
    }

    @Test
    void aThreadAuthenticatedPayoutOfAnUnknownClaimHalts() {
        Hash32 unknown = Hash32.of(filled(32, 0x3c));
        PayoutConfirmation.Result result = apply(payout(USDM_VAULT, 0x5b, List.of(unknown),
                List.of(pays(RECIPIENT, 1, 1_000_000))));
        assertThat(result.halted()).isTrue();
        assertThat(result.claims().getFirst().reason()).isEqualTo(Reason.UNKNOWN_CLAIM);
        assertThat(confirmation.claimStatus(store, unknown)).isEmpty();
    }

    @Test
    void aBatchConfirmsItsMatchingClaimsAndHaltsOnItsMismatch() {
        ClaimLeafV1 good = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        ClaimLeafV1 bad = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 20, 0);
        PayoutConfirmation.Result result = apply(payout(USDM_VAULT, 0x5c, List.of(good.claimId(), bad.claimId()),
                List.of(pays(RECIPIENT, 10, 1_000_000), pays(RECIPIENT, 21, 1_000_000))));
        assertThat(result.claims()).extracting(PayoutConfirmation.ClaimResult::outcome)
                .containsExactly(Outcome.PAID, Outcome.PAID_MISMATCH);
        assertThat(result.halted()).isTrue();
        assertThat(accounts.counters(USDM_VAULT).paid()).isEqualTo(BigInteger.TEN);
        assertThat(accounts.counters(USDM_VAULT).reserved()).isEqualTo(BigInteger.valueOf(20));
        accounts.checkInvariant(USDM_VAULT);
    }

    @Test
    void aHaltKeepsTheStatusLeafsExitDelayAndAHaltedDomainStaysHalted() {
        store.put(confirmation.domainStatusKey(),
                new DomainStatusV1(DomainStatusV1.Status.PAUSED, 3, 100, 21_600_000).encode());
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        apply(payout(USDM_VAULT, 0x5d, List.of(leaf.claimId()), List.of(pays(OTHER_RECIPIENT, 10, 1))));
        DomainStatusV1 halted = domain().orElseThrow();
        assertThat(halted).isEqualTo(new DomainStatusV1(DomainStatusV1.Status.HALTED, 20, 5_000_000, 21_600_000));

        ClaimLeafV1 next = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        confirmation.apply(store, observed(payout(USDM_VAULT, 0x5e, List.of(next.claimId()),
                List.of(pays(OTHER_RECIPIENT, 10, 1)))), 30, 9_000_000);
        assertThat(domain()).contains(halted); // since-height and since-ms of the first halt
    }

    @Test
    void reservedCountersThatCannotCoverThePayoutHaltInsteadOfThrowing() {
        Hash32 claimId = GatewayIds.claimId(DOMAIN, USDM_VAULT, ClaimKind.USER_WITHDRAWAL, new byte[] {9}, RECIPIENT,
                AssetValue.single(USDM, BigInteger.TEN), BigInteger.ZERO, 9);
        confirmation.writeReserved(store, new ClaimLeafV1(claimId, USDM_VAULT, ClaimKind.USER_WITHDRAWAL,
                AssetValue.single(USDM, BigInteger.TEN), BigInteger.ZERO, RECIPIENT, 1, 1)); // no reservation

        PayoutConfirmation.Result result = apply(payout(USDM_VAULT, 0x5f, List.of(claimId),
                List.of(pays(RECIPIENT, 10, 1_000_000))));

        assertThat(result.claims().getFirst().reason()).isEqualTo(Reason.LEDGER_INCONSISTENT);
        assertThat(result.halted()).isTrue();
        assertThat(accounts.counters(USDM_VAULT)).isEqualTo(AssetAccounts.VaultCounters.ZERO);
    }

    @Test
    void aSweepIsConfirmedFromItsSweepPaidClaimAndHandsTheGovernedClaimToTheConsumer() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.SWEEP_UNATTRIBUTED, 7, 1_200_000);
        PayoutObservationV1 sweep = new PayoutObservationV1(DOMAIN, USDM_VAULT, Hash32.of(filled(32, 0x60)), 900,
                BLOCK, 3, 1, PayoutObservationV1.Source.SWEEP_PAID, List.of(leaf.claimId()),
                List.of(pays(RECIPIENT, 7, 1_400_000)), Optional.empty());

        PayoutConfirmation.Result result = apply(sweep);

        assertThat(result.claims().getFirst().outcome()).isEqualTo(Outcome.PAID);
        assertThat(result.governed()).singleElement().extracting(PayoutConfirmation.GovernedPayout::leaf)
                .isEqualTo(leaf);
        assertThat(accounts.counters(USDM_VAULT)).isEqualTo(AssetAccounts.VaultCounters.ZERO);
    }

    @Test
    void envelopesThatDoNotMatchAreIgnoredAndWriteNothing() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        PayoutObservationV1 claim = payout(USDM_VAULT, 0x61, List.of(leaf.claimId()),
                List.of(pays(RECIPIENT, 10, 1_000_000)));
        TreeMap<String, byte[]> before = new TreeMap<>(store.values);
        PayoutConfirmation.ObservedPayout good = observed(claim);
        List<PayoutConfirmation.ObservedPayout> bad = List.of(
                new PayoutConfirmation.ObservedPayout("other", good.anchorTxId(), good.eventOrdinal(), good.slot(),
                        good.blockHash(), good.claim()),
                new PayoutConfirmation.ObservedPayout("payouts", filled(32, 1), good.eventOrdinal(), good.slot(),
                        good.blockHash(), good.claim()),
                new PayoutConfirmation.ObservedPayout("payouts", good.anchorTxId(), 0, good.slot(), good.blockHash(),
                        good.claim()),
                new PayoutConfirmation.ObservedPayout("payouts", good.anchorTxId(), good.eventOrdinal(), 901,
                        good.blockHash(), good.claim()),
                new PayoutConfirmation.ObservedPayout("payouts", good.anchorTxId(), good.eventOrdinal(), good.slot(),
                        filled(32, 2), good.claim()),
                new PayoutConfirmation.ObservedPayout("payouts", good.anchorTxId(), good.eventOrdinal(), good.slot(),
                        good.blockHash(), new byte[] {(byte) 0x80}),
                observed(new PayoutObservationV1(Hash32.of(filled(32, 9)), USDM_VAULT, claim.txId(), 900, BLOCK, 3,
                        2, PayoutObservationV1.Source.CONTINUATION, claim.claimIds(), claim.payouts(),
                        claim.continuation())),
                observed(new PayoutObservationV1(DOMAIN, Hash32.of(filled(32, 8)), claim.txId(), 900, BLOCK, 3, 2,
                        PayoutObservationV1.Source.CONTINUATION, claim.claimIds(), claim.payouts(),
                        claim.continuation())));
        for (PayoutConfirmation.ObservedPayout observed : bad) {
            assertThat(confirmation.apply(store, observed, 20, 1).disposition()).isEqualTo(Disposition.IGNORED);
        }
        assertThat(confirmation.apply(store, null, 20, 1).disposition()).isEqualTo(Disposition.IGNORED);
        assertThat(store.values.keySet()).isEqualTo(before.keySet());
        assertThat(status(leaf.claimId())).isEqualTo(ClaimStatus.RESERVED);
    }

    @Test
    void aBalanceExitRecordsItsEscapeNullifierAndNeverTouchesAClaim() {
        ClaimLeafV1 leaf = reserve(USDM_VAULT, ClaimKind.USER_WITHDRAWAL, 10, 0);
        Hash32 nullifier = GatewayIds.escapeNullifier(USDM_VAULT, ALICE);
        BalanceExitObservationV1 exit = new BalanceExitObservationV1(DOMAIN, USDM_VAULT, Hash32.of(filled(32, 0x62)),
                900, BLOCK, nullifier.bytes()[31] & 0x0f, 2, nullifier, ALICE, pays(RECIPIENT, 40, 2_000_000),
                new Continuation(1, BigInteger.valueOf(5), BigInteger.valueOf(3_000_000), 0,
                        Hash32.of(filled(32, 0xcd))));
        AssetAccounts.VaultCounters counters = accounts.counters(USDM_VAULT);

        assertThat(apply(exit).disposition()).isEqualTo(Disposition.RECORDED);
        SettlementRecords.EscapeRedemptionV1 record = store.get(confirmation.escapeKey(nullifier))
                .map(SettlementRecords.EscapeRedemptionV1::decode).orElseThrow();
        assertThat(record.holderId()).isEqualTo(ALICE);
        assertThat(record.txId()).isEqualTo(exit.txId());
        assertThat(status(leaf.claimId())).isEqualTo(ClaimStatus.RESERVED);
        assertThat(accounts.counters(USDM_VAULT)).isEqualTo(counters);
        assertThat(apply(exit).disposition()).isEqualTo(Disposition.DUPLICATE);

        BalanceExitObservationV1 forged = new BalanceExitObservationV1(DOMAIN, USDM_VAULT,
                Hash32.of(filled(32, 0x63)), 900, BLOCK, 1, 2, Hash32.of(filled(32, 0x11)), ALICE,
                pays(RECIPIENT, 40, 2_000_000), exit.continuation());
        assertThat(apply(forged).disposition()).isEqualTo(Disposition.IGNORED);
        assertThat(domain()).isEmpty();
    }

    @Test
    void claimCodecsRoundTripAndRejectInvalidShapes() {
        PayoutObservationV1 payout = payout(USDM_VAULT, 0x64, List.of(Hash32.of(filled(32, 1)),
                Hash32.of(filled(32, 2))), List.of(pays(RECIPIENT, 1, 2), pays(OTHER_RECIPIENT, 3, 4)));
        assertThat(PayoutClaimV1.decode(payout.encode())).isEqualTo(payout);
        assertThat(PayoutObservationV1.decode(payout.encode())).isEqualTo(payout);
        byte[] trailing = cat(payout.encode(), new byte[] {0});
        assertThatThrownBy(() -> PayoutClaimV1.decode(trailing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayoutObservationV1(DOMAIN, USDM_VAULT, payout.txId(), 1, BLOCK, 1, 1,
                PayoutObservationV1.Source.SWEEP_PAID, List.of(Hash32.of(filled(32, 1))),
                List.of(pays(RECIPIENT, 1, 1)), payout.continuation()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayoutObservationV1(DOMAIN, USDM_VAULT, payout.txId(), 1, BLOCK, 1, 1,
                PayoutObservationV1.Source.CONTINUATION, List.of(Hash32.of(filled(32, 1)), Hash32.of(filled(32, 1))),
                List.of(pays(RECIPIENT, 1, 1), pays(RECIPIENT, 1, 1)), payout.continuation()))
                .isInstanceOf(IllegalArgumentException.class);

        SettlementRecords.ClaimStatusV1 status = SettlementRecords.ClaimStatusV1.reserved(Hash32.of(filled(32, 5)), 4)
                .resolve(ClaimStatus.PAID, Hash32.of(filled(32, 6)), 9);
        assertThat(SettlementRecords.ClaimStatusV1.decode(status.encode())).isEqualTo(status);

        AnchorObservationV1 anchor = new AnchorObservationV1(Hash32.of(filled(32, 7)), 0, 10, BLOCK, true,
                Optional.of(new AnchorObservationV1.Anchored(12, Hash32.of(filled(32, 8)), Hash32.of(filled(32, 9)),
                        1_000, 1, List.of(Hash32.of(filled(32, 1)), Hash32.of(filled(32, 2))))));
        assertThat(AnchorObservationV1.decode(anchor.encode())).isEqualTo(anchor);
        AnchorObservationV1 unaccepted = new AnchorObservationV1(Hash32.of(filled(32, 7)), 0, 10, BLOCK, false,
                Optional.empty());
        assertThat(AnchorObservationV1.decode(unaccepted.encode())).isEqualTo(unaccepted);
        assertThatThrownBy(() -> new AnchorObservationV1.Anchored(1, BLOCK, BLOCK, 1, 1,
                List.of(Hash32.of(filled(32, 2)), Hash32.of(filled(32, 1)))))
                .isInstanceOf(IllegalArgumentException.class);

        List<Long> outputs = new java.util.ArrayList<>();
        for (long i = 0; i < 16; i++) outputs.add(i);
        ShardBootstrapObservationV1 bootstrap = new ShardBootstrapObservationV1(DOMAIN, USDM_VAULT,
                Hash32.of(filled(32, 3)), 1, BLOCK, ShardBootstrapObservationV1.Reason.NONE, outputs);
        assertThat(ShardBootstrapObservationV1.decode(bootstrap.encode())).isEqualTo(bootstrap);
        assertThatThrownBy(() -> new ShardBootstrapObservationV1(DOMAIN, USDM_VAULT, Hash32.of(filled(32, 3)), 1,
                BLOCK, ShardBootstrapObservationV1.Reason.WRONG_ADDRESS, outputs))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
