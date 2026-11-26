package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the vault's {@code Accept} of ADR-059 D8f as a pure function of resolved L1 outputs and protocol parameters:
 * deposit-shaped vault UTxOs merge into one exact {@code VaultContinuationV1([])} at the vault address, whose value is
 * the sum of the inputs (no floor applies), so they become accepted custody (V10) that {@code Settle} and {@code Exit}
 * can spend.
 * <ul>
 *   <li>{@link #cosigned}: {@code AcceptCosigned}, a threshold of the referenced anchor's members as required signers
 *       (V9). Each member signs only for deposits recorded {@code CREDITED} at or below the referenced anchor's height
 *       ({@link AcceptBodyCheck}).</li>
 *   <li>{@link #proved}: {@code AcceptProved}, one inclusion proof per deposit of
 *       {@code depositKeyPrefix ‖ DepositId = CREDITED} under the referenced anchor's root. Anyone may submit it.</li>
 * </ul>
 *
 * <p><b>Layout.</b> Output 0 is the continuation, output 1 the change. Deposit inputs are spent in canonical outpoint
 * order; the first in ledger order (the lead) carries the action and every other deposit the {@code Follow} marker
 * (V13), and proof {@code i} belongs to the {@code i}-th deposit in that order. The anchor and the exit-mode thread are
 * reference inputs (V1, V11); the vault script is referenced, never inlined. Fees and collateral come only from the
 * caller's key-locked funding inputs, so the vault's value equation stays exact.
 *
 * <p><b>Limits.</b> A co-signed batch holds at most {@link #MAX_COSIGNED} deposits and a proved one at most
 * {@link #MAX_PROVED} ({@code BUDGETS.md}: 16 proved deposits fit, relayers batch 12). Units and the final size are
 * measured as for payouts; a batch over a per-transaction limit is {@link PayoutTxBuilder.LimitExceeded}, so the
 * caller halves it.
 *
 * <p>Nothing here submits or signs.
 */
public final class AcceptTxBuilder {
    /** The most deposits one co-signed {@code Accept} merges (D8f: "batches of about 16"). */
    public static final int MAX_COSIGNED = 16;
    /** The most deposits one proved {@code Accept} merges ({@code BUDGETS.md}: the {@code AcceptProved} cap). */
    public static final int MAX_PROVED = 12;

    /**
     * The L1 context of an {@code Accept}.
     *
     * @param anchor        the live, pinned anchor UTxO (reference input; V9 members, or the root proofs are under)
     * @param exitMode      the exit-mode thread UTxO (reference input; {@code NORMAL} for this builder)
     * @param scriptRefs    the outputs carrying the vault reference script (reference inputs)
     * @param validFromSlot the validity lower bound
     * @param ttlSlot       the validity upper bound
     */
    public record Context(VaultPins pins, ResolvedOutput anchor, ResolvedOutput exitMode,
                          List<ResolvedOutput> scriptRefs, PayoutTxBuilder.Funding funding, long validFromSlot,
                          long ttlSlot, ProtocolParams protocolParams) {
        public Context {
            Objects.requireNonNull(pins, "pins");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(exitMode, "exit mode");
            scriptRefs = List.copyOf(scriptRefs);
            Objects.requireNonNull(funding, "funding");
            Objects.requireNonNull(protocolParams, "protocol params");
            if (validFromSlot < 0 || ttlSlot <= validFromSlot) throw new IllegalArgumentException("validity");
        }
    }

    /** A built, unsigned {@code Accept}: the deposits it merges, in ledger order, and their continuation. */
    public record Built(Transaction transaction, byte[] cbor, Hash32 txId, List<ResolvedOutput> deposits,
                        List<Hash32> depositIds, List<ResolvedOutput> resolvedInputs, BigInteger fee,
                        List<byte[]> requiredSigners) {
        public Built {
            cbor = cbor.clone();
            deposits = List.copyOf(deposits);
            depositIds = List.copyOf(depositIds);
            resolvedInputs = List.copyOf(resolvedInputs);
            requiredSigners = List.copyOf(requiredSigners);
        }

        @Override
        public byte[] cbor() {
            return cbor.clone();
        }

        /** The continuation this {@code Accept} creates (output 0), accepted custody once it is on L1. */
        public ResolvedOutput continuation() {
            TransactionOutput out = transaction.getBody().getOutputs().getFirst();
            return new ResolvedOutput(new ResolvedOutput.Outpoint(txId, 0), SettleBodyCheck.addressBytes(out),
                    out.getValue().getCoin(), new TreeMap<>(SettleBodyCheck.assets(out)),
                    java.util.Optional.of(datumBytes(out.getInlineDatum())), java.util.Optional.empty());
        }
    }

    private AcceptTxBuilder() {
    }

    /**
     * The co-signed {@code Accept} of {@code deposits}. {@code requiredSigners} are the 28-byte key hashes of the
     * referenced anchor's members who will sign (at least its threshold, V9).
     */
    public static Built cosigned(Context context, List<ResolvedOutput> deposits, List<byte[]> requiredSigners,
                                 TransactionEvaluator evaluator) {
        if (deposits.size() > MAX_COSIGNED) {
            throw new PayoutTxBuilder.BuildException("a co-signed Accept merges at most " + MAX_COSIGNED + " deposits");
        }
        if (requiredSigners.isEmpty()) {
            throw new PayoutTxBuilder.BuildException("a co-signed Accept needs the members as required signers");
        }
        List<ResolvedOutput> ordered = ordered(context.pins(), deposits);
        return build(context, ordered, GatewayPlutus.acceptCosigned(), requiredSigners, evaluator);
    }

    /**
     * The proved {@code Accept} of {@code deposits}: {@code proofs} serves, under the referenced anchor's root, the
     * inclusion proof of each deposit's record. Every proof must be of the script-computed key and show a
     * {@code CREDITED} record of this vault and outpoint, or the batch is refused here rather than failing on chain.
     */
    public static Built proved(Context context, List<ResolvedOutput> deposits, StateProof.Source proofs,
                               TransactionEvaluator evaluator) {
        if (deposits.size() > MAX_PROVED) {
            throw new PayoutTxBuilder.BuildException("a proved Accept merges at most " + MAX_PROVED + " deposits");
        }
        VaultPins pins = context.pins();
        List<ResolvedOutput> ordered = ordered(pins, deposits);
        List<StateProof> inclusion = new ArrayList<>();
        for (ResolvedOutput deposit : ordered) {
            Hash32 depositId = depositId(pins, deposit.outpoint());
            byte[] key = depositKey(pins, depositId);
            StateProof proof = proofs.inclusion(key).orElseThrow(() -> new PayoutTxBuilder.BuildException(
                    "no proof for deposit " + depositId));
            if (!Arrays.equals(proof.key(), key) || !creditedRecordOf(proof.value(), pins, deposit.outpoint())) {
                throw new PayoutTxBuilder.BuildException("the proof of deposit " + depositId
                        + " is not of its CREDITED record");
            }
            inclusion.add(proof);
        }
        return build(context, ordered, GatewayPlutus.acceptProved(inclusion), List.of(), evaluator);
    }

    /** The vault's {@code DepositId} of {@code outpoint}, as the script computes it from the spent outRef (D8f). */
    public static Hash32 depositId(VaultPins pins, ResolvedOutput.Outpoint outpoint) {
        return GatewayIds.depositId(pins.vaultId(), outpoint.txId(), outpoint.index());
    }

    /** The state key of a deposit's record: {@code depositKeyPrefix ‖ DepositId}. */
    public static byte[] depositKey(VaultPins pins, Hash32 depositId) {
        byte[] prefix = pins.depositKeyPrefix();
        byte[] key = Arrays.copyOf(prefix, prefix.length + Hash32.BYTES);
        System.arraycopy(depositId.bytes(), 0, key, prefix.length, Hash32.BYTES);
        return key;
    }

    /**
     * Deposit-shaped, as the vault's {@code Accept} requires of every input: at the exact vault address, an inline
     * datum whose decoded outermost value is the deposit constructor, and a value of lovelace and, for a token vault,
     * at most this vault's one asset.
     */
    public static boolean depositShaped(ResolvedOutput utxo, VaultPins pins) {
        if (!utxo.sameAddress(pins.vaultAddress()) || utxo.inlineDatum().isEmpty()) return false;
        if (VaultOutputShape.classify(utxo.inlineDatum().get()) != VaultOutputShape.DEPOSIT) return false;
        return utxo.assets().isEmpty()
                || (!pins.adaVault() && utxo.assets().size() == 1 && utxo.assets().containsKey(pins.asset()));
    }

    /** True when {@code value} is the {@code CREDITED} record of {@code outpoint}'s deposit in this vault. */
    static boolean creditedRecordOf(byte[] value, VaultPins pins, ResolvedOutput.Outpoint outpoint) {
        try {
            DepositRecordV1 record = DepositRecordV1.decode(value);
            return record.status() == DepositRecordV1.Status.CREDITED
                    && record.depositId().equals(depositId(pins, outpoint))
                    && record.vaultId().equals(pins.vaultId())
                    && record.txId().equals(outpoint.txId()) && record.outputIndex() == outpoint.index();
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static List<ResolvedOutput> ordered(VaultPins pins, List<ResolvedOutput> deposits) {
        if (deposits.isEmpty()) throw new PayoutTxBuilder.BuildException("an Accept merges at least one deposit");
        Set<ResolvedOutput.Outpoint> seen = new HashSet<>();
        for (ResolvedOutput deposit : deposits) {
            if (!depositShaped(deposit, pins)) {
                throw new PayoutTxBuilder.BuildException("input " + deposit.outpoint() + " is not a deposit-shaped"
                        + " output of this vault");
            }
            if (!seen.add(deposit.outpoint())) throw new PayoutTxBuilder.BuildException("duplicate deposit");
        }
        List<ResolvedOutput> ordered = new ArrayList<>(deposits);
        ordered.sort((a, b) -> a.outpoint().compareTo(b.outpoint()));
        return ordered;
    }

    private static Built build(Context context, List<ResolvedOutput> deposits, PlutusData action,
                               List<byte[]> requiredSigners, TransactionEvaluator evaluator) {
        VaultPins pins = context.pins();
        ProtocolParams pp = context.protocolParams();
        ExitRelayer.requireNormalMode(context.exitMode());
        BigInteger lovelace = BigInteger.ZERO;
        BigInteger asset = BigInteger.ZERO;
        for (ResolvedOutput deposit : deposits) {
            lovelace = lovelace.add(deposit.lovelace());
            asset = asset.add(pins.adaVault() ? BigInteger.ZERO : deposit.quantityOf(pins.asset()));
        }
        Map<AssetId, BigInteger> assets = asset.signum() == 0 ? Map.of() : Map.of(pins.asset(), asset);
        TransactionOutput continuation = PayoutTxBuilder.output(pins.vaultAddress(), lovelace, assets,
                GatewayPlutus.continuation(List.of()));
        if (lovelace.compareTo(new MinAdaCalculator(pp).calculateMinAda(continuation)) < 0) {
            throw new PayoutTxBuilder.BuildException("the merged deposits do not cover the continuation's min-UTxO");
        }
        List<TransactionOutput> outputs = new ArrayList<>();
        outputs.add(continuation);
        int changeIndex = outputs.size();
        outputs.add(PayoutTxBuilder.output(context.funding().changeAddress(), BigInteger.ZERO, Map.of(), null));

        TreeMap<ResolvedOutput.Outpoint, ResolvedOutput> spent = new TreeMap<>();
        deposits.forEach(deposit -> spent.put(deposit.outpoint(), deposit));
        for (ResolvedOutput input : context.funding().inputs()) {
            if (!input.assets().isEmpty() || input.paymentCredential().map(ResolvedOutput.PaymentCredential::script)
                    .orElse(true)) {
                throw new PayoutTxBuilder.BuildException("funding inputs are key-locked and hold lovelace only");
            }
            if (spent.put(input.outpoint(), input) != null) {
                throw new PayoutTxBuilder.BuildException("funding input is also spent");
            }
        }
        List<ResolvedOutput.Outpoint> order = new ArrayList<>(spent.keySet());
        Set<ResolvedOutput.Outpoint> depositSet = new HashSet<>();
        deposits.forEach(deposit -> depositSet.add(deposit.outpoint()));
        ResolvedOutput.Outpoint lead = order.stream().filter(depositSet::contains).findFirst().orElseThrow();
        List<Redeemer> redeemers = new ArrayList<>();
        BigInteger maxMem = new BigInteger(pp.getMaxTxExMem());
        BigInteger maxSteps = new BigInteger(pp.getMaxTxExSteps());
        for (int index = 0; index < order.size(); index++) {
            ResolvedOutput.Outpoint outpoint = order.get(index);
            if (!depositSet.contains(outpoint)) continue;
            PlutusData data = outpoint.equals(lead) ? action : GatewayPlutus.follow();
            redeemers.add(Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.valueOf(index)).data(data)
                    .exUnits(ExUnits.builder().mem(maxMem).steps(maxSteps).build()).build());
        }

        ResolvedOutput collateral = context.funding().inputs().stream()
                .max((a, b) -> a.lovelace().compareTo(b.lovelace())).orElseThrow();
        TreeSet<ResolvedOutput.Outpoint> referenced = new TreeSet<>();
        referenced.add(context.anchor().outpoint());
        referenced.add(context.exitMode().outpoint());
        context.scriptRefs().forEach(ref -> referenced.add(ref.outpoint()));
        List<TransactionInput> referenceInputs = referenced.stream().map(PayoutTxBuilder::input).toList();

        TransactionBody body = TransactionBody.builder()
                .inputs(order.stream().map(PayoutTxBuilder::input).toList())
                .outputs(outputs)
                .fee(BigInteger.ZERO)
                .ttl(context.ttlSlot())
                .validityStartInterval(context.validFromSlot())
                .collateral(List.of(PayoutTxBuilder.input(collateral.outpoint())))
                .referenceInputs(new ArrayList<>(referenceInputs))
                .requiredSigners(requiredSigners.stream().map(byte[]::clone).toList())
                .build();
        TransactionWitnessSet witnesses = new TransactionWitnessSet();
        witnesses.setRedeemers(redeemers);
        Transaction tx = Transaction.builder().body(body).witnessSet(witnesses).isValid(true).build();

        Map<ResolvedOutput.Outpoint, ResolvedOutput> resolved = new LinkedHashMap<>(spent);
        resolved.put(context.anchor().outpoint(), context.anchor());
        resolved.put(context.exitMode().outpoint(), context.exitMode());
        context.scriptRefs().forEach(ref -> resolved.put(ref.outpoint(), ref));
        BigInteger fee = PayoutTxBuilder.balance(tx, changeIndex, context.funding(), BigInteger.ZERO, collateral,
                resolved.values(), pp, evaluator, requiredSigners.size());
        byte[] cbor = PayoutTxBuilder.serialize(tx);
        List<Hash32> ids = deposits.stream().map(deposit -> depositId(pins, deposit.outpoint())).toList();
        return new Built(tx, cbor, Hash32.ofHex(TransactionUtil.getTxHash(tx)), deposits, ids,
                List.copyOf(resolved.values()), fee, requiredSigners);
    }

    private static byte[] datumBytes(PlutusData datum) {
        try {
            return datum.serializeToBytes();
        } catch (Exception failure) {
            throw new PayoutTxBuilder.BuildException("continuation datum", failure);
        }
    }
}
