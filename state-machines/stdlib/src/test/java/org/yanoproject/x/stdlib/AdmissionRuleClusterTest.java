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
import org.yanoproject.x.composite.bindings.EventBindingWorkflow;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.composite.contracts.CompositeGovernanceStatusV1;
import org.yanoproject.x.composite.contracts.CompositeProfileGovernanceV1;
import org.yanoproject.x.stdlib.contracts.BalancesContract;

import java.math.BigInteger;
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

/**
 * ADR-031.3 Phase 6 on three real members: a member with a different rule parameter fails closed at height 1; a
 * governed profile epoch tightens a limit exactly at its activation height while earlier receipts stay provable;
 * and a block full of rule denials finalizes with a legitimate transfer after them.
 *
 * <p>The transfer limit also reads {@code context.height}, so it is not static: an over-limit transfer is pooled and
 * refused at block time with a finalized, provable receipt instead of being refused at ingress.
 */
@Timeout(240)
class AdmissionRuleClusterTest {
    private static final String CHAIN = "admission-rule-cluster";
    private static final long ACTIVATION = 16;

    @Test
    void aMemberWithADifferentRuleParameterFailsClosedAtHeightOne(@TempDir Path directory) throws Exception {
        var seeds = seeds(131);
        var members = members(seeds);
        // The divergent member is never the proposer, so the other two keep finalizing with threshold 2.
        int divergent = divergentIndex(seeds, members);
        List<AppChainConfig> configs = new ArrayList<>();
        var ports = DeclarativeBindingsClusterTest.ports(3);
        for (int index = 0; index < 3; index++) {
            var ir = limit(index == divergent ? 20_000 : 10_000, 1);
            configs.add(config(seeds.get(index), members, ports, index, 100, Map.of(
                    DeclarativeCompositeProvider.IR_SETTING, hex(ir.encode()))));
        }
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs, ports, directory)) {
            int correct = divergent == 0 ? 1 : 0;
            var node = cluster.node(correct);
            String id = node.submit("points.v1", BalancesContract.mint(members.getFirst(), BigInteger.TEN));
            List<AppChainSubsystem> agreeing = new ArrayList<>();
            for (int index = 0; index < 3; index++) if (index != divergent) agreeing.add(cluster.node(index));
            await(() -> agreeing.stream().allMatch(peer -> receipt(peer, id).isPresent()));
            // Keep finalizing for longer than a catch-up interval while the divergent member stays connected.
            long deadline = System.nanoTime() + 6_000_000_000L;
            for (int value = 2; System.nanoTime() < deadline; value++) {
                String next = node.submit("points.v1", BalancesContract.mint(members.getFirst(),
                        BigInteger.valueOf(value)));
                await(() -> agreeing.stream().allMatch(peer -> receipt(peer, next).isPresent()));
            }
            for (var peer : agreeing) assertThat(peer.stateRoot()).isEqualTo(agreeing.getFirst().stateRoot());
            var outlier = cluster.node(divergent);
            assertThat(outlier.status().get("peers")).isInstanceOfSatisfying(Map.class, peers ->
                    assertThat(peers.values()).isNotEmpty().allMatch(Boolean.TRUE::equals));
            // It computes a different root for height 1 and rejects every certified block: it fails closed.
            assertThat(outlier.tipHeight()).isZero();
            assertThat(outlier.status().get("capabilityManifest"))
                    .isNotEqualTo(agreeing.getFirst().status().get("capabilityManifest"));
            // Nothing was committed, so the member restarted with the agreed rule catches up to the agreed root.
            cluster.stop(divergent);
            configs.set(divergent, config(seeds.get(divergent), members, ports, divergent, 100, Map.of(
                    DeclarativeCompositeProvider.IR_SETTING, hex(limit(10_000, 1).encode()))));
            cluster.start(divergent);
            await(() -> cluster.node(divergent).tipHeight() == agreeing.getFirst().tipHeight());
            assertThat(cluster.node(divergent).stateRoot()).isEqualTo(agreeing.getFirst().stateRoot());
        }
    }

    @Test
    void aGovernedEpochTightensTheLimitAtItsActivationHeight(@TempDir Path directory) throws Exception {
        var seeds = seeds(141);
        var members = members(seeds);
        var membership = new AppChainMembershipEpoch(0, members, 2);
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]);
        var before = limit(10_000, 1);
        var after = limit(5_000, ACTIVATION);
        Map<String, String> settings = Map.of(
                "membership.mode", "governed", "machines.composite.profile-mode", "governed",
                "machines.composite.profile-governance.min-activation-lag", "2",
                "machines.composite.profile-governance.proposal-ttl-blocks", "40",
                DeclarativeCompositeProvider.IR_SETTING, hex(before.encode()),
                "machines.composite.binding-ir-catalog[0]", hex(after.encode()));
        var ports = DeclarativeBindingsClusterTest.ports(3);
        List<AppChainConfig> configs = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            configs.add(config(seeds.get(index), members, ports, index, 100, settings));
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
        assertThat(nextProfile.digest()).isNotEqualTo(oldProfile.digest());
        byte[] proposalId = new byte[32];
        proposalId[0] = 7;
        var begin = new CompositeProfileGovernanceV1.Begin(proposalId, oldProfile.digest(), membership.digest(),
                nextProfile.digest(), nextProfile.canonicalBytes().length, 1, ACTIVATION, 30);
        byte[] proposalHash = CompositeProfileGovernanceV1.proposalHash(CHAIN, begin);
        // Transfers debit the sender's account: node 0 signs every transfer below.
        String account = hex(KeyGenUtil.getPublicKeyFromPrivateKey(seeds.getFirst()));
        String recipient = members.stream().filter(member -> !member.equals(account)).findFirst().orElseThrow();
        byte[] root;
        long height;
        String earlier;
        byte[] earlierReceipt;
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs, ports, directory)) {
            submit(cluster, 0, BalancesContract.mint(account, BigInteger.valueOf(100_000)));
            earlier = submit(cluster, 0, BalancesContract.transfer(recipient, BigInteger.valueOf(8_000)));
            earlierReceipt = cluster.node(0).query("composite/binding-receipt-v1/" + earlier, new byte[0]).payload();
            assertThat(BindingReceiptV1.decode(earlierReceipt).accepted()).isTrue();
            govern(cluster, 0, begin);
            govern(cluster, 0, new CompositeProfileGovernanceV1.Chunk(proposalId, 0, nextProfile.canonicalBytes()));
            govern(cluster, 0, new CompositeProfileGovernanceV1.Seal(proposalId));
            govern(cluster, 0, new CompositeProfileGovernanceV1.Approve(proposalHash));
            govern(cluster, 1, new CompositeProfileGovernanceV1.Approve(proposalHash));
            for (int member = 0; member < 3; member++) {
                govern(cluster, member, new CompositeProfileGovernanceV1.Ready(proposalHash, nextProfile.digest()));
            }
            // Before activation the old limit still admits the same transfer.
            String stillAllowed = null;
            while (cluster.node(0).tipHeight() < ACTIVATION - 1) {
                stillAllowed = submit(cluster, 0, BalancesContract.transfer(recipient, BigInteger.valueOf(8_000)));
            }
            assertThat(stillAllowed).isNotNull();
            assertThat(BindingReceiptV1.decode(cluster.node(0).query("composite/binding-receipt-v1/" + stillAllowed,
                    new byte[0]).payload()).accepted()).isTrue();
            assertThat(cluster.node(0).messageHeight(HexFormat.of().parseHex(stillAllowed))).contains(ACTIVATION - 1);
            String refused = submit(cluster, 0, BalancesContract.transfer(recipient, BigInteger.valueOf(8_000)));
            assertThat(cluster.node(0).messageHeight(HexFormat.of().parseHex(refused))).contains(ACTIVATION);
            assertThat(status(cluster).currentEpoch()).isEqualTo(1);
            for (var node : cluster.liveNodes()) {
                var receipt = BindingReceiptV1.decode(node.query("composite/binding-receipt-v1/" + refused,
                        new byte[0]).payload());
                assertThat(receipt.accepted()).isFalse();
                assertThat(receipt.steps().getFirst().rules().failure())
                        .isEqualTo(new BindingReceiptV1.RuleFailure("transfer-limit", 0, "TRANSFER_LIMIT_EXCEEDED"));
                assertEarlierReceipt(node, earlier, earlierReceipt, new LinkedHashSet<>(members));
                assertThat(node.stateRoot()).isEqualTo(cluster.node(0).stateRoot());
            }
            assertThat(BindingReceiptV1.decode(cluster.node(0).query("composite/binding-receipt-v1/"
                    + submit(cluster, 0, BalancesContract.transfer(recipient, BigInteger.valueOf(5_000))),
                    new byte[0]).payload()).accepted()).isTrue();
            root = cluster.node(0).stateRoot();
            height = cluster.node(0).tipHeight();
        }
        try (var restarted = new DeclarativeBindingsClusterTest.Cluster(configs, ports, directory)) {
            for (var node : restarted.liveNodes()) {
                assertThat(node.tipHeight()).isEqualTo(height);
                assertThat(node.stateRoot()).isEqualTo(root);
                assertEarlierReceipt(node, earlier, earlierReceipt, new LinkedHashSet<>(members));
            }
        }
    }

    @Test
    void aBlockFullOfRuleDenialsFinalizesWithALegitimateTransferAfterThem(@TempDir Path directory)
            throws Exception {
        var seeds = seeds(151);
        var members = members(seeds);
        var ports = DeclarativeBindingsClusterTest.ports(3);
        List<AppChainConfig> configs = new ArrayList<>();
        // A long block interval lets every submission below reach the same proposal.
        for (int index = 0; index < 3; index++) {
            configs.add(config(seeds.get(index), members, ports, index, 1_500, Map.of(
                    DeclarativeCompositeProvider.IR_SETTING, hex(limit(10_000, 1).encode()))));
        }
        String account = hex(KeyGenUtil.getPublicKeyFromPrivateKey(seeds.getFirst()));
        List<String> others = members.stream().filter(member -> !member.equals(account)).toList();
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs, ports, directory)) {
            submit(cluster, 0, BalancesContract.mint(account, BigInteger.valueOf(1_000_000)));
            var node = cluster.node(0);
            // A proposer tick can fall between the denials and the legitimate transfer, which then lands alone;
            // a later attempt shows the ordering. Every attempt's denials and transfer must still finalize.
            int position = 0;
            for (int attempt = 0; attempt < 3 && position == 0; attempt++) {
                List<String> refused = new ArrayList<>();
                for (int count = 0; count < 40; count++) {
                    refused.add(node.submit("points.v1",
                            BalancesContract.transfer(others.get(0), BigInteger.valueOf(20_000 + count))));
                }
                String legitimate = node.submit("points.v1",
                        BalancesContract.transfer(others.get(1), BigInteger.valueOf(9_000)));
                await(() -> cluster.liveNodes().stream().allMatch(member -> receipt(member, legitimate).isPresent()
                        && refused.stream().allMatch(id -> receipt(member, id).isPresent())));
                for (String id : refused) {
                    assertThat(BindingReceiptV1.decode(receipt(node, id).orElseThrow()).code())
                            .isEqualTo("ADMISSION_RULE_DENIED");
                }
                assertThat(BindingReceiptV1.decode(receipt(node, legitimate).orElseThrow()).accepted()).isTrue();
                long legitimateHeight = node.messageHeight(HexFormat.of().parseHex(legitimate)).orElseThrow();
                var messages = node.block(legitimateHeight).orElseThrow().messages();
                while (!HexFormat.of().formatHex(messages.get(position).getMessageId()).equals(legitimate)) {
                    position++;
                }
            }
            // The legitimate transfer finalized in a block after a run of denials.
            assertThat(position).as("denials before the legitimate transfer in its block").isPositive();
            assertThat(node.stateValue(CompositeStateKeys.componentKey("points",
                    BalancesContract.accountKey(others.get(1))))).isPresent();
            // The pool keeps flowing afterwards.
            submit(cluster, 0, BalancesContract.transfer(others.get(1), BigInteger.ONE));
            DeclarativeBindingsClusterTest.assertConvergence(cluster, new LinkedHashSet<>(members));
        }
    }

    /** One balances component with a transfer limit that also reads context, so it is evaluated at block time. */
    private static BindingIrV1 limit(long maxAmount, long workflowFromHeight) {
        var rule = new BindingIrV1.AdmissionRule("transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer",
                List.of(new BindingIrV1.Parameter("maxAmount", BindingIrV1.ParameterType.INTEGER, null)),
                List.of(new BindingIrV1.ExpressionClause(new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                        new Call("and", List.of(
                                new Call("gt", List.of(new Field(Scope.CONTEXT, "height"), new Literal(0L))),
                                new Call("le", List.of(new Field(Scope.COMMAND, "amount"),
                                        new Field(Scope.PARAMS, "maxAmount")))))))));
        return new BindingIrV1(List.of(new BindingIrV1.Component("points", "balances", "points.v1",
                Map.of("minter", new BindingSourceV1.Literal("")), 0, 1, List.of(new BindingIrV1.RuleAttachment(
                "transfer-limit", Map.of("maxAmount", new BindingSourceV1.Literal(maxAmount)))))),
                List.of(rule), List.of(), BindingIrV1.Limits.DEFAULT, workflowFromHeight);
    }

    private static AppChainConfig config(byte[] seed, List<String> members, List<Integer> ports, int index,
                                         long interval, Map<String, String> settings) {
        int own = ports.get(index);
        return AppChainConfig.builder(CHAIN).signingKeyHex(hex(seed))
                .memberKeysHex(new LinkedHashSet<>(members)).proposerKeyHex(members.getFirst()).threshold(2)
                .blockIntervalMs(interval)
                .stateCommitmentIdentity(StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]))
                .stateMachineId(DeclarativeCompositeProvider.ID).pluginSettings(settings)
                .peers(ports.stream().filter(port -> port != own)
                        .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList()).build();
    }

    private static List<byte[]> seeds(int first) {
        List<byte[]> seeds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            byte[] seed = new byte[32];
            seed[0] = (byte) (first + index);
            seeds.add(seed);
        }
        return seeds;
    }

    private static List<String> members(List<byte[]> seeds) {
        return seeds.stream().map(KeyGenUtil::getPublicKeyFromPrivateKey).map(AdmissionRuleClusterTest::hex)
                .sorted().toList();
    }

    /** A member whose key is not the sorted-first proposer key. */
    private static int divergentIndex(List<byte[]> seeds, List<String> members) {
        for (int index = 0; index < seeds.size(); index++) {
            if (!hex(KeyGenUtil.getPublicKeyFromPrivateKey(seeds.get(index))).equals(members.getFirst())) return index;
        }
        throw new IllegalStateException("no non-proposer member");
    }

    private static String submit(DeclarativeBindingsClusterTest.Cluster cluster, int node, byte[] body)
            throws Exception {
        String id = cluster.node(node).submit("points.v1", body);
        DeclarativeBindingsClusterTest.awaitReceipt(cluster, id);
        return id;
    }

    private static Optional<byte[]> receipt(AppChainSubsystem node, String id) {
        return node.stateValue(CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID,
                HexFormat.of().parseHex(id)));
    }

    private static void govern(DeclarativeBindingsClusterTest.Cluster cluster, int sender,
                               CompositeProfileGovernanceV1.Command command) throws Exception {
        String id = cluster.node(sender).submitPrivilegedSystemMessage(
                CompositeProfileGovernanceV1.TOPIC, command.encode());
        byte[] messageId = HexFormat.of().parseHex(id);
        await(() -> cluster.liveNodes().stream().allMatch(node -> node.messageHeight(messageId).isPresent()));
    }

    private static CompositeGovernanceStatusV1 status(DeclarativeBindingsClusterTest.Cluster cluster) {
        return CompositeGovernanceStatusV1.decode(cluster.node(0)
                .query("composite/governance-v1", new byte[0]).payload());
    }

    /** The pre-activation receipt stays queryable and certified under the newly active profile's root. */
    private static void assertEarlierReceipt(AppChainSubsystem node, String id, byte[] expected, Set<String> members) {
        byte[] key = CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID, HexFormat.of().parseHex(id));
        assertThat(node.query("composite/binding-receipt-v1/" + id, new byte[0]).payload()).isEqualTo(expected);
        DeclarativeBindingsClusterTest.verifyCertifiedProof(node, key, members, CHAIN);
    }

    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("cluster did not reach the expected finalized state");
    }
}
