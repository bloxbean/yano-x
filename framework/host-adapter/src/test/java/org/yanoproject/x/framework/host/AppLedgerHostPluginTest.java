package org.yanoproject.x.framework.host;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.StorageFilter;
import org.yanoproject.p2p.peer.PeerClientFactory;
import org.yanoproject.runtime.appchain.AppLedgerManager;
import org.yanoproject.runtime.appchain.AppLedgerRuntime;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
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
        assertThat(harness.services.get(AppLedgerHostPlugin.ASSEMBLY_SERVICE)).isSameAs(plugin);
        harness.initializationComplete = true;
        AppLedgerManager manager = plugin.assemble();
        assertThat(harness.owned.get()).isSameAs(manager);
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
    void disabledApplicationLedgerDoesNotRequireHostCapabilities() {
        Harness harness = new Harness();
        harness.services.remove(HostApi.SERVICE_KEY);
        harness.services.remove(AppLedgerHostPlugin.NODE_CONFIG_SERVICE);
        harness.services.put(AppLedgerHostPlugin.RUNTIME_OPTIONS_SERVICE,
                new RuntimeOptions(null, null, Map.of("yano.app-chain.enabled", false)));
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThat(plugin.chains).isEmpty();
        assertThat(harness.owned.get()).isNull();
        assertThat(plugin.assemble()).isNull();
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
    }

    @Test
    void explicitLegacyActivationRejectsMissingRuntimeOptions() {
        Harness harness = new Harness();
        harness.services.clear();
        harness.legacyConfig = Map.of("yano.app-chain.enabled", true);
        ProbePlugin plugin = new ProbePlugin();
        assertThatThrownBy(() -> plugin.init(harness)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AppLedgerHostPlugin.RUNTIME_OPTIONS_SERVICE);
        assertThat(plugin.chains).isEmpty();
    }

    @Test
    void disabledRuntimeOptionsTakePrecedenceOverLegacyCompatibilityConfig() {
        Harness harness = new Harness();
        harness.services.clear();
        harness.services.put(AppLedgerHostPlugin.RUNTIME_OPTIONS_SERVICE,
                new RuntimeOptions(null, null, Map.of("yano.app-chain.enabled", false)));
        harness.legacyConfig = Map.of("yano.app-chain.enabled", true);
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThat(plugin.chains).isEmpty();
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
        AppLedgerManager manager = plugin.assemble();
        try {
            assertThat(plugin.chains).hasSize(2);
            assertThatThrownBy(plugin::assemble).hasMessageContaining("already assembled");
        } finally {
            manager.close();
        }
    }

    @Test
    void configurationValidationIsDeferredUntilAssembly() {
        Harness harness = new Harness();
        harness.services.remove(AppLedgerHostPlugin.NODE_CONFIG_SERVICE);
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        assertThat(plugin.chains).isEmpty();
        assertThatThrownBy(plugin::assemble).hasMessageContaining(AppLedgerHostPlugin.NODE_CONFIG_SERVICE);
    }

    @Test
    void factoryWorkerCanRejectConcurrentAssemblyWithoutWaitingOnFactoryCompletion() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.attemptWorkerAssembly = true;
        plugin.init(harness);
        AppLedgerManager manager = plugin.assemble();
        try {
            assertThat(plugin.chains).hasSize(2);
        } finally {
            manager.close();
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
            harness.services.put(AppLedgerHostPlugin.NODE_CONFIG_SERVICE,
                    YanoConfig.builder().mode(RuntimeMode.CARDANO).rocksDBPath("unused-cardano")
                            .appChainStoragePath("appchain-test").build());
            harness.globals(transportGlobals(global, "shared", ""));
            ProbePlugin plugin = new ProbePlugin();
            plugin.allowCardanoDeployment = true;
            plugin.failConstructionAt = 0;
            plugin.init(harness);
            // Reach the optional runtime factory without imposing standalone
            // transport rules; this descriptor-free adapter test stays SDK-free.
            assertThatThrownBy(plugin::assemble).hasMessage("construction failed");
        }
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
        AppLedgerManager manager = plugin.assemble();
        try {
            assertThat(plugin.chains).hasSize(chains);
            assertThat(harness.owned.get()).isSameAs(manager);
        } finally {
            manager.close();
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

        @Override
        protected void validateDeployment(YanoConfig node) {
            if (!allowCardanoDeployment) super.validateDeployment(node);
        }

        @Override
        protected AppLedgerRuntime createChain(AppChainConfig config, long magic, String storage,
                YanoConfig node, HostApi host, PluginContext context) {
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

    private static final class Harness implements PluginContext {
        private final Map<String, Object> services = new HashMap<>();
        private final AtomicReference<Subsystem> owned = new AtomicReference<>();
        private boolean rejectOwnership;
        private boolean rejectProtocols;
        private boolean initializationComplete;
        private Runnable onProtocolRegistration = () -> { };
        private Runnable onOwnershipTransfer = () -> { };
        private Map<String, Object> legacyConfig = Map.of();

        Harness() {
            YanoConfig node = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER)
                    .appChainStoragePath("appchain-test").build();
            services.put(AppLedgerHostPlugin.NODE_CONFIG_SERVICE, node);
            globals(transportGlobals("dedicated", "", ""));
            N2NExtensionRegistry n2n = new N2NExtensionRegistry() {
                public boolean hasSharedOutboundSessions() { return false; }
                public void registerInbound(int id, AgentFactory factory) {
                    if (rejectProtocols) throw new IllegalStateException("protocol rejected");
                    onProtocolRegistration.run();
                }
                public void decorateOutbound(UnaryOperator<PeerClientFactory> decorator) {
                    throw new AssertionError("no shared outbound sessions");
                }
            };
            services.put(HostApi.SERVICE_KEY, new HostApi() {
                public HostApiVersion version() { return new HostApiVersion(1, 1); }
                public N2NExtensionRegistry n2n() { return n2n; }
                public PluginProviderRegistry providers() { return PluginProviderRegistry.empty(); }
                public HostLifecycleRegistry lifecycle() {
                    return service -> {
                        if (rejectOwnership) throw new IllegalStateException("ownership rejected");
                        owned.set(service);
                        onOwnershipTransfer.run();
                    };
                }
            });
        }
        private void globals(Map<String, Object> globals) {
            services.put(AppLedgerHostPlugin.RUNTIME_OPTIONS_SERVICE, new RuntimeOptions(null, null, globals));
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
        private static Map<String, Object> chain(String id) {
            Map<String, Object> settings = new HashMap<>(StateCommitmentIdentity.explicit(
                    StateCommitmentProfiles.require("mpf-blake2b256-v1"), new byte[32]).settings());
            settings.putAll(Map.of("chain-id", id,
                    "signing-key", "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                    "members", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"));
            return Map.copyOf(settings);
        }
    }
}
