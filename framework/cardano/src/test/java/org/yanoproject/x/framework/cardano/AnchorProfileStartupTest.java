package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 additions 3 and 4 fail startup closed on configurations they cannot honour: a
 * pinned anchor identity on a metadata-mode anchor, an invalid anchor profile, and the
 * membership handoff gate on a static-membership chain.
 */
@Timeout(60)
class AnchorProfileStartupTest {
    @TempDir
    Path directory;

    @Test
    void pinnedIdentityRequiresScriptMode() {
        Map<String, String> settings = new HashMap<>(pinned());
        AppChainConfig config = config("pinned-metadata", settings, new AppChainConfig.AnchorConfig(
                true, "aa".repeat(32), 10, 60, 7014));
        assertStartFails(config, "anchor.mode=script");
    }

    @Test
    void invalidAnchorProfileFailsStart() {
        AppChainConfig partialPin = config("partial-pin",
                Map.of(AnchorProfileSettings.PINNED_SCRIPT_HASH, "00".repeat(28)), null);
        assertStartFails(partialPin, "every key");
        AppChainConfig v2WithoutSlotTime = config("v2-no-slot-time",
                Map.of(AnchorProfileSettings.VERSION, "2"), null);
        assertStartFails(v2WithoutSlotTime, AnchorProfileSettings.SLOT_ZERO);
    }

    @Test
    void membershipHandoffRequiresGovernedMembership() {
        AppChainConfig config = config("handoff-static", Map.of("membership.anchor-handoff", "true"), null);
        assertStartFails(config, "membership.mode=governed");
    }

    private void assertStartFails(AppChainConfig config, String message) {
        AppChainSubsystem node = new AppChainSubsystem(config, 42, null, null,
                directory.resolve(config.chainId()).toString(), null,
                LoggerFactory.getLogger(AnchorProfileStartupTest.class));
        try {
            assertThatThrownBy(node::start).hasStackTraceContaining(message);
        } finally {
            node.close();
        }
    }

    private static Map<String, String> pinned() {
        return Map.of(
                AnchorProfileSettings.PINNED_THREAD_POLICY_ID, "01".repeat(28),
                AnchorProfileSettings.PINNED_THREAD_ASSET_NAME, "",
                AnchorProfileSettings.PINNED_SCRIPT_HASH, "02".repeat(28),
                AnchorProfileSettings.PINNED_GENESIS_ID, "03".repeat(32),
                AnchorProfileSettings.PINNED_APPLICATION_ID, "ordered-log",
                AnchorProfileSettings.PINNED_COMMITMENT_PROFILE, "mpf-blake2b256-v1",
                AnchorProfileSettings.PINNED_FORMAT_FINGERPRINT, "04".repeat(32));
    }

    private static AppChainConfig config(String chainId, Map<String, String> settings,
                                         AppChainConfig.AnchorConfig anchor) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 0x41);
        String publicKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        return AppChainConfig.builder(chainId)
                .signingKeyHex(HexUtil.encodeHexString(seed))
                .memberKeysHex(Set.of(publicKey))
                .proposerKeyHex(publicKey)
                .threshold(1)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .anchor(anchor)
                .pluginSettings(settings)
                .build();
    }
}
