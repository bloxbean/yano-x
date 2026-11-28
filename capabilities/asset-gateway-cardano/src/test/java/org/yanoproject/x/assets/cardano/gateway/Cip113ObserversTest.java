package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableBackend;
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
import org.yanoproject.x.assets.contracts.BalanceExitObservationV1;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-061 §10 P4, observer side: the {@code asset-cip113-wiring-v1} observer, the deposit observer's raw facts for a
 * CIP-113 vault, and the payout observer's balance-exit read from the custody's own reward redeemer.
 */
class Cip113ObserversTest {
    static final byte[] PLB = filled(0x3a);
    static final byte[] H = filled(0x4b);
    static final byte[] D = filled(0xd0);
    static final byte[] T = filled(0xd2);
    static final byte[] PARAMS_POLICY = filled(0x1d);
    static final String POLICY = "7a".repeat(28);
    static final byte[] BLOCK_HASH = new byte[32];
    static final long SLOT = 1_000;

    static byte[] filled(int value) {
        byte[] out = new byte[28];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    static String vaultWallet() {
        return new Address(ProgrammableBackend.smartWallet(PLB, H, 0)).toBech32();
    }

    static Map<String, String> domain() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("domain.address-network-id", "0");
        settings.put("domain.network-magic", "42");
        settings.put("domain.chain-id", "payments-1");
        settings.put("domain.genesis-id", "6c".repeat(32));
        return settings;
    }

    // ------------------------------------------------------------------ wiring

    @Test
    void theWiringObserverReportsOnlyTheParamsTokenWithItsPosition() {
        Map<String, String> settings = domain();
        settings.put("params-policy", HexFormat.of().formatHex(PARAMS_POLICY));
        Cip113WiringObserver observer = new Cip113WiringObserver("cip113-wiring", settings);
        byte[] datum = SettlementFixtures.constr(0, credential(D), credential(filled(0xd1)), credential(T),
                credential(filled(0xd3)), credential(filled(0xc9)), SettlementFixtures.constr(1));
        TransactionOutput params = TransactionOutput.builder().address(SettlementFixtures.ADA_VAULT)
                .amounts(List.of(lovelace(2_000_000), token(HexFormat.of().formatHex(PARAMS_POLICY),
                        "ProtocolParams".getBytes(), 1))).inlineDatum(HexFormat.of().formatHex(datum)).build();
        TransactionOutput undecodable = TransactionOutput.builder().address(SettlementFixtures.ADA_VAULT)
                .amounts(List.of(lovelace(2_000_000), token(HexFormat.of().formatHex(PARAMS_POLICY),
                        "ProtocolParams".getBytes(), 1))).inlineDatum("01").build();
        TransactionOutput lookAlike = TransactionOutput.builder().address(SettlementFixtures.ADA_VAULT)
                .amounts(List.of(lovelace(2_000_000))).inlineDatum(HexFormat.of().formatHex(datum)).build();
        TransactionBody other = TransactionBody.builder().txHash("01".repeat(32)).outputs(List.of(lookAlike)).build();
        TransactionBody upgrade = TransactionBody.builder().txHash("02".repeat(32))
                .outputs(List.of(lookAlike, params)).build();
        TransactionBody broken = TransactionBody.builder().txHash("03".repeat(32)).outputs(List.of(undecodable))
                .build();
        List<L1Observation> observations = observer.observe(SLOT, BLOCK_HASH, Block.builder()
                .transactionBodies(List.of(other, upgrade, broken)).build());
        assertThat(observations).hasSize(2);
        Cip113WiringClaimV1 first = Cip113WiringClaimV1.decode(observations.get(0).claim());
        assertThat(first.outputIndex()).isEqualTo(1);
        assertThat(first.txIndex()).isEqualTo(1);
        assertThat(first.fields().orElseThrow().dispatcher()).isEqualTo(D);
        assertThat(Cip113WiringClaimV1.decode(observations.get(1).claim()).fields()).isEmpty();
    }

    // ------------------------------------------------------------------ deposits

    static Map<String, String> depositSettings() {
        Map<String, String> settings = domain();
        settings.put("vaults.0.asset", POLICY + ".5553444d");
        settings.put("vaults.0.script-hash", HexFormat.of().formatHex(H));
        settings.put("vaults.0.address", vaultWallet());
        settings.put("vaults.0.custody", "cip113");
        settings.put("vaults.0.cip113.plb-hash", HexFormat.of().formatHex(PLB));
        settings.put("vaults.0.cip113.params-policy", HexFormat.of().formatHex(PARAMS_POLICY));
        settings.put("vaults.0.cip113.exit-delegate-hash", HexFormat.of().formatHex(filled(0x5e)));
        return settings;
    }

    /** Codex P2-7, Fable P2-3: the custody backend and e are part of a CIP-113 observer's consensus identity. */
    @Test
    void theCustodyBackendAndTheExitDelegateArePartOfTheIdentity() {
        Map<String, String> settings = depositSettings();
        List<String> julc = AssetVaultDepositSettings.parse(settings).identityFields();
        assertThat(julc).contains("programmable-backend=julc")
                .anyMatch(field -> field.endsWith(".cip113.exit-delegate-hash=" + "5e".repeat(28)));
        settings.put("programmable-backend", "aiken");
        assertThat(AssetVaultDepositSettings.parse(settings).identityFields()).contains("programmable-backend=aiken")
                .isNotEqualTo(julc);
        settings.remove("vaults.0.cip113.exit-delegate-hash");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AssetVaultDepositSettings.parse(settings))
                .hasMessageContaining("exit-delegate-hash");
    }

    @Test
    void theDepositObserverReportsRawFactsForEveryVaultOutput() {
        AssetVaultDepositObserver observer = new AssetVaultDepositObserver("deposits", depositSettings());
        byte[] refund = ProgrammableBackend.keySmartWallet(PLB, filled(0xa1), 0);
        ProgrammableDepositDatumV1 datum = new ProgrammableDepositDatumV1(filled(0xa1), refund, filled(0xa1),
                Hash32.of(new byte[32]), 0, 0);
        TransactionOutput deposit = TransactionOutput.builder().address(vaultWallet()).amounts(List.of(
                lovelace(2_000_000), token(POLICY, "USDM".getBytes(), 500)))
                .inlineDatum(HexFormat.of().formatHex(datum.encode())).build();
        TransactionOutput continuation = TransactionOutput.builder().address(vaultWallet()).amounts(List.of(
                lovelace(2_000_000), token(POLICY, "USDM".getBytes(), 5))).inlineDatum("d87a8180").build();
        Map<String, BigInteger> withdrawals = new LinkedHashMap<>();
        withdrawals.put(rewardAccount(T), BigInteger.ZERO);
        withdrawals.put(rewardAccount(D), BigInteger.valueOf(7));
        TransactionBody tx = TransactionBody.builder().txHash("04".repeat(32))
                .inputs(new LinkedHashSet<>(List.of(TransactionInput.builder().transactionId("00".repeat(32)).index(0)
                        .build())))
                .referenceInputs(new LinkedHashSet<>(List.of(TransactionInput.builder().transactionId("a0".repeat(32))
                        .index(0).build())))
                .withdrawals(withdrawals).outputs(List.of(deposit, continuation)).build();
        // Reward redeemers by ledger position: D (0xd0) sorts before T (0xd2). D's is TransferAct; T's a map.
        Witnesses witnesses = Witnesses.builder().redeemers(List.of(
                reward(0, SettlementFixtures.constr(0)), reward(1, new byte[] {(byte) 0xa0}))).build();
        List<L1Observation> observations = observer.observe(SLOT, BLOCK_HASH, Block.builder()
                .transactionBodies(List.of(tx)).transactionWitness(List.of(witnesses)).build());
        assertThat(observations).hasSize(2);
        ProgrammableDepositClaimV1 claim = ProgrammableDepositClaimV1.decode(observations.getFirst().claim());
        assertThat(claim.shape()).isEqualTo(ProgrammableDepositClaimV1.Shape.DEPOSIT);
        assertThat(claim.deposit().orElseThrow().originSpent()).isTrue();
        assertThat(claim.withdrawals().orElseThrow()).containsExactly(
                new ProgrammableDepositClaimV1.Withdrawal(D, Optional.of(0L)),
                new ProgrammableDepositClaimV1.Withdrawal(T, Optional.empty()));
        assertThat(claim.referenceInputs().orElseThrow()).hasSize(1);
        assertThat(ProgrammableDepositClaimV1.decode(observations.get(1).claim()).shape())
                .isEqualTo(ProgrammableDepositClaimV1.Shape.CONTINUATION);
    }

    // ------------------------------------------------------------------ payouts

    @Test
    void aBalanceExitIsReadFromTheCustodysRewardRedeemer() {
        byte[] shardPolicy = filled(0x71);
        ShardBackend.ShardScript shard = ShardBackend.JULC.deriveProgrammable(shardPolicy, H, 0);
        Map<String, String> settings = domain();
        settings.put("vaults.0.asset", POLICY + ".5553444d");
        settings.put("vaults.0.script-hash", HexFormat.of().formatHex(H));
        settings.put("vaults.0.address", vaultWallet());
        settings.put("vaults.0.custody", "cip113");
        settings.put("vaults.0.cip113.plb-hash", HexFormat.of().formatHex(PLB));
        settings.put("vaults.0.cip113.exit-delegate-hash", HexFormat.of().formatHex(filled(0x5e)));
        settings.put("vaults.0.shard-policy-id", HexFormat.of().formatHex(shardPolicy));
        settings.put("vaults.0.shard-script-hash", HexFormat.of().formatHex(shard.scriptHash()));
        settings.put("vaults.0.shard-address", new Address(shard.address()).toBech32());
        AssetVaultPayoutObserver observer = new AssetVaultPayoutObserver("payouts", settings);
        Hash32 vaultId = GatewayIds.vaultId(GatewayIds.domainId(0, 42, "payments-1", Hash32.ofHex("6c".repeat(32)),
                "asset-payments", "anchored-claim-vault-v1", 1), org.yanoproject.x.assets.contracts.AssetId.ofHex(
                POLICY, "5553444d"), 0);
        byte[] holder = filled(0xb1);
        Hash32 escape = GatewayIds.escapeNullifier(vaultId, holder);
        int shardIndex = escape.bytes()[31] & 0x0f;
        String recipient = new Address(ProgrammableBackend.keySmartWallet(PLB, filled(0x8b), 0)).toBech32();
        List<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(TransactionOutput.builder().address(recipient).amounts(List.of(lovelace(1_200_000),
                token(POLICY, "USDM".getBytes(), 40))).build());
        outputs.add(TransactionOutput.builder().address(vaultWallet()).amounts(List.of(lovelace(20_000_000),
                token(POLICY, "USDM".getBytes(), 960))).inlineDatum(HexFormat.of().formatHex(
                SettlementFixtures.constr(1, SettlementFixtures.list(SettlementFixtures.bytes(escape.bytes())))))
                .build());
        outputs.add(TransactionOutput.builder().address(new Address(shard.address()).toBech32()).amounts(List.of(
                lovelace(2_000_000), token(HexFormat.of().formatHex(shardPolicy), new byte[] {(byte) shardIndex}, 1)))
                .inlineDatum("d87983010058200000000000000000000000000000000000000000000000000000000000000000").build());
        Map<String, BigInteger> withdrawals = new LinkedHashMap<>();
        withdrawals.put(rewardAccount(H), BigInteger.ZERO);
        withdrawals.put(rewardAccount(D), BigInteger.ZERO);
        TransactionBody tx = TransactionBody.builder().txHash("05".repeat(32)).withdrawals(withdrawals)
                .outputs(outputs).build();
        // D (0xd0) sorts after H (0x4b): H's reward redeemer is at position 0, and it names the holder.
        Witnesses witnesses = Witnesses.builder().redeemers(List.of(
                reward(0, SettlementFixtures.balanceExitRedeemer(holder, shardIndex)),
                reward(1, SettlementFixtures.constr(0)),
                spend(0, SettlementFixtures.constr(0, SettlementFixtures.integer(0), SettlementFixtures.integer(1)))))
                .build();
        List<L1Observation> observations = observer.observe(SLOT, BLOCK_HASH, Block.builder()
                .transactionBodies(List.of(tx)).transactionWitness(List.of(witnesses)).build());
        assertThat(observations).hasSize(1);
        assertThat(PayoutClaimV1.decode(observations.getFirst().claim())).isInstanceOf(BalanceExitObservationV1.class);
        // Without the custody's withdrawal, the same outputs are never read as a payout.
        TransactionBody noCustody = TransactionBody.builder().txHash("06".repeat(32)).outputs(outputs).build();
        assertThat(observer.observe(SLOT, BLOCK_HASH, Block.builder().transactionBodies(List.of(noCustody))
                .transactionWitness(List.of(witnesses)).build())).isEmpty();
    }

    // ------------------------------------------------------------------

    static String rewardAccount(byte[] scriptHash) {
        byte[] account = new byte[29];
        account[0] = (byte) 0xf0;
        System.arraycopy(scriptHash, 0, account, 1, 28);
        return HexFormat.of().formatHex(account);
    }

    static Redeemer reward(int index, byte[] data) {
        return Redeemer.builder().tag(RedeemerTag.Reward).index(index)
                .data(Datum.builder().cbor(HexFormat.of().formatHex(data)).build()).build();
    }

    static Redeemer spend(int index, byte[] data) {
        return Redeemer.builder().tag(RedeemerTag.Spend).index(index)
                .data(Datum.builder().cbor(HexFormat.of().formatHex(data)).build()).build();
    }

    static byte[] credential(byte[] hash) {
        return SettlementFixtures.constr(1, SettlementFixtures.bytes(hash));
    }

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount token(String policy, byte[] name, long quantity) {
        return Amount.builder().unit(policy + HexFormat.of().formatHex(name)).policyId(policy).assetNameBytes(name)
                .quantity(BigInteger.valueOf(quantity)).build();
    }
}
