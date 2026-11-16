package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.framework.cardano.AppChainManager;
import org.yanoproject.x.framework.cardano.AppChainSubsystem;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;
import org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory;
import org.yanoproject.x.framework.config.AppChainConfigParser;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class CardanoAppLedgerPluginTest {
    @TempDir Path directory;

    @Test
    void integratedStandaloneRetainsLegacyEffectFactoryWithoutRequestingL1Capabilities() {
        AtomicReference<AppChainEffectContext> createdContext = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        AppEffectExecutorFactory factory = new AppEffectExecutorFactory() {
            public String scheme() { return "legacy"; }
            public List<AppEffectExecutor> create(String chainId, Map<String, String> settings) {
                throw new AssertionError("context-aware factory must be used");
            }
            public List<AppEffectExecutor> create(String chainId, Map<String, String> settings,
                    AppChainEffectContext context) {
                createdContext.set(context);
                return List.of(new AppEffectExecutor() {
                    public String id() { return "legacy"; }
                    public boolean supports(String type) { return "legacy.test".equals(type); }
                    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
                        return new EffectExecution.Confirmed(new byte[0], null);
                    }
                    public void close() { closed.incrementAndGet(); }
                });
            }
        };
        PluginProviderRegistry providers = new PluginProviderRegistry() {
            public <P> Optional<P> find(Class<P> type, String selector) {
                return type == AppEffectExecutorFactory.class && selector.equals("legacy")
                        ? Optional.of(type.cast(factory)) : Optional.empty();
            }
            public <P> List<String> names(Class<P> type) {
                return type == AppEffectExecutorFactory.class ? List.of("legacy") : List.of();
            }
        };
        HostApi host = new HostApi() {
            public HostApiVersion version() { return new HostApiVersion(1, 2); }
            public N2NExtensionRegistry n2n() { throw new UnsupportedOperationException(); }
            public PluginProviderRegistry providers() { return providers; }
        };
        AtomicInteger serviceLookups = new AtomicInteger();
        PluginContext context = (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(), new Class<?>[]{PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "logger" -> LoggerFactory.getLogger(CardanoAppLedgerPluginTest.class);
                    case "pluginClassLoader" -> Optional.empty();
                    case "eventBus" -> null;
                    case "getService" -> { serviceLookups.incrementAndGet(); yield Optional.empty(); }
                    default -> throw new AssertionError("Unexpected context call: " + method.getName());
                });
        YanoConfig node = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).build();
        byte[] seed = new byte[32];
        seed[0] = 7;
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.putAll(StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.require("mpf-blake2b256-v1"), new byte[32]).settings());
        settings.put("chain-id", "compat");
        settings.put("signing-key", HexFormat.of().formatHex(seed));
        settings.put("members", member);
        settings.put("sequencer.proposer", member);
        settings.put("block.interval-ms", 60000);
        settings.put("consensus.operating-profile", "standalone-v1");
        settings.put("effects.enabled", true);
        settings.put("effects.executor.enabled", true);
        settings.put("effects.executor.identity", "compat");
        settings.put("effects.executors.legacy.enabled", true);
        CardanoAppLedgerPlugin plugin = new CardanoAppLedgerPlugin();
        var parsed = AppChainConfigParser.parseChains(
                Map.of("yano.app-chain.chains", List.of(settings)), true).getFirst();
        var chain = plugin.createChain(parsed, 424242,
                directory.toString(), node, host, context);
        var manager = plugin.createManager(List.of(chain), node, context);
        try {
            assertThat(chain).isInstanceOf(AppChainSubsystem.class);
            assertThat(manager).isInstanceOf(AppChainManager.class);
            plugin.wireChain(chain, node, context);
            assertThat(serviceLookups).hasValue(0);
            manager.start();
            assertThat(createdContext.get()).isNotNull();
            assertThat(createdContext.get().l1UtxoView().get()).isNull();
            assertThat(createdContext.get().protocolParams().get()).isNull();
            assertThatThrownBy(() -> createdContext.get().submitTx(new byte[0]))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not wired");
        } finally {
            manager.close();
        }
        assertThat(closed).hasValue(1);
    }
}
