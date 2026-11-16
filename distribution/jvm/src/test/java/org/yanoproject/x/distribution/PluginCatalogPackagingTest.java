package org.yanoproject.x.distribution;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.plugin.PluginDigestMode;
import org.yanoproject.runtime.plugins.PluginRuntimeEnvironment;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moved from Yano's PluginCatalogPackagingTest: the composed application's single embedded index. */
class PluginCatalogPackagingTest {

    @Test
    void generatedIndexCarriesTheLoggingAndApplicationLedgerBundles() {
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), Thread.currentThread().getContextClassLoader())) {
            Set<String> bundleIds = environment.catalog().bundles().stream()
                    .map(bundle -> bundle.id())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("org.yanoproject.plugins.logging", "org.yanoproject.x.app-ledger"), bundleIds);
            assertTrue(environment.catalog().bundles().stream()
                    .allMatch(bundle -> bundle.selected()
                            && !bundle.legacy()
                            && bundle.digestMode() == PluginDigestMode.ARTIFACT_CLOSURE));
        }
    }
}
