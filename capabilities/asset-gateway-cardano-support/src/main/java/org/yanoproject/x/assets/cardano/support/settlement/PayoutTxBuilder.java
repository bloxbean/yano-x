package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.CostModel;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Language;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the vault payout transactions of ADR-059 D8 and D9 as pure functions of resolved L1 outputs, committed claim
 * leaves and protocol parameters:
 * <ul>
 *   <li>{@link #settle}: the co-signed batch {@code Settle(claims, shard)} of up to {@code maxSettleBatch} claims of
 *       one shard, required signers named by the caller (V9);</li>
 *   <li>{@link #exit}: the permissionless {@code Exit(claims, statusProof, shard)} of up to {@code maxExitBatch}
 *       claims, each with its inclusion proof under the referenced anchor's root (V1-V3).</li>
 * </ul>
 *
 * <p><b>Layout</b>, the one the vault and shard scripts check: outputs {@code 0..k-1} pay {@code claim[i]} (V5),
 * output {@code k} is the exact vault continuation with {@code VaultContinuationV1(ids)} (V6, V7), then the shard
 * continuation with the new root and the shard's lovelace and thread token, then the change. Vault inputs are
 * accepted custody only (V10), selected in canonical outpoint order; the first in ledger order (the lead) carries the
 * action and every other vault input the {@code Follow} marker (V13). The anchor and the exit-mode thread are reference
 * inputs (V1, V11); scripts are always referenced, never inlined. Fees, collateral and any payout supplement (D11:
 * min-ADA above a claim's {@code vaultLovelace}) come from the caller's key-locked funding inputs (I15).
 *
 * <p><b>Execution units</b> come from the injected {@link TransactionEvaluator} (the node's phase-2 evaluator at
 * runtime), with a margin; the fee covers the transaction size including the vkey witnesses still to come, the script
 * units and the Conway reference-script fee. A batch whose transaction exceeds {@code maxTxSize} is refused, so the
 * caller splits it.
 *
 * <p>Nothing here submits, signs or changes a claim's status (I12).
 */
public final class PayoutTxBuilder {
    /** Bytes of one vkey witness ({@code [vkey 32, signature 64]}) in the witness set. */
    static final int VKEY_WITNESS_BYTES = 101;
    private static final int SIZE_SLACK_BYTES = 64;
    private static final BigDecimal UNIT_MARGIN = new BigDecimal("1.05");
    private static final BigDecimal REF_SCRIPT_MULTIPLIER = new BigDecimal("1.2");
    private static final long REF_SCRIPT_SIZE_INCREMENT = 25_600;

    /** Why a payout cannot be built; the caller retries later, splits or re-selects. */
    public static final class BuildException extends RuntimeException {
        public BuildException(String message) {
            super(message);
        }

        public BuildException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The key-locked inputs that pay fees, collateral and supplements, and where change goes. */
    public record Funding(List<ResolvedOutput> inputs, byte[] changeAddress) {
        public Funding {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty()) throw new IllegalArgumentException("a payout needs a funding input");
            changeAddress = Objects.requireNonNull(changeAddress, "change address").clone();
        }
    }

    /**
     * The L1 context shared by both redeemers.
     *
     * @param vaultUtxos    candidate outputs at the vault address; only accepted custody is selected (V10)
     * @param shardInput    the live shard UTxO of the batch's shard
     * @param anchor        the live, pinned anchor UTxO (reference input)
     * @param exitMode      the exit-mode thread UTxO (reference input)
     * @param scriptRefs    the outputs carrying the vault and shard reference scripts (reference inputs)
     * @param validFromSlot the validity lower bound ({@code Exit}'s L1 time, V3)
     * @param ttlSlot       the validity upper bound
     */
    public record Context(VaultPins pins, List<ResolvedOutput> vaultUtxos, ResolvedOutput shardInput,
                          ResolvedOutput anchor, ResolvedOutput exitMode, List<ResolvedOutput> scriptRefs,
                          Funding funding, long validFromSlot, long ttlSlot, ProtocolParams protocolParams,
                          int maxVaultInputs) {
        public Context {
            Objects.requireNonNull(pins, "pins");
            vaultUtxos = List.copyOf(vaultUtxos);
            Objects.requireNonNull(shardInput, "shard input");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(exitMode, "exit mode");
            scriptRefs = List.copyOf(scriptRefs);
            Objects.requireNonNull(funding, "funding");
            Objects.requireNonNull(protocolParams, "protocol params");
            if (validFromSlot < 0 || ttlSlot <= validFromSlot || maxVaultInputs < 1) {
                throw new IllegalArgumentException("validity and vault input bound");
            }
        }
    }

    /** A built, unsigned payout transaction: the full transaction with redeemers and script data hash. */
    public record Built(Transaction transaction, byte[] cbor, Hash32 txId, List<Hash32> claimIds, int shardIndex,
                        List<ResolvedOutput> resolvedInputs, ShardMirror.Plan shardPlan, BigInteger fee,
                        List<byte[]> requiredSigners) {
        public Built {
            cbor = cbor.clone();
            claimIds = List.copyOf(claimIds);
            resolvedInputs = List.copyOf(resolvedInputs);
            requiredSigners = List.copyOf(requiredSigners);
        }

        @Override
        public byte[] cbor() {
            return cbor.clone();
        }
    }

    private PayoutTxBuilder() {
    }

    /**
     * The co-signed {@code Settle} of {@code claims}, in this order, on their one shard. {@code requiredSigners} are
     * the 28-byte key hashes of the anchor members who will sign (at least its threshold, V9).
     */
    public static Built settle(Context context, List<ClaimLeafV1> claims, ShardMirror mirror,
                               List<byte[]> requiredSigners, TransactionEvaluator evaluator) {
        VaultPins pins = context.pins();
        if (claims.size() > pins.maxSettleBatch()) {
            throw new BuildException("a Settle pays at most " + pins.maxSettleBatch() + " claims");
        }
        for (ClaimLeafV1 leaf : claims) {
            if (leaf.kind() != ClaimKind.USER_WITHDRAWAL) throw new BuildException("Settle pays user claims only");
        }
        if (requiredSigners.isEmpty()) throw new BuildException("a Settle needs the members as required signers");
        int shard = requireShard(claims, pins, context.shardInput());
        PlutusData action = GatewayPlutus.settle(claims, pins.asset(), shard);
        return payout(context, claims, mirror, action, requiredSigners, evaluator);
    }

    /**
     * The permissionless {@code Exit} of {@code claims}, each with its proof (same order) under the referenced anchor's
     * root, and the status-leaf proof under the same root. The caller checks eligibility
     * ({@code ClaimEligibility}); the script checks it again against {@code validFromSlot}'s time.
     */
    public static Built exit(Context context, List<ClaimLeafV1> claims, List<StateProof> proofs, StateProof status,
                             ShardMirror mirror, TransactionEvaluator evaluator) {
        VaultPins pins = context.pins();
        if (claims.size() > pins.maxExitBatch()) {
            throw new BuildException("an Exit pays at most " + pins.maxExitBatch() + " claims");
        }
        int shard = requireShard(claims, pins, context.shardInput());
        PlutusData action = GatewayPlutus.exit(claims, proofs, status, pins.asset(), shard);
        return payout(context, claims, mirror, action, List.of(), evaluator);
    }

    // ------------------------------------------------------------------ layout

    private static int requireShard(List<ClaimLeafV1> claims, VaultPins pins, ResolvedOutput shardInput) {
        if (claims.isEmpty() || claims.size() > VaultPins.ON_CHAIN_MAX_BATCH) {
            throw new BuildException("a payout pays 1-64 claims");
        }
        int shard = SettlementBatchV1.shardOf(claims.getFirst().claimId());
        Set<Hash32> seen = new HashSet<>();
        for (ClaimLeafV1 leaf : claims) {
            if (!leaf.vaultId().equals(pins.vaultId())) throw new BuildException("claim of another vault");
            if (SettlementBatchV1.shardOf(leaf.claimId()) != shard) throw new BuildException("claims of two shards");
            if (!seen.add(leaf.claimId())) throw new BuildException("duplicate claim");
        }
        if (shardInput.quantityOf(pins.shardToken(shard)).compareTo(BigInteger.ONE) != 0
                || !shardInput.sameAddress(pins.shardAddress())) {
            throw new BuildException("the shard input is not shard " + shard + " of this vault");
        }
        return shard;
    }

    private static Built payout(Context context, List<ClaimLeafV1> claims, ShardMirror mirror, PlutusData action,
                                List<byte[]> requiredSigners, TransactionEvaluator evaluator) {
        VaultPins pins = context.pins();
        ProtocolParams pp = context.protocolParams();
        int shard = SettlementBatchV1.shardOf(claims.getFirst().claimId());
        if (mirror.shardIndex() != shard) throw new BuildException("mirror of another shard");
        GatewayDatums.ShardDatum shardDatum = context.shardInput().inlineDatum().flatMap(GatewayDatums::shardDatum)
                .filter(datum -> datum.shardIndex() == shard)
                .orElseThrow(() -> new BuildException("the shard input has no shard datum"));
        if (!mirror.matches(shardDatum.nullifierRoot())) {
            // D8h §6: a third-party Exit or a rollback; the caller rebuilds the mirror from confirmations.
            throw new BuildException("the shard mirror does not match the live shard root");
        }
        List<Hash32> ids = claims.stream().map(ClaimLeafV1::claimId).toList();
        for (Hash32 id : ids) {
            if (mirror.contains(id)) throw new BuildException("claim " + id + " is already paid on L1");
        }
        ShardMirror.Plan plan = mirror.plan(ids);

        BigInteger quantity = BigInteger.ZERO;
        BigInteger vaultLovelace = BigInteger.ZERO;
        for (ClaimLeafV1 leaf : claims) {
            quantity = quantity.add(leaf.value().quantityOf(pins.asset()));
            vaultLovelace = vaultLovelace.add(leaf.vaultLovelace());
        }
        // The vault's lovelace debit: the claims' amounts for the ADA vault, their vaultLovelace otherwise (V6).
        BigInteger lovelaceDebit = pins.adaVault() ? quantity : vaultLovelace;
        BigInteger assetDebit = pins.adaVault() ? BigInteger.ZERO : quantity;
        List<ResolvedOutput> vaultInputs = selectVaultInputs(context, assetDebit, lovelaceDebit);
        BigInteger inLovelace = vaultInputs.stream().map(ResolvedOutput::lovelace).reduce(BigInteger.ZERO,
                BigInteger::add);
        BigInteger inAsset = pins.adaVault() ? BigInteger.ZERO : vaultInputs.stream()
                .map(input -> input.quantityOf(pins.asset())).reduce(BigInteger.ZERO, BigInteger::add);

        MinAdaCalculator minAda = new MinAdaCalculator(pp);
        List<TransactionOutput> outputs = new ArrayList<>();
        BigInteger supplements = BigInteger.ZERO;
        for (ClaimLeafV1 leaf : claims) {
            BigInteger claimQuantity = leaf.value().quantityOf(pins.asset());
            BigInteger base = pins.adaVault() ? claimQuantity : leaf.vaultLovelace();
            TransactionOutput out = output(leaf.recipient(), base,
                    pins.adaVault() ? Map.of() : nonZero(pins.asset(), claimQuantity), null);
            BigInteger required = minAda.calculateMinAda(out);
            if (required.compareTo(base) > 0) {
                out.getValue().setCoin(required);
                supplements = supplements.add(required.subtract(base));
            }
            outputs.add(out);
        }
        BigInteger continuationLovelace = inLovelace.subtract(lovelaceDebit);
        TransactionOutput continuation = output(pins.vaultAddress(), continuationLovelace,
                pins.adaVault() ? Map.of() : nonZero(pins.asset(), inAsset.subtract(assetDebit)),
                GatewayPlutus.continuation(ids));
        if (continuationLovelace.compareTo(pins.continuationFloor()) < 0
                || continuationLovelace.compareTo(minAda.calculateMinAda(continuation)) < 0) {
            throw new BuildException("the continuation would fall below the floor");
        }
        outputs.add(continuation);
        ResolvedOutput shardIn = context.shardInput();
        outputs.add(output(pins.shardAddress(), shardIn.lovelace(), Map.of(pins.shardToken(shard), BigInteger.ONE),
                GatewayPlutus.shardDatum(shard, plan.after())));
        int changeIndex = outputs.size();
        outputs.add(output(context.funding().changeAddress(), BigInteger.ZERO, Map.of(), null));

        // Inputs in ledger order; redeemer indexes refer to it.
        TreeMap<ResolvedOutput.Outpoint, ResolvedOutput> spent = new TreeMap<>();
        vaultInputs.forEach(input -> spent.put(input.outpoint(), input));
        spent.put(shardIn.outpoint(), shardIn);
        for (ResolvedOutput input : context.funding().inputs()) {
            if (!input.assets().isEmpty()) throw new BuildException("funding inputs hold lovelace only");
            if (spent.put(input.outpoint(), input) != null) throw new BuildException("funding input is also spent");
        }
        List<ResolvedOutput.Outpoint> order = new ArrayList<>(spent.keySet());
        Set<ResolvedOutput.Outpoint> vaultSet = new HashSet<>();
        vaultInputs.forEach(input -> vaultSet.add(input.outpoint()));
        ResolvedOutput.Outpoint lead = order.stream().filter(vaultSet::contains).findFirst().orElseThrow();
        PlutusData shardRedeemer = GatewayPlutus.shardRedeemer(plan.inserts());

        List<Redeemer> redeemers = new ArrayList<>();
        BigInteger maxMem = new BigInteger(pp.getMaxTxExMem());
        BigInteger maxSteps = new BigInteger(pp.getMaxTxExSteps());
        for (int index = 0; index < order.size(); index++) {
            ResolvedOutput.Outpoint outpoint = order.get(index);
            PlutusData data = outpoint.equals(lead) ? action : vaultSet.contains(outpoint) ? GatewayPlutus.follow()
                    : outpoint.equals(shardIn.outpoint()) ? shardRedeemer : null;
            if (data == null) continue;
            redeemers.add(Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.valueOf(index)).data(data)
                    .exUnits(ExUnits.builder().mem(maxMem).steps(maxSteps).build()).build());
        }

        // Collateral: the largest funding input, returned less the total collateral.
        ResolvedOutput collateral = context.funding().inputs().stream()
                .max((a, b) -> a.lovelace().compareTo(b.lovelace())).orElseThrow();
        List<TransactionInput> referenceInputs = new ArrayList<>();
        Set<ResolvedOutput.Outpoint> referenced = new java.util.TreeSet<>();
        referenced.add(context.anchor().outpoint());
        referenced.add(context.exitMode().outpoint());
        context.scriptRefs().forEach(ref -> referenced.add(ref.outpoint()));
        referenced.forEach(outpoint -> referenceInputs.add(input(outpoint)));

        TransactionBody body = TransactionBody.builder()
                .inputs(order.stream().map(PayoutTxBuilder::input).toList())
                .outputs(outputs)
                .fee(BigInteger.ZERO)
                .ttl(context.ttlSlot())
                .validityStartInterval(context.validFromSlot())
                .collateral(List.of(input(collateral.outpoint())))
                .referenceInputs(referenceInputs)
                .requiredSigners(requiredSigners.stream().map(byte[]::clone).toList())
                .build();
        TransactionWitnessSet witnesses = new TransactionWitnessSet();
        witnesses.setRedeemers(redeemers);
        Transaction tx = Transaction.builder().body(body).witnessSet(witnesses).isValid(true).build();

        Map<ResolvedOutput.Outpoint, ResolvedOutput> resolved = new LinkedHashMap<>(spent);
        resolved.put(context.anchor().outpoint(), context.anchor());
        resolved.put(context.exitMode().outpoint(), context.exitMode());
        context.scriptRefs().forEach(ref -> resolved.put(ref.outpoint(), ref));

        CostMdls costModels = costModels(pp);
        BigInteger fundingLovelace = context.funding().inputs().stream().map(ResolvedOutput::lovelace)
                .reduce(BigInteger.ZERO, BigInteger::add);
        long refScriptBytes = resolved.values().stream()
                .mapToLong(out -> out.referenceScript().map(ResolvedOutput.ReferenceScript::size).orElse(0)).sum();
        int vkeys = requiredSigners.size() + fundingKeys(context.funding().inputs());
        BigInteger fee = BigInteger.ZERO;
        for (int round = 0; round < 3; round++) {
            body.setFee(fee);
            setChangeAndCollateral(body, changeIndex, fundingLovelace.subtract(fee).subtract(supplements), collateral,
                    fee, pp, context.funding().changeAddress());
            setScriptDataHash(tx, costModels);
            if (round == 0) evaluate(tx, resolved.values(), evaluator);
            setScriptDataHash(tx, costModels);
            BigInteger next = fee(tx, pp, vkeys, refScriptBytes);
            if (next.compareTo(fee) <= 0) break;
            fee = next;
        }
        body.setFee(fee);
        setChangeAndCollateral(body, changeIndex, fundingLovelace.subtract(fee).subtract(supplements), collateral, fee,
                pp, context.funding().changeAddress());
        setScriptDataHash(tx, costModels);
        byte[] cbor = serialize(tx);
        if (cbor.length + vkeys * VKEY_WITNESS_BYTES > pp.getMaxTxSize()) {
            throw new BuildException("the transaction exceeds maxTxSize; split the batch");
        }
        return new Built(tx, cbor, Hash32.ofHex(TransactionUtil.getTxHash(tx)), ids, shard,
                List.copyOf(resolved.values()), plan, fee, requiredSigners);
    }

    /** Accepted custody (V10) at the exact vault address, in canonical order, until the debit and floor are covered. */
    static List<ResolvedOutput> selectVaultInputs(Context context, BigInteger assetDebit, BigInteger lovelaceDebit) {
        VaultPins pins = context.pins();
        List<ResolvedOutput> candidates = new ArrayList<>();
        for (ResolvedOutput utxo : context.vaultUtxos()) {
            if (accepted(utxo, pins)) candidates.add(utxo);
        }
        candidates.sort((a, b) -> a.outpoint().compareTo(b.outpoint()));
        List<ResolvedOutput> selected = new ArrayList<>();
        BigInteger asset = BigInteger.ZERO;
        BigInteger lovelace = BigInteger.ZERO;
        BigInteger lovelaceNeeded = lovelaceDebit.add(pins.continuationFloor());
        for (ResolvedOutput candidate : candidates) {
            if (asset.compareTo(assetDebit) >= 0 && lovelace.compareTo(lovelaceNeeded) >= 0) break;
            if (selected.size() == context.maxVaultInputs()) break;
            selected.add(candidate);
            asset = asset.add(pins.adaVault() ? BigInteger.ZERO : candidate.quantityOf(pins.asset()));
            lovelace = lovelace.add(candidate.lovelace());
        }
        if (asset.compareTo(assetDebit) < 0 || lovelace.compareTo(lovelaceNeeded) < 0) {
            throw new BuildException("accepted vault custody does not cover the batch");
        }
        return selected;
    }

    /**
     * V10, the one acceptance rule: at the exact vault address, a {@code VaultContinuationV1} holding only lovelace and
     * the vault asset, or a {@code FloorTopUpV1} holding lovelace alone.
     */
    public static boolean accepted(ResolvedOutput utxo, VaultPins pins) {
        if (!utxo.sameAddress(pins.vaultAddress()) || utxo.inlineDatum().isEmpty()) return false;
        VaultOutputShape shape = VaultOutputShape.classify(utxo.inlineDatum().get());
        return switch (shape) {
            case CONTINUATION -> utxo.assets().isEmpty()
                    || (!pins.adaVault() && utxo.assets().size() == 1 && utxo.assets().containsKey(pins.asset()));
            case FLOOR_TOP_UP -> utxo.assets().isEmpty();
            default -> false;
        };
    }

    // ------------------------------------------------------------------ fees and units

    private static void evaluate(Transaction tx, Collection<ResolvedOutput> resolved, TransactionEvaluator evaluator) {
        Result<List<EvaluationResult>> result;
        try {
            result = evaluator.evaluateTx(serialize(tx), utxos(resolved));
        } catch (Exception failure) {
            throw new BuildException("script evaluation failed: " + failure.getMessage(), failure);
        }
        if (result == null || !result.isSuccessful() || result.getValue() == null) {
            throw new BuildException("script evaluation failed: " + (result == null ? "no result" : result.getResponse()));
        }
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            EvaluationResult units = result.getValue().stream()
                    .filter(r -> r.getRedeemerTag() == redeemer.getTag() && r.getIndex() == redeemer.getIndex().intValue())
                    .findFirst().orElseThrow(() -> new BuildException("no units for redeemer " + redeemer.getIndex()));
            redeemer.setExUnits(ExUnits.builder()
                    .mem(margin(units.getExUnits().getMem()))
                    .steps(margin(units.getExUnits().getSteps())).build());
        }
    }

    private static BigInteger margin(BigInteger units) {
        return new BigDecimal(units).multiply(UNIT_MARGIN).setScale(0, RoundingMode.CEILING).toBigInteger()
                .add(BigInteger.valueOf(1_000));
    }

    static BigInteger fee(Transaction tx, ProtocolParams pp, int vkeys, long refScriptBytes) {
        long size = serialize(tx).length + (long) vkeys * VKEY_WITNESS_BYTES + SIZE_SLACK_BYTES;
        BigInteger sizeFee = BigInteger.valueOf(pp.getMinFeeA()).multiply(BigInteger.valueOf(size))
                .add(BigInteger.valueOf(pp.getMinFeeB()));
        BigDecimal mem = BigDecimal.ZERO;
        BigDecimal steps = BigDecimal.ZERO;
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            mem = mem.add(new BigDecimal(redeemer.getExUnits().getMem()));
            steps = steps.add(new BigDecimal(redeemer.getExUnits().getSteps()));
        }
        BigInteger scriptFee = pp.getPriceMem().multiply(mem).add(pp.getPriceStep().multiply(steps))
                .setScale(0, RoundingMode.CEILING).toBigInteger();
        return sizeFee.add(scriptFee).add(refScriptFee(pp, refScriptBytes));
    }

    /** Conway's tiered reference-script fee: each 25,600-byte tier costs 1.2 times the previous one per byte. */
    static BigInteger refScriptFee(ProtocolParams pp, long bytes) {
        BigDecimal price = pp.getMinFeeRefScriptCostPerByte() == null ? BigDecimal.ZERO
                : pp.getMinFeeRefScriptCostPerByte();
        BigDecimal total = BigDecimal.ZERO;
        long remaining = bytes;
        while (remaining >= REF_SCRIPT_SIZE_INCREMENT) {
            total = total.add(price.multiply(BigDecimal.valueOf(REF_SCRIPT_SIZE_INCREMENT)));
            price = price.multiply(REF_SCRIPT_MULTIPLIER);
            remaining -= REF_SCRIPT_SIZE_INCREMENT;
        }
        total = total.add(price.multiply(BigDecimal.valueOf(remaining)));
        return total.setScale(0, RoundingMode.CEILING).toBigInteger();
    }

    private static void setChangeAndCollateral(TransactionBody body, int changeIndex, BigInteger change,
                                               ResolvedOutput collateral, BigInteger fee, ProtocolParams pp,
                                               byte[] changeAddress) {
        TransactionOutput out = body.getOutputs().get(changeIndex);
        BigInteger min = new MinAdaCalculator(pp).calculateMinAda(out);
        if (change.compareTo(min) < 0) throw new BuildException("funding does not cover the fee and supplements");
        out.getValue().setCoin(change);
        BigInteger total = new BigDecimal(fee).multiply(pp.getCollateralPercent())
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).toBigInteger();
        BigInteger returned = collateral.lovelace().subtract(total);
        TransactionOutput collateralReturn = output(changeAddress, returned.max(BigInteger.ZERO), Map.of(), null);
        if (returned.compareTo(new MinAdaCalculator(pp).calculateMinAda(collateralReturn)) < 0) {
            throw new BuildException("the collateral input cannot cover the collateral");
        }
        body.setTotalCollateral(total);
        body.setCollateralReturn(collateralReturn);
    }

    static void setScriptDataHash(Transaction tx, CostMdls costModels) {
        try {
            List<PlutusData> datums = tx.getWitnessSet().getPlutusDataList() == null ? List.of()
                    : tx.getWitnessSet().getPlutusDataList();
            tx.getBody().setScriptDataHash(ScriptDataHashGenerator.generate(tx.getWitnessSet().getRedeemers(),
                    datums, costModels));
        } catch (Exception failure) {
            throw new BuildException("script data hash", failure);
        }
    }

    /** The PlutusV3 cost model of {@code pp}, the one the script integrity hash commits to. */
    public static CostMdls costModels(ProtocolParams pp) {
        CostModel model = CostModelUtil.getCostModelFromProtocolParams(pp, Language.PLUTUS_V3)
                .orElseThrow(() -> new BuildException("protocol parameters carry no PlutusV3 cost model"));
        CostMdls models = new CostMdls();
        models.add(model);
        return models;
    }

    private static int fundingKeys(List<ResolvedOutput> inputs) {
        Set<String> keys = new HashSet<>();
        for (ResolvedOutput input : inputs) {
            input.paymentCredential().filter(credential -> !credential.script())
                    .ifPresent(credential -> keys.add(HexFormat.of().formatHex(credential.hash())));
        }
        return Math.max(1, keys.size());
    }

    // ------------------------------------------------------------------ conversions

    static byte[] serialize(Transaction tx) {
        try {
            return tx.serialize();
        } catch (Exception failure) {
            throw new BuildException("transaction serialization", failure);
        }
    }

    static TransactionInput input(ResolvedOutput.Outpoint outpoint) {
        return new TransactionInput(outpoint.txIdHex(), outpoint.index());
    }

    private static Map<AssetId, BigInteger> nonZero(AssetId asset, BigInteger quantity) {
        return quantity.signum() == 0 ? Map.of() : Map.of(asset, quantity);
    }

    static TransactionOutput output(byte[] address, BigInteger lovelace, Map<AssetId, BigInteger> assets,
                                    PlutusData inlineDatum) {
        TransactionOutput out = TransactionOutput.builder()
                .address(new Address(address).toBech32())
                .value(value(lovelace, assets))
                .build();
        if (inlineDatum != null) out.setInlineDatum(inlineDatum);
        return out;
    }

    static Value value(BigInteger lovelace, Map<AssetId, BigInteger> assets) {
        Map<String, List<Asset>> byPolicy = new TreeMap<>();
        new TreeMap<>(assets).forEach((asset, quantity) -> byPolicy
                .computeIfAbsent(HexFormat.of().formatHex(asset.policyId()), ignored -> new ArrayList<>())
                .add(new Asset("0x" + HexFormat.of().formatHex(asset.assetName()), quantity)));
        List<MultiAsset> multiAssets = new ArrayList<>();
        byPolicy.forEach((policy, list) -> multiAssets.add(new MultiAsset(policy, list)));
        return Value.builder().coin(lovelace).multiAssets(multiAssets).build();
    }

    /** The resolved outputs as cardano-client-lib UTxOs, for a {@link TransactionEvaluator}. */
    public static Set<Utxo> utxos(Collection<ResolvedOutput> outputs) {
        Set<Utxo> set = new HashSet<>();
        for (ResolvedOutput out : outputs) set.add(utxo(out));
        return set;
    }

    public static Utxo utxo(ResolvedOutput out) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(out.lovelace()));
        out.assets().forEach((asset, quantity) -> amounts.add(Amount.asset(
                HexFormat.of().formatHex(asset.policyId()) + HexFormat.of().formatHex(asset.assetName()), quantity)));
        Utxo utxo = new Utxo();
        utxo.setTxHash(out.outpoint().txIdHex());
        utxo.setOutputIndex(out.outpoint().index());
        utxo.setAddress(new Address(out.address()).toBech32());
        utxo.setAmount(amounts);
        out.inlineDatum().ifPresent(datum -> utxo.setInlineDatum(HexFormat.of().formatHex(datum)));
        out.referenceScript().ifPresent(script -> utxo.setReferenceScriptHash(
                HexFormat.of().formatHex(script.scriptHash())));
        return utxo;
    }

    static Optional<ResolvedOutput> find(Collection<ResolvedOutput> outputs, ResolvedOutput.Outpoint outpoint) {
        return outputs.stream().filter(out -> out.outpoint().equals(outpoint)).findFirst();
    }
}
