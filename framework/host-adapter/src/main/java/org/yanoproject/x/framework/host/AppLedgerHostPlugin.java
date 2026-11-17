package org.yanoproject.x.framework.host;

import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.api.plugin.operations.PluginOperationsRecorder;
import org.yanoproject.api.plugin.spi.PluginProducts;
import org.yanoproject.runtime.kernel.Subsystem;
import org.yanoproject.runtime.util.LifecycleFailures;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.AppChainGateways;
import org.yanoproject.x.framework.api.appchain.AppLedgerOperatingProfile;
import org.yanoproject.x.framework.api.plugin.domain.L1TransactionBuilderService;
import org.yanoproject.x.framework.config.AppChainConfigParser;
import org.yanoproject.x.framework.config.AppChainPropertyKeys;
import org.yanoproject.x.framework.config.AppChainStoragePaths;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerManager;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Registers its assembly step and its stable {@link AppLedgers} holder during plugin initialization. The host
 * runs the assembly after every plugin has initialized, before sealing N2N/lifecycle registration. The host's
 * pre-publication lifecycle owns the resulting chain runtime; this plugin owns the domain-API and read-model
 * registries it creates and closes them from {@link #close()}. Deployment roots select either the standalone
 * subclass or the optional Cardano specialization.
 */
public class AppLedgerHostPlugin implements NodePlugin {
    public static final String BUNDLE_ID = "org.yanoproject.x.app-ledger";
    private static final Duration RESTART_DRAIN = Duration.ofSeconds(3);
    private final AppLedgerHolder holder = new AppLedgerHolder();
    private final List<AutoCloseable> registries = new ArrayList<>();
    private boolean initialized;
    private boolean assemblyAttempted;
    private boolean closed;
    private PluginContext assemblyContext;
    private HostApi assemblyHost;

    @Override public final String id() { return BUNDLE_ID; }

    @Override
    public final String version() {
        Properties properties = new Properties();
        try (InputStream input = AppLedgerHostPlugin.class.getResourceAsStream(
                "/META-INF/yano/app-ledger-version.properties")) {
            if (input == null) throw new IllegalStateException("Missing application-ledger version resource");
            properties.load(input);
            return Objects.requireNonNull(properties.getProperty("version"), "version");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read application-ledger version", failure);
        }
    }

    @Override
    public final synchronized void init(PluginContext context) {
        if (closed) throw new IllegalStateException("Application-ledger plugin is closed");
        if (initialized) throw new IllegalStateException("Application-ledger plugin already initialized");
        initialized = true;
        HostApi host = context.getService(HostApi.SERVICE_KEY, HostApi.class).orElse(null);
        if (host == null) {
            // The public legacy PluginManager may discover this provider on an
            // ordinary classpath without assembling an application-ledger host.
            // Disabled features need no capabilities; an explicit request must
            // still fail rather than silently ignoring a missing host contract.
            if (!Boolean.parseBoolean(String.valueOf(context.config().get(AppChainPropertyKeys.ENABLED)))) return;
            throw new IllegalStateException("Application-ledger host service is unavailable: " + HostApi.SERVICE_KEY);
        }
        host.version().requireCompatible(1, 2);
        assemblyContext = context;
        assemblyHost = host;
        context.registerService(AppLedgers.SERVICE_KEY, holder);
        host.lifecycle().registerAssembly(BUNDLE_ID, this::assemble);
    }

    /**
     * Assemble exactly once, as the host's assembly step. With app chains disabled the domain-API and
     * read-model registries are still built over empty gateways. No plugin services are registered here:
     * init-scoped registration is already closed; the holder registered during init is populated instead.
     */
    final void assemble() {
        PluginContext context;
        HostApi host;
        synchronized (this) {
            if (!initialized) throw new IllegalStateException("Application-ledger plugin is not initialized");
            if (closed) throw new IllegalStateException("Application-ledger plugin is closed");
            if (assemblyAttempted) throw new IllegalStateException("Application-ledger runtime already assembled");
            assemblyAttempted = true;
            context = assemblyContext;
            host = assemblyHost;
        }
        // Provider factories can invoke arbitrary callbacks. Do not retain the
        // assembly monitor while constructing or transferring runtime ownership.
        YanoConfig node = host.nodeConfig();
        Map<String, Object> globals = host.runtimeOptions().globals();
        HostLifecycleRegistry lifecycle = host.lifecycle();
        validateDeployment(node);
        boolean standalone = node.getMode() == RuntimeMode.APP_LEDGER;
        boolean enabled = Boolean.parseBoolean(String.valueOf(globals.get(AppChainPropertyKeys.ENABLED)));
        if (standalone && !enabled) {
            throw new IllegalArgumentException("yano.mode=app-ledger requires yano.app-chain.enabled=true");
        }
        List<AppChainConfig> configurations = enabled
                ? AppChainConfigParser.parseChains(globals, standalone) : List.of();
        if (standalone) {
            for (AppChainConfig chain : configurations) {
                AppLedgerOperatingProfile.STANDALONE.validate(chain);
                String transport = stringOf(chain.pluginSettings().get("transport.mode"),
                        stringOf(globals.get(AppChainPropertyKeys.TRANSPORT_MODE), "shared"));
                if (!"dedicated".equalsIgnoreCase(transport)) {
                    throw new IllegalArgumentException("Standalone app chain '" + chain.chainId()
                            + "' requires transport.mode=dedicated (effective: " + transport + ")");
                }
            }
        }
        long magic = standalone
                ? standaloneNetworkMagic(globals.get(AppChainPropertyKeys.NETWORK_MAGIC))
                : node.getProtocolMagic();
        String storage = enabled ? storageRoot(node, globals.get(AppChainPropertyKeys.STORAGE_PATH)) : null;
        if (standalone) host.n2n().requireAppOnlyNetwork(magic);
        if (enabled) context.logger().info("App-chain storage root: {}", storage);

        List<AppLedgerRuntime> chains = new ArrayList<>();
        List<AutoCloseable> created = new ArrayList<>();
        AppLedgerManager manager = null;
        boolean transferred = false;
        try {
            if (enabled) {
                for (AppChainConfig configuration : configurations) {
                    requireOpen();
                    AppLedgerRuntime chain = createChain(configuration, magic, storage, node, host, context);
                    chains.add(Objects.requireNonNull(chain, "application-ledger runtime"));
                    requireOpen();
                    wireChain(chain, node, context);
                    requireOpen();
                }
                manager = createManager(chains, node, context);
                requireOpen();
                manager.serverAgentRegistrations().forEach((protocol, factory) -> {
                    requireOpen();
                    host.n2n().registerInbound(protocol, factory);
                    requireOpen();
                });
                if (!standalone && host.n2n().hasSharedOutboundSessions() && node.isClientEnabled()) {
                    requireOpen();
                    AppLedgerManager sharedManager = manager;
                    String transport = stringOf(globals.get(AppChainPropertyKeys.TRANSPORT_MODE), "shared");
                    host.n2n().decorateOutbound(delegate -> sharedManager.wrapPeerClientFactory(
                            delegate, transport, node.getRemoteHost(), node.getRemotePort()));
                }
                requireOpen();
                lifecycle.registerBeforePublication(manager);
                transferred = true;
                // A registry callback may close this adapter while accepting the
                // manager. Report cancellation, but leave accepted ownership with
                // the host: its construction rollback closes the manager once.
                requireOpen();
                AppLedgerManager restartable = manager;
                lifecycle.registerRestartReadiness(BUNDLE_ID, () -> restartable.awaitStopped(RESTART_DRAIN));
            }
            AppChainGateways gateways = manager != null ? manager : AppChainGateways.empty();
            LocalReadModelRegistry readModels = new LocalReadModelRegistry();
            created.add(readModels);
            LocalReadModelContributionRegistry contributions =
                    new LocalReadModelContributionRegistry(host, gateways, readModels);
            created.add(contributions);
            Products products = new Products(contributions);
            PluginOperationsRecorder operations = lifecycle.registerPluginProducts(BUNDLE_ID, products);
            DomainApiRegistry domainApis = new DomainApiRegistry(host, gateways, context.logger(), readModels,
                    l1Transactions(node, context), operations);
            created.add(domainApis);
            products.domainApis = domainApis;
            // Derived read models start after every app-chain gateway is live; with app chains disabled
            // there is no gateway to follow, so no stage is registered.
            if (manager != null) {
                lifecycle.registerBeforePublication(new Subsystem() {
                    @Override public String name() { return "local-read-models"; }
                    @Override public void start() { contributions.resume(); }
                    @Override public void stop() { contributions.sealAndAwait(); }
                });
            }
            synchronized (this) {
                requireOpen();
                registries.addAll(created);
                holder.publish(new AppLedgerHolder.Assembled(gateways, domainApis, readModels));
            }
        } catch (Throwable failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                failure = close(created.get(index), failure);
            }
            if (!transferred) {
                if (manager != null) failure = close(manager, failure);
                else for (int index = chains.size() - 1; index >= 0; index--) {
                    failure = close(chains.get(index), failure);
                }
            }
            throw propagate(failure);
        }
    }

    protected void validateDeployment(YanoConfig node) {
        if (node.getMode() != RuntimeMode.APP_LEDGER) {
            throw new IllegalArgumentException("Standalone application-ledger plugin requires yano.mode=app-ledger");
        }
    }

    protected AppLedgerRuntime createChain(AppChainConfig chain, long magic, String storage,
            YanoConfig node, HostApi host, PluginContext context) {
        return new AppLedgerRuntime(chain, magic, context.eventBus(), null, storage,
                host.providers(), context.logger());
    }

    protected void wireChain(AppLedgerRuntime chain, YanoConfig node, PluginContext context) { }

    protected AppLedgerManager createManager(List<AppLedgerRuntime> chains, YanoConfig node,
            PluginContext context) {
        return new AppLedgerManager(chains, context.logger());
    }

    /** Unsigned L1 transaction construction offered to domain APIs; unavailable without Cardano L1. */
    protected L1TransactionBuilderService l1Transactions(YanoConfig node, PluginContext context) {
        return L1TransactionBuilderService.unavailable();
    }

    private static long standaloneNetworkMagic(Object value) {
        long magic;
        try {
            magic = Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "Standalone requires yano.app-chain.network-magic (unsigned 32-bit)");
        }
        if (magic <= 0 || magic > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                    "yano.app-chain.network-magic must be between 1 and 4294967295");
        }
        return magic;
    }

    private static String storageRoot(YanoConfig node, Object configured) {
        String path = configured == null ? null : String.valueOf(configured);
        if (node.getMode() != RuntimeMode.APP_LEDGER) {
            return AppChainStoragePaths.resolve(node.getRocksDBPath(), path).toString();
        }
        if (path == null) path = AppChainStoragePaths.DEFAULT_STORAGE_PATH;
        if (path.isBlank()) throw new IllegalArgumentException("yano.app-chain.storage.path is required");
        Path appPath = Path.of(path).toAbsolutePath().normalize();
        Path l1Path = Path.of(node.getRocksDBPath() == null ? "chainstate" : node.getRocksDBPath())
                .toAbsolutePath().normalize();
        if (appPath.equals(l1Path)) {
            throw new IllegalArgumentException("App storage must differ from yano.storage.path");
        }
        return path;
    }

    private static String stringOf(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    protected static <T> T requireService(PluginContext context, String key, Class<T> type) {
        return context.getService(key, type).orElseThrow(() ->
                new IllegalStateException("Application-ledger host service is unavailable: " + key));
    }

    private synchronized void requireOpen() {
        if (closed) throw new IllegalStateException("Application-ledger plugin is closed");
    }

    private static Throwable close(AutoCloseable resource, Throwable failure) {
        try { resource.close(); }
        catch (Throwable cleanup) { failure = LifecycleFailures.merge(failure, cleanup); }
        return failure;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return failure instanceof RuntimeException runtime ? runtime
                : new IllegalStateException("Application-ledger initialization failed", failure);
    }

    // The chain runtime is owned by the host lifecycle; the registries by close().
    @Override public final void start() { }
    @Override public final void stop() { }

    /** Closes the registries this plugin created, in reverse creation order. Repeated close is a no-op. */
    @Override
    public final void close() {
        List<AutoCloseable> owned;
        synchronized (this) {
            if (closed) return;
            closed = true;
            holder.clear();
            owned = List.copyOf(registries);
            registries.clear();
        }
        Throwable failure = null;
        for (int index = owned.size() - 1; index >= 0; index--) {
            failure = close(owned.get(index), failure);
        }
        if (failure != null) throw propagate(failure);
    }

    /** Host positions of the domain-API products: domain seal first, then the read models. */
    private static final class Products implements PluginProducts {
        private final LocalReadModelContributionRegistry readModels;
        private volatile DomainApiRegistry domainApis;

        private Products(LocalReadModelContributionRegistry readModels) {
            this.readModels = readModels;
        }

        @Override
        public void resume() {
            domainApis.resume();
        }

        /** Seals the read models even when the domain seal fails; both failures are reported. */
        @Override
        public void sealAndAwait() {
            Throwable failure = null;
            DomainApiRegistry domain = domainApis;
            try {
                if (domain != null) domain.sealAndAwait();
            } catch (Throwable sealFailure) {
                failure = sealFailure;
            }
            try {
                readModels.sealAndAwait();
            } catch (Throwable sealFailure) {
                failure = LifecycleFailures.merge(failure, sealFailure);
            }
            if (failure != null) throw propagate(failure);
        }
    }
}
