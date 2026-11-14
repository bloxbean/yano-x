package org.yanoproject.x.framework.host;

import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppLedgerOperatingProfile;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.host.HostLifecycleRegistry;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.appchain.config.AppChainConfigParser;
import org.yanoproject.runtime.appchain.AppLedgerManager;
import org.yanoproject.runtime.appchain.AppLedgerRuntime;
import org.yanoproject.runtime.util.LifecycleFailures;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Registers its assembly entry point during catalog initialization. The host
 * calls it after every plugin has initialized, before sealing N2N/lifecycle
 * registration. The existing pre-publication lifecycle owns the resulting runtime.
 * This library deliberately has no discovery descriptor; deployment roots select
 * either its standalone subclass or the optional Cardano specialization.
 */
public class AppLedgerHostPlugin implements NodePlugin {
    public static final String BUNDLE_ID = "org.yanoproject.x.app-ledger";
    public static final String NODE_CONFIG_SERVICE = "org.yanoproject.app-ledger.node-config";
    public static final String RUNTIME_OPTIONS_SERVICE = "org.yanoproject.app-ledger.runtime-options";
    public static final String ASSEMBLY_SERVICE = "org.yanoproject.app-ledger.assembly";
    private boolean initialized;
    private boolean assemblyAttempted;
    private boolean closed;
    private PluginContext assemblyContext;
    private RuntimeOptions assemblyOptions;

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
        RuntimeOptions options = context.getService(RUNTIME_OPTIONS_SERVICE, RuntimeOptions.class).orElse(null);
        if (options == null) {
            // The public legacy PluginManager may discover this provider on an
            // ordinary classpath without assembling an application-ledger host.
            // Disabled features need no capabilities; an explicit request must
            // still fail rather than silently ignoring a missing host contract.
            if (!Boolean.parseBoolean(String.valueOf(context.config().get("yano.app-chain.enabled")))) return;
            options = requireService(context, RUNTIME_OPTIONS_SERVICE, RuntimeOptions.class);
        }
        Map<String, Object> globals = options.globals();
        if (!Boolean.parseBoolean(String.valueOf(globals.get("yano.app-chain.enabled")))) return;

        assemblyContext = context;
        assemblyOptions = options;
        context.registerService(ASSEMBLY_SERVICE, this);
    }

    /**
     * Assemble exactly once after the host has successfully completed all
     * NodePlugin initialization callbacks. No plugin services are registered
     * here: init-scoped registration is already closed. The returned manager
     * also implements AppChainGateways for the host's existing public facade.
     * A disabled provider returns null and allocates no runtime resources.
     */
    public final AppLedgerManager assemble() {
        PluginContext context;
        RuntimeOptions options;
        synchronized (this) {
            if (!initialized) throw new IllegalStateException("Application-ledger plugin is not initialized");
            if (closed) throw new IllegalStateException("Application-ledger plugin is closed");
            if (assemblyAttempted) throw new IllegalStateException("Application-ledger runtime already assembled");
            assemblyAttempted = true;
            context = assemblyContext;
            options = assemblyOptions;
        }
        if (context == null) return null;
        // Provider factories can invoke arbitrary callbacks. Do not retain the
        // assembly monitor while constructing or transferring runtime ownership.
        Map<String, Object> globals = options.globals();
        YanoConfig node = requireService(context, NODE_CONFIG_SERVICE, YanoConfig.class);
        HostApi host = requireService(context, HostApi.SERVICE_KEY, HostApi.class);
        host.version().requireCompatible(1, 1);
        // Verify lifecycle availability before constructing or owning any chain.
        HostLifecycleRegistry lifecycle = host.lifecycle();
        validateDeployment(node);
        boolean standalone = node.getMode() == RuntimeMode.APP_LEDGER;
        List<AppChainConfig> configurations = AppChainConfigParser.parseChains(globals, standalone);
        if (standalone) {
            for (AppChainConfig chain : configurations) {
                AppLedgerOperatingProfile.STANDALONE.validate(chain);
                String transport = stringOf(chain.pluginSettings().get("transport.mode"),
                        stringOf(globals.get("yano.app-chain.transport.mode"), "shared"));
                if (!"dedicated".equalsIgnoreCase(transport)) {
                    throw new IllegalArgumentException("Standalone app chain '" + chain.chainId()
                            + "' requires transport.mode=dedicated (effective: " + transport + ")");
                }
            }
        }
        long magic = standalone
                ? standaloneNetworkMagic(globals.get("yano.app-chain.network-magic"))
                : node.getProtocolMagic();
        String storage = storageRoot(node);
        context.logger().info("App-chain storage root: {}", storage);

        List<AppLedgerRuntime> chains = new ArrayList<>();
        AppLedgerManager manager = null;
        boolean transferred = false;
        try {
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
                String transport = stringOf(globals.get("yano.app-chain.transport.mode"), "shared");
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
            return manager;
        } catch (Throwable failure) {
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

    private static String storageRoot(YanoConfig node) {
        if (node.getMode() == RuntimeMode.APP_LEDGER) return node.getAppChainStoragePath();
        return AppChainStoragePaths.resolve(node.getRocksDBPath(), node.getAppChainStoragePath()).toString();
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

    // Ownership is transferred exactly once to the existing host lifecycle.
    @Override public final void start() { }
    @Override public final void stop() { }
    @Override public final synchronized void close() { closed = true; }
}
