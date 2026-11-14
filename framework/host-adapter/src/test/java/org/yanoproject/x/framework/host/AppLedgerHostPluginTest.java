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
        assertThatThrownBy(() -> plugin.init(harness)).hasMessage("construction failed");
        assertThat(plugin.chains.getFirst().closes).isEqualTo(1);
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void wiringFailureClosesTheJustConstructedChain() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.failWiring = true;
        assertThatThrownBy(() -> plugin.init(harness)).hasMessage("wiring failed");
        assertThat(plugin.chains.getFirst().closes).isEqualTo(1);
    }

    @Test
    void rejectedOwnershipClosesTheManagerAndEveryChain() {
        Harness harness = new Harness();
        harness.rejectOwnership = true;
        ProbePlugin plugin = new ProbePlugin();
        assertThatThrownBy(() -> plugin.init(harness)).hasMessage("ownership rejected");
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void protocolRegistrationFailureClosesAllConstructedChains() {
        Harness harness = new Harness();
        harness.rejectProtocols = true;
        ProbePlugin plugin = new ProbePlugin();
        assertThatThrownBy(() -> plugin.init(harness)).hasMessage("protocol rejected");
        plugin.chains.forEach(chain -> assertThat(chain.closes).isEqualTo(1));
        assertThat(harness.owned.get()).isNull();
    }

    @Test
    void hostOwnsStartFailureRollbackAndCloseAfterSuccessfulTransfer() {
        Harness harness = new Harness();
        ProbePlugin plugin = new ProbePlugin();
        plugin.init(harness);
        AppLedgerManager manager = (AppLedgerManager) harness.owned.get();
        assertThat(harness.services.get(AppLedgerHostPlugin.GATEWAYS_SERVICE)).isSameAs(manager);
        assertThat(harness.services.get(AppLedgerHostPlugin.MANAGER_SERVICE)).isSameAs(manager);
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

        @Override
        protected AppLedgerRuntime createChain(AppChainConfig config, long magic, String storage,
                YanoConfig node, HostApi host, PluginContext context) {
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
        private Map<String, Object> legacyConfig = Map.of();

        Harness() {
            YanoConfig node = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER)
                    .appChainStoragePath("appchain-test").build();
            services.put(AppLedgerHostPlugin.NODE_CONFIG_SERVICE, node);
            services.put(AppLedgerHostPlugin.RUNTIME_OPTIONS_SERVICE, new RuntimeOptions(null, null,
                    Map.of("yano.app-chain.enabled", true, "yano.app-chain.network-magic", 424242,
                            "yano.app-chain.chains", List.of(chain("first"), chain("second")))));
            N2NExtensionRegistry n2n = new N2NExtensionRegistry() {
                public boolean hasSharedOutboundSessions() { return false; }
                public void registerInbound(int id, AgentFactory factory) {
                    if (rejectProtocols) throw new IllegalStateException("protocol rejected");
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
                    };
                }
            });
        }
        public EventBus eventBus() { return null; }
        public Logger logger() { return LoggerFactory.getLogger(AppLedgerHostPluginTest.class); }
        public Map<String, Object> config() { return legacyConfig; }
        public ScheduledExecutorService scheduler() { return null; }
        public Optional<ClassLoader> pluginClassLoader() { return Optional.empty(); }
        public void registerStorageFilter(StorageFilter filter) { throw new UnsupportedOperationException(); }
        public void registerService(String key, Object value) { services.put(key, value); }
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
