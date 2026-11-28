package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.yanoproject.api.CardanoHostServices;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.EpochSnapshotReader;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostApiVersion;
import org.yanoproject.api.host.N2NExtensionRegistry;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochStateProvider;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.framework.cardano.AppChainManager;
import org.yanoproject.x.framework.cardano.AppChainSubsystem;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;
import org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory;
import org.yanoproject.x.framework.api.plugin.domain.L1TransactionBuilderService;
import org.yanoproject.x.framework.config.AppChainConfigParser;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerRuntime;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class CardanoAppLedgerPluginTest {
    @TempDir Path directory;

    @Test
    void pricingParametersCarryTheReferenceScriptPriceOfTheSnapshot() {
        // The host's cardano-client-lib pricing parameters leave minFeeRefScriptCostPerByte unset; a gateway Accept
        // priced from them paid no reference-script fee and failed as FeeTooSmallUTxO on the ADR-059 M4 devnet.
        ProtocolParams host = new ProtocolParams();
        host.setMinFeeA(44);
        host.setMinFeeB(155_381);
        Supplier<ProtocolParams> pricing = CardanoAppLedgerPlugin.withReferenceScriptCost(() -> host,
                () -> BigDecimal.valueOf(15));

        ProtocolParams priced = pricing.get();
        assertThat(priced.getMinFeeRefScriptCostPerByte()).isEqualByComparingTo("15");
        assertThat(priced.getMinFeeA()).isEqualTo(44);
        assertThat(priced.getMinFeeB()).isEqualTo(155_381);
        assertThat(host.getMinFeeRefScriptCostPerByte()).as("the host's value is not mutated").isNull();

        host.setMinFeeRefScriptCostPerByte(BigDecimal.valueOf(20));
        assertThat(pricing.get()).as("a price the host supplies is kept").isSameAs(host);
        assertThat(CardanoAppLedgerPlugin.withReferenceScriptCost(() -> null, () -> BigDecimal.ONE).get()).isNull();
        ProtocolParams unpriced = new ProtocolParams();
        assertThat(CardanoAppLedgerPlugin.withReferenceScriptCost(() -> unpriced, () -> null).get())
                .isSameAs(unpriced);
        assertThat(CardanoAppLedgerPlugin.withReferenceScriptCost(() -> unpriced, () -> {
            throw new IllegalStateException("no snapshot");
        }).get()).isSameAs(unpriced);
    }

    @Test
    void theL1BuilderReportsTheHostsCurrentPricingParametersToDomainApis() {
        ProtocolParams host = new ProtocolParams();
        host.setMinFeeA(47);
        host.setMinFeeB(160_000);
        host.setCoinsPerUtxoSize("4310");
        host.setMaxTxSize(16_384);
        host.setMaxValSize("5000");
        AtomicReference<ProtocolParams> current = new AtomicReference<>(host);
        L1TransactionBuilderService service = new DefaultL1TransactionBuilderService(() -> null, current::get,
                () -> 10);
        assertThat(service.pricingParameters()).isEqualTo(
                new L1TransactionBuilderService.PricingParameters(47, 160_000, 4_310, 16_384, 5_000));
        current.set(null);
        assertThatThrownBy(service::pricingParameters).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> L1TransactionBuilderService.unavailable().pricingParameters())
                .isInstanceOf(IllegalStateException.class);
    }

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
            // ADR-059 D8b, D9: no block is finalized, so no proof is served.
            assertThat(createdContext.get().stateProofAtHeight(1, new byte[] {1})).isEmpty();
        } finally {
            manager.close();
        }
        assertThat(closed).hasValue(1);
    }

    @Test
    void cardanoModeWiresThePresentEpochSnapshotReaderWhenTheManagerInitializes() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        EpochSnapshotReader reader = proxy(EpochSnapshotReader.class, (method, arguments) ->
                method.getName().equals("epochAtSlot") ? (Long) arguments[0] / 10 : null);
        PluginContext context = cardanoContext(services(() -> {
            lookups.incrementAndGet();
            return Optional.of(reader);
        }, 0));
        CardanoAppLedgerPlugin plugin = new CardanoAppLedgerPlugin();
        var chain = plugin.createChain(cardanoChain(), 42, directory.toString(), CARDANO, host(), context);
        var manager = plugin.createManager(List.of(chain), CARDANO, context);
        try {
            assertThat(lookups).as("resolved at kernel initialization, not construction").hasValue(0);
            manager.init(null);
            assertThat(lookups).hasValue(1);
            assertThat(epochState(chain)).isInstanceOf(CardanoEpochStateProvider.class);
            assertThat(epochState(chain).epochAtSlot(370)).isEqualTo(37);
        } finally {
            manager.close();
        }
    }

    @Test
    void cardanoModeLeavesEpochObservationUnavailableWhenTheReaderIsEmpty() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        PluginContext context = cardanoContext(services(() -> {
            lookups.incrementAndGet();
            return Optional.empty();
        }, 0));
        CardanoAppLedgerPlugin plugin = new CardanoAppLedgerPlugin();
        var chain = plugin.createChain(cardanoChain(), 42, directory.toString(), CARDANO, host(), context);
        var manager = plugin.createManager(List.of(chain), CARDANO, context);
        try {
            manager.init(null);
            assertThat(lookups).hasValue(1);
            assertThat(epochState(chain)).isNull();
        } finally {
            manager.close();
        }
    }

    @Test
    void l1TransactionsAreBuiltFromCardanoServicesOnlyInCardanoMode() {
        PluginContext context = cardanoContext(services(Optional::empty, 77));
        CardanoAppLedgerPlugin plugin = new CardanoAppLedgerPlugin();

        L1TransactionBuilderService cardano = plugin.l1Transactions(CARDANO, context);
        assertThat(cardano).isInstanceOf(DefaultL1TransactionBuilderService.class);
        assertThat(cardano.tipSlot()).isEqualTo(77);

        L1TransactionBuilderService standalone = plugin.l1Transactions(
                YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).build(), context);
        assertThat(standalone).isNotInstanceOf(DefaultL1TransactionBuilderService.class);
        assertThatThrownBy(standalone::tipSlot).isInstanceOf(IllegalStateException.class)
                .hasMessage("L1 transaction-building service is unavailable");
    }

    private static final YanoConfig CARDANO = YanoConfig.builder().mode(RuntimeMode.CARDANO).build();

    private static AppChainConfig cardanoChain() {
        byte[] seed = new byte[32];
        seed[0] = 9;
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        Map<String, Object> settings = new LinkedHashMap<>(StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.require("mpf-blake2b256-v1"), new byte[32]).settings());
        settings.put("chain-id", "cardano-mode");
        settings.put("signing-key", HexFormat.of().formatHex(seed));
        settings.put("members", member);
        return AppChainConfigParser.parseChains(Map.of("yano.app-chain.chains", List.of(settings)), false)
                .getFirst();
    }

    private static CardanoHostServices services(Supplier<Optional<EpochSnapshotReader>> epochSnapshots,
                                                long tipSlot) {
        ChainBlockReader chain = proxy(ChainBlockReader.class, (method, arguments) ->
                method.getName().equals("getLocalTip") ? new ChainTip(tipSlot, new byte[32], 1) : null);
        return new CardanoHostServices(tx -> "", () -> null, chain, retention -> null,
                proxy(TxEvaluationGateway.class, (method, arguments) -> null),
                () -> null, () -> null, epochSnapshots);
    }

    private static PluginContext cardanoContext(CardanoHostServices services) {
        return proxy(PluginContext.class, (method, arguments) -> switch (method.getName()) {
            case "logger" -> LoggerFactory.getLogger(CardanoAppLedgerPluginTest.class);
            case "pluginClassLoader" -> Optional.empty();
            case "eventBus" -> null;
            case "getService" -> CardanoHostServices.SERVICE_KEY.equals(arguments[0])
                    ? Optional.of(services) : Optional.empty();
            default -> throw new AssertionError("Unexpected context call: " + method.getName());
        });
    }

    private static HostApi host() {
        return new HostApi() {
            public HostApiVersion version() { return new HostApiVersion(1, 2); }
            public N2NExtensionRegistry n2n() { throw new UnsupportedOperationException(); }
            public PluginProviderRegistry providers() { return PluginProviderRegistry.empty(); }
        };
    }

    /** The epoch-state provider the manager wired into the chain (no public accessor exists). */
    private static L1EpochStateProvider epochState(AppLedgerRuntime chain) throws Exception {
        Field field = AppChainSubsystem.class.getDeclaredField("epochStateProvider");
        field.setAccessible(true);
        return (L1EpochStateProvider) field.get(chain);
    }

    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> type.getSimpleName() + " stub";
                    default -> answer.apply(method, arguments);
                }));
    }
}
