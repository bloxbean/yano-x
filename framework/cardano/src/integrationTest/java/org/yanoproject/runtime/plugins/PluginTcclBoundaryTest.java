package org.yanoproject.runtime.plugins;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppChainInfo;
import org.yanoproject.x.framework.api.appchain.AppQueryContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.appchain.AppStateReader;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.codec.MessageCodec;
import org.yanoproject.x.framework.api.appchain.transition.CommandDescriptor;
import org.yanoproject.x.framework.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.x.framework.api.appchain.transition.EventDescriptor;
import org.yanoproject.x.framework.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.x.framework.api.appchain.transition.RuleFact;
import org.yanoproject.x.framework.api.appchain.transition.RuleValueView;
import org.yanoproject.x.framework.api.appchain.transition.TransitionContext;
import org.yanoproject.x.framework.api.appchain.transition.TransitionDecision;
import org.yanoproject.x.framework.api.appchain.transition.TransitionKernel;
import org.yanoproject.x.framework.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.x.framework.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.x.framework.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;
import org.yanoproject.x.framework.api.appchain.effects.EffectResult;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerContext;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerMode;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerModeProvider;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;
import org.yanoproject.x.framework.api.appchain.signer.SignerProviderFactory;
import org.yanoproject.x.framework.api.appchain.sink.FinalizedStreamSink;
import org.yanoproject.x.framework.api.appchain.sink.FinalizedStreamSinkFactory;
import org.yanoproject.x.framework.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import org.yanoproject.x.framework.api.appchain.snapshot.AuthenticatedSnapshotSourceCommitmentV1;
import org.yanoproject.x.framework.api.appchain.snapshot.SnapshotDescriptorDraftV1;
import org.yanoproject.x.framework.api.appchain.snapshot.SnapshotEntry;
import org.yanoproject.x.framework.api.appchain.snapshot.SnapshotSourceBoundary;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.plugin.PluginActivationException;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.x.framework.host.AppLedgerKindBindings;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginTcclBoundaryTest {
    @Test
    void providerFactoriesAndEveryReturnedSpiCallbackUsePluginTcclAndRestoreCaller()
            throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        AssertingStateMachineProvider machines = new AssertingStateMachineProvider(probe);
        AssertingSequencerProvider sequencers = new AssertingSequencerProvider(probe);
        AssertingObserverProvider observers = new AssertingObserverProvider(probe);
        AssertingSignerFactory signers = new AssertingSignerFactory(probe);
        AssertingExecutorFactory executors = new AssertingExecutorFactory(probe);
        AssertingSinkFactory sinks = new AssertingSinkFactory(probe);
        List<CatalogPluginProviderRegistry.Entry> entries = List.of(
                entry("machine-bundle", AppLedgerPluginKinds.APP_STATE_MACHINE,
                        "machine", machines, probe),
                entry("sequencer-bundle", AppLedgerPluginKinds.SEQUENCER_MODE,
                        "sequencer", sequencers, probe),
                entry("observer-bundle", AppLedgerPluginKinds.L1_OBSERVER,
                        "observer", observers, probe),
                entry("signer-bundle", AppLedgerPluginKinds.SIGNER_PROVIDER,
                        "signer", signers, probe),
                entry("executor-bundle", AppLedgerPluginKinds.EFFECT_EXECUTOR,
                        "executor", executors, probe),
                entry("sink-bundle", AppLedgerPluginKinds.FINALIZED_SINK,
                        "sink", sinks, probe));
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(AppLedgerPluginKinds.KINDS,
                entries, entries.stream().map(CatalogPluginProviderRegistry.Entry::bundleId).toList(),
                List.of(), plugin);

        Thread.currentThread().setContextClassLoader(caller);
        try {
            AppStateMachineProvider machineProvider = registry.require(
                    AppStateMachineProvider.class, "machine");
            assertCaller(caller);
            assertThat(machineProvider.id()).isEqualTo("machine");
            AppStateMachine firstMachine = machineProvider.create();
            AppStateMachine secondMachine = machineProvider.create(null);
            assertThat(secondMachine).isNotSameAs(firstMachine);
            firstMachine.id();
            exerciseKernel(firstMachine.transitionKernel().orElseThrow());
            assertCaller(caller);
            firstMachine.init(null, null);
            firstMachine.validate(null);
            firstMachine.validateForBlock(null, 1, null);
            assertThat(firstMachine.validatePrivilegedSystemSubmission(
                    "~governance/profile", new byte[]{1}).isAccepted()).isTrue();
            Map<String, Object> machineStatus = firstMachine.operationalStatus();
            assertThat(machineStatus).containsEntry("profile", "ready");
            assertThatThrownBy(() -> machineStatus.put("mutate", true))
                    .isInstanceOf(UnsupportedOperationException.class);
            List<AuthenticatedSnapshotSeriesDescriptorV1> snapshotSeries =
                    firstMachine.authenticatedSnapshotSeries();
            assertThat(snapshotSeries)
                    .extracting(AuthenticatedSnapshotSeriesDescriptorV1::seriesId)
                    .containsExactly("history");
            AuthenticatedSnapshotSourceCommitmentV1 source = firstMachine
                    .authenticatedSnapshotSourceCommitments().getFirst();
            assertThat(source.seriesId()).isEqualTo("history");
            assertThat(source.algorithm()).isEqualTo("test");
            assertThat(source.wireVersion()).isEqualTo("test-v1");
            assertThat(source.compatibilityId()).isEqualTo("test@test-v1");
            byte[] accumulator = source.initial(new SnapshotDescriptorDraftV1(
                    snapshotSeries.getFirst(), 0, "history-0",
                    new SnapshotSourceBoundary.AppHeight(0), 0, 0, 0,
                    new byte[32], 0, 0));
            assertThat(source.append(accumulator, 0, List.of())).containsExactly(2);
            assertThat(accumulator).containsExactly(1);
            assertThat(source.finish(accumulator, 0, 0)).containsExactly(3);
            firstMachine.apply(null, null, null);
            firstMachine.onEffectResult(null, null, null, null);
            assertThatThrownBy(() -> firstMachine.query(
                    "failure", new byte[0], (AppQueryContext) null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("expected query failure");
            assertCaller(caller);

            SequencerModeProvider sequencerProvider = registry.require(
                    SequencerModeProvider.class, "sequencer");
            sequencerProvider.id();
            SequencerMode sequencer = sequencerProvider.create(null);
            sequencer.id();
            sequencer.init(null);
            sequencer.shouldProposeNow(1);
            sequencer.checkProposal(new byte[0], 1);
            Map<String, Object> sequencerStatus = sequencer.status();
            assertThat(sequencerStatus).containsEntry("mode", "ready");
            assertThatThrownBy(() -> sequencerStatus.put("mutate", true))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertCaller(caller);

            L1ObserverProvider observerProvider = registry.require(
                    L1ObserverProvider.class, "observer");
            observerProvider.type();
            L1Observer observer = observerProvider.create("instance", Map.of());
            observer.observerId();
            List<L1Observation> observations = observer.observe(1, new byte[32], null);
            assertThat(observations).singleElement()
                    .satisfies(observation -> assertThat(observation.observerId())
                            .isEqualTo("instance"));
            assertThatThrownBy(() -> observations.add(observations.getFirst()))
                    .isInstanceOf(UnsupportedOperationException.class);
            Map<String, Object> observerStatus = observer.status();
            assertThat(observerStatus).containsEntry("observer", "ready");
            assertThatThrownBy(() -> observerStatus.put("mutate", true))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertCaller(caller);

            SignerProviderFactory signerFactory = registry.require(
                    SignerProviderFactory.class, "signer");
            signerFactory.scheme();
            SignerProvider signer = signerFactory.create("key");
            signer.sign(new byte[0]);
            signer.publicKey();
            signer.publicKeyHex();
            assertCaller(caller);

            AppEffectExecutorFactory executorFactory = registry.require(
                    AppEffectExecutorFactory.class, "executor");
            executorFactory.scheme();
            List<AppEffectExecutor> executorProducts = executorFactory.create("chain", Map.of());
            assertThat(executorProducts).hasSize(2);
            assertThat(executorProducts.get(1)).isSameAs(executorProducts.getFirst());
            AppEffectExecutor executor = executorProducts.getFirst();
            executor.id();
            assertThat(executor.effectTypes()).containsExactly("effect");
            assertThat(executor.operationalSnapshot().readiness())
                    .isEqualTo(EffectExecutorOperationalSnapshot.Readiness.READY);
            executor.supports("effect");
            executor.execute(null, null);
            executor.close();
            assertCaller(caller);

            FinalizedStreamSinkFactory sinkFactory = registry.require(
                    FinalizedStreamSinkFactory.class, "sink");
            sinkFactory.scheme();
            List<FinalizedStreamSink> sinkProducts = sinkFactory.create("chain", Map.of());
            assertThat(sinkProducts).hasSize(2);
            assertThat(sinkProducts.get(1)).isSameAs(sinkProducts.getFirst());
            FinalizedStreamSink sink = sinkProducts.getFirst();
            sink.id();
            sink.legacyCursorKey();
            assertThatThrownBy(() -> sink.deliver(null))
                    .isInstanceOf(IOException.class)
                    .hasMessage("expected delivery failure");
            assertCaller(caller);
            sink.close();

            registry.close();
            assertCaller(caller);
            assertThat(sinks.closeCalls).hasValue(1);
            assertThat(probe.callbacks.get()).isGreaterThan(35);
        } finally {
            registry.close();
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void observerResultsOwnConsensusCriticalByteArraysAfterCallback() {
        byte[] txHash = new byte[32];
        byte[] blockHash = new byte[32];
        byte[] claim = new byte[]{1, 2, 3};
        L1Observation retained = L1Observation.transaction(
                "retained", txHash, 7, blockHash, claim);
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() { return "retained"; }
            @Override public List<L1Observation> observe(
                    long slot, byte[] hash, Block block) {
                return List.of(retained);
            }
        };
        L1ObserverProvider rawProvider = new L1ObserverProvider() {
            @Override public String type() { return "retained"; }
            @Override public L1Observer create(String id, Map<String, String> settings) {
                return rawObserver;
            }
        };
        L1ObserverProvider provider = (L1ObserverProvider) AppLedgerPluginKinds.provider(
                AppLedgerPluginKinds.L1_OBSERVER, rawProvider,
                getClass().getClassLoader(), "retained-bundle", "retained",
                rawProvider.getClass().getName());

        L1Observation snapshot = provider.create("retained", Map.of())
                .observe(7, new byte[32], null).getFirst();
        txHash[0] = 9;
        blockHash[0] = 9;
        claim[0] = 9;
        byte[] exposed = snapshot.claim();
        exposed[1] = 9;

        assertThat(snapshot.transactionAnchor().transactionHash()).containsOnly(0);
        assertThat(snapshot.blockHash()).containsOnly(0);
        assertThat(snapshot.claim()).containsExactly(1, 2, 3);
    }

    @Test
    void observerAggregateClaimBudgetStopsLazyTraversalAtFirstOverflow() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        byte[] blockHash = new byte[32];
        InfiniteProbeList<L1Observation> observations = new InfiniteProbeList<>(
                probe, rogue, ignored -> L1Observation.transaction(
                        "observer-instance", new byte[32], 9, blockHash,
                        new byte[AppLedgerKindBindings.MAX_OBSERVATION_CLAIM_BYTES]));
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() { probe.check(); return "observer-instance"; }
            @Override public List<L1Observation> observe(
                    long slot, byte[] callbackHash, Block block) {
                probe.check();
                return observations;
            }
        };
        L1ObserverProvider rawProvider = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "bounded-observer"; }
            @Override public L1Observer create(String id, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider provider = providerFacade(
                L1ObserverProvider.class, AppLedgerPluginKinds.L1_OBSERVER,
                rawProvider, plugin, "observer-bundle", "bounded-observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            L1Observer observer = provider.create("observer-instance", Map.of());
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observer aggregate claims exceed 4194304 bytes");
            assertThat(observations.hasNextCalls).hasValue(5);
            assertThat(observations.nextCalls).hasValue(5);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void observerSnapshotsRejectIdentitySlotAndBlockHashMismatches() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        byte[] blockHash = new byte[32];
        blockHash[0] = 1;
        AtomicReference<L1Observation> result = new AtomicReference<>();
        AtomicReference<byte[]> callbackInput = new AtomicReference<>();
        AtomicInteger mutateCallback = new AtomicInteger();
        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() { probe.check(); return "observer-instance"; }
            @Override public List<L1Observation> observe(
                    long slot, byte[] callbackHash, Block block) {
                probe.check();
                callbackInput.set(callbackHash);
                if (mutateCallback.get() != 0) {
                    callbackHash[0] = 2;
                    return List.of(L1Observation.transaction(
                            "observer-instance", new byte[32], slot,
                            callbackHash, new byte[0]));
                }
                return List.of(result.get());
            }
        };
        L1ObserverProvider rawProvider = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "verified-observer"; }
            @Override public L1Observer create(String id, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider provider = providerFacade(
                L1ObserverProvider.class, AppLedgerPluginKinds.L1_OBSERVER,
                rawProvider, plugin, "observer-bundle", "verified-observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            L1Observer observer = provider.create("observer-instance", Map.of());
            result.set(L1Observation.transaction(
                    "wrong-observer", new byte[32], 9, blockHash, new byte[0]));
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation id does not match configured observer");

            result.set(L1Observation.transaction(
                    "observer-instance", new byte[32], 10, blockHash, new byte[0]));
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation slot does not match callback slot");

            result.set(L1Observation.transaction(
                    "observer-instance", new byte[32], 9, new byte[32], new byte[0]));
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation block hash does not match callback block hash");

            mutateCallback.set(1);
            assertThatThrownBy(() -> observer.observe(9, blockHash, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("L1 observation block hash does not match callback block hash");
            assertThat(callbackInput.get()).isNotSameAs(blockHash);
            assertThat(blockHash[0]).isEqualTo((byte) 1);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void signerFacadeOwnsBuffersValidatesSignatureAndSanitizesFailures() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        AtomicReference<byte[]> pluginInput = new AtomicReference<>();
        AtomicReference<byte[]> pluginOutput = new AtomicReference<>(filledBytes(64, (byte) 7));
        AtomicReference<RuntimeException> pluginFailure = new AtomicReference<>();
        SignerProvider rawSigner = new SignerProvider() {
            @Override public byte[] sign(byte[] message) {
                probe.check();
                RuntimeException failure = pluginFailure.get();
                if (failure != null) {
                    throw failure;
                }
                pluginInput.set(message);
                if (message.length > 0) {
                    message[0] = 99;
                }
                return pluginOutput.get();
            }
            @Override public byte[] publicKey() { probe.check(); return new byte[32]; }
        };
        SignerProviderFactory rawFactory = new SignerProviderFactory() {
            @Override public String scheme() { probe.check(); return "safe-signer"; }
            @Override public SignerProvider create(String keyReference) {
                probe.check();
                return rawSigner;
            }
        };
        SignerProviderFactory factory = providerFacade(
                SignerProviderFactory.class, AppLedgerPluginKinds.SIGNER_PROVIDER,
                rawFactory, plugin, "signer-bundle", "safe-signer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            SignerProvider signer = factory.create("key-reference");
            byte[] message = new byte[]{1, 2, 3};
            byte[] rawSignature = pluginOutput.get();
            byte[] signature = signer.sign(message);

            assertThat(pluginInput.get()).isNotSameAs(message);
            assertThat(message).containsExactly(1, 2, 3);
            assertThat(signature).isNotSameAs(rawSignature).containsOnly(7);
            rawSignature[0] = 8;
            signature[1] = 9;
            assertThat(signature[0]).isEqualTo((byte) 7);
            assertThat(rawSignature[1]).isEqualTo((byte) 7);
            assertCaller(caller);

            pluginOutput.set(new byte[63]);
            PluginActivationException lengthFailure = assertActivationFailure(
                    () -> signer.sign(new byte[0]), AppLedgerPluginKinds.SIGNER_PROVIDER,
                    "signer-bundle", "safe-signer", rawFactory);
            assertThat(lengthFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("64-byte Ed25519 signature");
            assertCaller(caller);

            String secret = "secret signer backend detail";
            IllegalStateException backendFailure = new IllegalStateException(secret);
            pluginFailure.set(backendFailure);
            assertSecretSafeActivationFailure(
                    () -> signer.sign(new byte[0]), backendFailure, secret,
                    AppLedgerPluginKinds.SIGNER_PROVIDER,
                    "signer-bundle", "safe-signer", rawFactory);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void sequencerFacadeDoesNotExposeTheConsensusBlockProposerBuffer() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        AtomicReference<byte[]> pluginInput = new AtomicReference<>();
        SequencerMode rawMode = new SequencerMode() {
            @Override public String id() { probe.check(); return "safe-sequencer"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { probe.check(); return false; }
            @Override public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
                probe.check();
                pluginInput.set(proposerKey);
                proposerKey[0] = 99;
                return ProposalEligibility.ACCEPT;
            }
        };
        SequencerModeProvider rawProvider = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "safe-sequencer"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return rawMode;
            }
        };
        SequencerModeProvider provider = providerFacade(
                SequencerModeProvider.class, AppLedgerPluginKinds.SEQUENCER_MODE,
                rawProvider, plugin, "sequencer-bundle", "safe-sequencer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            SequencerMode sequencer = provider.create(null);
            byte[] proposerKey = new byte[]{1, 2, 3};
            assertThat(sequencer.checkProposal(proposerKey, 7))
                    .isEqualTo(SequencerMode.ProposalEligibility.ACCEPT);
            assertThat(pluginInput.get()).isNotSameAs(proposerKey);
            assertThat(proposerKey).containsExactly(1, 2, 3);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void stateMachineProductIdentityAssertionStaysInsideActivationBoundary() {
        String secret = "state-machine-identity-secret";
        AppStateMachine rawMachine = new AppStateMachine() {
            @Override public String id() { throw new AssertionError(secret); }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) { }
        };
        AppStateMachineProvider rawProvider = new AppStateMachineProvider() {
            @Override public String id() { return "secret-safe-machine"; }
            @Override public AppStateMachine create() { return rawMachine; }
        };
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "secret-safe-bundle", AppLedgerPluginKinds.APP_STATE_MACHINE,
                        "secret-safe-machine", rawProvider.getClass().getName(), null,
                        () -> rawProvider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(AppLedgerPluginKinds.KINDS,
                List.of(entry), List.of("secret-safe-bundle"), List.of());
        try {
            AppStateMachine product = registry.require(
                    AppStateMachineProvider.class, "secret-safe-machine").create();

            assertThatThrownBy(product::id)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageContaining("identify state-machine product")
                    .hasMessageNotContaining(secret)
                    .hasRootCauseMessage(secret);
        } finally {
            registry.close();
        }
    }

    @Test
    void startupIdentityAndStatusFailuresRetainPluginActivationIdentity() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);

        SequencerMode failingMode = new FailingStartupSequencerMode(probe);
        SequencerModeProvider rawSequencers = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return failingMode;
            }
        };
        L1Observer failingObserver = new FailingStartupObserver(probe);
        L1ObserverProvider rawObservers = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "observer"; }
            @Override public L1Observer create(
                    String observerId,
                    Map<String, String> settings
            ) {
                probe.check();
                return failingObserver;
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, AppLedgerPluginKinds.SEQUENCER_MODE,
                rawSequencers, plugin, "sequencer-bundle", "sequencer");
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, AppLedgerPluginKinds.L1_OBSERVER,
                rawObservers, plugin, "observer-bundle", "observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            SequencerMode sequencer = sequencers.create(null);
            assertActivationFailure(
                    sequencer::id, AppLedgerPluginKinds.SEQUENCER_MODE,
                    "sequencer-bundle", "sequencer", rawSequencers);
            assertActivationFailure(
                    sequencer::status, AppLedgerPluginKinds.SEQUENCER_MODE,
                    "sequencer-bundle", "sequencer", rawSequencers);

            L1Observer observer = observers.create("observer-instance", Map.of());
            assertActivationFailure(
                    observer::observerId, AppLedgerPluginKinds.L1_OBSERVER,
                    "observer-bundle", "observer", rawObservers);
            assertActivationFailure(
                    observer::status, AppLedgerPluginKinds.L1_OBSERVER,
                    "observer-bundle", "observer", rawObservers);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void lazyObserverAndStatusCollectionsGetFreshTcclScopesAfterRogueCallbacks() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        L1Observation observation = L1Observation.transaction(
                "observer-instance", new byte[32], 1, new byte[32], new byte[0]);

        SequencerMode mode = new SequencerMode() {
            @Override public String id() { probe.check(); return "sequencer"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { return false; }
            @Override public ProposalEligibility checkProposal(byte[] key, long height) {
                return ProposalEligibility.ACCEPT;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new ProbeMap<>(probe, Map.of("mode", "ready"));
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, AppLedgerPluginKinds.SEQUENCER_MODE,
                new SequencerModeProvider() {
                    @Override public String id() { probe.check(); return "sequencer"; }
                    @Override public SequencerMode create(SequencerContext context) {
                        probe.check();
                        return mode;
                    }
                }, plugin, "sequencer-bundle", "sequencer");

        L1Observer observer = new L1Observer() {
            @Override public String observerId() {
                probe.check();
                return "observer-instance";
            }
            @Override public List<L1Observation> observe(
                    long slot, byte[] blockHash, Block block) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new ProbeList<>(probe, observation);
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return new ProbeMap<>(probe, Map.of("observer", "ready"));
            }
        };
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, AppLedgerPluginKinds.L1_OBSERVER,
                new L1ObserverProvider() {
                    @Override public String type() { probe.check(); return "observer"; }
                    @Override public L1Observer create(
                            String observerId, Map<String, String> settings) {
                        probe.check();
                        return observer;
                    }
                }, plugin, "observer-bundle", "observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            assertThat(sequencers.create(null).status()).containsEntry("mode", "ready");
            L1Observer exposed = observers.create("observer-instance", Map.of());
            assertThat(exposed.observe(1, new byte[32], null)).singleElement()
                    .satisfies(snapshot -> {
                        assertThat(snapshot.observerId()).isEqualTo(observation.observerId());
                        assertThat(snapshot.slot()).isEqualTo(observation.slot());
                        assertThat(snapshot.transactionAnchor().transactionHash())
                                .isEqualTo(observation.transactionAnchor().transactionHash());
                        assertThat(snapshot.blockHash()).isEqualTo(observation.blockHash());
                        assertThat(snapshot.claim()).isEqualTo(observation.claim());
                    });
            assertThat(exposed.status()).containsEntry("observer", "ready");
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void statusAndObservationSnapshotsRejectInfinitePluginCollectionsUnderTccl() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader caller = new MarkerClassLoader(original);
        ClassLoader plugin = new MarkerClassLoader(original);
        ClassLoader rogue = new MarkerClassLoader(original);
        ContextProbe probe = new ContextProbe(plugin);
        InfiniteProbeMap sequencerStatus = new InfiniteProbeMap(probe, rogue);
        InfiniteProbeMap observerStatus = new InfiniteProbeMap(probe, rogue);
        L1Observation observation = L1Observation.transaction(
                "observer-instance", new byte[32], 1, new byte[32], new byte[0]);
        InfiniteProbeList<L1Observation> observations = new InfiniteProbeList<>(
                probe, rogue, ignored -> observation);

        SequencerMode rawMode = new SequencerMode() {
            @Override public String id() { probe.check(); return "bounded-status"; }
            @Override public void init(SequencerContext context) { probe.check(); }
            @Override public boolean shouldProposeNow(long height) { return false; }
            @Override public ProposalEligibility checkProposal(byte[] key, long height) {
                return ProposalEligibility.ACCEPT;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return sequencerStatus;
            }
        };
        SequencerModeProvider rawSequencers = new SequencerModeProvider() {
            @Override public String id() { probe.check(); return "bounded-status"; }
            @Override public SequencerMode create(SequencerContext context) {
                probe.check();
                return rawMode;
            }
        };
        SequencerModeProvider sequencers = providerFacade(
                SequencerModeProvider.class, AppLedgerPluginKinds.SEQUENCER_MODE,
                rawSequencers, plugin, "sequencer-bundle", "bounded-status");

        L1Observer rawObserver = new L1Observer() {
            @Override public String observerId() {
                probe.check();
                return "observer-instance";
            }
            @Override public List<L1Observation> observe(
                    long slot, byte[] blockHash, Block block) {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return observations;
            }
            @Override public Map<String, Object> status() {
                probe.check();
                Thread.currentThread().setContextClassLoader(rogue);
                return observerStatus;
            }
        };
        L1ObserverProvider rawObservers = new L1ObserverProvider() {
            @Override public String type() { probe.check(); return "bounded-observer"; }
            @Override public L1Observer create(
                    String observerId, Map<String, String> settings) {
                probe.check();
                return rawObserver;
            }
        };
        L1ObserverProvider observers = providerFacade(
                L1ObserverProvider.class, AppLedgerPluginKinds.L1_OBSERVER,
                rawObservers, plugin, "observer-bundle", "bounded-observer");

        Thread.currentThread().setContextClassLoader(caller);
        try {
            PluginActivationException sequencerFailure = assertActivationFailure(
                    () -> sequencers.create(null).status(),
                    AppLedgerPluginKinds.SEQUENCER_MODE, "sequencer-bundle",
                    "bounded-status", rawSequencers);
            assertThat(sequencerFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin status must contain at most 256 entries");
            assertThat(sequencerStatus.hasNextCalls).hasValue(257);
            assertThat(sequencerStatus.nextCalls).hasValue(256);
            assertCaller(caller);

            L1Observer observer = observers.create("observer-instance", Map.of());
            assertThatThrownBy(() -> observer.observe(1, new byte[32], null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("L1 observer result must contain at most 4096 observations");
            assertThat(observations.hasNextCalls).hasValue(4_097);
            assertThat(observations.nextCalls).hasValue(4_096);
            assertCaller(caller);

            PluginActivationException observerFailure = assertActivationFailure(
                    observer::status, AppLedgerPluginKinds.L1_OBSERVER,
                    "observer-bundle", "bounded-observer", rawObservers);
            assertThat(observerFailure.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Plugin status must contain at most 256 entries");
            assertThat(observerStatus.hasNextCalls).hasValue(257);
            assertThat(observerStatus.nextCalls).hasValue(256);
            assertCaller(caller);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static CatalogPluginProviderRegistry.Entry entry(
            String bundleId,
            ContributionKind kind,
            String name,
            Object provider,
            ContextProbe probe) {
        return new CatalogPluginProviderRegistry.Entry(
                bundleId, kind, name, provider.getClass().getName(), null, () -> {
                    probe.check();
                    return provider;
                });
    }

    private static <T> T providerFacade(
            Class<T> providerType,
            ContributionKind kind,
            T delegate,
            ClassLoader loader,
            String bundleId,
            String selector
    ) {
        return providerType.cast(AppLedgerPluginKinds.provider(
                kind, delegate, loader, bundleId, selector, delegate.getClass().getName()));
    }

    private static AppEffectExecutorFactory executorFactory(
            String scheme,
            ContextProbe probe,
            List<AppEffectExecutor> products
    ) {
        return new AppEffectExecutorFactory() {
            @Override public String scheme() { probe.check(); return scheme; }
            @Override public List<AppEffectExecutor> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                return products;
            }
        };
    }

    private static FinalizedStreamSinkFactory sinkFactory(
            String scheme,
            ContextProbe probe,
            List<FinalizedStreamSink> products
    ) {
        return new FinalizedStreamSinkFactory() {
            @Override public String scheme() { probe.check(); return scheme; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                probe.check();
                return products;
            }
        };
    }

    private static PluginActivationException assertActivationFailure(
            ThrowingCallable callback,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        Throwable failure = catchThrowable(callback);
        assertThat(failure).isInstanceOf(PluginActivationException.class);
        PluginActivationException activation = (PluginActivationException) failure;
        assertThat(activation.bundleId()).isEqualTo(bundleId);
        assertThat(activation.contributionKind()).isEqualTo(kind.manifestKey());
        assertThat(activation.selector()).isEqualTo(selector);
        assertThat(activation.providerClass()).isEqualTo(rawProvider.getClass().getName());
        return activation;
    }

    private static void assertSecretSafeActivationFailure(
            ThrowingCallable callback,
            Throwable cause,
            String secret,
            ContributionKind kind,
            String bundleId,
            String selector,
            Object rawProvider
    ) {
        PluginActivationException activation = assertActivationFailure(
                callback, kind, bundleId, selector, rawProvider);
        assertThat(activation)
                .hasMessageNotContaining(secret)
                .hasCause(cause);
    }

    private static void assertCaller(ClassLoader caller) {
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
    }

    private static byte[] filledBytes(int length, byte value) {
        byte[] result = new byte[length];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static final class MarkerClassLoader extends ClassLoader {
        private MarkerClassLoader(ClassLoader parent) {
            super(parent);
        }
    }

    private static final class ContextProbe {
        private final ClassLoader expected;
        private final AtomicInteger callbacks = new AtomicInteger();

        private ContextProbe(ClassLoader expected) {
            this.expected = expected;
        }

        private void check() {
            callbacks.incrementAndGet();
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(expected);
        }
    }

    /** A map whose entry traversal and every entry read require the plugin context; entries may be foreign. */
    private static final class ProbeEntries extends AbstractMap<String, Object> {
        private final ContextProbe probe;
        private final List<Map.Entry<?, ?>> entries;
        private final boolean repeat;

        private ProbeEntries(ContextProbe probe, List<Map.Entry<?, ?>> entries, boolean repeat) {
            this.probe = probe;
            this.entries = entries;
            this.repeat = repeat;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Set<Entry<String, Object>> entrySet() {
            probe.check();
            return new AbstractSet<>() {
                @Override public Iterator<Entry<String, Object>> iterator() {
                    probe.check();
                    return new Iterator<>() {
                        private int index;
                        @Override public boolean hasNext() {
                            probe.check();
                            return repeat || index < entries.size();
                        }
                        @Override public Entry<String, Object> next() {
                            probe.check();
                            Map.Entry<?, ?> entry = entries.get(repeat ? 0 : index++);
                            return entry == null ? null
                                    : (Entry) new ProbeEntry(probe, entry.getKey(), entry.getValue());
                        }
                    };
                }
                @Override public int size() {
                    probe.check();
                    return repeat ? Integer.MAX_VALUE : entries.size();
                }
            };
        }
    }

    private record ProbeEntry(ContextProbe probe, Object key, Object value) implements Map.Entry<Object, Object> {
        @Override public Object getKey() { probe.check(); return key; }
        @Override public Object getValue() { probe.check(); return value; }
        @Override public Object setValue(Object replacement) { throw new UnsupportedOperationException(); }
    }

    private static final class ProbeList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final List<T> values;

        @SafeVarargs
        private ProbeList(ContextProbe probe, T... values) {
            this.probe = probe;
            this.values = List.of(values);
        }

        @Override public T get(int index) { probe.check(); return values.get(index); }
        @Override public int size() { probe.check(); return values.size(); }
    }

    private static final class ProbeMap<K, V> extends AbstractMap<K, V> {
        private final ContextProbe probe;
        private final Map<K, V> values;

        private ProbeMap(ContextProbe probe, Map<K, V> values) {
            this.probe = probe;
            this.values = values;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            probe.check();
            return values.entrySet();
        }
    }

    private static final class InfiniteProbeList<T> extends AbstractList<T> {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final IntFunction<T> values;
        private final AtomicInteger hasNextCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InfiniteProbeList(
                ContextProbe probe,
                ClassLoader rogue,
                IntFunction<T> values
        ) {
            this.probe = probe;
            this.rogue = rogue;
            this.values = values;
        }

        @Override
        public Iterator<T> iterator() {
            checkAndPoison();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    checkAndPoison();
                    hasNextCalls.incrementAndGet();
                    return true;
                }

                @Override
                public T next() {
                    probe.check();
                    T value = values.apply(nextCalls.getAndIncrement());
                    Thread.currentThread().setContextClassLoader(rogue);
                    return value;
                }
            };
        }

        @Override public T get(int index) { throw new UnsupportedOperationException(); }
        @Override public int size() { throw new UnsupportedOperationException(); }

        private void checkAndPoison() {
            probe.check();
            Thread.currentThread().setContextClassLoader(rogue);
        }
    }

    private static final class InfiniteProbeMap extends AbstractMap<String, Object> {
        private final ContextProbe probe;
        private final ClassLoader rogue;
        private final AtomicInteger hasNextCalls = new AtomicInteger();
        private final AtomicInteger nextCalls = new AtomicInteger();

        private InfiniteProbeMap(ContextProbe probe, ClassLoader rogue) {
            this.probe = probe;
            this.rogue = rogue;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            checkAndPoison();
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    checkAndPoison();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            checkAndPoison();
                            hasNextCalls.incrementAndGet();
                            return true;
                        }

                        @Override
                        public Entry<String, Object> next() {
                            probe.check();
                            int value = nextCalls.getAndIncrement();
                            Thread.currentThread().setContextClassLoader(rogue);
                            return new Entry<>() {
                                @Override public String getKey() {
                                    checkAndPoison();
                                    return "duplicate-key";
                                }

                                @Override public Object getValue() {
                                    checkAndPoison();
                                    return value;
                                }

                                @Override public Object setValue(Object replacement) {
                                    throw new UnsupportedOperationException();
                                }
                            };
                        }
                    };
                }

                @Override public int size() { throw new UnsupportedOperationException(); }
            };
        }

        private void checkAndPoison() {
            probe.check();
            Thread.currentThread().setContextClassLoader(rogue);
        }
    }

    private static final class AssertingStateMachineProvider implements AppStateMachineProvider {
        private final ContextProbe probe;

        private AssertingStateMachineProvider(ContextProbe probe) {
            this.probe = probe;
        }

        @Override public String id() { probe.check(); return "machine"; }
        @Override public AppStateMachine create() {
            probe.check();
            return new AssertingStateMachine(probe);
        }
        @Override public AppStateMachine create(AppStateMachineContext context) {
            probe.check();
            return new AssertingStateMachine(probe);
        }
    }

    private static <C, F> void exerciseKernel(TransitionKernel<C, F> kernel) {
        C command = kernel.codec().decode(new byte[]{1});
        assertThat(kernel.codec().encode(command)).containsExactly(1);
        assertThat(kernel.codec().type()).isEqualTo(byte[].class);
        var context = new TransitionContext(1, 0, 0, new byte[32], "test", new byte[32]);
        assertThat(kernel.admit(command).isAccepted()).isTrue();
        C rejected = kernel.codec().decode(new byte[]{0});
        assertThat(kernel.admit(rejected).reason()).isEqualTo("COMMAND_REJECTED");
        assertThat(kernel.admit(command, context).isAccepted()).isTrue();
        F facts = kernel.facts(command, context, null);
        assertThat(kernel.readParticipants()).isEmpty();
        assertThat(kernel.workBudgets()).containsExactly(new TransitionWorkBudget("crypto", new byte[]{9}, 5));
        assertThat(kernel.workReferences()).containsExactly(new TransitionWorkReference("machine", "crypto"));
        assertThat(kernel.workRequest(command, context)).contains(new TransitionWorkRequest(
                new TransitionWorkReference("machine", "crypto"), 1));
        assertThat(kernel.facts(command, context, null, Map.of())).isEqualTo(facts);
        assertThat(kernel.decide(command, context, facts)).isInstanceOf(TransitionDecision.Approved.class);
        assertThat(kernel.commands()).hasSize(1);
        assertThat(kernel.events()).hasSize(1);
        assertThat(kernel.configuration().settings()).isEmpty();
        byte[] logical = {4};
        assertThat(kernel.lookupKey(logical)).containsExactly(5);
        assertThat(logical).containsExactly(4);
        assertThat(kernel.ruleFacts()).containsExactly(new RuleFact("verified", RuleFact.Type.BOOLEAN),
                new RuleFact("roles", RuleFact.Type.TEXT_SET), new RuleFact("digest", RuleFact.Type.BYTES));
        Map<String, Object> values = kernel.ruleFactValues(command, context, facts);
        assertThat(values).containsEntry("verified", true).containsEntry("roles", List.of("auditor", "operator"));
        @SuppressWarnings("unchecked")
        List<String> copied = (List<String>) values.get("mutable");
        assertThat(copied).containsExactly("a", "b");
        assertThatThrownBy(() -> copied.add("c")).isInstanceOf(UnsupportedOperationException.class);
        byte[] digest = (byte[]) values.get("digest");
        digest[0] = 99;
        assertThat((byte[]) kernel.ruleFactValues(command, context, facts).get("digest")).containsExactly(7);
        assertThatThrownBy(() -> values.put("forged", true)).isInstanceOf(UnsupportedOperationException.class);
        // Oversized values are truncated one element past their bound, never rejected here: the engine rejects them.
        C oversized = kernel.codec().decode(new byte[]{2});
        Map<String, Object> overflow = kernel.ruleFactValues(oversized, context, facts);
        assertThat(overflow).hasSize(RuleFact.MAX_FACTS);
        assertThat((List<?>) overflow.get("roles")).hasSize(RuleFact.MAX_SET_ENTRIES + 1);
        assertThat((byte[]) overflow.get("blob")).hasSize(RuleFact.MAX_VALUE_BYTES + 1);
        assertThat(kernel.ruleFactValues(kernel.codec().decode(new byte[]{3}), context, facts)).isNull();
        Map<String, Object> repeated = kernel.ruleFactValues(kernel.codec().decode(new byte[]{4}), context, facts);
        // Overflowing its bound, the map becomes the marker alone rather than a collapsed, valid-looking entry.
        assertThat(repeated).containsOnlyKeys("");
        Map<String, Object> malformed = kernel.ruleFactValues(kernel.codec().decode(new byte[]{5}), context, facts);
        assertThat(malformed).containsOnlyKeys("", "roles");
        assertThat(malformed.get("roles")).isNotInstanceOf(java.util.TreeSet.class);
        exerciseRuleViews(kernel, command, context, facts);
    }

    /** ADR-031.4 typed views cross the facade under the plugin TCCL, as bounded host-owned copies. */
    private static <C, F> void exerciseRuleViews(TransitionKernel<C, F> kernel, C command, TransitionContext context,
                                                 F facts) {
        var balance = new RuleFact("balance", RuleFact.Type.INTEGER);
        assertThat(kernel.ruleValueViews()).containsExactly(new RuleValueView("", List.of(balance), List.of()),
                new RuleValueView("items", List.of(new RuleFact("status", RuleFact.Type.TEXT)),
                        List.of(new RuleFact("price", RuleFact.Type.INTEGER))));
        byte[] key = {4};
        byte[] local = kernel.ruleValueKey("items", key);
        assertThat(local).containsExactly(5);
        assertThat(key).containsExactly(4);
        local[0] = 42;
        assertThat(kernel.ruleValueKey("items", key)).containsExactly(5);
        Map<String, Object> decoded = kernel.ruleValueFields("items", key, new byte[]{1});
        assertThat(decoded).containsEntry("status", "ACTIVE").containsEntry("value.price", 12L);
        ((byte[]) decoded.get("value.tag"))[0] = 99;
        assertThat((byte[]) kernel.ruleValueFields("items", key, new byte[]{1}).get("value.tag")).containsExactly(7);
        assertThatThrownBy(() -> decoded.put("forged", true)).isInstanceOf(UnsupportedOperationException.class);
        // More than declared fields plus value fields: the marker alone, for the engine to reject.
        assertThat(kernel.ruleValueFields("items", key, new byte[]{2})).containsOnlyKeys("");
        assertThat(kernel.ruleValueFields("items", key, new byte[]{3})).isNull();

        assertThat(kernel.ruleWriteFields()).containsExactly(new RuleFact("op", RuleFact.Type.TEXT));
        assertThat(kernel.ruleWriteCoverageFields()).containsExactly(new RuleFact("actorId", RuleFact.Type.TEXT));
        List<Map<String, Object>> writes = kernel.ruleWrites(command);
        assertThat(writes).containsExactly(Map.of("op", "PUT"), Map.of("op", "REVOKE"));
        assertThatThrownBy(() -> writes.add(Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> writes.getFirst().put("op", "PUT")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(kernel.ruleWrites(kernel.codec().decode(new byte[]{2}))).hasSize(RuleValueView.MAX_WRITES + 1);
        assertThat(kernel.ruleWrites(kernel.codec().decode(new byte[]{3}))).isNull();
        // A null or foreign element becomes the host's non-conforming marker instead of escaping or throwing.
        assertThat(kernel.ruleWrites(kernel.codec().decode(new byte[]{5})))
                .allSatisfy(element -> assertThat(element).containsOnlyKeys(""));
        assertThat(kernel.ruleWriteCoverage(command, context, facts)).containsExactly(Map.of("actorId", "maker-a"),
                Map.of());
    }

    private static final class AssertingStateMachine implements AppStateMachine {
        private final ContextProbe probe;

        private AssertingStateMachine(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "machine"; }
        @Override public Optional<TransitionKernel<?, ?>> transitionKernel() {
            probe.check();
            return Optional.of(new TransitionKernel<byte[], Boolean>() {
                private final OrderedLogKernel delegate = new OrderedLogKernel();
                private final byte[] digest = {7};
                private final List<String> mutableRoles = new ArrayList<>(List.of("a", "b"));
                @Override public MessageCodec<byte[]> codec() {
                    probe.check();
                    return new MessageCodec<>() {
                        @Override public byte[] encode(byte[] value) { probe.check(); return value.clone(); }
                        @Override public byte[] decode(byte[] body) { probe.check(); return body.clone(); }
                        @Override public Class<byte[]> type() { probe.check(); return byte[].class; }
                    };
                }
                @Override public AdmissionResult admit(byte[] command) {
                    probe.check();
                    return command[0] == 0 ? AdmissionResult.reject("COMMAND_REJECTED") : AdmissionResult.accept();
                }
                @Override public AdmissionResult admit(byte[] command, TransitionContext context) {
                    probe.check();
                    return AdmissionResult.accept();
                }
                @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
                    probe.check();
                    return true;
                }
                @Override public List<String> readParticipants() { probe.check(); return List.of(); }
                @Override public byte[] lookupKey(byte[] logicalKey) {
                    probe.check();
                    logicalKey[0]++;
                    return logicalKey;
                }
                @Override public List<TransitionWorkBudget> workBudgets() {
                    probe.check();
                    return List.of(new TransitionWorkBudget("crypto", new byte[]{9}, 5));
                }
                @Override public List<TransitionWorkReference> workReferences() {
                    probe.check();
                    return List.of(new TransitionWorkReference("machine", "crypto"));
                }
                @Override public Optional<TransitionWorkRequest> workRequest(byte[] command, TransitionContext context) {
                    probe.check();
                    return Optional.of(new TransitionWorkRequest(new TransitionWorkReference("machine", "crypto"), 1));
                }
                @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state,
                                                Map<String, AppStateReader> participants) {
                    probe.check();
                    assertThat(participants).isEmpty();
                    return true;
                }
                @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
                    probe.check();
                    return delegate.decide(command, context, facts);
                }
                @Override public List<RuleFact> ruleFacts() {
                    probe.check();
                    return List.of(new RuleFact("verified", RuleFact.Type.BOOLEAN),
                            new RuleFact("roles", RuleFact.Type.TEXT_SET), new RuleFact("digest", RuleFact.Type.BYTES));
                }
                @Override public Map<String, Object> ruleFactValues(byte[] command, TransitionContext context,
                                                                    Boolean facts) {
                    probe.check();
                    return switch (command[0]) {
                        case 3 -> null;
                        case 2 -> {
                            List<Map.Entry<?, ?>> oversized = new ArrayList<>();
                            oversized.add(Map.entry("roles", IntStream.range(0, 100)
                                    .mapToObj(index -> "role" + index).toList()));
                            oversized.add(Map.entry("blob", new byte[1_000_000]));
                            for (int index = 0; index < RuleFact.MAX_FACTS - 2; index++) {
                                oversized.add(Map.entry("fact" + index, true));
                            }
                            yield new ProbeEntries(probe, oversized, false);
                        }
                        // A misbehaving iterator that repeats one key forever must still terminate.
                        case 4 -> new ProbeEntries(probe, List.of(Map.entry("verified", true)), true);
                        // A raw map: a non-text key, a null entry, and a plugin-owned value object.
                        case 5 -> new ProbeEntries(probe, Arrays.asList(new AbstractMap.SimpleEntry<>(42L, true),
                                null, Map.entry("roles", new java.util.TreeSet<>(List.of("a")))), false);
                        default -> new ProbeEntries(probe, List.of(Map.entry("verified", facts),
                                Map.entry("roles", new ProbeList<>(probe, "auditor", "operator")),
                                Map.entry("digest", digest), Map.entry("mutable", mutableRoles)), false);
                    };
                }
                @Override public List<RuleValueView> ruleValueViews() {
                    probe.check();
                    return List.of(new RuleValueView("", List.of(new RuleFact("balance", RuleFact.Type.INTEGER)),
                                    List.of()),
                            new RuleValueView("items", List.of(new RuleFact("status", RuleFact.Type.TEXT)),
                                    List.of(new RuleFact("price", RuleFact.Type.INTEGER))));
                }
                @Override public byte[] ruleValueKey(String namespace, byte[] key) {
                    probe.check();
                    key[0]++;
                    return key;
                }
                @Override public Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
                    probe.check();
                    return switch (stored[0]) {
                        case 3 -> null;
                        case 2 -> {
                            List<Map.Entry<?, ?>> oversized = new ArrayList<>();
                            for (int index = 0; index < 100; index++) oversized.add(Map.entry("f" + index, 1L));
                            yield new ProbeEntries(probe, oversized, false);
                        }
                        default -> new ProbeEntries(probe, List.of(Map.entry("status", "ACTIVE"),
                                Map.entry("value.price", 12L), Map.entry("value.tag", digest)), false);
                    };
                }
                @Override public List<RuleFact> ruleWriteFields() {
                    probe.check();
                    return List.of(new RuleFact("op", RuleFact.Type.TEXT));
                }
                @Override public List<RuleFact> ruleWriteCoverageFields() {
                    probe.check();
                    return List.of(new RuleFact("actorId", RuleFact.Type.TEXT));
                }
                @Override public List<Map<String, Object>> ruleWrites(byte[] command) {
                    probe.check();
                    return switch (command[0]) {
                        case 3 -> null;
                        case 2 -> IntStream.range(0, 200).mapToObj(index -> Map.<String, Object>of("op", "PUT"))
                                .toList();
                        case 5 -> {
                            List<Object> foreign = Arrays.asList(null, "not a map");
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> raw = (List<Map<String, Object>>) (List<?>) foreign;
                            yield raw;
                        }
                        default -> new ProbeList<Map<String, Object>>(probe, new ProbeEntries(probe,
                                List.of(Map.entry("op", "PUT")), false), Map.<String, Object>of("op", "REVOKE"));
                    };
                }
                @Override public List<Map<String, Object>> ruleWriteCoverage(byte[] command, TransitionContext context,
                                                                            Boolean facts) {
                    probe.check();
                    return List.of(Map.of("actorId", "maker-a"), Map.of());
                }
                @Override public List<CommandDescriptor> commands() { probe.check(); return delegate.commands(); }
                @Override public List<EventDescriptor> events() { probe.check(); return delegate.events(); }
                @Override public ConfigurationDescriptor configuration() {
                    probe.check();
                    return delegate.configuration();
                }
            });
        }
        @Override public void init(AppStateReader state, AppChainInfo info) { probe.check(); }
        @Override public AdmissionResult validate(AppMessage message) {
            probe.check();
            return AdmissionResult.accept();
        }
        @Override
        public AdmissionResult validateForBlock(
                AppMessage message, long candidateHeight, AppStateReader committedState
        ) {
            probe.check();
            return AdmissionResult.accept();
        }
        @Override
        public AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
            probe.check();
            return AdmissionResult.accept();
        }
        @Override public Map<String, Object> operationalStatus() {
            probe.check();
            return new ProbeMap<>(probe, Map.of("profile", "ready"));
        }
        @Override
        public List<AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
            probe.check();
            var profile = StateCommitmentProfiles.MPF;
            return List.of(new AuthenticatedSnapshotSeriesDescriptorV1(
                    "history", "history-v1",
                    AuthenticatedSnapshotSeriesDescriptorV1.Trigger.L1_EPOCH_BOUNDARY,
                    profile.id(), profile.formatFingerprint(), profile.proofEncodingId(),
                    AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN,
                    AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                    "test", "test-v1", 1, 1024, 32, 1024, 1,
                    AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET));
        }
        @Override
        public List<AuthenticatedSnapshotSourceCommitmentV1>
                authenticatedSnapshotSourceCommitments() {
            probe.check();
            return List.of(new AuthenticatedSnapshotSourceCommitmentV1() {
                @Override public String seriesId() { probe.check(); return "history"; }
                @Override public String algorithm() { probe.check(); return "test"; }
                @Override public String wireVersion() { probe.check(); return "test-v1"; }
                @Override public byte[] initial(SnapshotDescriptorDraftV1 draft) {
                    probe.check();
                    return new byte[]{1};
                }
                @Override public byte[] append(
                        byte[] accumulator, long chunkIndex, List<SnapshotEntry> entries
                ) {
                    probe.check();
                    accumulator[0] = 9;
                    return new byte[]{2};
                }
                @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
                    probe.check();
                    accumulator[0] = 8;
                    return new byte[]{3};
                }
            });
        }
        @Override
        public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                          AppEffectEmitter effects) {
            probe.check();
        }
        @Override
        public void onEffectResult(
                AppBlockExecutionContext context,
                EffectResult result,
                AppStateWriter writer,
                AppEffectEmitter effects
        ) {
            probe.check();
        }
        @Override public byte[] query(
                String path, byte[] params, AppQueryContext context
        ) {
            probe.check();
            throw new IllegalStateException("expected query failure");
        }
    }

    private static final class AssertingSequencerProvider implements SequencerModeProvider {
        private final ContextProbe probe;
        private final SequencerMode mode;

        private AssertingSequencerProvider(ContextProbe probe) {
            this.probe = probe;
            this.mode = new AssertingSequencerMode(probe);
        }

        @Override public String id() { probe.check(); return "sequencer"; }
        @Override public SequencerMode create(SequencerContext context) {
            probe.check();
            return mode;
        }
    }

    private static final class AssertingSequencerMode implements SequencerMode {
        private final ContextProbe probe;

        private AssertingSequencerMode(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "sequencer"; }
        @Override public void init(SequencerContext context) { probe.check(); }
        @Override public boolean shouldProposeNow(long height) { probe.check(); return true; }
        @Override public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            probe.check();
            return ProposalEligibility.ACCEPT;
        }
        @Override public Map<String, Object> status() {
            probe.check();
            return new ProbeMap<>(probe, Map.of("mode", "ready"));
        }
    }

    private static final class FailingStartupSequencerMode implements SequencerMode {
        private final ContextProbe probe;

        private FailingStartupSequencerMode(ContextProbe probe) {
            this.probe = probe;
        }

        @Override public String id() {
            probe.check();
            throw new IllegalStateException("expected sequencer id failure");
        }
        @Override public void init(SequencerContext context) { probe.check(); }
        @Override public boolean shouldProposeNow(long height) { return false; }
        @Override public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.REJECT;
        }
        @Override public Map<String, Object> status() {
            probe.check();
            throw new IllegalStateException("expected sequencer status failure");
        }
    }

    private static final class AssertingObserverProvider implements L1ObserverProvider {
        private final ContextProbe probe;
        private final L1Observer observer;

        private AssertingObserverProvider(ContextProbe probe) {
            this.probe = probe;
            this.observer = new AssertingObserver(probe);
        }

        @Override public String type() { probe.check(); return "observer"; }
        @Override public L1Observer create(String observerId, Map<String, String> settings) {
            probe.check();
            return observer;
        }
    }

    private static final class AssertingObserver implements L1Observer {
        private final ContextProbe probe;

        private AssertingObserver(ContextProbe probe) { this.probe = probe; }

        @Override public String observerId() { probe.check(); return "instance"; }
        @Override public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            probe.check();
            return new ProbeList<>(probe,
                    L1Observation.transaction("instance", new byte[32], slot,
                            new byte[32], new byte[0]));
        }
        @Override public Map<String, Object> status() {
            probe.check();
            return new ProbeMap<>(probe, Map.of("observer", "ready"));
        }
    }

    private static final class FailingStartupObserver implements L1Observer {
        private final ContextProbe probe;

        private FailingStartupObserver(ContextProbe probe) {
            this.probe = probe;
        }

        @Override public String observerId() {
            probe.check();
            throw new IllegalStateException("expected observer id failure");
        }
        @Override public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            return List.of();
        }
        @Override public Map<String, Object> status() {
            probe.check();
            throw new IllegalStateException("expected observer status failure");
        }
    }

    private static final class AssertingSignerFactory implements SignerProviderFactory {
        private final ContextProbe probe;
        private final SignerProvider signer;

        private AssertingSignerFactory(ContextProbe probe) {
            this.probe = probe;
            this.signer = new AssertingSigner(probe);
        }

        @Override public String scheme() { probe.check(); return "signer"; }
        @Override public SignerProvider create(String keyReference) {
            probe.check();
            return signer;
        }
    }

    private static final class AssertingSigner implements SignerProvider {
        private final ContextProbe probe;

        private AssertingSigner(ContextProbe probe) { this.probe = probe; }

        @Override public byte[] sign(byte[] message) { probe.check(); return new byte[64]; }
        @Override public byte[] publicKey() { probe.check(); return new byte[32]; }
        @Override public String publicKeyHex() { probe.check(); return "00".repeat(32); }
    }

    private static final class AssertingExecutorFactory implements AppEffectExecutorFactory {
        private final ContextProbe probe;
        private final AppEffectExecutor executor;

        private AssertingExecutorFactory(ContextProbe probe) {
            this.probe = probe;
            this.executor = new AssertingExecutor(probe);
        }

        @Override public String scheme() { probe.check(); return "executor"; }
        @Override public List<AppEffectExecutor> create(
                String chainId, Map<String, String> config) {
            probe.check();
            return new ProbeList<>(probe, executor, executor);
        }
    }

    private static final class AssertingExecutor implements AppEffectExecutor {
        private final ContextProbe probe;

        private AssertingExecutor(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "executor"; }
        @Override public Set<String> effectTypes() { probe.check(); return Set.of("effect"); }
        @Override public EffectExecutorOperationalSnapshot operationalSnapshot() {
            probe.check();
            return new EffectExecutorOperationalSnapshot(
                    EffectExecutorOperationalSnapshot.Readiness.READY,
                    1, 1, 0, 0, 0,
                    EffectExecutorOperationalSnapshot.AgeBucket.LESS_THAN_ONE_MINUTE,
                    EffectExecutorOperationalSnapshot.AgeBucket.NEVER,
                    EffectExecutorOperationalSnapshot.FailureCode.NONE);
        }
        @Override public boolean supports(String effectType) { probe.check(); return true; }
        @Override public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
            probe.check();
            return EffectExecution.confirmed(new byte[0]);
        }
        @Override public void close() { probe.check(); }
    }

    private static final class AssertingSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private final ContextProbe probe;
        private final FinalizedStreamSink sink;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private AssertingSinkFactory(ContextProbe probe) {
            this.probe = probe;
            this.sink = new AssertingSink(probe);
        }

        @Override public String scheme() { probe.check(); return "sink"; }
        @Override public List<FinalizedStreamSink> create(
                String chainId, Map<String, String> config) {
            probe.check();
            return new ProbeList<>(probe, sink, sink);
        }
        @Override public void close() { probe.check(); closeCalls.incrementAndGet(); }
    }

    private static final class AssertingSink implements FinalizedStreamSink {
        private final ContextProbe probe;

        private AssertingSink(ContextProbe probe) { this.probe = probe; }

        @Override public String id() { probe.check(); return "sink"; }
        @Override public boolean deliver(AppBlock block) throws Exception {
            probe.check();
            throw new IOException("expected delivery failure");
        }
        @Override public String legacyCursorKey() { probe.check(); return "old-sink"; }
        @Override public void close() { probe.check(); }
    }

}
