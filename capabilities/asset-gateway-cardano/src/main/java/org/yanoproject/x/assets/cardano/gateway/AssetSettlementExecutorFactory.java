package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.model.TxEvaluationResult;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;
import org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The {@code asset-settlement} effect-executor contribution (ADR-059 D1, D9), activated per chain by
 * {@code effects.executors.asset-settlement.*} ({@link AssetSettlementSettings}). One executor serves every vault of a
 * chain (D8h §7).
 *
 * <p>Every member registers the co-sign service, which answers {@code ~bridge/asset-settlement/sign} with its own
 * D8h body check against its own L1 view and committed state. Only the {@code owner=true} node (single-owner pinning)
 * also gets the executor that drives {@code asset.settlement.batch} effects; every other member gets a product that
 * owns no effect type, because a chain with effects enabled and no executor product fails activation, and members
 * must not race to submit the same batch.
 *
 * <p>The proof {@code Exit} relayer ({@code ExitRelayer} in the support library) needs state proofs at the referenced
 * anchor's height, which this context does not serve; it is wired with the node's proof API in a later stage.
 */
public final class AssetSettlementExecutorFactory implements AppEffectExecutorFactory {
    public static final String SCHEME = "asset-settlement";

    @Override
    public String scheme() {
        return SCHEME;
    }

    /** Config-only creation cannot wire the node-coupled stack; the runtime always calls the context overload. */
    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        return List.of();
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config, AppChainEffectContext context) {
        return create(config, context, System::currentTimeMillis);
    }

    /** The context overload with this node's wall clock, which tests replace. */
    static List<AppEffectExecutor> create(Map<String, String> config, AppChainEffectContext context,
                                          LongSupplier clock) {
        if (context == null) return List.of();
        AssetSettlementSettings settings = AssetSettlementSettings.parse(config);
        NodeL1View l1 = new NodeL1View(context.l1UtxoView());
        CommittedClaims committed = new CommittedClaims((path, request) -> {
            AppQueryResult result = context.query(path, request);
            return result == null ? null : result.payload();
        });
        byte[] memberKey = context.memberSigner().publicKey();
        byte[] memberKeyHash = Blake2bUtil.blake2bHash224(memberKey);
        AssetCosignService cosign = new AssetCosignService(context::diffuse, context.memberSigner(),
                bodyCheck(settings, l1, committed, memberKeyHash, context, clock), settings.roundTimeout());
        context.registerBridgeDiffusionHandler(cosign);
        if (!settings.owner()) return List.of(new CosignOnly(cosign));
        return List.of(new AssetSettlementExecutor(settings, l1, committed, cosign, context.protocolParams(),
                evaluator(context::txEvaluation), context::submitTx, clock, memberKey));
    }

    /**
     * The members' body check: the vault the request spends, against this node's own state, with the validity
     * interval bounded by this node's own L1 time (its clock on the pinned slot clock).
     */
    static java.util.function.Function<byte[], SettleBodyCheck.Outcome> bodyCheck(
            AssetSettlementSettings settings, NodeL1View l1, CommittedClaims committed, byte[] memberKeyHash,
            AppChainEffectContext context, LongSupplier clock) {
        return tx -> {
            var params = context.protocolParams().get();
            if (params == null) {
                return new SettleBodyCheck.Refused(new SettleBodyCheck.Refusal(
                        SettleBodyCheck.Refusal.Kind.UNRESOLVED_INPUT, "no protocol parameters yet"));
            }
            SettleBodyCheck.Outcome last = new SettleBodyCheck.Refused(new SettleBodyCheck.Refusal(
                    SettleBodyCheck.Refusal.Kind.HAZARD, "no configured vault"));
            SettleBodyCheck.Tip tip = new SettleBodyCheck.Tip(settings.slots().slotAt(clock.getAsLong()),
                    settings.slots().slotLengthMs());
            for (VaultPins pins : settings.pins()) {
                last = SettleBodyCheck.check(tx, pins, memberKeyHash, l1::resolve, committed,
                        PayoutTxBuilder.costModels(params), tip);
                if (last instanceof SettleBodyCheck.Signable) return last;
            }
            return last;
        };
    }

    /** The node's phase-2 evaluator as a cardano-client-lib evaluator. */
    static TransactionEvaluator evaluator(Supplier<TxEvaluationGateway> gateway) {
        return (cbor, inputs) -> {
            TxEvaluationGateway evaluation = gateway.get();
            if (evaluation == null || !evaluation.isTransactionEvaluationAvailable()) {
                return Result.error("transaction evaluation is unavailable");
            }
            try {
                List<EvaluationResult> results = new ArrayList<>();
                for (TxEvaluationResult result : evaluation.evaluateTransaction(cbor)) {
                    EvaluationResult converted = new EvaluationResult();
                    converted.setRedeemerTag(switch (result.tag().trim().toLowerCase(Locale.ROOT)) {
                        case "spend" -> RedeemerTag.Spend;
                        case "mint" -> RedeemerTag.Mint;
                        case "cert" -> RedeemerTag.Cert;
                        case "reward" -> RedeemerTag.Reward;
                        default -> throw new IllegalArgumentException("unsupported redeemer tag " + result.tag());
                    });
                    converted.setIndex(result.index());
                    converted.setExUnits(ExUnits.builder().mem(BigInteger.valueOf(result.memory()))
                            .steps(BigInteger.valueOf(result.steps())).build());
                    results.add(converted);
                }
                return Result.success("evaluated").withValue(results);
            } catch (Exception failure) {
                return Result.error(String.valueOf(failure.getMessage()));
            }
        };
    }

    /**
     * The co-sign service's operational status: {@code DEGRADED} with a {@code CONFIGURATION} failure while this
     * member's last refusal was {@code l1-tip-bound}, which an honest owner's body triggers only when this member's
     * clock lags the owner's by more than the allowed skew (D8h §1 step 0). Unknown otherwise.
     */
    static EffectExecutorOperationalSnapshot cosignSnapshot(AssetCosignService cosign) {
        boolean tipBound = cosign.lastRefusal()
                .map(refusal -> refusal.kind() == SettleBodyCheck.Refusal.Kind.L1_TIP_BOUND).orElse(false);
        if (!tipBound) return EffectExecutorOperationalSnapshot.unknown();
        return new EffectExecutorOperationalSnapshot(EffectExecutorOperationalSnapshot.Readiness.DEGRADED, 0, 0, 0, 0,
                0, EffectExecutorOperationalSnapshot.AgeBucket.NEVER,
                EffectExecutorOperationalSnapshot.AgeBucket.UNKNOWN,
                EffectExecutorOperationalSnapshot.FailureCode.CONFIGURATION);
    }

    /** A member that co-signs but never settles: it owns no effect type. */
    static final class CosignOnly implements AppEffectExecutor {
        private final AssetCosignService cosign;

        CosignOnly(AssetCosignService cosign) {
            this.cosign = cosign;
        }

        @Override
        public EffectExecutorOperationalSnapshot operationalSnapshot() {
            return cosignSnapshot(cosign);
        }

        @Override
        public String id() {
            return SCHEME;
        }

        @Override
        public Set<String> effectTypes() {
            return Set.of();
        }

        @Override
        public boolean supports(String effectType) {
            return false;
        }

        @Override
        public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
            throw new IllegalStateException("the co-sign-only " + SCHEME + " product owns no effect type (got "
                    + (effect == null ? "null" : effect.type()) + "; " + SettlementBatchV1.EFFECT_TYPE
                    + " runs on the owner)");
        }
    }
}
