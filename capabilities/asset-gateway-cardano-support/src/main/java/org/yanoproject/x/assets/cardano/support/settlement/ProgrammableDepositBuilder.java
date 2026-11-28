package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.CardanoAddresses;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The domain API's unsigned deposit builder for a CIP-113 vault (ADR-061 D6): an ordinary CIP-113 transfer from the
 * depositor's key-owned smart wallet to the vault smart wallet, carrying {@link ProgrammableDepositDatumV1}, laid out
 * by {@link ProgrammableVaultTxAssembler}, so every withdrawal takes its live reward balance and the live params
 * UTxO must name an approved dispatcher.
 *
 * <ul>
 *   <li>The origin is the depositor's PLB input holding the asset, the first in ledger order. It is always spent, so
 *       the origin is known before balancing.</li>
 *   <li>Output 0 is the deposit, so {@code output_index = 0}; output 1 returns the rest of the depositor's inputs to
 *       its smart wallet; the funding pays the deposit's lovelace and the fee.</li>
 *   <li>The owner key authorizes the smart-wallet inputs as a required signer.</li>
 *   <li>After balancing, the builder re-checks that the deposit sits at {@code output_index} and that the origin is
 *       still an input.</li>
 *   <li>The live params datum's four fields must equal an approved tuple <b>exactly</b>, not only its dispatcher:
 *       admission classifies the deposit with the fields of the params UTxO it referenced, so any other fields would
 *       make it {@code UNCREDITED(WIRING_UNPINNED)} ({@link ProgrammableVaultTxAssembler.Drift} instead).</li>
 *   <li>The final transaction stays within what the deposit observer reports in full: at most
 *       {@value ProgrammableDepositClaimV1#MAX_REFERENCE_INPUTS} distinct reference inputs and
 *       {@value ProgrammableDepositClaimV1#MAX_WITHDRAWALS} script withdrawals; above that the observer reports
 *       {@code OVERFLOW} and admission could not classify it, so the builder refuses.</li>
 * </ul>
 */
public final class ProgrammableDepositBuilder {
    private ProgrammableDepositBuilder() {
    }

    public record Request(List<ResolvedOutput> depositorInputs, byte[] ownerKeyHash, byte[] vaultWallet,
                          AssetId asset, BigInteger amount, BigInteger depositLovelace, byte[] beneficiary,
                          byte[] refundKeyHash, PayoutTxBuilder.Funding funding, List<ResolvedOutput> references,
                          long validFromSlot, long ttlSlot) {
        public Request {
            depositorInputs = List.copyOf(depositorInputs);
            ownerKeyHash = Objects.requireNonNull(ownerKeyHash, "owner key").clone();
            vaultWallet = Objects.requireNonNull(vaultWallet, "vault wallet").clone();
            Objects.requireNonNull(asset, "asset");
            if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
            Objects.requireNonNull(depositLovelace, "deposit lovelace");
            beneficiary = Objects.requireNonNull(beneficiary, "beneficiary").clone();
            refundKeyHash = Objects.requireNonNull(refundKeyHash, "refund key").clone();
            Objects.requireNonNull(funding, "funding");
            references = List.copyOf(references);
        }
    }

    public record Built(ProgrammableVaultTxAssembler.Assembled assembled, ProgrammableDepositDatumV1 datum,
                        Hash32 depositId) {
    }

    /** Builds the deposit; fails when the depositor's inputs do not hold {@code amount} of the asset. */
    public static Built build(ProgrammableVaultTxAssembler.Deployment deployment,
                              ProgrammableVaultTxAssembler.LiveWiring wiring, ProgrammableVaultTxAssembler.L1 l1,
                              Cip113Requirements.Adapter adapter, ProgrammableVaultTxAssembler.RewardBalances balances,
                              Hash32 vaultId, Request request, ProtocolParams pp, TransactionEvaluator evaluator) {
        if (wiring.fields().isEmpty() || !wiring.fields().get().equals(wiring.tuple().fields())) {
            throw new ProgrammableVaultTxAssembler.Drift("the live protocol params are not exactly an approved wiring"
                    + " tuple; a deposit now would be UNCREDITED(WIRING_UNPINNED)");
        }
        byte[] wallet = ProgrammableBackend.keySmartWallet(deployment.plbHash(), request.ownerKeyHash(),
                deployment.networkId());
        TreeMap<ResolvedOutput.Outpoint, ResolvedOutput> inputs = new TreeMap<>();
        for (ResolvedOutput input : request.depositorInputs()) {
            if (!Arrays.equals(input.address(), wallet)) {
                throw new IllegalArgumentException("a depositor input is not at the owner's smart wallet");
            }
            inputs.put(input.outpoint(), input);
        }
        ResolvedOutput origin = inputs.values().stream().filter(in -> in.quantityOf(request.asset()).signum() > 0)
                .findFirst().orElseThrow(() -> new PayoutTxBuilder.FundingShort("no depositor input holds the asset"));
        Map<AssetId, BigInteger> held = new TreeMap<>();
        BigInteger lovelace = BigInteger.ZERO;
        for (ResolvedOutput input : inputs.values()) {
            lovelace = lovelace.add(input.lovelace());
            input.assets().forEach((asset, quantity) -> held.merge(asset, quantity, BigInteger::add));
        }
        BigInteger rest = held.getOrDefault(request.asset(), BigInteger.ZERO).subtract(request.amount());
        if (rest.signum() < 0) throw new PayoutTxBuilder.FundingShort("the depositor holds less than the amount");
        if (rest.signum() == 0) held.remove(request.asset());
        else held.put(request.asset(), rest);
        byte[] refundAddress = ProgrammableBackend.keySmartWallet(deployment.plbHash(), request.refundKeyHash(),
                deployment.networkId());
        if (!CardanoAddresses.payableSmartWallet(refundAddress, deployment.plbHash(), deployment.networkId())) {
            throw new IllegalArgumentException("the refund address is not a key-owned smart wallet");
        }
        ProgrammableDepositDatumV1 datum = new ProgrammableDepositDatumV1(request.beneficiary(), refundAddress,
                request.refundKeyHash(), origin.outpoint().txId(), origin.outpoint().index(), 0);
        PlutusData inline;
        try {
            inline = PlutusData.deserialize(datum.encode());
        } catch (Exception failure) {
            throw new PayoutTxBuilder.BuildException("deposit datum", failure);
        }
        List<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(PayoutTxBuilder.output(request.vaultWallet(), request.depositLovelace(),
                Map.of(request.asset(), request.amount()), inline));
        outputs.add(PayoutTxBuilder.output(wallet, lovelace, held, null));
        ProgrammableVaultTxAssembler.Plan plan = new ProgrammableVaultTxAssembler.Plan(
                List.copyOf(inputs.values()), List.of(), request.funding(), outputs, request.references(), List.of(),
                List.of(request.ownerKeyHash()), request.validFromSlot(), request.ttlSlot());
        ProgrammableVaultTxAssembler.Assembled assembled = ProgrammableVaultTxAssembler.assemble(deployment, wiring,
                plan, l1, adapter, balances, pp, evaluator);
        // The D6 re-check: the deposit sits at its datum's output_index, and its origin is still an input.
        TransactionOutput deposit = assembled.transaction().getBody().getOutputs().getFirst();
        if (!Arrays.equals(new com.bloxbean.cardano.client.address.Address(deposit.getAddress()).getBytes(),
                request.vaultWallet())) {
            throw new PayoutTxBuilder.BuildException("the deposit is not at output_index 0 after balancing");
        }
        TransactionInput originInput = PayoutTxBuilder.input(origin.outpoint());
        if (!assembled.transaction().getBody().getInputs().contains(originInput)) {
            throw new PayoutTxBuilder.BuildException("the origin input is no longer spent after balancing");
        }
        // The observer's claim limits (D6): what admission can classify.
        if (assembled.indexes().referenceOrder().size() > ProgrammableDepositClaimV1.MAX_REFERENCE_INPUTS) {
            throw new PayoutTxBuilder.BuildException("the deposit references more than "
                    + ProgrammableDepositClaimV1.MAX_REFERENCE_INPUTS + " outputs, past what the observer reports");
        }
        if (assembled.indexes().withdrawalOrder().size() > ProgrammableDepositClaimV1.MAX_WITHDRAWALS) {
            throw new PayoutTxBuilder.BuildException("the deposit has more than "
                    + ProgrammableDepositClaimV1.MAX_WITHDRAWALS + " script withdrawals, past what the observer"
                    + " reports");
        }
        return new Built(assembled, datum, datum.depositId(vaultId));
    }
}
