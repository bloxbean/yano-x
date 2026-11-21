package org.yanoproject.x.framework.runtime.plugins;

import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeResolver;

import java.util.Objects;

/**
 * Catalog-owned resolver for execution runtimes (ADR-058 D13). A runtime decides state roots and runs
 * programs that the operator did not write, so it is resolved only from a manifested bundle that the
 * operator explicitly allow-listed. The genesis pin, the engine fingerprint, is checked by the
 * execution state machine as soon as it opens the runtime.
 */
public final class CatalogExecutionRuntimeResolver implements ExecutionRuntimeResolver {
    private final PluginProviderRegistry providers;

    public CatalogExecutionRuntimeResolver(PluginProviderRegistry providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    @Override
    public ExecutionRuntimeFactory resolve(String runtimeId) {
        Objects.requireNonNull(runtimeId, "runtimeId");
        if (!providers.names(ExecutionRuntimeFactory.class).contains(runtimeId)) {
            throw failure(runtimeId, "is not installed on this node (available: "
                    + providers.names(ExecutionRuntimeFactory.class) + ")");
        }
        PluginProviderRegistry.ContributionProvenance provenance = providers
                .contributionProvenance(ExecutionRuntimeFactory.class, runtimeId)
                .orElseThrow(() -> failure(runtimeId, "has no manifested artifact provenance"));
        if (!provenance.explicitlyAllowListed()) {
            throw failure(runtimeId, "is not owned by an explicitly allow-listed plugin bundle (bundle '"
                    + provenance.bundleId() + "'; add it to yano.plugins.allow-list)");
        }
        ExecutionRuntimeFactory factory = providers.find(ExecutionRuntimeFactory.class, runtimeId)
                .orElseThrow(() -> failure(runtimeId, "has no selected runtime factory"));
        if (!runtimeId.equals(factory.runtimeId())) {
            throw failure(runtimeId, "factory reports a different runtime id");
        }
        return factory;
    }

    private static IllegalStateException failure(String runtimeId, String reason) {
        return new IllegalStateException("Execution runtime '" + runtimeId + "' " + reason);
    }
}
