package org.yanoproject.x.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.yanoproject.api.appchain.l1view.L1Observation;
import org.yanoproject.x.eutxo.contracts.EutxoDepositClaim;
import org.yanoproject.x.eutxo.contracts.EutxoL2KeyBinding;
import org.yanoproject.x.eutxo.contracts.EutxoOutpoint;
import org.yanoproject.x.eutxo.contracts.EutxoVaultDatum;
import org.yanoproject.x.eutxo.contracts.EutxoBatchSettlementMarker;
import org.yanoproject.x.eutxo.contracts.EutxoSettlementDatum;
import org.yanoproject.x.eutxo.contracts.EutxoWithdrawalConfirmation;
import org.yanoproject.x.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptedVaultDepositObserverTest {
    private static final byte[] VAULT_HASH = fill(28, 7);
    private static final String VAULT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromScript(VAULT_HASH), Networks.testnet()).getAddress();
    private static final String OWNER = EutxoTestWallet.fromSeed(fill(32, 2)).address();

    @Test
    void exactAcceptedVaultOutputProducesOneCanonicalClaim() {
        EutxoVaultDatum datum = datum();
        AcceptedVaultDepositObserver observer = observer();
        Block block = block(List.of(output(VAULT_ADDRESS, 50, datum.encode())));

        List<L1Observation> observations = observer.observe(100, fill(32, 9), block);

        assertThat(observations).singleElement().satisfies(observation -> {
            EutxoDepositClaim claim = EutxoDepositClaim.decode(observation.claim());
            assertThat(claim.chainId()).isEqualTo("payments-eutxo");
            assertThat(claim.acceptedOutpoint())
                    .isEqualTo(new EutxoOutpoint("11".repeat(32), 0));
            assertThat(claim.stagingOutpoint()).isEqualTo(datum.stagingOutpoint());
            assertThat(claim.l2Address()).isEqualTo(OWNER);
            assertThat(claim.mirroredOutpoint()).isEqualTo(
                    EutxoDepositClaim.decode(claim.encode()).mirroredOutpoint());
        });
    }

    @Test
    void stagingOutputsAreIgnoredAndAmbiguousVaultOutputsCreditNothing() {
        AcceptedVaultDepositObserver observer = observer();
        EutxoVaultDatum datum = datum();
        assertThat(observer.observe(
                100,
                fill(32, 9),
                block(List.of(output(OWNER, 50, datum.encode())))))
                .isEmpty();
        assertThat(observer.observe(
                100,
                fill(32, 9),
                block(List.of(
                        output(VAULT_ADDRESS, 20, datum.encode()),
                        output(VAULT_ADDRESS, 30, datum.encode())))))
                .isEmpty();
    }

    @Test
    void acceptanceBuilderPreservesTheFullDepositAndPaysFeesExternally() {
        EutxoVaultDatum datum = datum();
        EutxoOutpoint feeOutpoint = new EutxoOutpoint("22".repeat(32), 1);
        com.bloxbean.cardano.client.transaction.spec.TransactionBody body =
                DepositAcceptanceBuilder.build(
                        datum.stagingOutpoint(),
                        BigInteger.valueOf(100),
                        feeOutpoint,
                        BigInteger.valueOf(12),
                        BigInteger.valueOf(2),
                        VAULT_ADDRESS,
                        OWNER,
                        datum,
                        500);

        assertThat(body.getInputs()).hasSize(2);
        assertThat(body.getInputs().getFirst().getTransactionId())
                .isEqualTo(datum.stagingOutpoint().transactionId());
        assertThat(body.getInputs().get(1).getTransactionId())
                .isEqualTo(feeOutpoint.transactionId());
        assertThat(body.getOutputs()).hasSize(2);
        assertThat(body.getOutputs().getFirst()).satisfies(output -> {
                    assertThat(output.getAddress()).isEqualTo(VAULT_ADDRESS);
                    assertThat(output.getValue().getCoin()).isEqualTo(BigInteger.valueOf(100));
                    assertThat(EutxoVaultDatum.decode(output.getInlineDatum().serializeToBytes()))
                            .isEqualTo(datum);
                });
        assertThat(body.getOutputs().get(1).getValue().getCoin())
                .isEqualTo(BigInteger.TEN);
        assertThat(body.getTtl()).isEqualTo(999);
    }

    @Test
    void rollbackBelowACreditedDepositRequiresAHalt() {
        assertThat(BridgeRollbackGuard.assess(90, 100).halt()).isTrue();
        assertThat(BridgeRollbackGuard.assess(100, 100).halt()).isFalse();
    }

    @Test
    void settlementMarkerProducesAnExactWithdrawalConfirmation() {
        EutxoSettlementDatum settlement =
                EutxoSettlementDatum.forAddress(
                1,
                "payments-eutxo",
                3,
                "55".repeat(32),
                OWNER,
                BigInteger.valueOf(20));
        WithdrawalConfirmationObserver observer =
                new WithdrawalConfirmationObserver("bridge-withdrawals", Map.of(
                        "chain-id", "payments-eutxo",
                        "bridge-epoch", "3",
                        "vault-address", VAULT_ADDRESS));
        Block settlementBlock = block(List.of(
                output(OWNER, 20, null),
                output(VAULT_ADDRESS, 30, settlement.encode())));

        assertThat(observer.observe(101, fill(32, 8), settlementBlock))
                .singleElement()
                .satisfies(observation -> {
                    EutxoWithdrawalConfirmation confirmation =
                            EutxoWithdrawalConfirmation.decode(observation.claim());
                    assertThat(confirmation.claimId()).isEqualTo("55".repeat(32));
                    assertThat(confirmation.destinationAddress()).isEqualTo(OWNER);
                    assertThat(confirmation.lovelace()).isEqualTo(BigInteger.valueOf(20));
                    assertThat(confirmation.continuingVaultLovelace())
                            .isEqualTo(BigInteger.valueOf(30));
                });
    }

    @Test
    void settlementWithoutExactPayoutIsSkippedByBothObservers() {
        EutxoSettlementDatum settlement =
                EutxoSettlementDatum.forAddress(
                1,
                "payments-eutxo",
                3,
                "55".repeat(32),
                OWNER,
                BigInteger.valueOf(20));
        WithdrawalConfirmationObserver withdrawalObserver =
                new WithdrawalConfirmationObserver("bridge-withdrawals", Map.of(
                        "chain-id", "payments-eutxo",
                        "bridge-epoch", "3",
                        "vault-address", VAULT_ADDRESS));
        Block mismatched = block(List.of(
                output(OWNER, 19, null),
                output(VAULT_ADDRESS, 30, settlement.encode())));

        assertThat(withdrawalObserver.observe(101, fill(32, 8), mismatched)).isEmpty();
        assertThat(observer().observe(101, fill(32, 8), mismatched)).isEmpty();
    }

    @Test
    void batchSettlementMarkerOnTheContinuingVaultOutputIsNotADeposit() {
        // Before the fix the first batched settlement halted L1 observation for good (#26).
        EutxoBatchSettlementMarker marker = new EutxoBatchSettlementMarker(1, List.of("66".repeat(32)));
        Block settlement = block(List.of(
                output(OWNER, 10, null),
                output(VAULT_ADDRESS, 20, marker.encode())));

        assertThat(observer().observe(102, fill(32, 9), settlement)).isEmpty();
        assertThat(observer().observe(103, fill(32, 10), block(List.of(
                output(VAULT_ADDRESS, 20, new byte[]{0x00}))))).isEmpty();
    }

    @Test
    void anythingPaidToTheVaultThatIsNotThisChainsDepositOrSettlementIsSkippedWithoutHidingGenuineOnes() {
        // Anyone can pay the public vault address with any value and datum. A throw on such an output made the
        // host retry the block forever, stopping all L1 observation for the chain; each one must be skipped
        // while genuine deposits and settlements in the same block are still observed.
        EutxoVaultDatum otherChain = new EutxoVaultDatum(EutxoVaultDatum.ABI_VERSION, "another-chain", OWNER,
                fill(32, 3), new EutxoOutpoint("44".repeat(32), 1), 1_000);
        EutxoSettlementDatum foreignEpoch = EutxoSettlementDatum.forAddress(
                1, "payments-eutxo", 4, "56".repeat(32), OWNER, BigInteger.valueOf(20));
        EutxoSettlementDatum genuineSettlement = EutxoSettlementDatum.forAddress(
                1, "payments-eutxo", 3, "55".repeat(32), OWNER, BigInteger.valueOf(20));
        TransactionOutput withToken = TransactionOutput.builder()
                .address(VAULT_ADDRESS)
                .amounts(List.of(
                        Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(50)).build(),
                        Amount.builder().unit("ab".repeat(28) + "01").quantity(BigInteger.ONE).build()))
                .inlineDatum(HexFormat.of().formatHex(datum().encode()))
                .build();
        Block block = blockOf(List.of(
                tx("a1", List.of(output(VAULT_ADDRESS, 50, null))),
                tx("a2", List.of(output(VAULT_ADDRESS, 50, new byte[]{0x00}))),
                tx("a3", List.of(withToken)),
                tx("a4", List.of(output(VAULT_ADDRESS, 2_000_000, datum().encode()))),
                tx("a5", List.of(output(VAULT_ADDRESS, 50, otherChain.encode()))),
                tx("a6", List.of(output(OWNER, 20, null), output(VAULT_ADDRESS, 30, foreignEpoch.encode()))),
                tx("a7", List.of(output(OWNER, 20, null), output(OWNER, 20, null),
                        output(VAULT_ADDRESS, 30, genuineSettlement.encode()))),
                tx("b1", List.of(output(VAULT_ADDRESS, 50, datum().encode()))),
                tx("b2", List.of(output(OWNER, 20, null), output(VAULT_ADDRESS, 30, genuineSettlement.encode())))));
        WithdrawalConfirmationObserver withdrawals =
                new WithdrawalConfirmationObserver("bridge-withdrawals", Map.of(
                        "chain-id", "payments-eutxo",
                        "bridge-epoch", "3",
                        "vault-address", VAULT_ADDRESS));

        assertThat(observer().observe(104, fill(32, 11), block))
                .singleElement()
                .satisfies(observation -> assertThat(EutxoDepositClaim.decode(observation.claim())
                        .acceptedOutpoint()).isEqualTo(new EutxoOutpoint("b1".repeat(32), 0)));
        assertThat(withdrawals.observe(104, fill(32, 11), block))
                .singleElement()
                .satisfies(observation -> assertThat(EutxoWithdrawalConfirmation.decode(observation.claim())
                        .settlementTransactionId()).isEqualTo("b2".repeat(32)));
    }

    @Test
    void aKeyBindingTheDepositorDoesNotAuthorizeIsNotCredited() {
        EutxoVaultDatum unauthorized = new EutxoVaultDatum(EutxoVaultDatum.ABI_VERSION, "payments-eutxo", OWNER,
                fill(32, 3), new EutxoOutpoint("44".repeat(32), 1), 1_000, fill(28, 9),
                new EutxoL2KeyBinding("zeroj-jubjub-dev-v1", 1, fill(32, 6)));

        assertThat(observer().observe(105, fill(32, 12),
                block(List.of(output(VAULT_ADDRESS, 50, unauthorized.encode()))))).isEmpty();
    }

    private static AcceptedVaultDepositObserver observer() {
        return new AcceptedVaultDepositObserver("bridge-deposits", Map.of(
                "chain-id", "payments-eutxo",
                "vault-address", VAULT_ADDRESS,
                "vault-script-hash", HexFormat.of().formatHex(VAULT_HASH),
                "max-lovelace", "1000000"));
    }

    private static EutxoVaultDatum datum() {
        return new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                "payments-eutxo",
                OWNER,
                fill(32, 3),
                new EutxoOutpoint("44".repeat(32), 1),
                1_000);
    }

    private static TransactionOutput output(String address, long lovelace, byte[] datum) {
        var builder = TransactionOutput.builder()
                .address(address)
                .amounts(List.of(Amount.builder()
                        .unit("lovelace")
                        .quantity(BigInteger.valueOf(lovelace))
                        .build()));
        if (datum != null) {
            builder.inlineDatum(HexFormat.of().formatHex(datum));
        }
        return builder.build();
    }

    private static Block block(List<TransactionOutput> outputs) {
        return Block.builder()
                .transactionBodies(List.of(TransactionBody.builder()
                        .txHash("11".repeat(32))
                        .outputs(outputs)
                        .build()))
                .build();
    }

    private static TransactionBody tx(String hashByte, List<TransactionOutput> outputs) {
        return TransactionBody.builder().txHash(hashByte.repeat(32)).outputs(outputs).build();
    }

    private static Block blockOf(List<TransactionBody> transactions) {
        return Block.builder().transactionBodies(transactions).build();
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
