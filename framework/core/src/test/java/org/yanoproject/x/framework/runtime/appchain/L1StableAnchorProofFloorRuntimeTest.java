package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.AppLedgerOperatingProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 framework addition 2 (D8b, D8h §5): with the L1-stable anchor floor, the
 * pruning scheduler keeps every proof from the latest L1-stable anchor height, prunes
 * nothing while no anchor is stable, and treats a floor below its watermark as a no-op.
 */
@Timeout(60)
class L1StableAnchorProofFloorRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void pruningNeverPassesTheL1StableAnchorHeight() throws Exception {
        AtomicLong stableAnchor = new AtomicLong();
        SimpleEventBus events = new SimpleEventBus();
        AppLedgerRuntime runtime = new AppLedgerRuntime(config(), 424242, events, null,
                directory.resolve("ledger").toString(), PluginProviderRegistry.empty(),
                LoggerFactory.getLogger(getClass())) {
            @Override
            protected long stableAnchoredHeight() {
                return stableAnchor.get();
            }
        };
        try {
            runtime.start();
            for (int index = 0; index < 5; index++) {
                runtime.submit("history", ("message-" + index).getBytes(StandardCharsets.UTF_8));
                int expected = index + 1;
                await(() -> runtime.tipHeight() >= expected);
            }

            // No L1-stable anchor yet: every pass completes without pruning anything.
            long firstPass = pruning(runtime, "lastCompletedAt");
            await(() -> pruning(runtime, "lastCompletedAt") > firstPass);
            assertThat(pruning(runtime, "lastRetainFrom")).isZero();
            assertThat(runtime.oldestProvableHeight()).isLessThanOrEqualTo(1);
            assertThat(pruningMap(runtime).get("l1StableAnchorFloor")).isEqualTo(true);

            // The anchor at height 3 becomes L1-stable: retention stops at 3 although the
            // tip-derived horizon (retain-heights 1) would keep only the tip.
            stableAnchor.set(3);
            await(() -> runtime.oldestProvableHeight() == 3);
            long settled = pruning(runtime, "lastCompletedAt");
            await(() -> pruning(runtime, "lastCompletedAt") > settled);
            assertThat(runtime.oldestProvableHeight()).isEqualTo(3);
            assertThat(pruning(runtime, "lastRetainFrom")).isEqualTo(3);
            assertThat(pruning(runtime, "lastStableAnchorHeight")).isEqualTo(3);

            // A floor below the watermark (e.g. a restarted stable view) is a no-op, not a failure.
            stableAnchor.set(2);
            long lowered = pruning(runtime, "lastCompletedAt");
            await(() -> pruning(runtime, "lastCompletedAt") > lowered);
            assertThat(pruningMap(runtime).get("degraded")).isEqualTo(false);
            assertThat(runtime.oldestProvableHeight()).isEqualTo(3);
        } finally {
            runtime.close();
            events.close();
        }
    }

    private static Map<?, ?> pruningMap(AppLedgerRuntime runtime) {
        Map<?, ?> commitment = (Map<?, ?>) runtime.status().get("stateCommitment");
        return (Map<?, ?>) commitment.get("proofPruning");
    }

    private static long pruning(AppLedgerRuntime runtime, String key) {
        return ((Number) pruningMap(runtime).get(key)).longValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static AppChainConfig config() {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 74);
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        return AppChainConfig.builder("proof-floor")
                .signingKeyHex(HexFormat.of().formatHex(seed))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(50)
                .stateMachineId(OrderedLogStateMachine.ID)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .pluginSettings(Map.of(
                        AppLedgerOperatingProfile.SETTING, AppLedgerOperatingProfile.STANDALONE.id(),
                        StateProofPruningSettings.ENABLED, "true",
                        StateProofPruningSettings.RETAIN_HEIGHTS, "1",
                        StateProofPruningSettings.INTERVAL_SECONDS, "1",
                        StateProofPruningSettings.L1_STABLE_ANCHOR_FLOOR, "true"))
                .build();
    }
}
