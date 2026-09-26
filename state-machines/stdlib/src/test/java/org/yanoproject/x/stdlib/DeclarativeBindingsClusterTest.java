package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.appchain.AppBlockHeader;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.bindings.EventBindingWorkflow;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.roles.DeclarativeRoleProviders;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.AdministratorAuthorityV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.GenesisActorV1;
import org.yanoproject.x.roles.contracts.GovernedAuthorizationLimitsV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;

import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real three-node host/N2N execution in disposable temporary directories and ephemeral loopback ports.
 * No Cardano traffic, retained cluster, effect delivery, or externally trusted proof context is involved.
 */
@Timeout(120)
class DeclarativeBindingsClusterTest {
    private static final String CHAIN = "bindings-three-node";
    private static final int THRESHOLD = 2;

    @Test
    void governedApprovalAppliesExactActionOnceAndRejectsMismatchAtomically(@TempDir Path directory)
            throws Exception {
        List<byte[]> seeds = List.of(seed(85), seed(86), seed(87));
        Set<String> members = new LinkedHashSet<>();
        seeds.forEach(seed -> members.add(hex(KeyGenUtil.getPublicKeyFromPrivateKey(seed))));
        String proposer = members.iterator().next();
        byte[] actorSeed = seed(90);
        var key = new ActorKeyEpochV1("reviewer-key", KeyGenUtil.getPublicKeyFromPrivateKey(actorSeed),
                1, 0, RecordStatus.ACTIVE);
        var actor = new ActorRecordV1("reviewer", "review-org", 1, RecordStatus.ACTIVE,
                List.of("reviewer"), List.of(key), new byte[0]);
        var policy = new ApprovalPolicyV1("review", 1, RecordStatus.ACTIVE, List.of("reviewer"),
                List.of(new ApprovalPolicyV1.RequiredClause("review", "reviewer", 1,
                        ApprovalPolicyV1.DistinctBy.ACTOR)), ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, 100);
        var governed = new GovernedGenesisV1(CHAIN,
                new AdministratorAuthorityV1("admin", 1, List.of("reviewer"), 1, 100),
                List.of(new OrganizationRecordV1("review-org", 1, RecordStatus.ACTIVE, new byte[0])),
                List.of(new GenesisActorV1(actor,
                        List.of(ActorKeyProofV1.sign(CHAIN, "reviewer", 1, key, actorSeed)))),
                List.of(), List.of(policy), GovernedAuthorizationLimitsV1.defaults());
        var domainConfig = AppChainConfig.builder(CHAIN).signingKeyHex(hex(seeds.getFirst()))
                .memberKeysHex(members).proposerKeyHex(proposer).threshold(THRESHOLD).blockIntervalMs(100)
                .stateMachineId(AuthenticatedMapStateMachine.ID).build();
        var collection = new AuthenticatedMapContract.CollectionDescriptor("records",
                AuthenticatedMapContract.AUTH_APPROVAL, "review", true, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, "");
        var genesis = AuthenticatedMapGenesisFactory.mpf(domainConfig, seed(91), 16, 32768,
                List.of(collection), List.of(), List.of(), governed);
        var ir = governedDocument(genesis, governed);
        List<Integer> ports = ports(3);
        List<AppChainConfig> configs = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int selfPort = ports.get(index);
            configs.add(AppChainConfig.builder(CHAIN).signingKeyHex(hex(seeds.get(index)))
                    .memberKeysHex(members).proposerKeyHex(proposer).threshold(THRESHOLD).blockIntervalMs(100)
                    .peers(ports.stream().filter(port -> port != selfPort)
                            .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList())
                    .stateCommitmentIdentity(StdlibTestStateCommitments.mpf(CHAIN))
                    .stateMachineId(DeclarativeCompositeProvider.ID)
                    .pluginSettings(Map.of(DeclarativeCompositeProvider.IR_SETTING, hex(ir.encode()))).build());
        }
        try (Cluster cluster = new Cluster(configs, ports, directory)) {
            var leader = cluster.node(0);
            var expected = action("bad", new byte[]{1});
            byte[] expectedHash = payloadHash(genesis, expected);
            byte[] stagedDifferent = AuthenticatedMapAuthorizationContract.encodeAction(action("bad", new byte[]{2}));
            String badPropose = leader.submit("reviews.v1", stagedCommand("bad", ActorStatementV1.Action.PROPOSE,
                    expectedHash, stagedDifferent, actorSeed));
            awaitReceipt(cluster, badPropose);
            assertThat(receipt(leader, badPropose).accepted()).isTrue();
            String badVote = leader.submit("reviews.v1", stagedCommand("bad", ActorStatementV1.Action.APPROVE,
                    expectedHash, new byte[0], actorSeed));
            awaitReceipt(cluster, badVote);
            for (var node : cluster.liveNodes()) {
                assertThat(receipt(node, badVote).accepted()).isFalse();
                assertThat(proposal(node, "bad").status()).isEqualTo(ApprovalProposalV1.ProposalStatus.PENDING);
                assertThat(node.stateValue(componentKey("reviews", StagedActorCommandV1.stateKey("bad"))))
                        .hasValue(stagedDifferent);
                assertThat(node.stateValue(componentKey("registry",
                        AuthenticatedMapContract.canonicalKey("records", "bad".getBytes())))).isEmpty();
                byte[] work = node.stateValue(componentKey("actors", RoleWorkflowKeys.cryptoWork())).orElseThrow();
                ByteBuffer counter = ByteBuffer.wrap(work);
                assertThat(counter.getLong()).isEqualTo(receipt(node, badVote).height());
                assertThat(counter.getInt()).isEqualTo(1);
            }
            var acceptedAction = action("good", new byte[]{3});
            byte[] acceptedHash = payloadHash(genesis, acceptedAction);
            String goodPropose = leader.submit("reviews.v1", stagedCommand("good", ActorStatementV1.Action.PROPOSE,
                    acceptedHash, AuthenticatedMapAuthorizationContract.encodeAction(acceptedAction), actorSeed));
            awaitReceipt(cluster, goodPropose);
            String goodVote = leader.submit("reviews.v1", stagedCommand("good", ActorStatementV1.Action.APPROVE,
                    acceptedHash, new byte[0], actorSeed));
            awaitReceipt(cluster, goodVote);
            byte[] consumptionKey = componentKey("registry", AuthenticatedMapContract.approvalConsumptionKey("good"));
            byte[] consumption = leader.stateValue(consumptionKey).orElseThrow();
            for (var node : cluster.liveNodes()) {
                assertThat(receipt(node, goodVote).accepted()).isTrue();
                assertThat(proposal(node, "good").status()).isEqualTo(ApprovalProposalV1.ProposalStatus.APPROVED);
                assertThat(node.stateValue(componentKey("reviews", StagedActorCommandV1.stateKey("good")))).isEmpty();
                assertThat(node.stateValue(consumptionKey)).hasValue(consumption);
                verifyCertifiedProof(node, consumptionKey, members);
            }
            String replay = leader.submit("reviews.v1", stagedCommand("good", ActorStatementV1.Action.APPROVE,
                    acceptedHash, new byte[0], actorSeed));
            awaitReceipt(cluster, replay);
            for (var node : cluster.liveNodes()) {
                assertThat(receipt(node, replay).accepted()).isFalse();
                assertThat(node.stateValue(consumptionKey)).hasValue(consumption);
                var entry = node.stateValue(componentKey("registry",
                        AuthenticatedMapContract.canonicalKey("records", "good".getBytes())))
                        .map(AuthenticatedMapContract::decodeEntry).orElseThrow();
                assertThat(entry.revision()).isEqualTo(1);
                assertThat(entry.value()).isEqualTo(new byte[]{3});
            }
            assertConvergence(cluster, members);
        }
    }

    private static BindingIrV1 governedDocument(AuthenticatedMapContract.Genesis genesis,
                                                GovernedGenesisV1 governance) {
        var roleConfiguration = Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(hex(governance.encode())));
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("actors", DeclarativeRoleProviders.ACTORS_ID, "actors.v1",
                        roleConfiguration, 0),
                new BindingIrV1.Component("reviews", DeclarativeRoleProviders.APPROVALS_ID, "reviews.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(hex(governance.encode())),
                                "actor-component", new BindingSourceV1.Literal("actors")), 0),
                new BindingIrV1.Component("registry", AuthenticatedMapLeafStateMachine.ID, "registry.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(
                                        hex(AuthenticatedMapContract.encodeGenesis(genesis))),
                                "actors", new BindingSourceV1.Literal("actors"),
                                "approvals", new BindingSourceV1.Literal("reviews")), 0)),
                List.of(new BindingIrV1.Binding("apply-approved", "reviews", DeclarativeRoleProviders.APPROVED_EVENT,
                        List.of(), new BindingIrV1.CommandTarget("registry", "apply-action",
                        BindingIrV1.Mapping.fields(List.of(field("action", "action"),
                                field("approvalReference", "proposalId")))))), BindingIrV1.Limits.DEFAULT);
    }
    private static AuthenticatedMapAuthorizationContract.MapActionV1 action(String key, byte[] value) {
        return new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                List.of(AuthenticatedMapContract.Mutation.put("records", key.getBytes(), value)),
                List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_APPROVAL, "review", 1)));
    }
    private static byte[] payloadHash(AuthenticatedMapContract.Genesis genesis,
                                       AuthenticatedMapAuthorizationContract.MapActionV1 action) {
        return AuthenticatedMapAuthorizationContract.approvalPayloadHash(AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action));
    }
    private static byte[] stagedCommand(String id, ActorStatementV1.Action action, byte[] payloadHash,
                                         byte[] staged, byte[] actorSeed) {
        var statement = new ActorStatementV1(action, CHAIN, id, "review", 1,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, payloadHash, 100,
                "reviewer", 1, "reviewer-key", action == ActorStatementV1.Action.APPROVE ? "review" : "");
        return new StagedActorCommandV1(SignedActorCommandV1.sign(statement, actorSeed), staged).encode();
    }
    private static byte[] componentKey(String component, byte[] local) {
        return CompositeStateKeys.componentKey(component, local);
    }
    private static ApprovalProposalV1 proposal(AppChainSubsystem node, String id) {
        return node.stateValue(componentKey("reviews", RoleWorkflowKeys.proposal(id)))
                .map(ApprovalProposalV1::decode).orElseThrow();
    }
    static void awaitReceipt(Cluster cluster, String id) throws Exception {
        byte[] key = CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID, HexFormat.of().parseHex(id));
        await(() -> cluster.liveNodes().stream().allMatch(node -> node.stateValue(key).isPresent()));
    }

    @Test
    void cascadesConvergeWithCertifiedProofsFollowerCatchupAndWholeClusterRestart(@TempDir Path directory)
            throws Exception {
        List<byte[]> seeds = List.of(seed(81), seed(82), seed(83));
        Set<String> members = new LinkedHashSet<>();
        seeds.forEach(seed -> members.add(hex(KeyGenUtil.getPublicKeyFromPrivateKey(seed))));
        List<Integer> ports = ports(3);
        List<AppChainConfig> configs = new ArrayList<>();
        String proposer = members.iterator().next();
        for (int index = 0; index < 3; index++) {
            int selfPort = ports.get(index);
            configs.add(AppChainConfig.builder(CHAIN).signingKeyHex(hex(seeds.get(index)))
                    .memberKeysHex(members).proposerKeyHex(proposer).threshold(THRESHOLD).blockIntervalMs(100)
                    .peers(ports.stream().filter(port -> port != selfPort)
                            .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList())
                    .stateCommitmentIdentity(StdlibTestStateCommitments.mpf(CHAIN))
                    .stateMachineId(DeclarativeCompositeProvider.ID)
                    .pluginSettings(Map.of(DeclarativeCompositeProvider.IR_SETTING, hex(document().encode())))
                    .build());
        }
        byte[] approvalKey = CompositeStateKeys.componentKey("reviews", ApprovalsStateMachine.itemKey("order-1"));
        byte[] auditKey = CompositeStateKeys.componentKey("audit", DocTrailContract.entityKey("order-1"));
        byte[] root;
        long height;
        String voteId;
        try (Cluster cluster = new Cluster(configs, ports, directory)) {
            AppChainSubsystem leader = cluster.node(0);
            String orderId = leader.submit("orders.v1", KvRegistryStateMachine.put(new byte[]{1}, new byte[]{42}));
            await(() -> cluster.liveNodes().stream().allMatch(node -> node.stateValue(approvalKey).isPresent()));
            voteId = leader.submit("reviews.v1", ApprovalsStateMachine.approve("order-1"));
            await(() -> cluster.liveNodes().stream().allMatch(node -> node.stateValue(auditKey).isPresent()));
            assertThat(receipt(leader, orderId).accepted()).isTrue();
            assertThat(receipt(leader, voteId).accepted()).isTrue();
            assertConvergence(cluster, members);
            byte[] receiptKey = CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID,
                    HexFormat.of().parseHex(voteId));
            for (AppChainSubsystem node : cluster.liveNodes()) verifyCertifiedProof(node, receiptKey, members);

            // A peer's envelope remains that peer's principal throughout a cascade: routing cannot
            // lend the proposer's identity to an unauthorized update of the first sender's registry key.
            String rejectedId = cluster.node(1).submit("orders.v1",
                    KvRegistryStateMachine.put(new byte[]{1}, new byte[]{99}));
            byte[] rejectedReceiptKey = CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID,
                    HexFormat.of().parseHex(rejectedId));
            await(() -> cluster.liveNodes().stream().allMatch(node ->
                    node.stateValue(rejectedReceiptKey).isPresent()));
            for (AppChainSubsystem node : cluster.liveNodes()) {
                assertThat(receipt(node, rejectedId).accepted()).isFalse();
                assertThat(auditCount(node, auditKey)).isEqualTo(1);
            }
            assertConvergence(cluster, members);

            long stoppedAt = cluster.node(2).tipHeight();
            cluster.stop(2);
            String offlineId = leader.submit("audit.v1", DocTrailStateMachine.append(
                    "order-1", seed(99), "while-follower-offline"));
            await(() -> cluster.liveNodes().stream().allMatch(node -> auditCount(node, auditKey) == 2));
            assertThat(leader.tipHeight()).isGreaterThan(stoppedAt);
            cluster.start(2);
            await(() -> cluster.node(2).tipHeight() == leader.tipHeight()
                    && auditCount(cluster.node(2), auditKey) == 2);
            assertThat(receipt(cluster.node(2), offlineId).accepted()).isTrue();
            assertConvergence(cluster, members);
            verifyCertifiedProof(cluster.node(2), auditKey, members);
            root = leader.stateRoot();
            height = leader.tipHeight();
        }
        try (Cluster restarted = new Cluster(configs, ports, directory)) {
            await(() -> restarted.liveNodes().stream().allMatch(node -> node.tipHeight() == height));
            assertConvergence(restarted, members);
            for (AppChainSubsystem node : restarted.liveNodes()) {
                assertThat(node.stateRoot()).isEqualTo(root);
                assertThat(auditCount(node, auditKey)).isEqualTo(2);
                assertThat(receipt(node, voteId).accepted()).isTrue();
                verifyCertifiedProof(node, auditKey, members);
            }
        }
    }

    static void assertConvergence(Cluster cluster, Set<String> members) throws Exception {
        AppChainSubsystem leader = cluster.node(0);
        await(() -> cluster.liveNodes().stream().allMatch(node -> node.tipHeight() == leader.tipHeight()));
        for (AppChainSubsystem node : cluster.liveNodes()) {
            assertThat(node.stateRoot()).isEqualTo(leader.stateRoot());
            assertThat(node.stateCommitmentIdentity()).isEqualTo(leader.stateCommitmentIdentity());
            assertThat(node.status().get("consensusProfile")).isEqualTo(leader.status().get("consensusProfile"));
            assertThat(node.status().get("capabilityManifest"))
                    .isEqualTo(leader.status().get("capabilityManifest"));
            var certificate = node.block(node.tipHeight()).orElseThrow().cert();
            assertThat(certificate.signatures()).hasSizeGreaterThanOrEqualTo(THRESHOLD);
            assertThat(certificate.signatures()).allSatisfy(signature ->
                    assertThat(members).contains(hex(signature.signer())));
        }
    }

    static void verifyCertifiedProof(AppChainSubsystem node, byte[] key, Set<String> members) {
        verifyCertifiedProof(node, key, members, CHAIN);
    }

    static void verifyCertifiedProof(AppChainSubsystem node, byte[] key, Set<String> members, String chain) {
        var envelope = node.stateProofEnvelope(key).orElseThrow();
        var nativeProof = envelope.proof();
        var snapshot = nativeProof.snapshot();
        var identity = snapshot.identity();
        var header = AppBlockHeader.from(node.block(snapshot.height()).orElseThrow());
        var metadata = ProofVerifier.profileMetadata(identity.profile().id()).orElseThrow();
        var block = new AppChainClient.CertifiedBlockHeader(header.version(), header.height(), hex(header.prevHash()),
                header.l1Slot(), hex(header.l1BlockHash()), header.timestamp(), hex(header.messagesRoot()),
                hex(header.stateRoot()), hex(header.blockHash()), header.view(), hex(header.consensusContextDigest()),
                hex(header.proposer()), hex(header.justificationDigest()));
        var certificate = new AppChainClient.FinalityCertificate(envelope.finalityCertificate().scheme(),
                envelope.finalityCertificate().signatures().stream().map(signature ->
                        new AppChainClient.FinalitySignature(hex(signature.signer()), hex(signature.signature())))
                        .toList());
        var proof = new AppChainClient.Proof(hex(key), chain, hex(snapshot.stateRoot()), hex(nativeProof.nativeProof()),
                hex(nativeProof.value()), null, snapshot.height(), envelope.proofSchemaVersion(), metadata.id(),
                metadata.backend(), metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                hex(identity.genesisId()), metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), snapshot.height(), AppChainClient.ProofPresence.PRESENT, block, certificate);
        // Keys and genesis are fixture-owned pins, not trust derived from an arbitrary remote response.
        var trust = new ProofVerifier.FinalityTrustContext(chain, metadata.id(), hex(identity.genesisId()),
                members, THRESHOLD, hex(header.consensusContextDigest()));
        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
        var wrong = new ProofVerifier.FinalityTrustContext(chain, metadata.id(), hex(identity.genesisId()),
                members, THRESHOLD, hex(seed(100)));
        assertThat(ProofVerifier.verifyCertified(proof, wrong)).isFalse();
    }

    private static long auditCount(AppChainSubsystem node, byte[] key) {
        return node.stateValue(key).map(DocTrailStateMachine::decodeEntry).map(entry -> entry.count()).orElse(0L);
    }
    private static BindingReceiptV1 receipt(AppChainSubsystem node, String id) {
        return BindingReceiptV1.decode(node.query("composite/binding-receipt-v1/" + id, new byte[0]).payload());
    }
    private static BindingIrV1 document() {
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("orders", "kv-registry", "orders.v1",
                        Map.of("value-format", new BindingSourceV1.Literal("raw")), 0),
                new BindingIrV1.Component("reviews", "approvals", "reviews.v1", Map.of(), 0),
                new BindingIrV1.Component("audit", "doc-trail", "audit.v1", Map.of(), 0)), List.of(
                new BindingIrV1.Binding("propose-order", "orders", "kv-registry.entry-put.v1", List.of(),
                        new BindingIrV1.CommandTarget("reviews", "propose", BindingIrV1.Mapping.fields(List.of(
                                literal("itemId", "order-1"), field("payload", "value"), literal("required", 1L),
                                literal("deadlineMillis", 0L))))),
                new BindingIrV1.Binding("audit-approval", "reviews", "approvals.item-approved.v1", List.of(),
                        new BindingIrV1.CommandTarget("audit", "append", BindingIrV1.Mapping.fields(List.of(
                                field("entityId", "itemId"), field("entryHash", "payloadHash"),
                                literal("reference", "approved")))))), BindingIrV1.Limits.DEFAULT);
    }
    private static BindingIrV1.Assignment field(String target, String source) {
        return new BindingIrV1.Assignment(target, new BindingSourceV1.Field(source));
    }
    private static BindingIrV1.Assignment literal(String target, Object value) {
        return new BindingIrV1.Assignment(target, new BindingSourceV1.Literal(value));
    }
    private static byte[] seed(int first) { byte[] value = new byte[32]; value[0] = (byte) first; return value; }
    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    static List<Integer> ports(int count) throws Exception {
        Set<Integer> ports = new LinkedHashSet<>();
        while (ports.size() < count) try (ServerSocket socket = new ServerSocket(0)) {
            ports.add(socket.getLocalPort());
        }
        return List.copyOf(ports);
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 25_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("declarative cluster did not reach expected finalized state");
    }

    /** Owns only this test's temporary host instances and loopback N2N servers. */
    static final class Cluster implements AutoCloseable {
        private final List<AppChainConfig> configs;
        private final List<Integer> ports;
        private final Path directory;
        private final AppChainSubsystem[] nodes = new AppChainSubsystem[3];
        private final NodeServer[] servers = new NodeServer[3];
        private final Thread[] threads = new Thread[3];

        Cluster(List<AppChainConfig> configs, List<Integer> ports, Path directory) throws Exception {
            this.configs = configs;
            this.ports = ports;
            this.directory = directory;
            try {
                for (int index = 0; index < 3; index++) start(index);
                await(() -> liveNodes().stream().allMatch(node -> {
                    Object peers = node.status().get("peers");
                    return peers instanceof Map<?, ?> map && !map.isEmpty()
                            && map.values().stream().allMatch(Boolean.TRUE::equals);
                }));
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        AppChainSubsystem node(int index) { return nodes[index]; }
        List<AppChainSubsystem> liveNodes() {
            return java.util.Arrays.stream(nodes).filter(java.util.Objects::nonNull).toList();
        }
        void start(int index) {
            var node = new AppChainSubsystem(configs.get(index), 42, null, null,
                    directory.resolve("node-" + index).toString(), null, StdlibTestPluginProviders.registry(),
                    LoggerFactory.getLogger(DeclarativeBindingsClusterTest.class));
            nodes[index] = node;
            node.start();
            NodeServer server = new NodeServer(ports.get(index),
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(42, false, 0, false),
                    new EmptyL1ChainState(), null, null, node.serverAgentFactories());
            servers[index] = server;
            Thread thread = new Thread(server::start, "bindings-fixture-server-" + index);
            threads[index] = thread;
            thread.setDaemon(true);
            thread.start();
        }
        void stop(int index) throws InterruptedException {
            if (nodes[index] != null) {
                nodes[index].close();
                nodes[index] = null;
            }
            if (servers[index] != null) {
                servers[index].shutdown();
                servers[index] = null;
            }
            if (threads[index] != null) {
                threads[index].join(5000);
                assertThat(threads[index].isAlive()).as("loopback N2N server terminated").isFalse();
                threads[index] = null;
            }
        }
        @Override public void close() throws Exception {
            Exception failure = null;
            for (int index = 0; index < 3; index++) try { stop(index); }
            catch (Exception cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            if (failure != null) throw failure;
        }
    }

    /** Empty Cardano side of the test transport: this suite exercises app-chain N2N only. */
    private static final class EmptyL1ChainState implements ChainState {
        @Override public void storeBlock(byte[] hash, Long number, Long slot, byte[] block) { }
        @Override public byte[] getBlock(byte[] hash) { return null; }
        @Override public boolean hasBlock(byte[] hash) { return false; }
        @Override public void storeBlockHeader(byte[] hash, Long number, Long slot, byte[] header) { }
        @Override public byte[] getBlockHeader(byte[] hash) { return null; }
        @Override public byte[] getBlockByNumber(Long number) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long number) { return null; }
        @Override public Point findNextBlock(Point point) { return null; }
        @Override public Point findNextBlockHeader(Point point) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return List.of(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long count) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long number) { return null; }
        @Override public void rollbackTo(Long slot) { }
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
