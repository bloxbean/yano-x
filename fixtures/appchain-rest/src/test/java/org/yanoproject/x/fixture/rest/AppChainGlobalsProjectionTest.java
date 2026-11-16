package org.yanoproject.x.fixture.rest;

import io.quarkus.arc.All;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yanoproject.app.spi.RuntimeGlobalsContributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-064 I7: typed Quarkus configuration projects into the runtime globals the host passes to
 * plugins, for flat and indexed chains, with an explicit enabled=false and the storage default.
 */
@QuarkusTest
@TestProfile(AppChainGlobalsProjectionTest.Profile.class)
class AppChainGlobalsProjectionTest {

    public static class Profile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.enabled", "false");
            overrides.put("yano.app-chain.chain-id", "flat");
            overrides.put("yano.app-chain.threshold", "2");
            overrides.put("yano.app-chain.effects.enabled", "true");
            overrides.put("yano.app-chain.chains[0].chain-id", "orders");
            overrides.put("yano.app-chain.chains[0].block.interval-ms", "1000");
            overrides.put("yano.app-chain.chains[0].effects.enabled", "true");
            return overrides;
        }
    }

    @Inject
    @All
    List<RuntimeGlobalsContributor> contributors;

    @Test
    void projectsTheTypedConfiguration() {
        Map<String, Object> globals = new HashMap<>();
        contributors.forEach(contributor -> globals.putAll(contributor.globals()));

        assertThat(globals)
                .containsEntry("yano.app-chain.enabled", false)
                .containsEntry("yano.app-chain.chain-id", "flat")
                .containsEntry("yano.app-chain.threshold", 2)
                .containsEntry("yano.app-chain.block.interval-ms", 2000L)
                .containsEntry("yano.app-chain.effects.enabled", "true")
                .containsEntry("yano.app-chain.storage.path", "appchain-chainstate")
                .doesNotContainKey("yano.app-chain.chains");
    }
}
