package org.yanoproject.x.framework.host;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.api.host.PluginServiceLookup;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.StorageFilter;
import org.yanoproject.api.plugin.operations.PluginOperation;
import org.yanoproject.api.plugin.operations.PluginOperationOutcome;
import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;
import org.yanoproject.api.plugin.spi.PluginProducts;
import org.yanoproject.p2p.peer.PeerClientFactory;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerManager;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerRuntime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppLedgerHostPluginTest {
    @Test
    void constructionFailureClosesPreviouslyConstructedChains() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.failConstructionAt = 1;
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessage("construction failed");
        assertThat(plugin.chains.getFirst().closes).isEqualTo(1);
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void wiringFailureClosesTheJustConstructedChain() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.failWiring = true;
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessage("wiring failed");
        assertThat(plugin.chains.getFirst().closes).isEqualTo(1);
    }

    @Test
    void rejectedOwnershipClosesTheManagerAndEveryChain() {
        Harness harness = new Harness();
        harness.rejectOwnership = true;
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessage("ownership rejected");
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void protocolRegistrationFailureClosesAllConstructedChains() {
        Harness harness = new Harness();
        harness.rejectProtocols = true;
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessage("protocol rejected");
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void hostOwnsStartFailureRollbackAndCloseAfterSuccessfulTransfer() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.assembly).isNotNull();
        assertThat(harness.services.get(AppLedgers.SERVICE_KEY)).isInstanceOf(AppLedgerHolder.class);
        harness.initializationComplete = true;
        harness.assembly.run();
        AppLedgerManager manager = harness.owned.get();
        assertThat(manager).isNotNull();
        plugin.start();
        plugin.stop();
        plugin.close();
        plugin.chains.forEach(chain -> {
            assertThat(chain.starts).isZero();
            assertThat(chain.closes).isZero();
        });
        plugin.chains.get(1).failStart = true;
        assertThatThrownBy(manager::start).hasMessage("chain start failed");
        assertThat(plugin.chains.getFirst().stops).isEqualTo(1);
        manager.close();
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
    }

    @Test
    void assemblyPublishesTheHolderAndRegistersEveryHostPosition() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        AppLedgers ledgers = AppLedgers.of(harness);
        assertThat(ledgers.appChains().all()).isEmpty();
        assertThat(ledgers.localReadModels()).isEmpty();
        plugin.assemble();
        try {
            assertThat(harness.published).extracting(Subsystem::name)
                    .containsExactly(harness.owned.get().name(), "local-read-models");
            assertThat(harness.readiness).isNotNull();
            assertThat(harness.readiness.getAsBoolean()).isTrue();
            assertThat(harness.products).isNotNull();
            assertThat(ledgers.appChains().all()).hasSize(2);
            assertThat(ledgers.appChain()).isEmpty();
            assertThat(ledgers.localReadModels()).isPresent();
            assertThat(ledgers.domainApis().routes()).isEmpty();
            harness.products.resume();
            harness.products.sealAndAwait();
            harness.products.sealAndAwait();
        } finally {
            harness.owned.get().close();
            plugin.close();
        }
        assertThat(ledgers.appChains().all()).isEmpty();
        assertThat(ledgers.localReadModels()).isEmpty();
    }

    @Test
    void disabledAppChainsStillAssembleDomainRegistriesInCardanoMode() {
        Harness harness = new Harness();
        harness.node = YanoConfig.builder().mode(RuntimeMode.CARDANO).build();
        harness.globals(Map.of("yano.app-chain.enabled", false));
        ProbePlugin plugin = new ProbePlugin();
        plugin.allowCardanoDeployment = true;
        plugin.init(harness);
        plugin.assemble();
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
        assertThat(harness.readiness).isNull();
        assertThat(harness.published).extracting(Subsystem::name).containsExactly("local-read-models");
        assertThat(harness.appOnlyNetwork).isNull();
        AppLedgers ledgers = AppLedgers.of(harness);
        assertThat(ledgers.appChains().all()).isEmpty();
        assertThat(ledgers.localReadModels()).isPresent();
        harness.products.resume();
        harness.products.sealAndAwait();
        plugin.close();
        assertThat(ledgers.localReadModels()).isEmpty();
    }

    @Test
    void standaloneRequiresEnabledAppChainsBeforeAnyResource() {
        Harness harness = new Harness();
        harness.globals(Map.of("yano.app-chain.enabled", false));
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("yano.mode=app-ledger requires yano.app-chain.enabled=true");
        assertThat(harness.products).isNull();
        assertThat(harness.appOnlyNetwork).isNull();
    }

    @Test
    void standaloneSuppliesTheAppOnlyNetworkAndValidatesStorage() {
        Harness harness = new Harness();
        plugin(harness).assemble();
        assertThat(harness.appOnlyNetwork).isEqualTo(424242L);
        harness.owned.get().close();

        for (String storage : List.of("", "  ")) {
            Harness blank = new Harness();
            Map<String, Object> globals = new HashMap<>(flatGlobals("dedicated"));
            globals.put("yano.app-chain.storage.path", storage);
            blank.globals(globals);
            assertThatThrownBy(plugin(blank)::assemble).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("yano.app-chain.storage.path is required");
        }
        Harness same = new Harness();
        same.node = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).rocksDBPath("shared-store").build();
        Map<String, Object> globals = new HashMap<>(flatGlobals("dedicated"));
        globals.put("yano.app-chain.storage.path", "./shared-store");
        same.globals(globals);
        assertThatThrownBy(plugin(same)::assemble).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("App storage must differ from yano.storage.path");
        assertThat(same.appOnlyNetwork).isNull();
    }

    @Test
    void partialAssemblyClosesTheRegistriesItCreatedAndLeavesTheManagerWithTheHost() {
        Harness harness = new Harness();
        harness.rejectReadModelStage = true;
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessage("read-model stage rejected");
        assertThat(harness.products).isNotNull();
        // The domain registry was closed in reverse creation order: it can no longer resume.
        assertThatThrownBy(harness.products::resume).isInstanceOf(IllegalStateException.class);
        harness.products.sealAndAwait();
        assertThat(AppLedgers.of(harness).localReadModels()).isEmpty();
        plugin.chains.forEach(chain -> assertThat(chain.closes).isZero());
        harness.owned.get().close();
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
        plugin.close();
    }

    @Test
    void closeBeforeAssemblyAndRepeatedCloseAreNoOps() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        plugin.close();
        plugin.close();
        assertThatThrownBy(plugin::assemble).hasMessageContaining("closed");
        assertThat(harness.products).isNull();

        Harness assembled = new Harness();
        ProbePlugin owner = plugin(assembled);
        owner.assemble();
        owner.close();
        assertThatThrownBy(assembled.products::resume).isInstanceOf(IllegalStateException.class);
        owner.close();
        assertThat(AppLedgers.of(assembled).appChains().all()).isEmpty();
        assembled.owned.get().close();
    }

    @Test
    void legacyDiscoveryWithoutFeatureActivationNeedsNoHostServices() {
        Harness harness = new Harness();
        harness.services.clear();
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        plugin.start();
        plugin.stop();
        plugin.close();
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
        assertThat(harness.assembly).isNull();
    }

    @Test
    void explicitLegacyActivationRejectsAMissingHostApi() {
        Harness harness = new Harness();
        harness.services.clear();
        harness.legacyConfig = Map.of("yano.app-chain.enabled", true);
        ProbePlugin plugin = new ProbePlugin();
        assertThatThrownBy(() -> plugin.init(harness)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(HostApi.SERVICE_KEY);
        assertThat(plugin.chains).isEmpty();
    }

    @Test
    void olderHostApiLevelIsRejectedDuringInit() {
        Harness harness = new Harness();
        harness.version = new HostApiVersion(1, 1);
        ProbePlugin plugin = new ProbePlugin();
        assertThatThrownBy(() -> plugin.init(harness)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1.2");
        assertThat(harness.assembly).isNull();
    }

    @Test
    void factoriesObserveLaterPluginInitializationAndAssemblyPublishesNoServices() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.requireLaterService = true;
        plugin.init(harness);
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
        // A different plugin initializes after the framework provider.
        harness.registerService("later-plugin-ready", Boolean.TRUE);
        harness.initializationComplete = true;
        plugin.assemble();
        try {
            assertThat(plugin.chains).hasSize(2);
            assertThatThrownBy(plugin::assemble).hasMessageContaining("already assembled");
        } finally {
            harness.owned.get().close();
        }
    }

    @Test
    void configurationValidationIsDeferredUntilAssembly() {
        Harness harness = new Harness();
        harness.node = null;
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThat(plugin.chains).isEmpty();
        assertThatThrownBy(plugin::assemble).hasMessageContaining("node configuration unavailable");
    }

    @Test
    void factoryWorkerCanRejectConcurrentAssemblyWithoutWaitingOnFactoryCompletion() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.attemptWorkerAssembly = true;
        plugin.init(harness);
        plugin.assemble();
        try {
            assertThat(plugin.chains).hasSize(2);
        } finally {
            harness.owned.get().close();
        }
    }

    @Test
    void closeFromFactoryWorkerCancelsAssemblyAndClosesReturnedChain() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.closeFromFactoryWorker = true;
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessageContaining("closed");
        assertThat(plugin.chains).hasSize(1);
        assertThat(plugin.chains.getFirst().closes).isEqualTo(1);
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void closeDuringProtocolRegistrationCancelsBeforeOwnershipTransfer() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        harness.onProtocolRegistration = plugin::close;
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessageContaining("closed");
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void closeDuringSuccessfulOwnershipTransferLeavesCleanupWithHost() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        harness.onOwnershipTransfer = plugin::close;
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).hasMessageContaining("closed");
        assertThat(harness.owned.get()).isNotNull();
        assertThat(harness.products).isNull();
        plugin.chains.forEach(chain -> assertThat(chain.closes).isZero());
        harness.owned.get().close();
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
    }

    @Test
    void rejectedInitServiceRegistrationCreatesNoRuntimeResources() {
        Harness harness = new Harness();
        harness.initializationComplete = true;
        ProbePlugin plugin = new ProbePlugin();
        assertThatThrownBy(() -> plugin.init(harness)).hasMessage("service registration outside init");
        plugin.close();
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
        assertThat(harness.assembly).isNull();
        assertThatThrownBy(plugin::assemble).hasMessageContaining("closed");
    }

    @Test
    void laterPluginInitFailureClosesAdapterWithoutConstructingFactories() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        // The host must skip assembly when a later plugin init fails; catalog
        // rollback closes this provider without any ledger factories having run.
        plugin.close();
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
        assertThatThrownBy(plugin::assemble).hasMessageContaining("closed");
    }

    @Test
    void indexedTransportUsesChainOverrideThenGlobalThenSharedDefault() {
        for (String global : List.of(" dedicated ", "shared", "")) {
            for (String perChain : List.of(" dedicated ", "shared", "")) {
                Harness harness = new Harness();
                harness.globals(transportGlobals(global, "dedicated", perChain));
                String effective = !perChain.isEmpty() ? perChain.trim()
                        : !global.isEmpty() ? global.trim() : "shared";
                if (effective.equals("dedicated")) assertAssemblyAccepts(harness, 2);
                else assertTransportRejectedBeforeFactories(harness, "second");
            }
        }
    }

    @Test
    void blankIndexedTransportFallsBackAndDedicatedIsCaseInsensitive() {
        for (String perChain : List.of("", "  ", "Dedicated", " DEDICATED ")) {
            Map<String, Object> first = new HashMap<>(Harness.chain("first"));
            first.put("transport.mode", perChain);
            Harness harness = new Harness();
            harness.globals(Map.of("yano.app-chain.enabled", true,
                    "yano.app-chain.network-magic", 424242,
                    "yano.app-chain.transport.mode", " Dedicated ",
                    "yano.app-chain.chains", List.of(first)));
            assertAssemblyAccepts(harness, 1);
        }
        for (String transport : List.of("Dedicated", " DEDICATED ")) {
            Harness harness = new Harness();
            harness.globals(flatGlobals(transport));
            assertAssemblyAccepts(harness, 1);
        }
    }

    @Test
    void blankIndexedAndGlobalModesStillRejectTheSharedDefault() {
        Map<String, Object> first = new HashMap<>(Harness.chain("first"));
        first.put("transport.mode", "  ");
        Harness harness = new Harness();
        harness.globals(Map.of("yano.app-chain.enabled", true,
                "yano.app-chain.network-magic", 424242,
                "yano.app-chain.transport.mode", "  ",
                "yano.app-chain.chains", List.of(first)));
        assertTransportRejectedBeforeFactories(harness, "first");
    }

    @Test
    void standaloneNeverDecoratesSharedOutboundSessionsEvenWhenHostOffersThem() {
        Harness harness = new Harness();
        harness.sharedOutboundSessions = true;
        harness.node = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).enableClient(true).build();
        harness.globals(transportGlobals("shared", "dedicated", "Dedicated"));
        // Harness.decorateOutbound throws: per-chain dedicated overrides must
        // never cause the standalone runtime to reuse the global shared session.
        assertAssemblyAccepts(harness, 2);
    }

    @Test
    void standaloneNetworkMagicRejectsMissingMalformedAndOutOfRangeBeforeFactories() {
        for (String magic : List.of("", " ", "not-a-number", "-1", "0", "4294967296", "9223372036854775808")) {
            Harness harness = new Harness();
            Map<String, Object> globals = new HashMap<>(flatGlobals("dedicated"));
            if (magic.isEmpty()) globals.remove("yano.app-chain.network-magic");
            else globals.put("yano.app-chain.network-magic", magic);
            harness.globals(globals);
            ProbePlugin plugin = new ProbePlugin();
            plugin.init(harness);
            assertThatThrownBy(plugin::assemble).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("yano.app-chain.network-magic");
            assertThat(plugin.chains).isEmpty();
            assertThat(harness.owned.get()).isNull();
        }
    }

    @Test
    void standaloneNetworkMagicPreservesUnsignedUpperBound() {
        Harness harness = new Harness();
        Map<String, Object> globals = new HashMap<>(flatGlobals("dedicated"));
        globals.put("yano.app-chain.network-magic", " 4294967295 ");
        harness.globals(globals);
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        plugin.assemble();
        try {
            assertThat(plugin.lastMagic).isEqualTo(0xffff_ffffL);
            assertThat(harness.appOnlyNetwork).isEqualTo(0xffff_ffffL);
        } finally { harness.owned.get().close(); }
    }

    @Test
    void indexedSharedRejectsGlobalDedicatedBeforeAnyChainFactoryRuns() {
        Harness harness = new Harness();
        harness.globals(transportGlobals("dedicated", "dedicated", "shared"));
        assertTransportRejectedBeforeFactories(harness, "second");
    }

    @Test
    void indexedMissingTransportRejectsTheImplicitSharedDefault() {
        Harness harness = new Harness();
        harness.globals(transportGlobals("", "dedicated", ""));
        assertTransportRejectedBeforeFactories(harness, "second");
    }

    @Test
    void flatDedicatedRetainsItsExistingConfigurationShape() {
        Harness harness = new Harness();
        harness.globals(flatGlobals(" dedicated "));
        assertAssemblyAccepts(harness, 1);
    }

    @Test
    void flatSharedAndUnspecifiedTransportRejectBeforeFactories() {
        for (String transport : List.of("shared", "")) {
            Harness harness = new Harness();
            harness.globals(flatGlobals(transport));
            assertTransportRejectedBeforeFactories(harness, "flat");
        }
    }

    @Test
    void cardanoModeDoesNotAcquireTheStandaloneTransportRestriction() {
        for (String global : List.of("shared", "dedicated", "")) {
            Harness harness = new Harness();
            harness.node = YanoConfig.builder().mode(RuntimeMode.CARDANO).rocksDBPath("unused-cardano").build();
            harness.globals(transportGlobals(global, "shared", ""));
            ProbePlugin plugin = new ProbePlugin();
            plugin.allowCardanoDeployment = true;
            plugin.failConstructionAt = 0;
            plugin.init(harness);
            // Reach the optional runtime factory without imposing standalone
            // transport rules; this descriptor-free adapter test stays SDK-free.
            assertThatThrownBy(plugin::assemble).hasMessage("construction failed");
            assertThat(harness.appOnlyNetwork).isNull();
        }
    }

    private static ProbePlugin plugin(Harness harness) {
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        return plugin;
    }

    private static Map<String, Object> transportGlobals(String global, String firstMode, String secondMode) {
        Map<String, Object> first = new HashMap<>(Harness.chain("first"));
        Map<String, Object> second = new HashMap<>(Harness.chain("second"));
        if (!firstMode.isEmpty()) first.put("transport.mode", firstMode);
        if (!secondMode.isEmpty()) second.put("transport.mode", secondMode);
        Map<String, Object> globals = new HashMap<>(Map.of("yano.app-chain.enabled", true,
                "yano.app-chain.network-magic", 424242, "yano.app-chain.chains", List.of(first, second)));
        if (!global.isEmpty()) globals.put("yano.app-chain.transport.mode", global);
        return globals;
    }

    private static Map<String, Object> flatGlobals(String transport) {
        Map<String, Object> globals = new HashMap<>(Map.of("yano.app-chain.enabled", true,
                "yano.app-chain.network-magic", 424242));
        Harness.chain("flat").forEach((key, value) -> globals.put("yano.app-chain." + key, value));
        if (!transport.isEmpty()) globals.put("yano.app-chain.transport.mode", transport);
        return globals;
    }

    private static void assertAssemblyAccepts(Harness harness, int chains) {
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        plugin.assemble();
        try {
            assertThat(plugin.chains).hasSize(chains);
            assertThat(harness.owned.get()).isNotNull();
        } finally {
            harness.owned.get().close();
            plugin.close();
        }
    }

    private static void assertTransportRejectedBeforeFactories(Harness harness, String chainId) {
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThatThrownBy(plugin::assemble).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Standalone app chain '" + chainId
                        + "' requires transport.mode=dedicated (effective: shared)");
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
    }

    private static final class ProbeChain extends AppLedgerRuntime {
        private int starts;
        private int stops;
        private int closes;
        private boolean failStart;

        ProbeChain(AppChainConfig config) {
            super(config, 424242, null, null, "unused-test-runtime", PluginProviderRegistry.empty(),
                    LoggerFactory.getLogger(ProbeChain.class));
        }
        @Override public void start() {
            starts++;
            if (failStart) throw new IllegalStateException("chain start failed");
        }
        @Override public void stop() { stops++; }
        @Override public void close() { closes++; }
    }

    private static final class ProbePlugin extends AppLedgerHostPlugin {
        private final List<ProbeChain> chains = new ArrayList<>();
        private int failConstructionAt = -1;
        private boolean failWiring;
        private boolean requireLaterService;
        private boolean attemptWorkerAssembly;
        private boolean closeFromFactoryWorker;
        private boolean allowCardanoDeployment;
        private long lastMagic;

        @Override
        protected void validateDeployment(YanoConfig node) {
            if (!allowCardanoDeployment) super.validateDeployment(node);
        }

        @Override
        protected AppLedgerRuntime createChain(AppChainConfig config, long magic, String storage,
                YanoConfig node, HostApi host, PluginContext context) {
            lastMagic = magic;
            if (requireLaterService) {
                assertThat(context.getService("later-plugin-ready", Boolean.class)).contains(true);
            }
            if (closeFromFactoryWorker) {
                Thread worker = Thread.ofVirtual().start(this::close);
                try { worker.join(3000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertThat(worker.isAlive()).as("close does not wait on its calling factory").isFalse();
            }
            if (attemptWorkerAssembly) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread worker = Thread.ofVirtual().start(() -> {
                    try { assemble(); }
                    catch (Throwable rejected) { failure.set(rejected); }
                });
                try { worker.join(3000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertThat(worker.isAlive()).as("assembly monitor released before factory callback").isFalse();
                assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("already assembled");
            }
            if (chains.size() == failConstructionAt) throw new IllegalStateException("construction failed");
            ProbeChain chain = new ProbeChain(config);
            chains.add(chain);
            return chain;
        }
        @Override
        protected void wireChain(AppLedgerRuntime chain, YanoConfig node, PluginContext context) {
            if (failWiring) throw new IllegalStateException("wiring failed");
        }
    }

    private static final class Harness implements PluginContext, PluginServiceLookup {
        private final Map<String, Object> services = new HashMap<>();
        private final AtomicReference<AppLedgerManager> owned = new AtomicReference<>();
        private final List<Subsystem> published = new ArrayList<>();
        private YanoConfig node = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).build();
        private Map<String, Object> globals;
        private HostApiVersion version = new HostApiVersion(1, 2);
        private Runnable assembly;
        private BooleanSupplier readiness;
        private PluginProducts products;
        private Long appOnlyNetwork;
        private boolean rejectOwnership;
        private boolean rejectProtocols;
        private boolean rejectReadModelStage;
        private boolean sharedOutboundSessions;
        private boolean initializationComplete;
        private Runnable onProtocolRegistration = () -> { };
        private Runnable onOwnershipTransfer = () -> { };
        private Map<String, Object> legacyConfig = Map.of();

        Harness() {
            globals(transportGlobals("dedicated", "", ""));
            N2NExtensionRegistry n2n = new N2NExtensionRegistry() {
                public boolean hasSharedOutboundSessions() { return sharedOutboundSessions; }
                public void registerInbound(int id, AgentFactory factory) {
                    if (rejectProtocols) throw new IllegalStateException("protocol rejected");
                    onProtocolRegistration.run();
                }
                public void decorateOutbound(UnaryOperator<PeerClientFactory> decorator) {
                    throw new AssertionError("no shared outbound sessions");
                }
                public void requireAppOnlyNetwork(long magic) { appOnlyNetwork = magic; }
            };
            HostLifecycleRegistry lifecycle = new HostLifecycleRegistry() {
                public void registerBeforePublication(Subsystem service) {
                    if (service instanceof AppLedgerManager manager) {
                        if (rejectOwnership) throw new IllegalStateException("ownership rejected");
                        owned.set(manager);
                        published.add(service);
                        onOwnershipTransfer.run();
                        return;
                    }
                    if (rejectReadModelStage) throw new IllegalStateException("read-model stage rejected");
                    published.add(service);
                }
                public void registerAssembly(String owner, Runnable step) {
                    assertThat(owner).isEqualTo(AppLedgerHostPlugin.BUNDLE_ID);
                    assembly = step;
                }
                public void registerRestartReadiness(String owner, BooleanSupplier ready) {
                    assertThat(owner).isEqualTo(AppLedgerHostPlugin.BUNDLE_ID);
                    readiness = ready;
                }
                public PluginOperationsRecorder registerPluginProducts(String owner, PluginProducts value) {
                    assertThat(owner).isEqualTo(AppLedgerHostPlugin.BUNDLE_ID);
                    products = value;
                    return NO_OPERATIONS;
                }
            };
            services.put(HostApi.SERVICE_KEY, new HostApi() {
                public HostApiVersion version() { return version; }
                public N2NExtensionRegistry n2n() { return n2n; }
                public PluginProviderRegistry providers() { return PluginProviderRegistry.empty(); }
                public HostLifecycleRegistry lifecycle() { return lifecycle; }
                public YanoConfig nodeConfig() {
                    if (node == null) throw new IllegalStateException("node configuration unavailable");
                    return node;
                }
                public RuntimeOptions runtimeOptions() { return new RuntimeOptions(null, null, globals); }
                public String networkName() { return "app-424242"; }
                public Map<String, Object> bundleConfig(String bundleId) { return Map.of(); }
            });
        }
        private void globals(Map<String, Object> globals) {
            this.globals = globals;
        }
        public EventBus eventBus() { return null; }
        public Logger logger() { return LoggerFactory.getLogger(AppLedgerHostPluginTest.class); }
        public Map<String, Object> config() { return legacyConfig; }
        public ScheduledExecutorService scheduler() { return null; }
        public Optional<ClassLoader> pluginClassLoader() { return Optional.empty(); }
        public void registerStorageFilter(StorageFilter filter) { throw new UnsupportedOperationException(); }
        public void registerService(String key, Object value) {
            if (initializationComplete) throw new IllegalStateException("service registration outside init");
            services.put(key, value);
        }
        public <T> Optional<T> getService(String key, Class<T> type) {
            Object value = services.get(key);
            return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
        }
        public <T> Optional<T> service(String key, Class<T> type) { return getService(key, type); }
        private static Map<String, Object> chain(String id) {
            Map<String, Object> settings = new HashMap<>(StateCommitmentIdentity.explicit(
                    StateCommitmentProfiles.require("mpf-blake2b256-v1"), new byte[32]).settings());
            settings.putAll(Map.of("chain-id", id,
                    "signing-key", "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                    "members", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"));
            return Map.copyOf(settings);
        }
    }

    private static final PluginOperationsRecorder NO_OPERATIONS = new PluginOperationsRecorder() {
        public void activating(String kind, String bundleId) { }
        public void active(String kind, String bundleId) { }
        public void activationFailed(String kind, String bundleId) { }
        public void stopped(String kind, String bundleId, boolean succeeded) { }
        public void admitted(String bundleId) { }
        public void started(String bundleId) { }
        public void queueFinished(String bundleId) { }
        public void callbackFinished(String bundleId) { }
        public void outcome(String bundleId, PluginOperation operation, PluginOperationOutcome outcome) { }
    };
}
