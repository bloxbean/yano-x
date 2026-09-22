package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppChainMembershipView;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateMachineResolver;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.appchain.config.AppChainEffectsConfig;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.composite.contracts.CompositeGovernanceStatusV1;
import org.yanoproject.x.composite.contracts.CompositeProfileGovernanceV1;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real ADR-015 quorum/readiness cutover of binding generations, with persisted three-node restart. */
@Timeout(120)
class BindingProfileGovernanceClusterTest {
    private static final String CHAIN = "binding-profile-governance";
    private static final long ACTIVATION = 16;

    @Test
    void governedBindingProgramActivatesOnlyAtCommittedHeightAndSurvivesRestart(@TempDir Path directory)
            throws Exception {
        var seeds = new ArrayList<byte[]>();
        for (int index = 0; index < 3; index++) {
            byte[] seed = new byte[32];
            seed[0] = (byte) (121 + index);
            seeds.add(seed);
        }
        var members = seeds.stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                .map(BindingProfileGovernanceClusterTest::hex).sorted().toList();
        var membership = new AppChainMembershipEpoch(0, members, 2);
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]);
        var before = document("before", 1);
        var after = document("after", ACTIVATION);
        Map<String, String> settings = Map.of(
                "membership.mode", "governed", "machines.composite.profile-mode", "governed",
                "machines.composite.profile-governance.min-activation-lag", "2",
                "machines.composite.profile-governance.proposal-ttl-blocks", "40",
                DeclarativeCompositeProvider.IR_SETTING, hex(before.encode()),
                "machines.composite.binding-ir-catalog[0]", hex(after.encode()));
        var ports = DeclarativeBindingsClusterTest.ports(3);
        var configs = new ArrayList<AppChainConfig>();
        for (int index = 0; index < 3; index++) {
            int own = ports.get(index);
            configs.add(AppChainConfig.builder(CHAIN).signingKeyHex(hex(seeds.get(index)))
                    .memberKeysHex(new LinkedHashSet<>(members)).proposerKeyHex(members.getFirst()).threshold(2)
                    .blockIntervalMs(100).stateCommitmentIdentity(identity)
                    .stateMachineId(DeclarativeCompositeProvider.ID).pluginSettings(settings)
                    .peers(ports.stream().filter(port -> port != own)
                            .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList()).build());
        }
        AppChainConsensusProfile consensus = AppChainEffectsConfig.from(configs.getFirst())
                .consensusProfile(configs.getFirst());
        AppStateMachineContext context = new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return identity.settings(); }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() { return Optional.of(consensus); }
            @Override public Optional<AppChainMembershipView> membershipView() {
                return Optional.of(height -> membership);
            }
            @Override public Optional<AppStateMachineResolver> stateMachineResolver() {
                return Optional.of((id, child) -> StdlibTestPluginProviders.registry()
                        .require(AppStateMachineProvider.class, id).create(child));
            }
        };
        var oldProfile = DeclarativeCompositeProvider.entry(context, before).profile();
        var nextProfile = DeclarativeCompositeProvider.entry(context, after).profile();
        byte[] proposalId = new byte[32];
        proposalId[0] = 1;
        var begin = new CompositeProfileGovernanceV1.Begin(proposalId, oldProfile.digest(), membership.digest(),
                nextProfile.digest(), nextProfile.canonicalBytes().length, 1, ACTIVATION, 30);
        byte[] proposalHash = CompositeProfileGovernanceV1.proposalHash(CHAIN, begin);
        byte[] root;
        long height;
        String oldSourceId;
        byte[] oldReceipt;
        byte[] oldReceiptKey;
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs, ports, directory)) {
            oldSourceId = write(cluster, 1);
            oldReceipt = cluster.node(0).query("composite/binding-receipt-v1/" + oldSourceId, new byte[0]).payload();
            oldReceiptKey = CompositeStateKeys.workflowStateKey("event-bindings", HexFormat.of().parseHex(oldSourceId));
            govern(cluster, 0, begin);
            govern(cluster, 0, new CompositeProfileGovernanceV1.Chunk(proposalId, 0, nextProfile.canonicalBytes()));
            govern(cluster, 0, new CompositeProfileGovernanceV1.Seal(proposalId));
            govern(cluster, 0, new CompositeProfileGovernanceV1.Approve(proposalHash));
            govern(cluster, 1, new CompositeProfileGovernanceV1.Approve(proposalHash));
            govern(cluster, 0, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest()));
            govern(cluster, 1, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest()));
            assertThat(status(cluster).proposal().statusCode()).isEqualTo(1);
            govern(cluster, 2, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest()));
            assertThat(status(cluster).proposal().statusCode()).isEqualTo(2);
            while (cluster.node(0).tipHeight() < ACTIVATION - 1) write(cluster, (int) cluster.node(0).tipHeight() + 1);
            for (var node : cluster.liveNodes()) {
                assertThat(node.query("composite/active-profile-v1", new byte[0]).payload())
                        .isEqualTo(oldProfile.canonicalBytes());
                assertThat(node.stateValue(auditKey("after"))).isEmpty();
            }
            write(cluster, 99);
            assertThat(status(cluster).currentEpoch()).isEqualTo(1);
            assertThat(status(cluster).activeFromHeight()).isEqualTo(ACTIVATION);
            for (var node : cluster.liveNodes()) {
                assertThat(node.query("composite/active-profile-v1", new byte[0]).payload())
                        .isEqualTo(nextProfile.canonicalBytes());
                assertThat(node.stateValue(auditKey("before"))).isPresent();
                assertThat(node.stateValue(auditKey("after"))).isPresent();
                assertThat(DocTrailStateMachine.decodeEntry(node.stateValue(auditKey("after")).orElseThrow()).count())
                        .isEqualTo(1);
                assertThat(node.stateRoot()).isEqualTo(cluster.node(0).stateRoot());
                assertOldReceipt(node, oldSourceId, oldReceiptKey, oldReceipt, new LinkedHashSet<>(members));
            }
            root = cluster.node(0).stateRoot();
            height = cluster.node(0).tipHeight();
        }
        try (var restarted = new DeclarativeBindingsClusterTest.Cluster(configs, ports, directory)) {
            for (var node : restarted.liveNodes()) {
                assertThat(node.tipHeight()).isEqualTo(height);
                assertThat(node.stateRoot()).isEqualTo(root);
                assertThat(node.query("composite/active-profile-v1", new byte[0]).payload())
                        .isEqualTo(nextProfile.canonicalBytes());
                assertOldReceipt(node, oldSourceId, oldReceiptKey, oldReceipt, new LinkedHashSet<>(members));
            }
            write(restarted, 100);
            assertThat(DocTrailStateMachine.decodeEntry(restarted.node(0)
                    .stateValue(auditKey("after")).orElseThrow()).count()).isEqualTo(2);
        }
    }

    private static void govern(DeclarativeBindingsClusterTest.Cluster cluster, int sender,
                               CompositeProfileGovernanceV1.Command command) throws Exception {
        String id = cluster.node(sender).submitPrivilegedSystemMessage(
                CompositeProfileGovernanceV1.TOPIC, command.encode());
        byte[] messageId = HexFormat.of().parseHex(id);
        await(() -> cluster.liveNodes().stream().allMatch(node -> node.messageHeight(messageId).isPresent()));
    }

    private static String write(DeclarativeBindingsClusterTest.Cluster cluster, int value) throws Exception {
        String id = cluster.node(0).submit("records.v1",
                KvRegistryContract.put(new byte[]{1}, new byte[]{(byte) value}));
        DeclarativeBindingsClusterTest.awaitReceipt(cluster, id);
        return id;
    }

    /** Old-generation receipts remain queryable and certified under the newly active profile's current root. */
    private static void assertOldReceipt(AppChainSubsystem node, String sourceId,
                                         byte[] key, byte[] expected, Set<String> members) {
        assertThat(node.query("composite/binding-receipt-v1/" + sourceId, new byte[0]).payload()).isEqualTo(expected);
        assertThat(node.stateProofEnvelope(key).orElseThrow().proof().value()).isEqualTo(expected);
        DeclarativeBindingsClusterTest.verifyCertifiedProof(node, key, members, CHAIN);
    }

    private static CompositeGovernanceStatusV1 status(DeclarativeBindingsClusterTest.Cluster cluster) {
        return CompositeGovernanceStatusV1.decode(cluster.node(0)
                .query("composite/governance-v1", new byte[0]).payload());
    }

    private static byte[] auditKey(String entity) {
        return CompositeStateKeys.componentKey("audit", DocTrailStateMachine.entityKey(entity));
    }

    private static BindingIrV1 document(String destination, long fromHeight) {
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("records", "kv-registry", "records.v1",
                        Map.of("value-format", new BindingSourceV1.Literal("raw")), 0),
                new BindingIrV1.Component("audit", "doc-trail", "audit.v1", Map.of(), 0)), List.of(
                new BindingIrV1.Binding("record-audit", "records", "kv-registry.entry-put.v1", List.of(),
                        new BindingIrV1.CommandTarget("audit", "append", BindingIrV1.Mapping.fields(List.of(
                                new BindingIrV1.Assignment("entityId", new BindingSourceV1.Literal(destination)),
                                new BindingIrV1.Assignment("entryHash", new BindingSourceV1.Field("valueHash")),
                                new BindingIrV1.Assignment("reference", new BindingSourceV1.Literal("cutover"))))))),
                BindingIrV1.Limits.DEFAULT, fromHeight);
    }

    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 25_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("governance command did not finalize on every peer");
    }
}
