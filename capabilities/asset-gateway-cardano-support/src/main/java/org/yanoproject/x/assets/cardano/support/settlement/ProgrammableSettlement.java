package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The owner's CIP-113 vault transactions (ADR-061 D5, D10): the co-signed {@code Settle} and {@code Accept} of a vault
 * whose custody is a smart wallet, laid out by {@link ProgrammableVaultTxAssembler}. They select and pay exactly as
 * the native builders do ({@link PayoutTxBuilder}, {@link AcceptTxBuilder}): the same accepted custody (V10), the same
 * payouts and continuation (V5 to V7), the same shard fold. What differs is the authorization: every vault input is a
 * PLB input with {@code BaseSpendRedeemer}, and the action is the custody script {@code h}'s withdrawal redeemer.
 */
public final class ProgrammableSettlement {
    private ProgrammableSettlement() {
    }

    /**
     * What a CIP-113 build reads beyond the native context: the live wiring, the L1 registry and outpoint reads, the
     * asset's substandard adapter and the live reward balances, all from the builder's own L1 view.
     */
    public record Cip113(ProgrammableVaultTxAssembler.LiveWiring wiring, ProgrammableVaultTxAssembler.L1 l1,
                         Cip113Requirements.Adapter adapter, ProgrammableVaultTxAssembler.RewardBalances balances) {
        public Cip113 {
            Objects.requireNonNull(wiring, "wiring");
            Objects.requireNonNull(l1, "l1");
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(balances, "balances");
        }
    }

    /**
     * The co-signed {@code Settle} of {@code claims} on their one shard: the CIP-113 form of
     * {@link PayoutTxBuilder#settle}. {@code context.scriptRefs()} are every reference-script UTxO the transaction
     * runs (PLB, the dispatcher, the transfer delegate, the hook, {@code h} and the shard).
     */
    public static PayoutTxBuilder.Built settle(PayoutTxBuilder.Context context, Cip113 cip113, List<ClaimLeafV1> claims,
                                               ShardMirror mirror, List<byte[]> requiredSigners,
                                               TransactionEvaluator evaluator) {
        VaultPins pins = requireProgrammable(context.pins());
        if (claims.isEmpty() || claims.size() > pins.maxSettleBatch()) {
            throw new PayoutTxBuilder.BuildException("a Settle pays 1-" + pins.maxSettleBatch() + " claims");
        }
        if (requiredSigners.isEmpty()) {
            throw new PayoutTxBuilder.BuildException("a Settle needs the members as required signers");
        }
        int shard = SettlementBatchV1.shardOf(claims.getFirst().claimId());
        Set<Hash32> seen = new HashSet<>();
        for (ClaimLeafV1 leaf : claims) {
            if (leaf.kind() != ClaimKind.USER_WITHDRAWAL) {
                throw new PayoutTxBuilder.BuildException("Settle pays user claims only");
            }
            if (!leaf.vaultId().equals(pins.vaultId())) throw new PayoutTxBuilder.BuildException("claim of another vault");
            if (SettlementBatchV1.shardOf(leaf.claimId()) != shard) {
                throw new PayoutTxBuilder.BuildException("claims of two shards");
            }
            if (!seen.add(leaf.claimId())) throw new PayoutTxBuilder.BuildException("duplicate claim");
        }
        ResolvedOutput shardIn = context.shardInput();
        if (!PayoutTxBuilder.shardOnly(shardIn, pins, shard) || !shardIn.sameAddress(pins.shardAddress())) {
            throw new PayoutTxBuilder.BuildException("the shard input is not shard " + shard
                    + " of this vault holding only its thread token");
        }
        if (mirror.shardIndex() != shard) throw new PayoutTxBuilder.BuildException("mirror of another shard");
        GatewayDatums.ShardDatum shardDatum = shardIn.inlineDatum().flatMap(GatewayDatums::shardDatum)
                .filter(datum -> datum.shardIndex() == shard)
                .orElseThrow(() -> new PayoutTxBuilder.BuildException("the shard input has no shard datum"));
        if (!mirror.matches(shardDatum.nullifierRoot())) {
            throw new PayoutTxBuilder.BuildException("the shard mirror does not match the live shard root");
        }
        List<Hash32> ids = claims.stream().map(ClaimLeafV1::claimId).toList();
        for (Hash32 id : ids) {
            if (mirror.contains(id)) throw new PayoutTxBuilder.BuildException("claim " + id + " is already paid on L1");
        }
        ShardMirror.Plan plan = mirror.plan(ids);

        BigInteger quantity = BigInteger.ZERO;
        BigInteger lovelaceDebit = BigInteger.ZERO;
        for (ClaimLeafV1 leaf : claims) {
            quantity = quantity.add(leaf.value().quantityOf(pins.asset()));
            lovelaceDebit = lovelaceDebit.add(leaf.vaultLovelace());
        }
        List<ResolvedOutput> vaultInputs = PayoutTxBuilder.selectVaultInputs(context, quantity, lovelaceDebit, ids);
        BigInteger inLovelace = sum(vaultInputs, ResolvedOutput::lovelace);
        BigInteger inAsset = sum(vaultInputs, in -> in.quantityOf(pins.asset()));

        ProtocolParams pp = context.protocolParams();
        MinAdaCalculator minAda = new MinAdaCalculator(pp);
        List<TransactionOutput> outputs = new ArrayList<>();
        for (ClaimLeafV1 leaf : claims) {
            TransactionOutput out = PayoutTxBuilder.payoutOutput(pins, leaf);
            BigInteger required = minAda.calculateMinAda(out);
            if (required.compareTo(out.getValue().getCoin()) > 0) out.getValue().setCoin(required);
            outputs.add(out);
        }
        BigInteger continuationLovelace = inLovelace.subtract(lovelaceDebit);
        TransactionOutput continuation = PayoutTxBuilder.output(pins.vaultAddress(), continuationLovelace,
                nonZero(pins.asset(), inAsset.subtract(quantity)), GatewayPlutus.continuation(ids));
        if (continuationLovelace.compareTo(pins.continuationFloor()) < 0
                || continuationLovelace.compareTo(minAda.calculateMinAda(continuation)) < 0) {
            throw new PayoutTxBuilder.BuildException("the continuation would fall below the floor");
        }
        outputs.add(continuation);
        outputs.add(PayoutTxBuilder.output(pins.shardAddress(), shardIn.lovelace(),
                Map.of(pins.shardToken(shard), BigInteger.ONE), GatewayPlutus.shardDatum(shard, plan.after())));

        List<ResolvedOutput> references = new ArrayList<>(List.of(context.anchor(), context.exitMode()));
        references.addAll(context.scriptRefs());
        PlutusData action = GatewayPlutus.settle(claims, pins.asset(), shard);
        ProgrammableVaultTxAssembler.Plan txPlan = new ProgrammableVaultTxAssembler.Plan(vaultInputs,
                List.of(new ProgrammableVaultTxAssembler.ScriptSpend(shardIn, GatewayPlutus.shardRedeemer(
                        plan.inserts()))), context.funding(), outputs, references,
                List.of(new ProgrammableVaultTxAssembler.CustodyWithdrawal(pins.vaultScriptHash(), action)),
                requiredSigners, context.validFromSlot(), context.ttlSlot());
        ProgrammableVaultTxAssembler.Assembled assembled = assemble(pins, cip113, txPlan, pp, evaluator);
        return new PayoutTxBuilder.Built(assembled.transaction(), assembled.cbor(), assembled.txId(), ids, shard,
                assembled.resolved(), plan, assembled.fee(), requiredSigners);
    }

    /**
     * The co-signed {@code Accept} of {@code deposits}: deposit-shaped outputs of the vault holding only its asset and
     * lovelace, merged into one empty continuation (output 0). Each deposit's id is the origin-keyed {@code DepositId}
     * of its datum (D6), never its live outpoint.
     */
    public static AcceptTxBuilder.Built acceptCosigned(AcceptTxBuilder.Context context, Cip113 cip113,
                                                       List<ResolvedOutput> deposits, List<byte[]> requiredSigners,
                                                       TransactionEvaluator evaluator) {
        VaultPins pins = requireProgrammable(context.pins());
        if (deposits.isEmpty() || deposits.size() > AcceptTxBuilder.MAX_COSIGNED) {
            throw new PayoutTxBuilder.BuildException("a co-signed Accept merges 1-" + AcceptTxBuilder.MAX_COSIGNED
                    + " deposits");
        }
        if (requiredSigners.isEmpty()) {
            throw new PayoutTxBuilder.BuildException("a co-signed Accept needs the members as required signers");
        }
        List<ResolvedOutput> ordered = new ArrayList<>(deposits);
        ordered.sort(Comparator.comparing(ResolvedOutput::outpoint));
        List<Hash32> ids = new ArrayList<>();
        for (ResolvedOutput deposit : ordered) {
            ids.add(depositId(pins, deposit).orElseThrow(() -> new PayoutTxBuilder.BuildException(
                    "input " + deposit.outpoint() + " is not a deposit of this vault holding only its asset")));
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new PayoutTxBuilder.BuildException("two inputs carry one DepositId");
        }
        BigInteger lovelace = sum(ordered, ResolvedOutput::lovelace);
        BigInteger asset = sum(ordered, in -> in.quantityOf(pins.asset()));
        List<TransactionOutput> outputs = List.of(PayoutTxBuilder.output(pins.vaultAddress(), lovelace,
                nonZero(pins.asset(), asset), GatewayPlutus.continuation(List.of())));
        List<ResolvedOutput> references = new ArrayList<>(List.of(context.anchor(), context.exitMode()));
        references.addAll(context.scriptRefs());
        ProgrammableVaultTxAssembler.Plan plan = new ProgrammableVaultTxAssembler.Plan(ordered, List.of(),
                context.funding(), outputs, references,
                List.of(new ProgrammableVaultTxAssembler.CustodyWithdrawal(pins.vaultScriptHash(),
                        GatewayPlutus.acceptCosigned())), requiredSigners, context.validFromSlot(), context.ttlSlot());
        ProgrammableVaultTxAssembler.Assembled assembled = assemble(pins, cip113, plan, context.protocolParams(),
                evaluator);
        return new AcceptTxBuilder.Built(assembled.transaction(), assembled.cbor(), assembled.txId(), ordered, ids,
                assembled.resolved(), assembled.fee(), requiredSigners);
    }

    /**
     * The origin-keyed {@code DepositId} of {@code utxo} when it is a deposit of this CIP-113 vault that {@code Accept}
     * can spend: at the vault smart wallet, deposit-shaped by the one total predicate (D6), and holding only the
     * vault's asset and lovelace. Empty otherwise.
     */
    public static Optional<Hash32> depositId(VaultPins pins, ResolvedOutput utxo) {
        if (pins.programmable().isEmpty() || !utxo.sameAddress(pins.vaultAddress()) || utxo.inlineDatum().isEmpty()) {
            return Optional.empty();
        }
        boolean onlyAsset = utxo.assets().isEmpty()
                || (utxo.assets().size() == 1 && utxo.assets().containsKey(pins.asset()));
        if (!onlyAsset) return Optional.empty();
        ProgrammableVaultTxAssembler.Deployment deployment = pins.programmable().get().deployment();
        return ProgrammableDepositDatumV1.depositShaped(utxo.inlineDatum().get(), deployment.plbHash(),
                deployment.networkId()).map(datum -> datum.depositId(pins.vaultId()));
    }

    private static ProgrammableVaultTxAssembler.Assembled assemble(VaultPins pins, Cip113 cip113,
                                                                   ProgrammableVaultTxAssembler.Plan plan,
                                                                   ProtocolParams pp, TransactionEvaluator evaluator) {
        try {
            return ProgrammableVaultTxAssembler.assemble(pins.programmable().orElseThrow().deployment(),
                    cip113.wiring(), plan, cip113.l1(), cip113.adapter(), cip113.balances(), pp, evaluator);
        } catch (Cip113Requirements.Unassemblable unassemblable) {
            throw new PayoutTxBuilder.BuildException(unassemblable.getMessage(), unassemblable);
        }
    }

    private static VaultPins requireProgrammable(VaultPins pins) {
        if (pins.programmable().isEmpty()) throw new IllegalArgumentException("not a CIP-113 vault");
        return pins;
    }

    private static Map<AssetId, BigInteger> nonZero(AssetId asset, BigInteger quantity) {
        return quantity.signum() == 0 ? Map.of() : Map.of(asset, quantity);
    }

    private static BigInteger sum(List<ResolvedOutput> outputs,
                                  java.util.function.Function<ResolvedOutput, BigInteger> value) {
        return outputs.stream().map(value).reduce(BigInteger.ZERO, BigInteger::add);
    }
}
