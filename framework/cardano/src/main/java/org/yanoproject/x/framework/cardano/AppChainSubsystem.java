package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.yanoproject.x.framework.api.appchain.AppAnchorCommitment;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.SequencedL1Observation;
import org.yanoproject.x.framework.api.appchain.codec.AppBlockCodec;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochObserver;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochStateProvider;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;
import org.yanoproject.x.framework.api.appchain.state.StateProofEnvelope;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;
import org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory;
import org.yanoproject.x.framework.cardano.api.events.AppChainAnchoredEvent;
import org.yanoproject.x.framework.runtime.appchain.AppChainEngine;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerRuntime;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerStore;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional Cardano specialization; retains the existing public constructors and wiring signatures. */
public final class AppChainSubsystem extends AppLedgerRuntime {
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus, Logger log) {
        this(config, protocolMagic, eventBus, null, null, log);
    }


    /**
     * @param stateMachine custom state machine (library mode); null = resolve
     *                     from config.stateMachineId() (built-ins)
     * @param ledgerPath   base dir for the app ledger; null = "appchain-chainstate"
     */
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                             AppStateMachine stateMachine, String ledgerPath, Logger log) {
        this(config, protocolMagic, eventBus, stateMachine, ledgerPath, null, log);
    }


    /**
     * @param stateMachine       custom state machine (library mode); null = resolve
     *                           from config.stateMachineId() (built-ins only)
     * @param pluginClassLoader  retained only for source compatibility in library
     *                           mode; runtime extensions require a catalog registry
     */
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                             AppStateMachine stateMachine, String ledgerPath,
                             ClassLoader pluginClassLoader, Logger log) {
        this(config, protocolMagic, eventBus, stateMachine, ledgerPath,
                pluginClassLoader, PluginProviderRegistry.empty(), false, log);
    }


    /**
     * Catalog-aware constructor used by normal runtime assembly. The registry
     * owns and caches provider factories; each factory invocation below still
     * creates fresh chain-scoped state machines, modes, observers, sinks and
     * effect executors.
     *
     * @param pluginClassLoader retained for source/assembly symmetry; provider
     *                          discovery is exclusively through
     *                          {@code pluginProviders}
     * @param pluginProviders   catalog-selected typed provider registry
     */
    public AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                             AppStateMachine stateMachine, String ledgerPath,
                             ClassLoader pluginClassLoader,
                             PluginProviderRegistry pluginProviders, Logger log) {
        this(config, protocolMagic, eventBus, stateMachine, ledgerPath,
                pluginClassLoader, pluginProviders, false, log);
    }

    private AppChainSubsystem(AppChainConfig config, long protocolMagic, EventBus eventBus,
                              AppStateMachine stateMachine, String ledgerPath,
                              ClassLoader pluginClassLoader, PluginProviderRegistry pluginProviders,
                              boolean ownsPluginProviders, Logger log) {
        super(config, protocolMagic, eventBus, stateMachine, ledgerPath, pluginClassLoader,
                pluginProviders, ownsPluginProviders,
                id -> RotatingSequencerMode.ID.equals(id) ? new RotatingSequencerMode() : null, log);
    }

    // M3: L1 anchoring + stable L1 reference tracking
    private volatile AnchorService anchorService;


    /** Script anchors (008.4). Every ledger member gets one (co-sign verifier);
     *  only the anchor.enabled node runs it in leader mode. */
    private volatile ScriptAnchorService scriptAnchorService;


    /** L1 observations (008.4 I3.2): all members recompute; the scheduled
     *  proposer injects. Null when no observers are configured. */
    private volatile L1ObservationService observationService;


    private volatile L1EpochObservationCoordinator epochObservationCoordinator;


    private volatile L1EpochStateProvider
            epochStateProvider;


    private volatile java.util.function.Function<byte[], String> txSubmitter;


    private volatile java.util.function.Supplier<org.yanoproject.api.utxo.UtxoState> utxoStateSupplier;


    /** The node's canonical chain state, read by L1 delivery (app-layer ADR-038, D1). */
    private volatile ChainBlockReader l1Chain;


    /** This chain's block-body retention requirement, owned from node construction until close (D7a). */
    private volatile BlockBodyRetentionRegistry.Registration l1Retention;


    /** The current generation's L1 delivery loop; null for L1-less chains (D3). */
    private volatile L1DeliveryLoop l1Delivery;


    private final List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> eventSubscriptions =
            new ArrayList<>();


    /**
     * Current protocol parameters for anchor tx pricing: linear fee (008.1
     * I1.5) plus, for script anchors (008.4), the ex-unit prices and the
     * PlutusV3 cost model (script-data-hash language views). The script
     * fields may be null — the script anchor falls back to Conway defaults.
     */
    public record AnchorFeeParams(long minFeeA, long minFeeB,
                                  java.math.BigDecimal priceMem,
                                  java.math.BigDecimal priceStep,
                                  long[] costModelV3) {
        /** Pre-008.4 signature — linear fee only. */
        public AnchorFeeParams(long minFeeA, long minFeeB) {
            this(minFeeA, minFeeB, null, null, null);
        }
    }


    private volatile java.util.function.Supplier<AnchorFeeParams> anchorFeeParamsSupplier;


    /**
     * Wire the node's protocol-parameter fee source for anchor transactions.
     * Optional — without it (or when the supplier returns null) the anchor tx
     * uses the configured {@code anchor.fallback-fee-lovelace}.
     */
    public void wireAnchorFees(java.util.function.Supplier<AnchorFeeParams> supplier) {
        this.anchorFeeParamsSupplier = supplier;
    }


    private volatile java.util.function.Supplier<com.bloxbean.cardano.client.api.model.ProtocolParams>
            anchorProtocolParamsSupplier;


    /**
     * Wire the node's full protocol parameters (cardano-client-lib model) for
     * QuickTx-based script-anchor tx construction (Iteration 4). Optional —
     * without it the script anchor uses Conway defaults.
     */
    public void wireAnchorProtocolParams(
            java.util.function.Supplier<com.bloxbean.cardano.client.api.model.ProtocolParams> supplier) {
        this.anchorProtocolParamsSupplier = supplier;
    }


    public void wireL1(java.util.function.Function<byte[], String> txSubmitter,
                       java.util.function.Supplier<org.yanoproject.api.utxo.UtxoState> utxoStateSupplier) {
        this.txSubmitter = txSubmitter;
        this.utxoStateSupplier = utxoStateSupplier;
    }


    private volatile L1UtxoViewFilter utxoViewFilter = L1UtxoViewFilter.NONE;


    /**
     * Wire the node's flattened global configuration so the script anchor can check the
     * configured UTxO storage filter ({@code yano.filters.utxo.*}) against what co-signing
     * needs (ADR-059 review P2-1). Optional: without it the view is treated as unfiltered.
     */
    public void wireL1UtxoFilter(Map<String, ?> nodeGlobals) {
        this.utxoViewFilter = L1UtxoViewFilter.fromGlobals(nodeGlobals);
    }


    /**
     * Wire the node's canonical chain state and this chain's body-retention registration (app-layer ADR-038). The
     * registration is held across stop and start, and released on {@link #close()}.
     */
    public void wireL1Chain(ChainBlockReader chain, BlockBodyRetentionRegistry.Registration retention) {
        this.l1Chain = Objects.requireNonNull(chain, "chain");
        this.l1Retention = retention;
    }


    /** Wire the persistent, epoch-pinned ledger-state source used by ADR-028 observers. */
    public void wireL1EpochState(
            L1EpochStateProvider provider) {
        this.epochStateProvider = Objects.requireNonNull(provider, "provider");
    }


    private volatile org.yanoproject.api.TxEvaluationGateway txEvaluation;


    /** Wire the node's phase-2 evaluator for script-executor tx assembly. */
    public void wireTxEvaluation(
            org.yanoproject.api.TxEvaluationGateway evaluation) {
        this.txEvaluation = evaluation;
    }


    /**
     * The node-coupled surface for context-aware executor factories
     * (ADR-UTXO-009 SP-M6) — every capability scoped to THIS chain.
     * Suppliers re-read live fields so factories built before L1 wiring
     * still see the eventual tx submitter / UTxO view.
     */
    private AppChainEffectContext
            effectContext() {
        return new AppChainEffectContext() {
            @Override
            public String chainId() {
                return config.chainId();
            }

            @Override
            public void diffuse(String topic, byte[] body) {
                buildAndDiffuse(topic, body, 120);
            }

            @Override
            public SignerProvider
                    memberSigner() {
                return signer;
            }

            @Override
            public java.util.function.Supplier<Set<String>> members() {
                return group::members;
            }

            @Override
            public java.util.function.IntSupplier threshold() {
                return group::threshold;
            }

            @Override
            public AppQueryResult query(
                    String path, byte[] request) {
                return AppChainSubsystem.this.query(path, request);
            }

            @Override
            public java.util.function.Supplier<
                    org.yanoproject.api.utxo.UtxoState> l1UtxoView() {
                return () -> {
                    var supplier = utxoStateSupplier;
                    return supplier != null ? supplier.get() : null;
                };
            }

            @Override
            public String submitTx(byte[] transactionCbor) {
                var submitter = txSubmitter;
                if (submitter == null) {
                    throw new IllegalStateException(
                            "L1 transaction submission is not wired yet");
                }
                return submitter.apply(transactionCbor);
            }

            @Override
            public java.util.function.Supplier<
                    com.bloxbean.cardano.client.api.model.ProtocolParams>
                    protocolParams() {
                return () -> {
                    var supplier = anchorProtocolParamsSupplier;
                    return supplier != null ? supplier.get() : null;
                };
            }

            @Override
            public org.yanoproject.api.TxEvaluationGateway txEvaluation() {
                return txEvaluation;
            }

            @Override
            public void registerBridgeDiffusionHandler(
                    BridgeDiffusionHandler handler) {
                AppChainSubsystem.this.registerBridgeDiffusionHandler(handler);
            }

            @Override
            public Optional<StateProofEnvelope> stateProofAtHeight(long height, byte[] key) {
                return AppChainSubsystem.this.stateProofEnvelopeAtHeight(height, key);
            }

            @Override
            public long stableAnchoredHeight() {
                return AppChainSubsystem.this.stableAnchoredHeight();
            }
        };
    }


    /** Build a non-expiring framework-owned input carried only inside proposals. */
    private AppMessage buildDurableObservationInput(L1Observation observation) {
        String topic = observation.topic();
        byte[] body = observation.encode();
        if (!validTopic(topic) || !validEnvelopeBodyProfile(topic, body)) {
            quarantineUnencodableObservation(observation);
            throw new IllegalStateException("OBSERVATION_UNENCODABLE");
        }
        long expiresAt = Long.MAX_VALUE;
        long seq = 0;
        byte[] signedBody = AppMessage.signedBodyBytes(config.chainId(), topic,
                signer.publicKey(), seq, expiresAt, body);
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(config.chainId(), topic,
                        signer.publicKey(), seq, expiresAt, body))
                .chainId(config.chainId())
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(seq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }


    private List<L1Observation> mandatoryObservationFacts(long stableSlot) {
        List<L1Observation> available = new ArrayList<>();
        L1ObservationService blockObservations = observationService;
        if (blockObservations != null) {
            available.addAll(blockObservations.drainInjectable(stableSlot,
                    config.maxBlockMessages(), Long.MAX_VALUE));
        }
        L1EpochObservationCoordinator epochObservations = epochObservationCoordinator;
        if (epochObservations != null) {
            available.addAll(epochObservations.pendingForProposal(
                    config.maxBlockMessages(), Long.MAX_VALUE));
        }
        return canonicalObservationPrefix(available, config.maxBlockMessages());
    }


    static List<L1Observation> canonicalObservationPrefix(
            List<L1Observation> available, int maxMessages) {
        if (maxMessages <= 0) {
            return List.of();
        }
        List<L1Observation> ordered = new ArrayList<>(available);
        ordered.sort(L1Observation.CANONICAL_ORDER);
        List<L1Observation> selected = new ArrayList<>();
        for (L1Observation observation : ordered) {
            if (selected.size() == maxMessages) {
                break;
            }
            selected.add(observation);
        }
        return List.copyOf(selected);
    }


    private void quarantineUnencodableObservation(L1Observation observation) {
        if (observation.anchor() instanceof L1Observation.EpochAnchor) {
            L1EpochObservationCoordinator coordinator = epochObservationCoordinator;
            if (coordinator != null) {
                coordinator.quarantineUnencodable(observation);
            }
        } else {
            L1ObservationService service = observationService;
            if (service != null) {
                service.quarantineUnencodable(observation);
            }
        }
    }


    private AppChainEngine.L1RefVerdict verifyMandatoryObservationPrefix(
            AppBlockExecutionContext executionContext) {
        AppBlock block = executionContext.block();
        List<SequencedL1Observation> proposed = executionContext.l1Observations();
        long stableSlot = block.l1Slot();
        L1ObservationService blockObservations = observationService;
        if (blockObservations != null && blockObservations.newestSlot() < stableSlot) {
            return AppChainEngine.L1RefVerdict.AHEAD;
        }
        for (SequencedL1Observation sequenced : proposed) {
            L1Observation observation = sequenced.observation();
            AppChainEngine.L1RefVerdict verdict;
            if (observation.anchor() instanceof L1Observation.EpochAnchor) {
                L1EpochObservationCoordinator coordinator = epochObservationCoordinator;
                verdict = coordinator != null
                        ? coordinator.verify(observation, false)
                        : AppChainEngine.L1RefVerdict.MISMATCH;
            } else {
                verdict = blockObservations != null
                        ? blockObservations.verify(sequenced)
                        : AppChainEngine.L1RefVerdict.MISMATCH;
            }
            if (verdict != AppChainEngine.L1RefVerdict.OK) {
                return verdict;
            }
        }
        List<L1Observation> expected = mandatoryObservationFacts(stableSlot);
        if (proposed.size() > expected.size()) {
            return AppChainEngine.L1RefVerdict.MISMATCH;
        }
        for (int index = 0; index < proposed.size(); index++) {
            if (!Arrays.equals(expected.get(index).encode(),
                    proposed.get(index).observation().encode())) {
                return AppChainEngine.L1RefVerdict.MISMATCH;
            }
        }
        if (proposed.size() < expected.size()) {
            if (block.messages().size() > proposed.size()) {
                return AppChainEngine.L1RefVerdict.MISMATCH;
            }
            AppMessage next = unsignedObservationInput(
                    expected.get(proposed.size()), block.proposer());
            List<AppMessage> extended = new ArrayList<>(block.messages());
            extended.add(next);
            AppBlock withNext = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.consensusContextDigest(), block.view(), block.prevHash(),
                    block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    AppBlockCodec.messagesRoot(extended), block.stateRoot(), extended,
                    block.proposer(), block.justification(), block.cert());
            if (AppBlockCodec.serialize(withNext).length <= config.proposalMaxBytes()) {
                return AppChainEngine.L1RefVerdict.MISMATCH;
            }
        }
        return AppChainEngine.L1RefVerdict.OK;
    }


    private AppMessage unsignedObservationInput(L1Observation observation, byte[] proposer) {
        String topic = observation.topic();
        byte[] body = observation.encode();
        long expiresAt = Long.MAX_VALUE;
        long seq = 0;
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(config.chainId(), topic,
                        proposer, seq, expiresAt, body))
                .chainId(config.chainId())
                .topic(topic)
                .sender(proposer)
                .senderSeq(seq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(new byte[AppChainConfig.ED25519_SIGNATURE_BYTES])
                .build();
    }


    @Override
    public Optional<AppAnchorCommitment>
            latestAnchorCommitment() {
        return generationUseOr(Optional.empty(), this::latestAnchorCommitmentWithinGeneration);
    }


    @Override
    public Optional<AppAnchorCommitment>
            anchorCommitment(long height) {
        if (height <= 0) return Optional.empty();
        return generationUseOr(Optional.empty(), () -> anchorCommitmentWithinGeneration(height));
    }


    protected Optional<AppAnchorCommitment>
            anchorCommitmentWithinGeneration(long height) {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null || height <= 0) return Optional.empty();
        byte[] encoded = currentLedger.metaBytes(AnchorService.META_ANCHOR_HISTORY);
        if (encoded == null) return Optional.empty();
        AnchorService.Confirmation confirmation = AnchorService.ConfirmationHistory.decode(encoded).stream()
                .filter(value -> value.toHeight() == height).findFirst().orElse(null);
        if (confirmation == null) return Optional.empty();
        AppBlock block = currentLedger.block(height).orElse(null);
        if (block == null || !config.chainId().equals(block.chainId())) return Optional.empty();
        byte[] calculated = AppBlockCodec.blockHash(block);
        if (!java.util.Arrays.equals(calculated, confirmation.blockHash())) return Optional.empty();
        String mode = config.anchor() != null && config.anchor().scriptMode()
                ? AppChainConfig.AnchorConfig.MODE_SCRIPT : AppChainConfig.AnchorConfig.MODE_METADATA;
        return Optional.of(new AppAnchorCommitment(
                config.chainId(), mode, height, block.stateRoot(), calculated,
                confirmation.txHash(), confirmation.l1Slot()));
    }


    protected Optional<AppAnchorCommitment>
            latestAnchorCommitmentWithinGeneration() {
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null) {
            return Optional.empty();
        }
        Optional<AppLedgerStore.ConfirmedAnchorSnapshot> snapshot =
                currentLedger.confirmedAnchorSnapshot();
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        AppLedgerStore.ConfirmedAnchorSnapshot confirmed = snapshot.orElseThrow();
        AppBlock anchoredBlock = confirmed.block();
        if (!config.chainId().equals(anchoredBlock.chainId())
                || anchoredBlock.height() != confirmed.height()) {
            return Optional.empty();
        }
        byte[] calculatedHash =
                AppBlockCodec.blockHash(anchoredBlock);
        if (!java.util.Arrays.equals(confirmed.blockHash(), calculatedHash)) {
            log.warn("Ignoring inconsistent confirmed anchor record for app-chain '{}' at height {}",
                    config.chainId(), confirmed.height());
            return Optional.empty();
        }
        String mode = config.anchor() != null && config.anchor().scriptMode()
                ? AppChainConfig.AnchorConfig.MODE_SCRIPT
                : AppChainConfig.AnchorConfig.MODE_METADATA;
        return Optional.of(new AppAnchorCommitment(
                config.chainId(), mode, confirmed.height(), anchoredBlock.stateRoot(), calculatedHash,
                confirmed.transactionHash(), confirmed.l1Slot()));
    }


    @Override
    public boolean rebaselineL1Delivery() {
        return generationUseOr(false, () -> {
            L1DeliveryLoop delivery = l1Delivery;
            if (delivery == null) {
                throw new UnsupportedOperationException("App-chain '" + config.chainId() + "' runs no L1 delivery");
            }
            boolean accepted = delivery.requestRebaseline();
            log.warn("App-chain '{}' L1 delivery re-baseline {} (admin)", config.chainId(),
                    accepted ? "accepted" : "refused");
            return accepted;
        });
    }


    protected boolean forceAnchorWithinGeneration() {
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentScriptAnchor != null && config.anchoringEnabled()
                && config.anchor() != null && config.anchor().scriptMode()) {
            return currentScriptAnchor.forceAnchorNow();
        }
        AnchorService currentAnchor = anchorService;
        if (currentAnchor == null) {
            return false; // anchoring disabled
        }
        return currentAnchor.forceAnchorNow();
    }


    /**
     * Bootstrap the script anchor (ADR 008.4 §2.5, admin action): mint the
     * one-shot thread NFT and lock the initial datum at the anchor validator.
     * Only valid on the anchor leader with {@code anchor.mode: script}.
     */
    public Map<String, Object> bootstrapScriptAnchor() {
        return requireGenerationUse(this::bootstrapScriptAnchorWithinGeneration);
    }


    private Map<String, Object> bootstrapScriptAnchorWithinGeneration() {
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentScriptAnchor == null)
            throw new IllegalStateException("Script-anchor service is not available on this node");
        if (!config.anchoringEnabled() || config.anchor() == null || !config.anchor().scriptMode())
            throw new IllegalStateException("Script anchoring is not enabled for this chain "
                    + "(set anchor.enabled: true and anchor.mode: script)");
        return currentScriptAnchor.bootstrap();
    }


    /**
     * Starts L1 delivery when this chain consumes L1 at all (app-layer ADR-038, D3). L1-less library chains, with no
     * chain state wired or nothing that reads L1, run none. Each pass runs inside this generation's lease, so stop
     * waits for an in-flight pass and a retired loop never touches the next generation.
     */
    private void startL1Delivery(long generationToken, AppLedgerStore ledgerStore) {
        ChainBlockReader chain = l1Chain;
        // Fail fast on a silently degraded L1 linkage (ADR 008.1 I1.3): without a chain to read, blocks would carry
        // l1Slot=0, anchors would never confirm and observers would never run.
        boolean requiresL1 = config.l1StabilityDepth() > 0 || config.anchoringEnabled()
                || observationService != null || epochObservationCoordinator != null;
        if (requiresL1 && chain == null) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': l1.stability-depth, anchoring or L1 observers are configured but no L1 chain reader is "
                    + "wired — refusing to start with a silent L1 linkage (set l1.stability-depth: 0, disable "
                    + "anchoring and remove observers for L1-less chains)");
        }
        // The script-anchor verifier every member runs also reads L1, but alone it does not require it.
        if (chain == null || !(requiresL1 || scriptAnchorService != null)) {
            if (l1Retention != null) {
                l1Retention.update(OptionalLong.empty()); // no loop, so no block bodies to keep (D7a)
            }
            return;
        }
        L1DeliveryLoop delivery = new L1DeliveryLoop(config.chainId(), config.l1StabilityDepth(), chain, ledgerStore,
                new L1Phases(ledgerStore, chain, anchorService, scriptAnchorService, observationService,
                        epochObservationCoordinator),
                l1Retention, log);
        this.l1Delivery = delivery;
        delivery.start(pass -> generationUseOrNoop(generationToken, pass));
    }


    /** Node L1 events only wake the delivery loop; chain state is the source of truth (ADR-038, D3, I6). */
    private void subscribeL1Events() {
        L1DeliveryLoop delivery = l1Delivery;
        if (eventBus == null || delivery == null) {
            return;
        }
        eventSubscriptions.addAll(acquireL1Subscriptions(eventBus, event -> delivery.wake(),
                event -> delivery.wakeForRollback()));
    }


    /** Delivery status (app-layer ADR-038, D10): cursor, intent, phase, lag and whether the fence is open. */
    private Map<String, Object> l1DeliveryStatus(L1DeliveryLoop delivery) {
        L1DeliveryLoop.Snapshot snapshot = delivery.snapshot();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", snapshot.state().name());
        status.put("deliveryHealthy", delivery.deliveryHealthy());
        L1DeliveryRecord record = snapshot.record();
        if (record != null) {
            L1Point cursor = record.cursor();
            status.put("phase", record.phase().name());
            status.put("cursorBlock", cursor.blockNumber());
            status.put("cursorSlot", cursor.slot());
            status.put("cursorHash", cursor.isOrigin() ? "origin" : HexUtil.encodeHexString(cursor.blockHash()));
            status.put("deliveredPoints", record.window().size());
            status.put("baselinePoints", record.baseline().size());
            status.put("baselineFromBlock", record.baseline().getFirst().blockNumber());
            if (record.pending() != null) {
                status.put("pending", record.pending().kind().name() + " " + record.pending().point());
            }
            ChainBlockReader chain = l1Chain;
            var tip = chain != null ? chain.getLocalTip() : null;
            if (tip != null) {
                status.put("bodyTipBlock", tip.getBlockNumber());
                status.put("lagBlocks", Math.max(0L, tip.getBlockNumber() - cursor.blockNumber()));
            }
        }
        if (snapshot.lastFailure() != null) {
            status.put("lastFailure", snapshot.lastFailure());
        }
        BlockBodyRetentionRegistry.Registration retention = l1Retention;
        if (retention != null) {
            retention.oldestRequiredBlockNumber().ifPresent(block -> status.put("retainedFromBlock", block));
        }
        String readiness = l1DeliveryReadinessProblem(delivery);
        if (readiness != null) {
            status.put("notReady", readiness);
        }
        return status;
    }


    /**
     * Readiness signal (ADR-038, D10): degraded while delivery is not {@code RUNNING} or lags the body tip by more
     * than {@code l1.delivery.readiness-max-lag-blocks}. Safety never depends on it; the D9a fence does that.
     */
    private String l1DeliveryReadinessProblem(L1DeliveryLoop delivery) {
        L1DeliveryLoop.Snapshot snapshot = delivery.snapshot();
        if (snapshot.state() != L1DeliveryLoop.State.RUNNING) {
            return "L1 delivery " + snapshot.state();
        }
        ChainBlockReader chain = l1Chain;
        ChainTip tip = chain != null ? chain.getLocalTip() : null;
        long lag = tip != null && snapshot.record() != null
                ? tip.getBlockNumber() - snapshot.record().cursor().blockNumber() : 0L;
        return lag > l1ReadinessMaxLagBlocks ? "L1 delivery lags " + lag + " blocks" : null;
    }


    /** Anchor completion runs only once L1 state is reconciled (ADR-038, D8b rule 9). */
    private boolean l1Reconciled() {
        L1DeliveryLoop delivery = l1Delivery;
        if (delivery == null) {
            return true;
        }
        L1DeliveryRecord record = delivery.snapshot().record();
        return record != null && record.phase() == L1DeliveryRecord.Phase.RECONCILED;
    }


    /**
     * One generation's L1 phases (app-layer ADR-038, D3, D4a), with the services captured when the generation
     * started. Each phase reports an outcome; a phase that throws is retryable and never stops the others.
     */
    private final class L1Phases implements L1DeliveryLoop.Host {
        private final AppLedgerStore ledgerStore;
        private final ChainBlockReader chain;
        private final AnchorService anchor;
        private final ScriptAnchorService scriptAnchor;
        private final L1ObservationService observations;
        private final L1EpochObservationCoordinator epochObservations;

        private L1Phases(AppLedgerStore ledgerStore, ChainBlockReader chain, AnchorService anchor,
                         ScriptAnchorService scriptAnchor, L1ObservationService observations,
                         L1EpochObservationCoordinator epochObservations) {
            this.ledgerStore = ledgerStore;
            this.chain = chain;
            this.anchor = anchor;
            this.scriptAnchor = scriptAnchor;
            this.observations = observations;
            this.epochObservations = epochObservations;
        }

        @Override
        public List<L1PhaseResult> applyBlock(BlockAppliedEvent event) {
            byte[] l1BlockHash = HexUtil.decodeHexString(event.blockHash());
            Block block = event.block();
            List<L1PhaseResult> results = new ArrayList<>(4);
            // L1 observations (008.4 I3.2): EVERY member recomputes; injection stays stability-gated.
            if (observations != null && block != null) {
                results.add(phase("observation", () -> observations.deliver(event.slot(), l1BlockHash, block)));
            }
            if (epochObservations != null) {
                results.add(phase("epoch observation wake-up", () -> {
                    epochObservations.onBlockApplied(event.slot(), event.blockNumber(), l1BlockHash);
                    return L1PhaseResult.DURABLE;
                }));
            }
            List<String> txHashes = validTransactionHashes(block);
            if (anchor != null && txHashes != null) {
                results.add(phase("anchor confirmation",
                        () -> anchor.onL1Block(event.slot(), l1BlockHash, txHashes)));
            }
            if (scriptAnchor != null && txHashes != null) {
                results.add(phase("script-anchor confirmation",
                        () -> scriptAnchor.onL1Block(event.slot(), l1BlockHash, txHashes)));
            }
            return results;
        }

        @Override
        public List<L1PhaseResult> rollbackTo(L1Point target) {
            long slot = target.isOrigin() ? -1L : target.slot();
            List<L1PhaseResult> results = new ArrayList<>(4);
            if (anchor != null) {
                results.add(phase("metadata-anchor rollback", () -> anchor.onL1Rollback(slot)));
            }
            if (scriptAnchor != null) {
                results.add(phase("script-anchor rollback", () -> scriptAnchor.onL1Rollback(slot)));
            }
            if (observations != null) {
                results.add(phase("observation rollback", () -> observations.rollback(slot)));
            }
            if (epochObservations != null) {
                results.add(phase("epoch observation rollback",
                        () -> epochObservations.rollback(Math.max(slot, 0L))));
            }
            return results;
        }

        /**
         * Any app block, anchor confirmation, or entry in the epoch-observations column family (observation journal
         * records, cursors and markers, and epoch-spool jobs) is L1-derived state an upgrade must reconcile (D8). A
         * new chain with observers also takes one reconciliation pass, which only records its baseline.
         */
        @Override
        public boolean hasL1DerivedState() {
            byte[] anchorHistory = ledgerStore.metaBytes(AnchorService.META_ANCHOR_HISTORY);
            return ledgerStore.tipHeight() > 0 || (anchorHistory != null && anchorHistory.length > 0)
                    || !ledgerStore.epochSpoolScan(new byte[0], 1).isEmpty();
        }

        @Override
        public L1DeliveryLoop.Reconciliation reconcile(BiPredicate<Long, byte[]> canonicalAtSlot) {
            List<Consumer<WriteBatch>> stagers = new ArrayList<>();
            // Journal records and finalized cursors (D8b rule 3).
            if (observations != null) {
                L1ObservationJournal.ReconcileDecision journal = observations.reconcile(canonicalAtSlot);
                if (journal.quarantine() != null) {
                    return quarantine(journal.quarantine());
                }
                stagers.add(batch -> ledgerStore.stageEpochSpoolMutations(batch, journal.invalidations()));
            }
            // Epoch-spool jobs record their boundary block (rule 3, "everything else L1-derived").
            if (epochObservations != null) {
                L1ObservationJournal.ReconcileDecision epochs = epochObservations.reconcile(canonicalAtSlot);
                if (epochs.quarantine() != null) {
                    return quarantine(epochs.quarantine());
                }
                stagers.add(batch -> ledgerStore.stageEpochSpoolMutations(batch, epochs.invalidations()));
            }
            // Finalized evidence older than the cursors: the L1 observations in committed app blocks (rule 4).
            CommittedEvidence evidence = scanCommittedObservations(canonicalAtSlot);
            if (evidence == CommittedEvidence.DEAD) {
                return quarantine("DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION");
            }
            if (evidence == CommittedEvidence.UNAVAILABLE) {
                return new L1DeliveryLoop.Reconciliation(batch -> { }, null, true, OptionalLong.empty());
            }
            // Anchor facts and confirmations that record an L1 point, whatever their schema (rule 3). Both anchor
            // services share one confirmation journal; a script identity reset, staged after it, replaces it.
            if (anchor != null || scriptAnchor != null) {
                stagers.add(AnchorService.reconcileHistory(ledgerStore, canonicalAtSlot));
            }
            if (anchor != null) {
                stagers.add(anchor.reconcile(canonicalAtSlot));
            }
            if (scriptAnchor != null) {
                stagers.add(scriptAnchor.reconcile(canonicalAtSlot));
            }
            // A legacy failed-callback marker: deliver from the failed block, or drop it as orphaned (D8a).
            OptionalLong baselineTop = OptionalLong.empty();
            long failedSlot = observations != null ? observations.callbackFailureSlot() : -1L;
            if (failedSlot >= 0) {
                Optional<CanonicalBlockReference> failed = chain.getCanonicalBlockReferenceAtSlot(failedSlot);
                if (failed.isPresent()) {
                    baselineTop = OptionalLong.of(failed.get().blockNumber() - 1);
                } else {
                    stagers.add(batch -> ledgerStore.stageEpochSpoolMutations(batch,
                            List.of(L1ObservationJournal.clearCallbackFailureMutation())));
                }
            }
            return new L1DeliveryLoop.Reconciliation(batch -> stagers.forEach(stager -> stager.accept(batch)),
                    null, false, baselineTop);
        }

        @Override
        public void reconciled() {
            if (observations != null) {
                observations.reloadAfterReconciliation();
            }
            if (epochObservations != null) {
                epochObservations.reloadAfterReconciliation();
            }
            if (scriptAnchor != null) {
                scriptAnchor.reloadAfterReconciliation();
            }
        }

        private L1DeliveryLoop.Reconciliation quarantine(String reason) {
            return new L1DeliveryLoop.Reconciliation(batch -> ledgerStore.stageEpochSpoolMutations(batch,
                    List.of(L1ObservationJournal.quarantineMutation(reason))), reason, false,
                    OptionalLong.empty());
        }

        /**
         * Checks every L1 observation in committed app blocks (D8b rule 4). Read-only. Blocks at or below the
         * retention prune cursor had their bodies stripped by configuration, below an L1 anchor, and are treated as
         * settled (Q8(b) with the prune cursor as the horizon); otherwise a retention-enabled chain could never
         * reconcile. The journal's finalized cursors are still checked. Any other missing body makes the evidence
         * unavailable (rule 6).
         */
        private CommittedEvidence scanCommittedObservations(BiPredicate<Long, byte[]> canonicalAtSlot) {
            boolean unavailable = false;
            for (long height = ledgerStore.pruneCursor() + 1; height <= ledgerStore.tipHeight(); height++) {
                AppBlock block = ledgerStore.block(height).orElse(null);
                if (block == null) {
                    unavailable = true;
                    continue;
                }
                for (AppMessage message : block.messages()) {
                    String topic = message.getTopic();
                    if (topic == null || !topic.startsWith(L1Observation.TOPIC_PREFIX)) {
                        continue;
                    }
                    byte[] body = message.getBody();
                    L1Observation observation = body == null || body.length == 0 ? null : L1Observation.decode(body);
                    if (observation == null) {
                        unavailable = true;
                    } else if (!canonicalAtSlot.test(observation.slot(), observation.blockHash())) {
                        return CommittedEvidence.DEAD;
                    }
                }
            }
            return unavailable ? CommittedEvidence.UNAVAILABLE : CommittedEvidence.CANONICAL;
        }

        private L1PhaseResult phase(String name, Supplier<L1PhaseResult> body) {
            try {
                return body.get();
            } catch (RuntimeException failure) {
                reportL1PhaseFailure(name, failure);
                return L1PhaseResult.retryable("L1_PHASE_FAILED_" + name.replace(' ', '_').toUpperCase(Locale.ROOT));
            }
        }
    }


    private enum CommittedEvidence { CANONICAL, DEAD, UNAVAILABLE }


    /** Hashes of the block's valid transactions; null without a parsed body (Byron). */
    private static List<String> validTransactionHashes(Block block) {
        if (block == null || block.getTransactionBodies() == null) {
            return null;
        }
        List<Integer> invalidTransactions = block.getInvalidTransactions();
        Set<Integer> invalidIndexes = invalidTransactions != null
                ? new HashSet<>(invalidTransactions) : Set.of();
        List<String> txHashes = new ArrayList<>();
        List<com.bloxbean.cardano.yaci.core.model.TransactionBody> bodies = block.getTransactionBodies();
        for (int index = 0; index < bodies.size(); index++) {
            if (!invalidIndexes.contains(index)) {
                txHashes.add(bodies.get(index).getTxHash());
            }
        }
        return txHashes;
    }


    /**
     * Acquire the applied/rollback pair transactionally. A half-installed L1
     * view is worse than a failed start: stability, observations and anchors
     * would evolve from different event streams.
     */
    static List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle>
            acquireL1Subscriptions(
                    EventBus eventBus,
                    java.util.function.Consumer<org.yanoproject.api.events.BlockAppliedEvent>
                            applied,
                    java.util.function.Consumer<org.yanoproject.api.events.RollbackEvent>
                            rollback
            ) {
        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(rollback, "rollback");
        List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> acquired =
                new ArrayList<>(2);
        try {
            acquired.add(Objects.requireNonNull(eventBus.subscribe(
                            org.yanoproject.api.events.BlockAppliedEvent.class,
                            ctx -> applied.accept(ctx.event()),
                            com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()),
                    "EventBus returned null BlockAppliedEvent subscription"));
            acquired.add(Objects.requireNonNull(eventBus.subscribe(
                            org.yanoproject.api.events.RollbackEvent.class,
                            ctx -> rollback.accept(ctx.event()),
                            com.bloxbean.cardano.yaci.events.api.SubscriptionOptions.builder().build()),
                    "EventBus returned null RollbackEvent subscription"));
            return List.copyOf(acquired);
        } catch (Throwable subscriptionFailure) {
            Throwable outcome = subscriptionFailure;
            for (int i = acquired.size() - 1; i >= 0; i--) {
                try {
                    acquired.get(i).close();
                } catch (Throwable closeFailure) {
                    outcome = LifecycleFailures.merge(outcome, closeFailure);
                }
            }
            throw propagateLifecycleFailure(outcome,
                    "Failed to install app-chain L1 subscriptions");
        }
    }


    private void publishConfirmedAnchor(AnchorService.ConfirmedAnchor confirmed) {
        if (confirmed == null || eventBus == null) {
            return;
        }
        runL1Phase("anchor event publication", () ->
                eventBus.publish(new AppChainAnchoredEvent(
                                config.chainId(), confirmed.fromHeight(), confirmed.toHeight(),
                                confirmed.txHash(), confirmed.l1Slot()),
                        EventMetadata.builder().build(), PublishOptions.builder().build()));
    }


    private void runL1Phase(String phase, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            reportL1PhaseFailure(phase, failure);
        }
    }


    private void reportL1PhaseFailure(String phase, Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatal(failure);
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            log.warn("App-chain L1 {} failed (errorType={})",
                    phase, failure.getClass().getName());
        } catch (Throwable loggingFailure) {
            // A recoverable logging backend failure must not cancel the event
            // subscription or prevent later independent phases from running.
            LifecycleFailures.rethrowIfProcessFatal(loggingFailure);
            if (loggingFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }


    private final AtomicLong l1RefDeferrals = new AtomicLong();


    /**
     * Follower-side verdict on a proposed L1 ref against this node's OWN delivered L1 points (ADR 008.1 I1.3),
     * fenced by delivery health (app-layer ADR-038, D9a): UNKNOWN while the delivered view is not fresh.
     */
    protected AppChainEngine.L1RefVerdict checkL1Ref(long slot, byte[] blockHash) {
        L1DeliveryLoop delivery = l1Delivery;
        AppChainEngine.L1RefVerdict verdict = delivery != null
                ? delivery.checkL1Ref(slot, blockHash) : AppChainEngine.L1RefVerdict.UNKNOWN;
        if (verdict == AppChainEngine.L1RefVerdict.AHEAD) {
            l1RefDeferrals.incrementAndGet();
        }
        return verdict;
    }


    /**
     * The stable L1 point: l1StabilityDepth blocks below the newest delivered point, fenced by delivery health and
     * an at-read canonical check (app-layer ADR-038, D9a). Null when unavailable or at depth 0.
     */
    protected AppChainEngine.L1Ref stableL1Ref() {
        L1DeliveryLoop delivery = l1Delivery;
        return delivery != null ? delivery.stablePoint() : null;
    }


    /**
     * The node's newest applied L1 block, read from chain state and unfenced: the sequencer's slot clock, anchor TTL
     * hints, and the point script anchors match against the UTxO store. Never a consensus input.
     */
    protected AppChainEngine.L1Ref l1TipPoint() {
        ChainBlockReader chain = l1Chain;
        ChainTip tip = chain != null ? chain.getLocalTip() : null;
        return tip != null && tip.getBlockHash() != null ? new AppChainEngine.L1Ref(tip.getSlot(), tip.getBlockHash())
                : null;
    }


    /**
     * The voting gate's delivery condition (app-layer ADR-038, D9a): only chains whose consensus reads L1
     * (l1.stability-depth > 0) need healthy delivery; L1-less and depth-0 chains vote as before.
     */
    private boolean l1DeliveryHealthyForVoting() {
        if (config.l1StabilityDepth() <= 0) {
            return true;
        }
        L1DeliveryLoop delivery = l1Delivery;
        return delivery != null && delivery.deliveryHealthy();
    }


    /**
     * The L1_ANCHORED effect gate frontier (ADR-010 F7): the highest app height
     * covered by an anchor confirmed at or below {@link #stableL1Ref()}, i.e.
     * at least {@code l1.stability-depth} blocks below this node's L1 tip.
     * Node-local execution-plane state, never consensus input. 0 without a
     * stable L1 point (depth 0, or too few L1 blocks seen since start).
     */
    protected long stableAnchoredHeight() {
        AppLedgerStore currentLedger = ledger;
        AppChainEngine.L1Ref stable = stableL1Ref();
        return currentLedger == null || stable == null
                ? 0L : AnchorService.stableAnchoredHeight(currentLedger, stable.slot());
    }


    /**
     * Why this node can never see a stability-deep anchor for the L1_ANCHORED
     * gate, or null when it can. Effects config rejects this only for
     * {@code effects.default-gate}; a state machine can still emit L1_ANCHORED
     * effects explicitly.
     *
     * <p>Anchor confirmations are recorded only by this node's own anchor
     * service or by the script-anchor service, which every member with L1
     * access runs as a follower even with {@code anchor.enabled=false}. Only
     * the certain cases are reported: a member that follows a chain anchored
     * by another mode is not flagged.
     */
    protected String l1AnchoredGateUnreachableReason() {
        if (config.l1StabilityDepth() <= 0) {
            return "l1.stability-depth is 0, so no anchor ever becomes stability-deep";
        }
        if (anchorService == null && scriptAnchorService == null) {
            return "this node neither submits anchors nor follows script anchors (no L1 transaction access)";
        }
        return null;
    }


    private boolean l1ObservationInputsHealthy() {
        L1ObservationService blockObservations = observationService;
        L1EpochObservationCoordinator epochObservations = epochObservationCoordinator;
        return (blockObservations == null || blockObservations.healthy())
                && (epochObservations == null || epochObservations.healthy());
    }


    protected byte[] observerProfileDigest(byte[] l1NetworkGenesisId) {
        return L1ObservationService.consensusProfileDigest(
                config.pluginSettings(), pluginProviders, l1NetworkGenesisId);
    }


    @Override
    protected void configureLedgerIntegration(AppLedgerStore ledgerStore, AppChainEngine chainEngine, byte[] observerProfileDigest, byte[] observationIdentityContext) {
        this.observationService = L1ObservationService.fromRegistry(
                config.pluginSettings(), Math.max(config.l1StabilityDepth(), 1) + 64,
                pluginProviders, log);
        if (observationService != null) {
            L1ObservationJournal observationJournal = new L1ObservationJournal(
                    ledgerStore,
                    parseLongSetting("observation.journal.max-bytes",
                            L1ObservationJournal.DEFAULT_MAX_BYTES),
                    Math.toIntExact(parseLongSetting(
                            "observation.journal.max-entries",
                            L1ObservationJournal.DEFAULT_MAX_ENTRIES)),
                    observationIdentityContext);
            this.observationService = observationService.withJournal(observationJournal);
            if (config.l1StabilityDepth() <= 0) {
                // Injection is gated on the stable L1 ref — without it a
                // reorg-able fact could finalize into a chain that never
                // rolls back (ADR 008.4 §3.1: slot <= block l1-ref REQUIRED)
                throw new IllegalArgumentException("L1 observers require l1.stability-depth > 0 "
                        + "(observations are injected only once stability-deep — rollback safety)");
            }
            log.info("App-chain '{}' L1 observers configured: {}",
                    config.chainId(), observationService.status().keySet());
        }
        List<L1EpochObserver>
                epochObservers = L1EpochObservationCoordinator.observersFromConfig(
                config.pluginSettings(), pluginProviders);
        if (!epochObservers.isEmpty()) {
            if (config.retentionEnabled()) {
                throw new IllegalArgumentException(
                        "L1 epoch observers require retention.enabled=false");
            }
            var stateSource = epochStateProvider;
            if (stateSource == null) {
                throw new IllegalArgumentException(
                        "L1 epoch observers require a persistent epoch-state provider");
            }
            this.epochObservationCoordinator = new L1EpochObservationCoordinator(
                    epochObservers,
                    stateSource,
                    new EpochObservationSpool(
                            ledgerStore, EpochObservationSpool.DEFAULT_MAX_BYTES),
                    config.epochStabilityDepth(),
                    1,
                    config.maxMessageBytes(),
                    this::isScheduledProposer,
                    observation -> true,
                    chainEngine::markAuthenticatedSnapshotsDisputed,
                    config.chainId(),
                    log);
            log.info("App-chain '{}' L1 epoch observers configured: {}",
                    config.chainId(), epochObservers.stream()
                            .map(L1EpochObserver::observerId)
                            .toList());
        }
        chainEngine.setVotingHealth(() -> l1ObservationInputsHealthy() && l1DeliveryHealthyForVoting());
        chainEngine.setConsensusContextProvider(height ->
                chainEngine.configuredConsensusContextDigest(
                        height, observerProfileDigest));
        chainEngine.setBlockCommitHook((block, batch) -> {
            L1ObservationService observations = observationService;
            if (observations != null) {
                observations.stageFinalized(block, batch);
            }
        });
        chainEngine.setBlockInFlightHook(block -> {
            L1ObservationService observations = observationService;
            if (observations != null) {
                observations.markInFlight(block);
            }
        });
        chainEngine.setBlockPreparedHook(block -> {
            L1ObservationService observations = observationService;
            if (observations != null) {
                observations.markPrepared(block);
            }
        });
        if (observationService != null || epochObservationCoordinator != null) {
            chainEngine.setFrameworkStateHook((block, state) ->
                    L1ObservationJournal.commitAuthenticatedCursors(
                            block, state, observationIdentityContext));
        }
        if (observationService != null || epochObservationCoordinator != null) {
            chainEngine.setMandatoryInputProvider(reference ->
                    mandatoryObservationFacts(reference != null ? reference.slot() : 0).stream()
                            .map(this::buildDurableObservationInput)
                            .toList());
            chainEngine.setObservationPrefixValidator(
                    this::verifyMandatoryObservationPrefix);
            chainEngine.setUnencodableMandatoryInputHandler(message ->
                    quarantineUnencodableObservation(
                            L1Observation.decode(message.getBody())));
            chainEngine.setObservationValidator((sequenced, historicalCatchUp) -> {
                var observation = sequenced.observation();
                if (observation.anchor()
                        instanceof L1Observation.EpochAnchor) {
                    L1EpochObservationCoordinator coordinator =
                            epochObservationCoordinator;
                    return coordinator != null
                            ? coordinator.verify(observation, historicalCatchUp)
                            : AppChainEngine.L1RefVerdict.MISMATCH;
                }
                L1ObservationService service = observationService;
                return service != null ? service.verify(sequenced)
                        : AppChainEngine.L1RefVerdict.MISMATCH;
            });
        }
    }

    @Override
    protected void createAnchorServices(AppLedgerStore ledgerStore) {
        boolean anchorScriptMode = config.anchor() != null && config.anchor().scriptMode();
        Supplier<AppChainEngine.L1Ref> anchorPointSupplier = this::l1TipPoint;
        Supplier<Long> anchorSlotSupplier = () -> {
            AppChainEngine.L1Ref last = anchorPointSupplier.get();
            return last != null ? last.slot() : 0L;
        };
        if (config.anchoringEnabled() && !anchorScriptMode) {
            if (txSubmitter == null || utxoStateSupplier == null) {
                log.warn("App-chain anchoring configured but L1 access is not wired — anchoring disabled");
            } else {
                this.anchorService = new AnchorService(
                        config.chainId(),
                        config.anchor(),
                        ledgerStore,
                        txSubmitter,
                        utxoStateSupplier,
                        h -> ledgerStore.block(h).orElse(null),
                        ledgerStore::tipHeight,
                        protocolMagic,
                        log);
                this.anchorService.wireFees(
                        () -> {
                            var supplier = anchorFeeParamsSupplier;
                            AnchorFeeParams params = supplier != null ? supplier.get() : null;
                            return params != null
                                    ? new AnchorService.FeeParams(params.minFeeA(), params.minFeeB())
                                    : null;
                        },
                        anchorSlotSupplier);
                this.anchorService.setConfirmationListener(this::publishConfirmedAnchor);
                this.anchorService.setCompletionGate(this::l1Reconciled);
            }
        }
        // ADR-059 anchor profile (v2, pinned identity) and membership handoff: chain-wide
        // settings every member reads. Invalid values and a pin on a non-script anchor fail
        // startup instead of degrading to an unpinned or v1 anchor.
        AnchorProfileSettings anchorProfile = AnchorProfileSettings.from(config.pluginSettings(), protocolMagic);
        boolean membershipHandoff = MembershipHandoff.selected(config.pluginSettings());
        if (anchorProfile.pinned() != null && config.anchor() != null && !anchorScriptMode) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': a pinned anchor identity requires anchor.mode=script");
        }
        ScriptAnchorService createdScriptService = null;
        // Script anchors (008.4): EVERY ledger member runs the co-sign
        // verifier (zero follower config — sign requests are verified
        // against this node's own ledger + L1 view); the node with
        // anchor.enabled + anchor.mode=script leads bootstrap/advances.
        if (txSubmitter != null && utxoStateSupplier != null) {
            boolean scriptLeader = config.anchoringEnabled() && anchorScriptMode;
            try {
                var scriptConfig = config.anchor() != null && config.anchor().script() != null
                        ? config.anchor().script()
                        : AppChainConfig.AnchorScriptConfig.defaults();
                var anchorCfg = config.anchor() != null ? config.anchor()
                        : new AppChainConfig.AnchorConfig(
                                false, "", 0, 0, 0);
                ScriptAnchorService scriptService = new ScriptAnchorService(
                        config.chainId(),
                        config.stateMachineId(),
                        anchorCfg,
                        ledgerStore,
                        txSubmitter,
                        utxoStateSupplier,
                        h -> ledgerStore.block(h).orElse(null),
                        ledgerStore::tipHeight,
                        new AnchorScriptArtifacts(scriptConfig, anchorProfile),
                        signer,
                        group::members,
                        group::threshold,
                        (topic, body) -> buildAndDiffuse(topic, body, 120),
                        scriptLeader,
                        protocolMagic,
                        log);
                scriptService.wireTxPricing(
                        () -> {
                            var supplier = anchorProtocolParamsSupplier;
                            return supplier != null ? supplier.get() : null;
                        },
                        anchorPointSupplier);
                // A follower's confirmation must not run ahead of delivery: the loop rolls back only from
                // recorded points, so an L1 fact above the cursor would survive a fork there (ADR-038 D3).
                scriptService.wireCanonicalHashAtSlot(slot -> {
                    ChainBlockReader chain = l1Chain;
                    L1DeliveryLoop delivery = l1Delivery;
                    if (chain == null || delivery == null || slot > delivery.healthyCursorSlot()) {
                        return null;
                    }
                    return chain.getCanonicalBlockReferenceAtSlot(slot)
                            .map(CanonicalBlockReference::blockHash).orElse(null);
                });
                scriptService.setConfirmationListener(this::publishConfirmedAnchor);
                scriptService.setCompletionGate(this::l1Reconciled);
                scriptService.wireAnchorProfile(anchorProfile);
                scriptService.wireMembershipHandoff(membershipHandoff, this::pendingMembershipHandoff);
                createdScriptService = scriptService;
            } catch (Exception e) {
                log.warn("Script-anchor service unavailable (errorType={})",
                        e.getClass().getName());
            }
        }
        if (createdScriptService != null) {
            // An anchor-profile.version (or validator) switch on an existing thread fails
            // startup with its cause instead of refusing every advance (review P3).
            createdScriptService.verifyPersistedScriptHash();
            // Fails startup closed on any pinned-identity mismatch (ADR-059 D8b).
            createdScriptService.installPinnedIdentity();
            // Co-signing resolves every body input locally: a filtered UTxO store must admit
            // the anchor thread and the leader's wallet (review P2-1).
            createdScriptService.checkUtxoViewFilter(utxoViewFilter);
            this.scriptAnchorService = createdScriptService;
        } else if (anchorProfile.pinned() != null && txSubmitter != null && utxoStateSupplier != null) {
            throw new IllegalStateException("App-chain '" + config.chainId()
                    + "': the pinned script anchor could not be started");
        }
        if (membershipHandoff && createdScriptService == null) {
            throw new IllegalStateException("App-chain '" + config.chainId() + "': "
                    + MembershipHandoff.SETTING + "=true requires a running script anchor, which "
                    + "could not be started");
        }
    }

    /**
     * {@code membership.anchor-handoff} needs a script anchor to carry the pending set: an
     * explicit non-script anchor mode or a node without L1 transaction access cannot.
     */
    @Override
    protected String membershipHandoffUnsupportedReason() {
        if (config.anchor() != null && !config.anchor().scriptMode()) {
            return "requires anchor.mode=script (this chain anchors in metadata mode)";
        }
        if (txSubmitter == null || utxoStateSupplier == null) {
            return "requires L1 access for the script anchor, and this node has none";
        }
        return null;
    }

    /**
     * Why the L1-stable anchor floor finds no stable anchor (proof-pruning status): no
     * stability depth or anchor service, no stable L1 point yet, no confirmation yet,
     * confirmations without an L1 inclusion block (legacy metadata journal entries), or a
     * confirmation journal whose bounded window holds only anchors newer than the stable point.
     */
    @Override
    protected String l1StableAnchorFloorReason() {
        String unreachable = l1AnchoredGateUnreachableReason();
        if (unreachable != null) {
            return unreachable;
        }
        AppChainEngine.L1Ref stable = stableL1Ref();
        if (stable == null) {
            return "this node has not yet followed l1.stability-depth L1 blocks, so it has no "
                    + "L1-stable point";
        }
        AppLedgerStore currentLedger = ledger;
        if (currentLedger == null) {
            return null;
        }
        return AnchorService.stableAnchorAbsenceReason(currentLedger, stable.slot());
    }

    @Override
    protected void startIntegrationInputs(long generationToken, AppLedgerStore ledgerStore) {
        startL1Delivery(generationToken, ledgerStore);
        subscribeL1Events();
        if (epochObservationCoordinator != null) {
            epochObservationCoordinator.start();
        }
    }

    @Override
    protected void scheduleIntegration(ScheduledExecutorService exec) {
        AnchorService currentAnchor = anchorService;
        if (currentAnchor != null) {
            exec.scheduleWithFixedDelay(
                    () -> generationUseOrNoop(currentAnchor::tick), 10, 10, TimeUnit.SECONDS);
            log.info("App-chain L1 anchoring enabled (address: {}, every {} blocks, label {})",
                    currentAnchor.anchorAddress(), config.anchor().everyBlocks(),
                    config.anchor().metadataLabel());
        }
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        boolean metadataAnchorLeader = config.anchoringEnabled()
                && config.anchor() != null && !config.anchor().scriptMode();
        if (currentScriptAnchor != null && !metadataAnchorLeader) {
            // Every member polls its OWN authenticated thread UTxO so
            // follower status/evidence/finality gates survive missed event
            // ordering and restart. ScriptAnchorService.tick() keeps all tx
            // construction/submission leader-only.
            exec.scheduleWithFixedDelay(
                    () -> generationUseOrNoop(currentScriptAnchor::tick),
                    10, 10, TimeUnit.SECONDS);
            if (config.anchoringEnabled()
                    && config.anchor() != null && config.anchor().scriptMode()) {
                log.info("App-chain L1 SCRIPT anchoring enabled (008.4): wallet={}, every {} blocks",
                        currentScriptAnchor.anchorAddress(), config.anchor().everyBlocks());
            }
        }
    }

    @Override
    protected void appendIntegrationStatus(Map<String, Object> status) {
        AnchorService currentAnchor = anchorService;
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentAnchor != null) {
            Map<String, Object> anchorStatus = currentAnchor.status();
            anchorStatus.put("lagBlocks",
                    Math.max(0, tipHeight() - currentAnchor.lastAnchoredHeight()));
            anchorStatus.put("stableAnchoredHeight", stableAnchoredHeight());
            status.put("anchor", anchorStatus);
        } else if (currentScriptAnchor != null
                && ((config.anchoringEnabled() && config.anchor() != null && config.anchor().scriptMode())
                        || currentScriptAnchor.bootstrapped())) {
            // Script mode (008.4): leader always; followers once they adopt
            // the anchor identity from a verified sign request
            Map<String, Object> anchorStatus = currentScriptAnchor.status();
            anchorStatus.put("lagBlocks",
                    Math.max(0, tipHeight() - currentScriptAnchor.lastAnchoredHeight()));
            anchorStatus.put("stableAnchoredHeight", stableAnchoredHeight());
            status.put("anchor", anchorStatus);
        }
        L1DeliveryLoop currentDelivery = l1Delivery;
        if (currentDelivery != null) {
            status.put("l1Delivery", l1DeliveryStatus(currentDelivery));
        }
        L1ObservationService currentObservations = observationService;
        if (currentObservations != null) {
            status.put("observers", currentObservations.status());
        }
        L1EpochObservationCoordinator currentEpochObservations = epochObservationCoordinator;
        if (currentEpochObservations != null) {
            status.put("epochObservers", currentEpochObservations.status());
        }
    }

    @Override
    protected void acknowledgeIntegratedObservations(AppBlock block) {
        L1EpochObservationCoordinator epochCoordinator = epochObservationCoordinator;
        L1ObservationService blockObservations = observationService;
        if (epochCoordinator != null || blockObservations != null) {
            try {
                AppBlockExecutionContext execution =
                        AppBlockExecutionContext.fromValidatedBlock(block);
                for (SequencedL1Observation observation : execution.l1Observations()) {
                    if (observation.observation().anchor()
                            instanceof L1Observation.EpochAnchor) {
                        if (epochCoordinator != null) {
                            epochCoordinator.onFinalized(observation.observation());
                        }
                    } else if (blockObservations != null) {
                        blockObservations.acknowledge(observation.observation());
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("Failed to acknowledge finalized L1 observations (errorType={})",
                        failure.getClass().getName());
            }
        }
    }

    @Override
    protected void stopIntegrationInputs(SynchronousCleanupFailures cleanupFailures) {
        for (var subscription : eventSubscriptions) {
            try {
                subscription.close();
            } catch (Throwable cleanupFailure) {
                recordCleanupFailure("event subscription", cleanupFailure, cleanupFailures);
            }
        }
        eventSubscriptions.clear();

        // The loop writes the app ledger, so it stops before the ledger closes. The sealed generation already
        // turns any further pass into a no-op; close waits for the one in flight.
        L1DeliveryLoop retiringDelivery = l1Delivery;
        l1Delivery = null;
        if (retiringDelivery != null) {
            closeResource("L1 delivery loop", retiringDelivery::close, cleanupFailures);
        }

        L1EpochObservationCoordinator retiringEpochCoordinator =
                epochObservationCoordinator;
        epochObservationCoordinator = null;
        if (retiringEpochCoordinator != null) {
            closeResource("L1 epoch observation coordinator",
                    retiringEpochCoordinator::close, cleanupFailures);
        }

    }

    @Override
    protected void clearIntegrationReferences() {
        anchorService = null;
        scriptAnchorService = null;
        observationService = null;
    }

    @Override
    protected void closeIntegrationOwnership() {
        BlockBodyRetentionRegistry.Registration retention = l1Retention;
        if (retention != null) {
            retention.close();
        }
    }

    @Override
    protected List<AppEffectExecutor> createEffectExecutors(
            String scheme, Map<String, String> executorConfig) {
        AppEffectExecutorFactory factory = pluginProviders.find(
                        AppEffectExecutorFactory.class, scheme)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No selected AppEffectExecutorFactory for scheme '" + scheme
                                + "' (available: "
                                + pluginProviders.names(AppEffectExecutorFactory.class) + ")"));
        List<AppEffectExecutor> created =
                Objects.requireNonNull(
                        factory.create(config.chainId(), executorConfig,
                                effectContext()),
                        "AppEffectExecutorFactory.create returned null");
        return created;
    }

    @Override
    protected String effectExecutorOwner(String scheme) {
        return pluginProviders.contributionOwner(AppEffectExecutorFactory.class, scheme).orElse("legacy");
    }

    @Override
    protected void acceptAnchorMessage(AppMessage message) {
        ScriptAnchorService currentScriptAnchor = scriptAnchorService;
        if (currentScriptAnchor != null) currentScriptAnchor.onAnchorMessage(message);
    }

    @Override
    protected long l1RefDeferralsCount() { return l1RefDeferrals.get(); }

    @Override
    protected boolean hasIntegrationResources() {
        return anchorService != null || scriptAnchorService != null || observationService != null
                || epochObservationCoordinator != null || !eventSubscriptions.isEmpty();
    }

    @Override
    protected String integrationReadinessProblem() {
        L1DeliveryLoop delivery = l1Delivery;
        return delivery != null ? l1DeliveryReadinessProblem(delivery) : null;
    }
}
