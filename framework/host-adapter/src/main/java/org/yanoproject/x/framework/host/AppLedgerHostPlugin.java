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
 * Constructs the existing ledger runtime during catalog initialization. The
 * host's pre-publication lifecycle owns start, stop and close after registration.
 * This library deliberately has no discovery descriptor; deployment roots select
 * either its standalone subclass or the optional Cardano specialization.
 */
public class AppLedgerHostPlugin implements NodePlugin {
    public static final String BUNDLE_ID = "org.yanoproject.x.app-ledger";
    public static final String NODE_CONFIG_SERVICE = "org.yanoproject.app-ledger.node-config";
    public static final String RUNTIME_OPTIONS_SERVICE = "org.yanoproject.app-ledger.runtime-options";
    public static final String GATEWAYS_SERVICE = "org.yanoproject.app-ledger.gateways";
    public static final String MANAGER_SERVICE = "org.yanoproject.app-ledger.manager";
    private boolean initialized;

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
    public final void init(PluginContext context) {
        if (initialized) throw new IllegalStateException("Application-ledger plugin already initialized");
        initialized = true;
        YanoConfig node = requireService(context, NODE_CONFIG_SERVICE, YanoConfig.class);
        RuntimeOptions options = requireService(context, RUNTIME_OPTIONS_SERVICE, RuntimeOptions.class);
        Map<String, Object> globals = options.globals();
        if (!Boolean.parseBoolean(String.valueOf(globals.get("yano.app-chain.enabled")))) return;

        HostApi host = requireService(context, HostApi.SERVICE_KEY, HostApi.class);
        host.version().requireCompatible(1, 1);
        // Verify lifecycle availability before constructing or owning any chain.
        HostLifecycleRegistry lifecycle = host.lifecycle();
        validateDeployment(node);
        boolean standalone = node.getMode() == RuntimeMode.APP_LEDGER;
        List<AppChainConfig> configurations = AppChainConfigParser.parseChains(globals, standalone);
        if (standalone) configurations.forEach(AppLedgerOperatingProfile.STANDALONE::validate);
        long magic = standalone
                ? Long.parseLong(String.valueOf(globals.get("yano.app-chain.network-magic")))
                : node.getProtocolMagic();
        String storage = storageRoot(node);
        context.logger().info("App-chain storage root: {}", storage);

        List<AppLedgerRuntime> chains = new ArrayList<>();
        AppLedgerManager manager = null;
        boolean transferred = false;
        try {
            for (AppChainConfig configuration : configurations) {
                AppLedgerRuntime chain = createChain(configuration, magic, storage, node, host, context);
                chains.add(Objects.requireNonNull(chain, "application-ledger runtime"));
                wireChain(chain, node, context);
            }
            manager = createManager(chains, node, context);
            manager.serverAgentRegistrations().forEach(host.n2n()::registerInbound);
            if (host.n2n().hasSharedOutboundSessions() && node.isClientEnabled()) {
                AppLedgerManager sharedManager = manager;
                String transport = stringOf(globals.get("yano.app-chain.transport.mode"), "shared");
                host.n2n().decorateOutbound(delegate -> sharedManager.wrapPeerClientFactory(
                        delegate, transport, node.getRemoteHost(), node.getRemotePort()));
            }
            context.registerService(GATEWAYS_SERVICE, manager);
            context.registerService(MANAGER_SERVICE, manager);
            lifecycle.registerBeforePublication(manager);
            transferred = true;
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
    @Override public final void close() { }
}
