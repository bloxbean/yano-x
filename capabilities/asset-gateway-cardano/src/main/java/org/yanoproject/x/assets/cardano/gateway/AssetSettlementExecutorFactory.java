package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.model.TxEvaluationResult;
import org.yanoproject.x.assets.cardano.support.settlement.AcceptBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.Hash32;
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
import java.util.Optional;
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
 * <p>The members' co-sign service checks both co-signed bodies: a {@code Settle} with {@link SettleBodyCheck} and a
 * co-signed {@code Accept} with {@link AcceptBodyCheck} (D8h §1). The owner also batches credited deposits into
 * {@code Accept}s ({@link DepositAcceptor}, {@code accept.interval-ms}); any node with {@code exit-relayer.enabled}
 * relays {@code EXIT_ELIGIBLE} claims by proof {@code Exit} ({@link ExitRelayTask}), with state proofs from this
 * node at the referenced live anchor's height, and a relaying non-owner also submits proved {@code Accept}s. They
 * run on a timer owned by the executor product, which stops it on close; on the owner they share the {@code Settle}
 * executor's assembly lock, pending-spend holds and fee wallet ({@link OwnerAssembly}), and a relaying non-owner has
 * its own.
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
        return create(config, context, System::currentTimeMillis, true);
    }

    /** The context overload with this node's wall clock, which tests replace, and no timer: tests run the passes. */
    static List<AppEffectExecutor> create(Map<String, String> config, AppChainEffectContext context,
                                          LongSupplier clock) {
        return create(config, context, clock, false);
    }

    static List<AppEffectExecutor> create(Map<String, String> config, AppChainEffectContext context,
                                          LongSupplier clock, boolean schedule) {
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
        TransactionEvaluator evaluator = evaluator(context::txEvaluation);
        NodeServices node = new NodeServices(settings, l1, committed, context.protocolParams(), evaluator,
                context::submitTx, clock, NodeServices.proofsOf(context));
        int network = settings.vaults().domain().addressNetworkId();
        AssetSettlementSettings.Tasks taskSettings = settings.tasks();
        // D15: every node may reconcile; it only reads its own L1 view and committed state.
        ReconciliationTask reconciliation = taskSettings.reconciliationIntervalMs() > 0
                ? new ReconciliationTask(l1, committed, settings.pins(), taskSettings.reconciliationReport(), clock)
                : null;
        if (!settings.owner()) {
            if (!taskSettings.exitRelayer()) {
                CosignOnly product = new CosignOnly(cosign, null, null, reconciliation);
                if (schedule && reconciliation != null) {
                    SettlementTasks tasks = new SettlementTasks(context.chainId());
                    tasks.every(taskSettings.reconciliationIntervalMs(), "asset reconciliation",
                            reconciliation::runOnce);
                    product.ownTasks(tasks);
                }
                return products(product, null, null, reconciliation);
            }
            // A relaying non-owner assembles alone, with its own fee wallet; it also proves Accepts (Fable P2-2), so
            // custody keeps flowing into the vault while the owner is down.
            FeeWallet wallet = new FeeWallet(taskSettings.relayerSeed().orElseThrow(), network, memberKey,
                    "exit-relayer.fee-wallet.key-file");
            OwnerAssembly assembly = new OwnerAssembly(clock.getAsLong(), settings.slots(),
                    OwnerAssembly.ttlFileFor(settings.pendingTtlFile(), context.chainId(), wallet.address()));
            ExitRelayTask relayer = new ExitRelayTask(node, assembly, wallet);
            DepositAcceptor acceptor = taskSettings.acceptIntervalMs() > 0
                    ? DepositAcceptor.provedOnly(node, assembly, wallet) : null;
            CosignOnly product = new CosignOnly(cosign, relayer, acceptor, reconciliation);
            if (schedule) {
                SettlementTasks tasks = new SettlementTasks(context.chainId());
                tasks.every(taskSettings.exitRelayIntervalMs(), "exit relayer", relayer::runOnce);
                if (acceptor != null) {
                    tasks.every(taskSettings.acceptIntervalMs(), "proved deposit acceptor", acceptor::runOnce);
                }
                if (reconciliation != null) {
                    tasks.every(taskSettings.reconciliationIntervalMs(), "asset reconciliation",
                            reconciliation::runOnce);
                }
                product.ownTasks(tasks);
            }
            return products(product, acceptor, relayer, reconciliation);
        }
        FeeWallet wallet = new FeeWallet(settings.feeWalletSeed().orElseThrow(() ->
                new IllegalArgumentException("the settlement owner needs fee-wallet.key-file")), network, memberKey,
                "fee-wallet.key-file");
        OwnerAssembly assembly = new OwnerAssembly(clock.getAsLong(), settings.slots(),
                OwnerAssembly.ttlFileFor(settings.pendingTtlFile(), context.chainId(), wallet.address()));
        AssetSettlementExecutor executor = new AssetSettlementExecutor(settings, l1, committed, cosign,
                context.protocolParams(), evaluator, context::submitTx, clock, memberKey, assembly, wallet);
        DepositAcceptor acceptor = taskSettings.acceptIntervalMs() > 0
                ? new DepositAcceptor(node, cosign, memberKey, assembly, wallet) : null;
        ExitRelayTask relayer = taskSettings.exitRelayer() ? new ExitRelayTask(node, assembly, wallet) : null;
        executor.wire(acceptor, relayer);
        if (schedule && (acceptor != null || relayer != null || reconciliation != null)) {
            SettlementTasks tasks = new SettlementTasks(context.chainId());
            if (acceptor != null) tasks.every(taskSettings.acceptIntervalMs(), "deposit acceptor", acceptor::runOnce);
            if (relayer != null) tasks.every(taskSettings.exitRelayIntervalMs(), "exit relayer", relayer::runOnce);
            if (reconciliation != null) {
                tasks.every(taskSettings.reconciliationIntervalMs(), "asset reconciliation", reconciliation::runOnce);
            }
            executor.ownTasks(tasks);
        }
        return products(executor, acceptor, relayer, reconciliation);
    }

    /**
     * The chain's products on this node: the one that owns the tasks first, then one {@link TaskProduct} per task
     * that runs here, whose executor snapshot is that task's status and metrics (D15a).
     */
    private static List<AppEffectExecutor> products(AppEffectExecutor owner, DepositAcceptor acceptor,
                                                    ExitRelayTask relayer, ReconciliationTask reconciliation) {
        List<AppEffectExecutor> products = new ArrayList<>();
        products.add(owner);
        if (acceptor != null) products.add(acceptor.product());
        if (relayer != null) products.add(relayer.product());
        if (reconciliation != null) products.add(reconciliation.product());
        return List.copyOf(products);
    }

    /**
     * The members' body check, for a co-signed {@code Accept} or a {@code Settle}: the vault the request spends (the
     * first configured vault with a resolved input at its address), against this node's own state, with the validity
     * interval bounded by this node's own L1 time (its clock on the pinned slot clock). Only that vault is checked, so
     * its refusal is the one reported.
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
            // No input at any vault (or none resolved): the first vault's check reports why.
            VaultPins pins = spentVault(tx, settings.pins(), l1).orElse(settings.pins().getFirst());
            SettleBodyCheck.Tip tip = new SettleBodyCheck.Tip(settings.slots().slotAt(clock.getAsLong()),
                    settings.slots().slotLengthMs());
            if (AcceptBodyCheck.isAcceptRequest(tx)) {
                return AcceptBodyCheck.check(tx, pins, memberKeyHash, l1::resolve, committed,
                        PayoutTxBuilder.costModels(params), tip);
            }
            return SettleBodyCheck.check(tx, pins, memberKeyHash, l1::resolve, committed,
                    PayoutTxBuilder.costModels(params), tip);
        };
    }

    /** The first configured vault with an input of {@code tx} at its address, resolved in this node's own view. */
    static Optional<VaultPins> spentVault(byte[] tx, List<VaultPins> vaults, NodeL1View l1) {
        List<ResolvedOutput> inputs = new ArrayList<>();
        try {
            for (TransactionInput input : Transaction.deserialize(tx).getBody().getInputs()) {
                l1.resolve(new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()), input.getIndex()))
                        .ifPresent(inputs::add);
            }
        } catch (Exception malformed) {
            return Optional.empty();
        }
        return vaults.stream().filter(pins -> inputs.stream().anyMatch(in -> in.sameAddress(pins.vaultAddress())))
                .findFirst();
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

    /**
     * {@code snapshot} (the {@code Settle} attempts, D15a), {@code DEGRADED} with a {@code CONFIGURATION} failure while
     * this member's last co-sign refusal was {@code l1-tip-bound} (see {@link #cosignSnapshot}).
     */
    static EffectExecutorOperationalSnapshot withCosign(EffectExecutorOperationalSnapshot snapshot,
                                                        AssetCosignService cosign) {
        boolean tipBound = cosign.lastRefusal()
                .map(refusal -> refusal.kind() == SettleBodyCheck.Refusal.Kind.L1_TIP_BOUND).orElse(false);
        if (!tipBound) return snapshot;
        return new EffectExecutorOperationalSnapshot(EffectExecutorOperationalSnapshot.Readiness.DEGRADED,
                snapshot.attempts(), snapshot.successes(), snapshot.retryableFailures(), snapshot.terminalFailures(),
                snapshot.inFlight(), snapshot.lastSuccessAge(), snapshot.lastFailureAge(),
                EffectExecutorOperationalSnapshot.FailureCode.CONFIGURATION);
    }

    /**
     * A member that co-signs but never settles: it owns no effect type. When it relays exits (and proves
     * {@code Accept}s) or reconciles, it owns their timer and stops it on close.
     */
    static final class CosignOnly implements AppEffectExecutor {
        private final AssetCosignService cosign;
        private final ExitRelayTask relayer;
        private final DepositAcceptor acceptor;
        private final ReconciliationTask reconciliation;
        private volatile AutoCloseable tasks;

        CosignOnly(AssetCosignService cosign, ExitRelayTask relayer, DepositAcceptor acceptor,
                   ReconciliationTask reconciliation) {
            this.cosign = cosign;
            this.relayer = relayer;
            this.acceptor = acceptor;
            this.reconciliation = reconciliation;
        }

        Optional<ReconciliationTask> reconciliation() {
            return Optional.ofNullable(reconciliation);
        }

        Optional<ExitRelayTask> relayer() {
            return Optional.ofNullable(relayer);
        }

        Optional<DepositAcceptor> acceptor() {
            return Optional.ofNullable(acceptor);
        }

        void ownTasks(AutoCloseable tasks) {
            this.tasks = tasks;
        }

        @Override
        public void close() {
            AutoCloseable owned = tasks;
            if (owned == null) return;
            try {
                owned.close();
            } catch (Exception ignored) {
                // Bounded shutdown: the scheduler is stopped whatever the close reports.
            }
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
