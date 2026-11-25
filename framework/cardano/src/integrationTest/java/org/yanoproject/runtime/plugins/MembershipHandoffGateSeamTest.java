package org.yanoproject.runtime.plugins;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.AppChainGateway;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGateProvider;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.framework.cardano.AppChainSubsystem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 D8b review N1: a capability bundle contributes the membership handoff gate
 * through the {@code membership-handoff-gate} contribution kind (production descriptor,
 * binding and facade, resolved by the catalog provider registry), the chain selects it by
 * the consensus-committed {@code membership.anchor-handoff-gate}, and a governed change
 * goes pending, is cancelled, is re-committed and activates when the gate reports the
 * anchor fact for that specific handoff.
 */
@Timeout(90)
class MembershipHandoffGateSeamTest {
    private static final String GATE_ID = "test-anchor-gate";
    private static final String FACT_TOPIC = "handoff-fact";

    @TempDir
    Path directory;

    @Test
    void aContributedGateDrivesAGovernedChangeToActivation() throws Exception {
        AtomicInteger created = new AtomicInteger();
        MembershipHandoffGateProvider raw = new MembershipHandoffGateProvider() {
            @Override
            public String id() {
                return GATE_ID;
            }

            @Override
            public MembershipHandoffGate create(String chainId, Map<String, String> chainSettings) {
                created.incrementAndGet();
                return MembershipHandoffGateSeamTest::anchoredFor;
            }
        };
        String bundleId = "com.example.handoff-gate";
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(AppLedgerPluginKinds.KINDS,
                List.of(new CatalogPluginProviderRegistry.Entry(bundleId,
                        AppLedgerPluginKinds.MEMBERSHIP_HANDOFF_GATE, GATE_ID, raw.getClass().getName(), null,
                        () -> raw)),
                List.of(bundleId), List.of(), Thread.currentThread().getContextClassLoader());
        assertThat(registry.names(MembershipHandoffGateProvider.class)).containsExactly(GATE_ID);

        AppChainSubsystem node = new AppChainSubsystem(config(), 42, null, null,
                directory.resolve("chain").toString(), null, registry,
                LoggerFactory.getLogger(MembershipHandoffGateSeamTest.class));
        node.wireL1(cbor -> "00".repeat(32), () -> EMPTY_UTXOS);
        try {
            node.start();
            assertThat(created).hasValue(1);
            assertThat(handoff(node).get("gate")).isEqualTo("l1-stable-anchor");
            assertThat(handoff(node).containsKey("pendingCommittedHeight")).isFalse();

            // A governed change goes pending instead of activating.
            long epochBefore = ((Number) node.status().get("membershipEpochFromHeight")).longValue();
            node.setThreshold(1);
            await(() -> handoff(node).containsKey("pendingCommittedHeight"));
            long first = pending(node);

            // A governed cancel through the gateway withdraws exactly that change.
            AppChainGateway gateway = node;
            assertThat(gateway.cancelPendingMembershipHandoff()).isEqualTo(first);
            await(() -> !handoff(node).containsKey("pendingCommittedHeight"));

            // Re-committed: a fact for the earlier, cancelled handoff does not finalize it
            // (the gate binds the fact to the handoff's committed height).
            node.setThreshold(1);
            await(() -> handoff(node).containsKey("pendingCommittedHeight"));
            long second = pending(node);
            assertThat(second).isGreaterThan(first);
            node.submit(FACT_TOPIC, ("anchored:" + first).getBytes(StandardCharsets.UTF_8));
            long afterStale = node.tipHeight() + 1;
            await(() -> node.tipHeight() >= afterStale);
            assertThat(pending(node)).isEqualTo(second);

            // The fact for this handoff finalizes it; the epoch starts after the activation lag.
            node.submit(FACT_TOPIC, ("anchored:" + second).getBytes(StandardCharsets.UTF_8));
            await(() -> !handoff(node).containsKey("pendingCommittedHeight"));
            long epochAfter = ((Number) node.status().get("membershipEpochFromHeight")).longValue();
            assertThat(epochAfter).isGreaterThan(Math.max(epochBefore, second));
        } finally {
            node.close();
        }
    }

    /** Finalizes only on a fact that names this handoff's committed height (javadoc rule). */
    private static boolean anchoredFor(AppBlock block, MembershipHandoff handoff) {
        String expected = "anchored:" + handoff.committedHeight();
        for (AppMessage message : block.messages()) {
            if (FACT_TOPIC.equals(message.getTopic())
                    && expected.equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static Map<?, ?> handoff(AppChainSubsystem node) {
        Object value = node.status().get("membershipHandoff");
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static long pending(AppChainSubsystem node) {
        return ((Number) handoff(node).get("pendingCommittedHeight")).longValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static AppChainConfig config() {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 0x47);
        String member = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        return AppChainConfig.builder("handoff-seam")
                .signingKeyHex(HexUtil.encodeHexString(seed))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .threshold(1)
                .blockIntervalMs(50)
                .stateCommitmentIdentity(StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, fill(0x31)))
                .pluginSettings(Map.of("membership.mode", "governed",
                        "membership.anchor-handoff", "true",
                        "membership.anchor-handoff-gate", GATE_ID))
                .build();
    }

    private static byte[] fill(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final UtxoState EMPTY_UTXOS = new UtxoState() {
        @Override
        public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
            return List.of();
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return Optional.empty();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    };
}
