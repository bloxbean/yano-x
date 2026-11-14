package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppCapabilityManifest;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppLedgerOperatingProfile;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppQueryException;
import org.yanoproject.api.appchain.AppQueryResult;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.proof.ProofSubjectProvider;
import org.yanoproject.api.appchain.transition.FinalizedMessageIndex;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class StandaloneAppLedgerRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void failedTerminalHookCompletesConcurrentClosesAndRejectsSelfClose() throws Exception {
        SimpleEventBus events = new SimpleEventBus();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closes = new AtomicInteger();
        var failure = new IllegalStateException("integration close failed");
        AppLedgerRuntime runtime = new AppLedgerRuntime(config(), 424242, events, null,
                directory.resolve("ledger").toString(), PluginProviderRegistry.empty(),
                LoggerFactory.getLogger(getClass())) {
            @Override protected void closeIntegrationOwnership() {
                closes.incrementAndGet();
                assertThatThrownBy(this::close).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("callback/lifecycle transition");
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                throw failure;
            }
        };
        var workers = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory());
        try {
            var first = workers.submit(runtime::close);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(runtime::close);
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            for (var close : List.of(first, second)) {
                assertThatThrownBy(() -> close.get(3, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class,
                                thrown -> assertThat(thrown.getCause()).isSameAs(failure));
            }
            assertThatThrownBy(runtime::close).isSameAs(failure);
            assertThat(closes).hasValue(1);
        } finally {
            release.countDown();
            workers.shutdownNow();
            events.close();
        }
    }

    @Test
    void failedIntegrationStopHooksStillCloseGenericLedgerAndRetainBothFailures() {
        SimpleEventBus events = new SimpleEventBus();
        var stopFailure = new IllegalStateException("integration stop failed");
        var clearFailure = new IllegalStateException("integration references failed");
        AppChainConfig config = config();
        String storage = directory.resolve("ledger").toString();
        AppLedgerRuntime runtime = new AppLedgerRuntime(config, 424242, events, null,
                storage, PluginProviderRegistry.empty(), LoggerFactory.getLogger(getClass())) {
            @Override protected void stopIntegrationInputs(SynchronousCleanupFailures failures) {
                throw stopFailure;
            }
            @Override protected void clearIntegrationReferences() { throw clearFailure; }
        };
        try {
            runtime.start();
            assertThatThrownBy(runtime::close).isSameAs(stopFailure);
            assertThat(stopFailure.getSuppressed()).contains(clearFailure);
            assertThatThrownBy(runtime::close).isSameAs(stopFailure);
            // Reopening proves generic teardown released the real RocksDB lock.
            try (AppLedgerRuntime reopened = new AppLedgerRuntime(config, 424242, events, null,
                    storage, PluginProviderRegistry.empty(), LoggerFactory.getLogger(getClass()))) {
                reopened.start();
            }
        } finally {
            events.close();
        }
    }

    @Test
    void managerWaitsForRealQueryCleanupWithoutMakingPendingRestartTerminal() throws Exception {
        SimpleEventBus events = new SimpleEventBus();
        QueryableOrderedLog machine = new QueryableOrderedLog();
        AppLedgerRuntime runtime = runtime(config(), machine, events);
        AppLedgerManager manager = new AppLedgerManager(List.of(runtime), LoggerFactory.getLogger(getClass()));
        var workers = Executors.newFixedThreadPool(2, Thread.ofVirtual().factory());
        try {
            manager.start();
            byte[] id = HexFormat.of().parseHex(runtime.submit("orders", new byte[]{1}));
            awaitFinalized(runtime, id);
            machine.blockQueries = true;
            workers.submit(() -> runtime.query("message", id));
            assertThat(machine.queryEntered.await(3, TimeUnit.SECONDS)).isTrue();
            manager.stop();
            assertThat(manager.awaitStopped(Duration.ofMillis(50))).isFalse();
            assertThatThrownBy(runtime::start).hasMessageContaining("still draining");
            workers.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    assertThatThrownBy(() -> manager.awaitStopped(Duration.ofSeconds(1)))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Interrupted");
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                } finally { Thread.interrupted(); }
            }).get(3, TimeUnit.SECONDS);
            machine.releaseQuery.countDown();
            assertThat(manager.awaitStopped(Duration.ofSeconds(3))).isTrue();
            assertThat(manager.awaitStopped(Duration.ZERO)).isTrue();
            machine.blockQueries = false;
            manager.start();
            assertThat(runtime.messageHeight(id)).isPresent();
        } finally {
            machine.releaseQuery.countDown();
            manager.close();
            workers.shutdownNow();
            events.close();
        }
    }

    @Test
    void managerPreservesCleanupFailurePrecedenceAcrossChains() {
        SimpleEventBus events = new SimpleEventBus();
        var ordinary = new IllegalStateException("ordinary cleanup");
        var fatal = new AssertionError("fatal cleanup");
        List<AppLedgerRuntime> runtimes = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            int selected = index;
            runtimes.add(new AppLedgerRuntime(config("cleanup-" + index), 424242, events, null,
                    directory.resolve("cleanup").toString(), PluginProviderRegistry.empty(),
                    LoggerFactory.getLogger(getClass())) {
                @Override protected void stopIntegrationInputs(SynchronousCleanupFailures failures) {
                    if (selected == 0) throw ordinary;
                    throw fatal;
                }
            });
        }
        AppLedgerManager manager = new AppLedgerManager(runtimes, LoggerFactory.getLogger(getClass()));
        try {
            manager.start();
            manager.stop(); // Runtime retains cleanup failures for restart/terminal close.
            assertThatThrownBy(() -> manager.awaitStopped(Duration.ofSeconds(1))).isSameAs(fatal);
            assertThat(fatal.getSuppressed()).contains(ordinary);
            assertThatThrownBy(() -> manager.awaitStopped(Duration.ofNanos(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            assertThatThrownBy(manager::close).isSameAs(fatal);
            events.close();
        }
    }

    @Test
    void finalizesQueriesAndRestartsWithoutCardanoRuntimeOrSdk() throws Exception {
        for (String unavailable : List.of(
                "org.yanoproject.runtime.appchain.AppChainSubsystem",
                "org.yanoproject.runtime.appchain.AppChainManager",
                "org.yanoproject.api.appchain.anchor.AnchorDatumV1",
                "com.bloxbean.cardano.client.plutus.spec.PlutusData",
                "com.bloxbean.cardano.client.transaction.util.TransactionUtil")) {
            assertThatThrownBy(() -> Class.forName(unavailable))
                    .as("The lean runtime must not contain %s", unavailable)
                    .isInstanceOf(ClassNotFoundException.class);
        }

        AppChainConfig config = config();
        QueryableOrderedLog machine = new QueryableOrderedLog();
        SimpleEventBus events = new SimpleEventBus();
        byte[] firstId;
        byte[] secondId;
        AppBlock firstBlock;
        AppBlock secondBlock;
        try {
            AppLedgerRuntime runtime = runtime(config, machine, events);
            try (AppLedgerManager manager = new AppLedgerManager(List.of(runtime),
                    LoggerFactory.getLogger(getClass()))) {
                assertThat(manager.byId(config.chainId())).contains(runtime);
                manager.start();
                firstId = HexFormat.of().parseHex(runtime.submit("orders",
                        "first standalone order".getBytes(StandardCharsets.UTF_8)));
                firstBlock = awaitFinalized(runtime, firstId);
                assertFinality(firstBlock, firstId);
                assertQuery(runtime, machine, firstId, firstBlock);
                assertThat(runtime.finalizedCommitment(firstBlock.height()).orElseThrow().stateRoot())
                        .isEqualTo(firstBlock.stateRoot());

                manager.stop();
                assertThatThrownBy(() -> runtime.query("message", firstId))
                        .isInstanceOfSatisfying(AppQueryException.class,
                                failure -> assertThat(failure.code()).isEqualTo(AppQueryException.Code.UNAVAILABLE));
                assertThat(manager.awaitStopped(Duration.ofSeconds(3))).isTrue();
                manager.start();
                assertThat(AppBlockCodec.blockHash(runtime.block(firstBlock.height()).orElseThrow()))
                        .isEqualTo(AppBlockCodec.blockHash(firstBlock));
                assertQuery(runtime, machine, firstId, firstBlock);

                secondId = HexFormat.of().parseHex(runtime.submit("orders",
                        "order after restart".getBytes(StandardCharsets.UTF_8)));
                secondBlock = awaitFinalized(runtime, secondId);
                assertFinality(secondBlock, secondId);
                assertThat(secondBlock.height()).isEqualTo(firstBlock.height() + 1);
                assertThat(secondBlock.prevHash()).isEqualTo(AppBlockCodec.blockHash(firstBlock));
                assertQuery(runtime, machine, secondId, secondBlock);
            }

            // A fresh owner must reopen the same persisted ledger after terminal close.
            QueryableOrderedLog reopenedMachine = new QueryableOrderedLog();
            AppLedgerRuntime reopened = runtime(config, reopenedMachine, events);
            try (AppLedgerManager manager = new AppLedgerManager(List.of(reopened),
                    LoggerFactory.getLogger(getClass()))) {
                manager.start();
                assertThat(reopened.messageHeight(firstId)).contains(firstBlock.height());
                assertThat(reopened.messageHeight(secondId)).contains(secondBlock.height());
                assertThat(reopened.stateRoot()).isEqualTo(secondBlock.stateRoot());
                assertQuery(reopened, reopenedMachine, secondId, secondBlock);
            }
        } finally {
            events.close();
        }
    }

    private AppLedgerRuntime runtime(AppChainConfig config, AppStateMachine machine, SimpleEventBus events) {
        return new AppLedgerRuntime(config, 424242, events, machine,
                directory.resolve("ledger").toString(), PluginProviderRegistry.empty(),
                LoggerFactory.getLogger(getClass()));
    }

    private AppChainConfig config() {
        return config("lean-runtime");
    }

    private AppChainConfig config(String chainId) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 73);
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        return AppChainConfig.builder(chainId)
                .signingKeyHex(HexFormat.of().formatHex(seed))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(50)
                .stateMachineId(OrderedLogStateMachine.ID)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .pluginSettings(Map.of(AppLedgerOperatingProfile.SETTING,
                        AppLedgerOperatingProfile.STANDALONE.id()))
                .build();
    }

    private static AppBlock awaitFinalized(AppLedgerRuntime runtime, byte[] id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (runtime.messageHeight(id).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(runtime.messageHeight(id)).as("submitted message finalized").isPresent();
        return runtime.block(runtime.messageHeight(id).orElseThrow()).orElseThrow();
    }

    private static void assertFinality(AppBlock block, byte[] id) {
        assertThat(block.cert().signatures()).hasSize(1);
        assertThat(block.messages()).anySatisfy(message -> assertThat(message.getMessageId()).isEqualTo(id));
        assertThat(block.l1Slot()).isZero();
        assertThat(block.l1BlockHash()).isEmpty();
    }

    private static void assertQuery(AppLedgerRuntime runtime, QueryableOrderedLog machine,
                                    byte[] id, AppBlock block) {
        Thread caller = Thread.currentThread();
        AppQueryResult result = runtime.query("message", id);
        FinalizedMessageIndex.MessageRecord record = FinalizedMessageIndex.decode(result.payload());
        assertThat(record.height()).isEqualTo(block.height());
        assertThat(record.topic()).isEqualTo("orders");
        assertThat(result.committedHeight()).isEqualTo(block.height());
        assertThat(result.stateRoot()).isEqualTo(block.stateRoot());
        assertThat(machine.queryThread.get()).isNotSameAs(caller);
        assertThat(machine.queryThread.get().getName()).startsWith("app-chain-query-");
    }

    private static final class QueryableOrderedLog implements AppStateMachine {
        private final OrderedLogStateMachine delegate = new OrderedLogStateMachine();
        private final AtomicReference<Thread> queryThread = new AtomicReference<>();
        private final CountDownLatch queryEntered = new CountDownLatch(1);
        private final CountDownLatch releaseQuery = new CountDownLatch(1);
        private volatile boolean blockQueries;

        @Override
        public String id() { return delegate.id(); }

        @Override
        public AppCapabilityManifest capabilityManifest() { return delegate.capabilityManifest(); }

        @Override
        public Optional<TransitionKernel<?, ?>> transitionKernel() { return delegate.transitionKernel(); }

        @Override
        public List<ProofSubjectProvider> proofSubjectProviders() { return delegate.proofSubjectProviders(); }

        @Override
        public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
            delegate.apply(context, writer, effects);
        }

        @Override
        public byte[] query(String path, byte[] request, AppQueryContext state) {
            assertThat(path).isEqualTo("message");
            queryThread.set(Thread.currentThread());
            if (blockQueries) {
                queryEntered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { releaseQuery.await(); break; }
                    catch (InterruptedException cancellation) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return state.get(FinalizedMessageIndex.messageKey(request)).orElseThrow();
        }
    }
}
