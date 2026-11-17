package org.yanoproject.x.framework.host;

import org.yanoproject.api.host.PluginServiceLookup;
import org.yanoproject.x.framework.api.appchain.AppChainGateway;
import org.yanoproject.x.framework.api.appchain.AppChainGateways;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiGateway;
import org.yanoproject.x.framework.api.plugin.domain.LocalReadModelHost;

import java.util.Objects;
import java.util.Optional;

/**
 * Embedder access to the application ledgers of a running Yano host, for example
 * {@code AppLedgers.of(yano).appChains()}.
 *
 * <p>Every call resolves the application-ledger plugin's service again, so a result is never stale across a
 * restart or close. Before assembly and after close the views are empty.</p>
 */
public final class AppLedgers {
    /** Plugin service key of the application-ledger holder. */
    public static final String SERVICE_KEY = "org.yanoproject.x.app-ledger.services";

    private final PluginServiceLookup lookup;

    private AppLedgers(PluginServiceLookup lookup) {
        this.lookup = lookup;
    }

    /**
     * @param lookup plugin services of a Yano host, such as the {@code Yano} instance
     * @return per-call view of its application ledgers
     */
    public static AppLedgers of(PluginServiceLookup lookup) {
        return new AppLedgers(Objects.requireNonNull(lookup, "lookup"));
    }

    /** All hosted app chains; empty when app chains are disabled or not assembled. */
    public AppChainGateways appChains() {
        return current().map(AppLedgerHolder.Assembled::appChains).orElse(AppChainGateways.empty());
    }

    /** The single hosted app chain; empty when none or several are hosted. */
    public Optional<AppChainGateway> appChain() {
        return appChains().single();
    }

    /** Domain API dispatcher; empty when not assembled. */
    public DomainApiGateway domainApis() {
        return current().map(AppLedgerHolder.Assembled::domainApis).orElse(DomainApiGateway.empty());
    }

    /** Registration seam for node-local derived read models; empty when not assembled. */
    public Optional<LocalReadModelHost> localReadModels() {
        return current().map(AppLedgerHolder.Assembled::localReadModels);
    }

    private Optional<AppLedgerHolder.Assembled> current() {
        return lookup.service(SERVICE_KEY, AppLedgerHolder.class).flatMap(AppLedgerHolder::current);
    }
}
