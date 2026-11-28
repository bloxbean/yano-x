package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultDepositObserverProvider;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultPayoutObserverProvider;
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * ADR-059 D10 end to end through the {@code asset-payments} state machine: the real payout observer reads a
 * thread-authenticated Settle from the parsed L1 model, the ledger confirms the reserved claim exactly once
 * (redelivery and a re-payment after a rolled-back payout are no-ops), a payout to the wrong recipient moves the
 * genesis status leaf to {@code HALTED}, and a look-alike never changes state (I2, I12, I18). Reservation is a later
 * stage, so the test writes the reserved claim the way that transition will.
 */
class AssetPaymentsPayoutTest {
    static final String PAYOUTS = "payouts";
    static final String SHARD_POLICY = "a2".repeat(28);
    static final byte[] SHARD_HASH = shardHash(SHARD_POLICY, USDM_HASH);
    static final String SHARD_ADDRESS = enterprise(SHARD_HASH);

    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final PaymentsHarness ledger = new PaymentsHarness(settings());
    final L1Observer deposits = new AssetVaultDepositObserverProvider().create(OBSERVER, observerSettings());
    final L1Observer payouts = new AssetVaultPayoutObserverProvider().create(PAYOUTS, payoutSettings());
    int txByte = 0x40;
    int nonce;

    /** The harness settings plus a payout observer over the same domain and vaults (USDM with a shard thread). */
    static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>(PaymentsHarness.settings());
        settings.put("machines.asset-payments.payout-observer-id", PAYOUTS);
        payoutSettings().forEach((key, value) -> settings.put("observers." + PAYOUTS + "." + key, value));
        settings.put("observers." + PAYOUTS + ".type", AssetVaultPayoutObserverProvider.TYPE);
        return settings;
    }

    static Map<String, String> payoutSettings() {
        Map<String, String> settings = new TreeMap<>(observerSettings());
        String[] policies = {"a1".repeat(28), SHARD_POLICY, "a3".repeat(28)};
        String[] shardHashes = {HexFormat.of().formatHex(shardHash(policies[0], ADA_HASH)),
                HexFormat.of().formatHex(SHARD_HASH), HexFormat.of().formatHex(shardHash(policies[2], EURC_HASH))};
        for (int i = 0; i < 3; i++) {
            settings.put("vaults." + i + ".shard-policy-id", policies[i]);
            settings.put("vaults." + i + ".shard-script-hash", shardHashes[i]);
            settings.put("vaults." + i + ".shard-address", enterprise(HexFormat.of().parseHex(shardHashes[i])));
        }
        settings.remove("type");
        return settings;
    }

    /** The julc shard of a vault, as the payout observer requires its shard pin to be (ADR-059 r23). */
    static byte[] shardHash(String shardPolicyHex, byte[] vaultHash) {
        return ShardBackend.JULC.derive(HexFormat.of().parseHex(shardPolicyHex), vaultHash, 0).scriptHash();
    }

    static String bech32(byte[] raw) {
        return new Address(raw).toBech32();
    }

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount token(String policy, byte[] name, long quantity) {
        String nameHex = HexFormat.of().formatHex(name);
        return Amount.builder().unit(policy + "." + nameHex).policyId(policy).assetNameBytes(name)
                .quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount usdm(long quantity) {
        return token(POLICY, USDM.assetName(), quantity);
    }

    static TransactionOutput output(String address, byte[] datum, Amount... amounts) {
        return TransactionOutput.builder().address(address).amounts(List.of(amounts))
                .inlineDatum(datum == null ? null : HexFormat.of().formatHex(datum)).build();
    }

    /** Plutus Data, definite and compact, enough for the shapes the observer reads. */
    static byte[] constr(int index, byte[]... fields) {
        return TestWallet.cat(new byte[] {(byte) 0xd8, (byte) (121 + index)}, list(fields));
    }

    static byte[] list(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | items.length);
        for (byte[] item : items) out.writeBytes(item);
        return out.toByteArray();
    }

    static byte[] bstr(byte[] value) {
        return TestWallet.cat(TestWallet.bstrHead(value.length), value);
    }

    // ------------------------------------------------------------------ chain steps

    void fund() {
        IntentEnvelopeV1Messages messages = new IntentEnvelopeV1Messages();
        ledger.block(T0, messages.register(alice), messages.register(bob));
        TransactionBody tx = TransactionBody.builder().txHash(HexFormat.of().formatHex(filled(32, txByte++)))
                .outputs(List.of(output(USDM_ADDRESS, new DepositDatumV1(alice.accountId(), alice.address(),
                        alice.keyHash()).encode(), lovelace(1_500_000), usdm(100)))).build();
        ledger.block(T0 + 1_000, deposits.observe(1_000, filled(32, 0xb1), Block.builder()
                .transactionBodies(List.of(tx)).build()).stream().map(PaymentsHarness::deposit)
                .toArray(Message[]::new));
    }

    /** Writes a reserved user withdrawal of alice's USDM as the reserving transition will (later stage). */
    ClaimLeafV1 reserve(long amount, long vaultLovelace) {
        AssetAccounts.Store store = new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) { return ledger.get(key); }
            @Override public void put(byte[] key, byte[] value) { ledger.writer().put(key, value); }
            @Override public void delete(byte[] key) { ledger.writer().delete(key); }
        };
        AssetValue value = AssetValue.single(USDM, BigInteger.valueOf(amount));
        Hash32 claimId = GatewayIds.claimId(DOMAIN, USDM_VAULT, ClaimKind.USER_WITHDRAWAL, alice.accountId(),
                alice.address(), value, BigInteger.valueOf(vaultLovelace), ++nonce);
        ClaimLeafV1 leaf = new ClaimLeafV1(claimId, USDM_VAULT, ClaimKind.USER_WITHDRAWAL, value,
                BigInteger.valueOf(vaultLovelace), alice.address(), ledger.height, T0);
        new AssetAccounts(AssetPaymentsContract.PREFIX, store).reserve(USDM_VAULT, alice.accountId(),
                BigInteger.valueOf(amount), BigInteger.valueOf(vaultLovelace));
        confirmation().writeReserved(store, leaf);
        return leaf;
    }

    PayoutConfirmation confirmation() {
        return new PayoutConfirmation(AssetPaymentsContract.PREFIX, PAYOUTS, DOMAIN, 86_400_000L,
                id -> Optional.empty());
    }

    /** A Settle of {@code leaf} paying {@code recipient}, with or without its shard thread output. */
    Message[] settle(ClaimLeafV1 leaf, byte[] recipient, boolean threaded) {
        return settle(payouts, leaf, recipient, threaded);
    }

    /** The same Settle, observed by {@code observer} (one whose vaults include USDM with this shard pin). */
    Message[] settle(L1Observer observer, ClaimLeafV1 leaf, byte[] recipient, boolean threaded) {
        int shard = leaf.claimId().bytes()[31] & 0x0f;
        List<TransactionOutput> outputs = new ArrayList<>(List.of(
                output(bech32(recipient), null, lovelace(1_700_000),
                        usdm(leaf.value().quantityOf(USDM).longValueExact())),
                output(USDM_ADDRESS, constr(1, list(bstr(leaf.claimId().bytes()))), lovelace(2_000_000))));
        if (threaded) {
            outputs.add(output(SHARD_ADDRESS, constr(0, new byte[] {1}, new byte[] {(byte) shard},
                    bstr(filled(32, 7))), lovelace(1_200_000), token(SHARD_POLICY, new byte[] {(byte) shard}, 1)));
        }
        TransactionBody tx = TransactionBody.builder().txHash(HexFormat.of().formatHex(filled(32, txByte++)))
                .outputs(outputs).mint(List.of()).build();
        byte[] settle = constr(0, list(constr(0, bstr(leaf.claimId().bytes()))), new byte[] {(byte) shard});
        Witnesses witnesses = Witnesses.builder().redeemers(List.of(Redeemer.builder().tag(RedeemerTag.Spend)
                .index(0).data(Datum.builder().cbor(HexFormat.of().formatHex(settle)).build()).build())).build();
        return observer.observe(2_000, filled(32, 0xb2), Block.builder().transactionBodies(List.of(tx))
                .transactionWitness(List.of(witnesses)).build()).stream().map(PaymentsHarness::deposit)
                .toArray(Message[]::new);
    }

    SettlementRecords.ClaimStatus status(ClaimLeafV1 leaf) {
        return confirmation().claimStatus(new ReadStore(ledger), leaf.claimId()).orElseThrow().status();
    }

    DomainStatusV1 domainStatus() {
        return ledger.get(AssetPaymentsContract.domainStatusKey()).map(DomainStatusV1::decode).orElseThrow();
    }

    record ReadStore(PaymentsHarness ledger) implements AssetAccounts.Store {
        @Override public Optional<byte[]> get(byte[] key) { return ledger.get(key); }
        @Override public void put(byte[] key, byte[] value) { throw new IllegalStateException(); }
        @Override public void delete(byte[] key) { throw new IllegalStateException(); }
    }

    /** Owner-signed REGISTER_ACCOUNT messages, as the M3 tests build them. */
    final class IntentEnvelopeV1Messages {
        Message register(TestWallet wallet) {
            return intent(owner(wallet, envelope(wallet.accountId(), WalletAuthorizer.REGISTER_ACCOUNT,
                    new org.yanoproject.x.walletauth.IntentEnvelopeV1.Owner(), ++nonce, T0 + 60_000,
                    org.yanoproject.x.assetpayments.contracts.PaymentsBodies.REGISTER_ACCOUNT)));
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void genesisWritesAnActiveStatusLeafWithTheConfiguredExitDelay() {
        ledger.block(T0);
        assertThat(domainStatus()).isEqualTo(new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 0, 0, 86_400_000L));
        Map<String, String> custom = new LinkedHashMap<>(settings());
        custom.put("machines.asset-payments.exit-delay-ms", String.valueOf(6L * 3_600_000));
        PaymentsHarness other = new PaymentsHarness(custom);
        other.block(T0);
        assertThat(other.get(AssetPaymentsContract.domainStatusKey()).map(DomainStatusV1::decode).orElseThrow()
                .exitDelayMs()).isEqualTo(6L * 3_600_000);
    }

    @Test
    void aThreadAuthenticatedSettleConfirmsTheReservedClaimExactlyOnce() {
        fund();
        ClaimLeafV1 leaf = reserve(60, 1_000_000);
        Message[] paid = settle(leaf, alice.address(), true);
        assertThat(paid).hasSize(1);

        ledger.block(T0 + 2_000, paid);
        assertThat(status(leaf)).isEqualTo(SettlementRecords.ClaimStatus.PAID);
        assertThat(ledger.counters(USDM_VAULT).paid()).isEqualTo(BigInteger.valueOf(60));
        assertThat(ledger.counters(USDM_VAULT).reserved()).isZero();
        assertThat(ledger.counters(USDM_VAULT).lovelacePaid()).isEqualTo(BigInteger.valueOf(1_000_000));
        ledger.checkInvariants();

        // Redelivery, and a re-payment after the first payout rolled back on L1: no-ops.
        AssetAccounts.VaultCounters once = ledger.counters(USDM_VAULT);
        ledger.block(T0 + 3_000, paid);
        ledger.block(T0 + 4_000, settle(leaf, alice.address(), true));
        assertThat(ledger.counters(USDM_VAULT)).isEqualTo(once);
        assertThat(status(leaf)).isEqualTo(SettlementRecords.ClaimStatus.PAID);
        assertThat(domainStatus().status()).isEqualTo(DomainStatusV1.Status.ACTIVE);
    }

    @Test
    void aLookAlikeChangesNothingAndAMismatchHaltsTheDomain() {
        fund();
        ClaimLeafV1 leaf = reserve(60, 0);
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        assertThat(settle(leaf, bob.address(), false)).isEmpty(); // no shard thread output: never observed (I2)

        ledger.block(T0 + 2_000, settle(leaf, bob.address(), true));
        assertThat(status(leaf)).isEqualTo(SettlementRecords.ClaimStatus.PAID_MISMATCH);
        DomainStatusV1 halted = domainStatus();
        assertThat(halted.status()).isEqualTo(DomainStatusV1.Status.HALTED);
        assertThat(halted.sinceHeight()).isEqualTo(ledger.height);
        assertThat(halted.exitDelayMs()).isEqualTo(86_400_000L);
        assertThat(ledger.counters(USDM_VAULT).reserved()).isEqualTo(BigInteger.valueOf(60)); // resolved by governance
        assertThat(before).isNotEqualTo(ledger.state);
        ledger.checkInvariants();
    }

    @Test
    void payoutObserverSettingsMustDescribeTheLedgersDomainAndVaults() {
        Map<String, String> wrongType = new LinkedHashMap<>(settings());
        wrongType.put("observers." + PAYOUTS + ".type", "asset-vault-deposit-v1");
        assertThatThrownBy(() -> machine(wrongType)).isInstanceOf(IllegalArgumentException.class);
        Map<String, String> otherVault = new LinkedHashMap<>(settings());
        otherVault.put("observers." + PAYOUTS + ".vaults.1.script-hash", "09".repeat(28));
        assertThatThrownBy(() -> machine(otherVault)).isInstanceOf(IllegalArgumentException.class);
        Map<String, String> sameId = new LinkedHashMap<>(settings());
        sameId.put("machines.asset-payments.payout-observer-id", OBSERVER);
        assertThatThrownBy(() -> machine(sameId)).isInstanceOf(IllegalArgumentException.class);
        Map<String, String> shortDelay = new LinkedHashMap<>(settings());
        shortDelay.put("machines.asset-payments.exit-delay-ms", "60000");
        assertThatThrownBy(() -> machine(shortDelay)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AddressProvider.getEntAddress(Credential.fromKey(alice.keyHash()), Networks.testnet())
                .getAddress()).isEqualTo(bech32(alice.address()));
    }
}
