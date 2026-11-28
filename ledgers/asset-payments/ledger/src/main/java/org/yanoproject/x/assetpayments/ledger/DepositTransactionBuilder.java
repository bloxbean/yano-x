package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.RegistryEntryV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.framework.api.plugin.domain.L1TransactionBuilderService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the unsigned L1 deposit a CIP-30 wallet signs with {@code signTx} (#109 §3.1, ADR-059 D5): one output at the
 * vault address carrying the vault's asset and an inline {@link DepositDatumV1}, paid from the wallet's own UTxOs
 * (CIP-30 {@code getUtxos()}), with change and fee back to the wallet's change address. Nothing is signed or submitted
 * here, and nothing reads node stores: the inputs are exactly the UTxOs the wallet supplied, so a wrong or spent UTxO
 * only yields a transaction the ledger refuses.
 *
 * <p>The output is a creditable deposit by construction: the deposit shape, exactly the vault's registered asset and
 * nothing else, and a positive quantity (D5). For a token vault the output's lovelace becomes the depositor's min-ADA
 * credit (D11); it is raised to the output's min-UTxO when the request asks for less, and an ADA deposit below
 * its min-UTxO is refused. Fees use the linear fee rule with
 * the host's current L1 protocol parameters, read for each build, so the fee matches what L1 charges; a mismatch
 * would only make the wallet or the node refuse the transaction, never change what the deposit credits.
 */
final class DepositTransactionBuilder {
    static final int MAX_UTXOS = 256;
    static final int MAX_UTXO_BYTES = 16 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    private static final TransactionProcessor NO_SUBMIT = new TransactionProcessor() {
        @Override
        public Result<String> submitTransaction(byte[] cborData) {
            throw new UnsupportedOperationException("the deposit builder never submits");
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
            throw new UnsupportedOperationException("a deposit runs no script");
        }
    };

    /** The linear fee and min-UTxO parameters a payment needs: the host's current L1 protocol parameters. */
    record FeeParameters(long minFeeA, long minFeeB, long coinsPerUtxoByte, long maxTxSize, long maxValueSize) {
        FeeParameters {
            if (minFeeA < 0 || minFeeB < 0 || coinsPerUtxoByte < 1 || maxTxSize < 1 || maxValueSize < 1
                    || minFeeA > Integer.MAX_VALUE || minFeeB > Integer.MAX_VALUE || maxTxSize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid deposit fee parameters");
            }
        }

        static FeeParameters from(L1TransactionBuilderService.PricingParameters pricing) {
            return new FeeParameters(pricing.minFeeA(), pricing.minFeeB(), pricing.coinsPerUtxoByte(),
                    pricing.maxTxSize(), pricing.maxValueSize());
        }

        ProtocolParams protocolParams() {
            ProtocolParams params = new ProtocolParams();
            params.setMinFeeA((int) minFeeA);
            params.setMinFeeB((int) minFeeB);
            params.setCoinsPerUtxoSize(Long.toString(coinsPerUtxoByte));
            params.setMaxTxSize((int) maxTxSize);
            params.setMaxValSize(Long.toString(maxValueSize));
            params.setKeyDeposit("2000000");
            params.setPoolDeposit("500000000");
            params.setMinFeeRefScriptCostPerByte(java.math.BigDecimal.valueOf(15));
            params.setCollateralPercent(java.math.BigDecimal.valueOf(150));
            params.setMaxCollateralInputs(3);
            return params;
        }
    }

    /** A deposit to build; {@code validToSlot} is required, so a built deposit always has a TTL. */
    record Request(RegistryEntryV1 vault, DepositDatumV1 datum, BigInteger quantity, BigInteger lovelace,
                   String changeAddress, List<Utxo> utxos, long validToSlot, FeeParameters fees) {
        Request {
            if (validToSlot < 1) throw new IllegalArgumentException("a deposit needs a validity upper bound");
            java.util.Objects.requireNonNull(fees, "fees");
        }
    }

    record Built(byte[] cbor, String transactionId, long fee, int outputIndex, BigInteger outputLovelace,
                 BigInteger outputQuantity, List<String> inputs, long validToSlot) { }

    DepositTransactionBuilder() {
    }

    /**
     * Decodes CIP-30 {@code getUtxos()} entries, each the CBOR {@code [transaction_input, transaction_output]}. Only
     * plain key-address outputs without a datum or reference script are kept: those are the ones a wallet spends with
     * a single vkey witness and no script.
     */
    static List<Utxo> decodeCip30(List<String> hexUtxos) {
        if (hexUtxos == null || hexUtxos.isEmpty() || hexUtxos.size() > MAX_UTXOS) {
            throw new IllegalArgumentException("utxos must list 1-256 CIP-30 UTxOs");
        }
        Map<String, Utxo> out = new LinkedHashMap<>();
        for (String hex : hexUtxos) {
            if (hex == null || hex.length() > 2 * MAX_UTXO_BYTES || !hex.matches("(?:[0-9a-fA-F]{2})+")) {
                throw new IllegalArgumentException("a UTxO must be CBOR hex of at most 16 KiB");
            }
            try {
                DataItem item = CborSerializationUtil.deserialize(HEX.parseHex(hex.toLowerCase(java.util.Locale.ROOT)));
                if (!(item instanceof Array pair) || pair.getDataItems().size() != 2
                        || !(pair.getDataItems().get(0) instanceof Array input)) {
                    throw new IllegalArgumentException("a UTxO is [transaction_input, transaction_output]");
                }
                TransactionInput in = TransactionInput.deserialize(input);
                TransactionOutput output = TransactionOutput.deserialize(pair.getDataItems().get(1));
                if (output.getDatumHash() != null || output.getInlineDatum() != null || output.getScriptRef() != null) {
                    continue;
                }
                Address address = new Address(output.getAddress());
                if (address.getPaymentCredentialHash().isEmpty() || address.isScriptHashInPaymentPart()) continue;
                out.putIfAbsent(in.getTransactionId() + "#" + in.getIndex(), new Utxo(in.getTransactionId(),
                        in.getIndex(), output.getAddress(), amounts(output.getValue()), null, null, null));
            } catch (IllegalArgumentException invalid) {
                throw invalid;
            } catch (Exception malformed) {
                throw new IllegalArgumentException("a UTxO is not valid CBOR");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no spendable key-address UTxO was supplied");
        return List.copyOf(out.values());
    }

    Built build(Request request) {
        RegistryEntryV1 vault = request.vault();
        AssetId asset = vault.asset();
        if (request.quantity().signum() <= 0) throw new IllegalArgumentException("quantity must be positive");
        Address change = new Address(request.changeAddress());
        if (change.getPaymentCredentialHash().isEmpty() || change.isScriptHashInPaymentPart()) {
            throw new IllegalArgumentException("the change address must have a key payment credential");
        }
        PlutusData datum;
        try {
            datum = PlutusData.deserialize(request.datum().encode());
        } catch (Exception impossible) {
            throw new IllegalStateException("the deposit datum does not decode", impossible);
        }
        ProtocolParams params = request.fees().protocolParams();
        BigInteger minUtxo = minUtxo(params, vault, request.quantity(), datum);
        List<Amount> amounts = new ArrayList<>();
        if (asset.isAda()) {
            if (request.quantity().compareTo(minUtxo) < 0) {
                throw new IllegalArgumentException("an ADA deposit must be at least the output's min-UTxO of "
                        + minUtxo + " lovelace");
            }
            amounts.add(Amount.lovelace(request.quantity()));
        } else {
            // The output's lovelace is the depositor's min-ADA credit (D11); never below the output's min-UTxO.
            amounts.add(Amount.lovelace(request.lovelace().max(minUtxo)));
            amounts.add(Amount.asset(HEX.formatHex(asset.policyId()) + HEX.formatHex(asset.assetName()),
                    request.quantity()));
        }
        // Every supplied UTxO is offered under the change address: they are all the wallet's. The fee estimate counts
        // one witness per distinct payment key of the inputs CCL selects (UtxoUtil.getNoOfRequiredSigners), so a
        // wallet that offers many addresses' UTxOs pays only for the keys it spends from.
        List<Utxo> offered = request.utxos().stream()
                .sorted(Comparator.comparing(DepositTransactionBuilder::lovelaceOf).reversed()).toList();
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return page == null || page == 0 ? offered : List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return offered.stream().filter(utxo -> utxo.getTxHash().equals(txHash)
                        && utxo.getOutputIndex() == outputIndex).findFirst();
            }
        };
        ProtocolParamsSupplier paramsSupplier = () -> params;
        Tx tx = new Tx().payToContract(vault.vaultAddress(), amounts, datum).from(change.toBech32());
        QuickTxBuilder.TxContext context = new QuickTxBuilder(supplier, paramsSupplier, NO_SUBMIT).compose(tx)
                .mergeOutputs(false)
                .validTo(request.validToSlot());
        Transaction unsigned;
        try {
            unsigned = context.build();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("the wallet's UTxOs cannot pay this deposit and its fee", failure);
        }
        return describe(unsigned, vault, request.datum(), request.validToSlot());
    }

    private static Built describe(Transaction unsigned, RegistryEntryV1 vault, DepositDatumV1 datum,
                                  long validTo) {
        byte[] cbor;
        try {
            cbor = unsigned.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("the deposit does not serialize", failure);
        }
        List<TransactionOutput> outputs = unsigned.getBody().getOutputs();
        int index = -1;
        for (int i = 0; i < outputs.size(); i++) {
            if (vault.vaultAddress().equals(outputs.get(i).getAddress())) {
                if (index >= 0) throw new IllegalStateException("the deposit has more than one vault output");
                index = i;
            }
        }
        if (index < 0) throw new IllegalStateException("the deposit has no vault output");
        TransactionOutput deposit = outputs.get(index);
        byte[] inlineDatum;
        try {
            inlineDatum = deposit.getInlineDatum() == null ? null : deposit.getInlineDatum().serializeToBytes();
        } catch (Exception failure) {
            throw new IllegalStateException("the deposit datum does not serialize", failure);
        }
        if (!Arrays.equals(inlineDatum, datum.encode())) {
            throw new IllegalStateException("the vault output does not carry the exact deposit datum");
        }
        Value value = deposit.getValue();
        BigInteger quantity = vault.asset().isAda() ? value.getCoin() : tokenQuantity(value, vault.asset());
        int otherAssets = value.getMultiAssets() == null ? 0 : value.getMultiAssets().stream()
                .mapToInt(multi -> multi.getAssets().size()).sum();
        if (otherAssets != (vault.asset().isAda() ? 0 : 1) || quantity.signum() <= 0) {
            throw new IllegalStateException("the vault output is not a single-asset deposit of the vault's asset");
        }
        List<String> inputs = unsigned.getBody().getInputs().stream()
                .map(input -> input.getTransactionId() + "#" + input.getIndex()).toList();
        return new Built(cbor, TransactionUtil.getTxHash(cbor), unsigned.getBody().getFee().longValueExact(), index,
                value.getCoin(), quantity, inputs, validTo);
    }

    /** The min-UTxO of the deposit output itself (the vault address, its value and the inline datum). */
    private static BigInteger minUtxo(ProtocolParams params, RegistryEntryV1 vault, BigInteger quantity,
                                      PlutusData datum) {
        AssetId asset = vault.asset();
        Value value = asset.isAda() ? Value.builder().coin(quantity).build() : Value.builder()
                .coin(BigInteger.ONE)
                .multiAssets(List.of(new MultiAsset(HEX.formatHex(asset.policyId()),
                        List.of(new Asset("0x" + HEX.formatHex(asset.assetName()), quantity)))))
                .build();
        TransactionOutput output = TransactionOutput.builder().address(vault.vaultAddress()).value(value)
                .inlineDatum(datum).build();
        return new MinAdaCalculator(params).calculateMinAda(output);
    }

    static BigInteger tokenQuantity(Value value, AssetId asset) {
        if (value.getMultiAssets() == null) return BigInteger.ZERO;
        String policy = HEX.formatHex(asset.policyId());
        BigInteger total = BigInteger.ZERO;
        for (MultiAsset multi : value.getMultiAssets()) {
            if (!policy.equals(multi.getPolicyId())) continue;
            for (Asset entry : multi.getAssets()) {
                if (Arrays.equals(entry.getNameAsBytes(), asset.assetName())) total = total.add(entry.getValue());
            }
        }
        return total;
    }

    private static List<Amount> amounts(Value value) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(value.getCoin()));
        if (value.getMultiAssets() != null) {
            for (MultiAsset multi : value.getMultiAssets()) {
                for (Asset entry : multi.getAssets()) {
                    amounts.add(Amount.asset(multi.getPolicyId() + HEX.formatHex(entry.getNameAsBytes()),
                            entry.getValue()));
                }
            }
        }
        return amounts;
    }

    private static BigInteger lovelaceOf(Utxo utxo) {
        return utxo.getAmount().stream().filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
    }
}
