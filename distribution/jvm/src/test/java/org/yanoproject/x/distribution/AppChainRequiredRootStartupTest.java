package org.yanoproject.x.distribution;

import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enabled app chains without the application-ledger root bundle in {@code yano.plugins.required} stop the
 * composed application instead of leaving it up with a logged runtime-initialization failure.
 */
@QuarkusMainTest
@TestProfile(AppChainRequiredRootStartupTest.Profile.class)
class AppChainRequiredRootStartupTest {

    public static class Profile extends NoAutoStartTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("yano.app-chain.enabled", "true");
            overrides.put("yano.plugins.required", "org.yanoproject.plugins.logging");
            return overrides;
        }
    }

    @Test
    @Launch(value = {}, exitCode = 1)
    void enabledAppChainsWithoutTheRootBundleFailStartup(LaunchResult result) {
        assertThat(result.getOutput() + result.getErrorOutput()).contains(
                "yano.app-chain.enabled=true requires bundle org.yanoproject.x.app-ledger in yano.plugins.required");
    }
}
