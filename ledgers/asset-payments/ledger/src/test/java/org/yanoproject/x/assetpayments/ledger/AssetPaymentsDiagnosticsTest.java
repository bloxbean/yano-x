package org.yanoproject.x.assetpayments.ledger;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.lovelace;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.output;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.token;
import static org.yanoproject.x.assetpayments.ledger.AssetPaymentsStateMachineTest.transferBody;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/** ADR-059 D15a: the ledger's node-local diagnostics, and dropped exceptions are counted, never silent. */
class AssetPaymentsDiagnosticsTest {
    static final IntentEnvelopeV1.Owner OWNER = new IntentEnvelopeV1.Owner();
    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final PaymentsHarness ledger = new PaymentsHarness(AssetPaymentsPayoutTest.settings());
    final AssetPaymentsStateMachineTest l1 = new AssetPaymentsStateMachineTest();
    final AssetPaymentsPayoutTest settlement = new AssetPaymentsPayoutTest();
    int nonce = 9_000;

    IntentEnvelopeV1 env(TestWallet wallet, String action, byte[] body) {
        return envelope(wallet.accountId(), action, OWNER, ++nonce, T0 + 600_000, body);
    }

    @SuppressWarnings("unchecked")
    Map<String, Long> counters() {
        return (Map<String, Long>) ledger.machine.operationalStatus().get("counters");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> vault(Hash32 vault) {
        return (Map<String, Object>) ((Map<String, Object>) ledger.machine.operationalStatus().get("vaults"))
                .get(vault.hex());
    }

    @Test
    void depositsIntentsPayoutsAndBacklogAreCountedByOutcome() {
        IntentEnvelopeV1 register = env(alice, WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT);
        ledger.block(T0, intent(owner(alice, register)), intent(owner(bob, env(bob, WalletAuthorizer.REGISTER_ACCOUNT,
                PaymentsBodies.REGISTER_ACCOUNT))));
        Message[] deposits = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100)),
                output(USDM_ADDRESS, TestWallet.of(9), lovelace(1_500_000), token(USDM, 5)),
                output(EURC_ADDRESS, null, lovelace(1_500_000)));
        ledger.block(T0 + 1_000, deposits);
        ledger.block(T0 + 2_000, deposits); // redelivered
        IntentEnvelopeV1 tooMuch = env(alice, AssetPaymentsContract.TRANSFER, transferBody(USDM, bob, 1_000));
        IntentEnvelopeV1 withdraw = env(alice, AssetPaymentsContract.REQUEST_WITHDRAWAL,
                new PaymentsBodies.RequestWithdrawal(USDM_VAULT, alice.address(), BigInteger.valueOf(40),
                        BigInteger.valueOf(1_000_000)).encode());
        ledger.block(T0 + 3_000, intent(owner(alice, tooMuch)), intent(owner(alice, withdraw)),
                intent(owner(alice, register)));

        Map<String, Long> counters = counters();
        assertThat(counters).containsEntry("deposits.credited", 1L)
                .containsEntry("deposits.uncredited.UNKNOWN_BENEFICIARY", 1L)
                .containsEntry("deposits.unattributed.NOT_A_DEPOSIT", 1L)
                .containsEntry("deposits.duplicate", 3L)
                .containsEntry("intents.accepted.REGISTER_ACCOUNT", 2L)
                .containsEntry("intents.rejected.INSUFFICIENT_BALANCE", 1L)
                .containsEntry("intents.accepted.REQUEST_WITHDRAWAL", 1L)
                .containsEntry("intents.replayed", 1L);
        assertThat(vault(USDM_VAULT)).containsEntry("reserved", "40").containsEntry("reservedLovelace", "1000000")
                .containsEntry("available", "60").containsEntry("status", "ACTIVE");
        assertThat(ledger.machine.operationalStatus()).containsEntry("domainStatus", "ACTIVE");

        Hash32 claimId = Hash32.of(ledger.get(AssetPaymentsContract.claimIndexKey(0)).orElseThrow());
        ClaimLeafV1 leaf = ledger.get(AssetPaymentsWithdrawalTest.RECORDS.claimLeafKey(claimId))
                .map(ClaimLeafV1::decode).orElseThrow();
        Message[] paid = settlement.settle(leaf, alice.address(), true);
        ledger.block(T0 + 9_000, paid);
        ledger.block(T0 + 10_000, paid);
        assertThat(counters()).containsEntry("payouts.paid", 1L).containsEntry("payouts.duplicate", 1L);
        assertThat(ledger.machine.operationalStatus().get("paidLatencyMs"))
                .isEqualTo(Map.of("count", 1L, "sum", 6_000L, "max", 6_000L));
        assertThat(vault(USDM_VAULT)).containsEntry("reserved", "0");
    }

    @Test
    void aDroppedDepositOrPayoutIsCountedAndTheChainContinues() {
        ledger.block(T0, intent(owner(alice, env(alice, WalletAuthorizer.REGISTER_ACCOUNT,
                PaymentsBodies.REGISTER_ACCOUNT))));
        Message[] funded = l1.l1(output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100)));
        ledger.block(T0 + 1_000, funded);
        IntentEnvelopeV1 withdraw = env(alice, AssetPaymentsContract.REQUEST_WITHDRAWAL,
                new PaymentsBodies.RequestWithdrawal(USDM_VAULT, alice.address(), BigInteger.TEN,
                        BigInteger.valueOf(1_000_000)).encode());
        ledger.block(T0 + 2_000, intent(owner(alice, withdraw)));
        Hash32 claimId = Hash32.of(ledger.get(AssetPaymentsContract.claimIndexKey(0)).orElseThrow());
        ClaimLeafV1 leaf = ledger.get(AssetPaymentsWithdrawalTest.RECORDS.claimLeafKey(claimId))
                .map(ClaimLeafV1::decode).orElseThrow();

        // A corrupt account record makes the credit check throw; a corrupt registry entry the payout lookup.
        ledger.writer().put(AssetPaymentsContract.accountKey(alice.accountId()), new byte[] {1});
        ledger.block(T0 + 3_000, l1.l1(output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 1))));
        byte[] registry = ledger.get(AssetPaymentsContract.registryKey(USDM_VAULT)).orElseThrow();
        ledger.writer().put(AssetPaymentsContract.registryKey(USDM_VAULT), new byte[] {2});
        ledger.block(T0 + 4_000, settlement.settle(leaf, alice.address(), true));
        assertThat(counters()).containsEntry("dropped.deposit", 1L).containsEntry("dropped.payout", 1L);
        assertThat(ledger.counters(USDM_VAULT).credited()).isEqualTo(BigInteger.valueOf(100));
        assertThat(ledger.counters(USDM_VAULT).paid()).isZero();

        ledger.writer().put(AssetPaymentsContract.registryKey(USDM_VAULT), registry);
        ledger.block(T0 + 5_000, settlement.settle(leaf, alice.address(), true));
        assertThat(ledger.counters(USDM_VAULT).paid()).isEqualTo(BigInteger.TEN);
    }

    @Test
    void theVaultCountersQueryAnswersTheCommittedCountersAndHeightForReconciliation() {
        ledger.block(T0, intent(owner(alice, env(alice, WalletAuthorizer.REGISTER_ACCOUNT,
                PaymentsBodies.REGISTER_ACCOUNT))));
        ledger.block(T0 + 1_000, l1.l1(output(USDM_ADDRESS, alice, lovelace(1_500_000), token(USDM, 100))));
        var view = SettlementQueries.decodeCounters(ledger.query(SettlementQueries.VAULT_COUNTERS,
                USDM_VAULT.bytes())).orElseThrow();
        assertThat(view.height()).isEqualTo(ledger.height);
        assertThat(view.counters()).isEqualTo(ledger.counters(USDM_VAULT));
        assertThat(SettlementQueries.decodeCounters(ledger.query(SettlementQueries.VAULT_COUNTERS,
                EURC_VAULT.bytes())).orElseThrow().counters()).isEqualTo(AssetAccounts.VaultCounters.ZERO);
        assertThatThrownBy(() -> ledger.query(SettlementQueries.VAULT_COUNTERS, filled(32, 9)))
                .isInstanceOf(AppQueryException.class);
    }

    @Test
    void aReExecutedHeightReplacesItsCountsAndALaterHeightKeepsThem() {
        LedgerDiagnostics diagnostics = new LedgerDiagnostics();
        diagnostics.begin(5);
        diagnostics.count("deposits.credited");
        diagnostics.end(Map.of(), "ACTIVE");
        diagnostics.begin(5); // the same height again: a round that did not finalize, then the finalized one
        diagnostics.count("deposits.credited");
        diagnostics.paidAfter(70);
        diagnostics.end(Map.of(), "ACTIVE");
        diagnostics.begin(6);
        diagnostics.count("deposits.credited");
        assertThat(diagnostics.snapshot().get("counters")).isEqualTo(Map.of("deposits.credited", 2L));
        assertThat(diagnostics.snapshot().get("paidLatencyMs")).isEqualTo(Map.of("count", 1L, "sum", 70L,
                "max", 70L));
    }
}
