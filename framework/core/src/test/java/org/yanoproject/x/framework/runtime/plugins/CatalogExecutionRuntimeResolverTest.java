package org.yanoproject.x.framework.runtime.plugins;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.plugin.PluginDigestMode;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogExecutionRuntimeResolverTest {
    private static final String RUNTIME = "graaljs";

    @Test
    void resolvesARuntimeOfAnExplicitlyAllowListedManifestedBundle() {
        ExecutionRuntimeFactory factory = factory(RUNTIME);
        assertThat(new CatalogExecutionRuntimeResolver(registry(factory, provenance(true))).resolve(RUNTIME))
                .isSameAs(factory);
    }

    @Test
    void refusesMissingUnlistedUnmanifestedAndMislabelledRuntimes() {
        assertThatThrownBy(() -> new CatalogExecutionRuntimeResolver(registry(factory(RUNTIME), provenance(true)))
                .resolve("chicory"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'chicory' is not installed on this node (available: [graaljs])");
        assertThatThrownBy(() -> new CatalogExecutionRuntimeResolver(registry(factory(RUNTIME), provenance(false)))
                .resolve(RUNTIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicitly allow-listed")
                .hasMessageContaining("com.example.runtime");
        assertThatThrownBy(() -> new CatalogExecutionRuntimeResolver(registry(factory(RUNTIME), null))
                .resolve(RUNTIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no manifested artifact provenance");
        assertThatThrownBy(() -> new CatalogExecutionRuntimeResolver(registry(factory("other"), provenance(true)))
                .resolve(RUNTIME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different runtime id");
    }

    private static ExecutionRuntimeFactory factory(String id) {
        return new ExecutionRuntimeFactory() {
            @Override public String runtimeId() { return id; }
            @Override public ExecutionRuntime open(RuntimeOptions options) { throw new AssertionError("not opened"); }
        };
    }

    private static PluginProviderRegistry.ContributionProvenance provenance(boolean allowed) {
        return new PluginProviderRegistry.ContributionProvenance("com.example.runtime", "sha256:" + "42".repeat(32),
                PluginDigestMode.JAR, allowed);
    }

    private static PluginProviderRegistry registry(ExecutionRuntimeFactory factory,
                                                   PluginProviderRegistry.ContributionProvenance provenance) {
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                return providerType == ExecutionRuntimeFactory.class && RUNTIME.equals(selector)
                        ? Optional.of(providerType.cast(factory)) : Optional.empty();
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                return providerType == ExecutionRuntimeFactory.class ? List.of(RUNTIME) : List.of();
            }

            @Override
            public <P> Optional<ContributionProvenance> contributionProvenance(Class<P> providerType, String selector) {
                return Optional.ofNullable(provenance);
            }
        };
    }
}
