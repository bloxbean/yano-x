package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppBlockHeader;
import org.yanoproject.api.appchain.effects.EffectView;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.appchain.config.AppChainEffectsConfig;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compiles checked-in author recipes through real catalog bundles, executes them on three actual N2N hosts,
 * and replays their finalized blocks through the offline rehearsal API. Receipt-byte equality is tested,
 * not inferred from decoded fields. Temporary state is never an external deployment or production trust pin.
 */
@Timeout(180)
class BindingRecipesIT {
    @TempDir Path temporary;

    @Test void procurementYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("procurement"); }
    @Test void attestationYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("attestation"); }
    @Test void dppYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("dpp-approval"); }
    @Test void feedYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("feed-approval"); }
    @Test void payloadAdmissionAndLazyBaselineHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("payload-admission");
    }

    private void qualify(String recipe) throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        String yaml = recipe.equals("payload-admission") ? """
                composite:
                  components:
                    - {id: source, machine: ordered-log}
                    - {id: target, machine: ordered-log}
                  bindings:
                    - id: copy
                      from: {component: source, event: composite.command-accepted.v1}
                      to: {component: target, command: append, rawBody: body}
                """ : Files.readString(Path.of("..", "..", "examples", "bindings", recipe + ".yaml"));
        var genesis = productGenesis(yaml);
        String chain = genesis == null ? "binding-" + recipe + "-parity" : genesis.chainId();
        List<byte[]> seeds = memberSeeds();
        List<String> members = seeds.stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                .map(BindingRecipesIT::hex).sorted().toList();
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                MessageDigest.getInstance("SHA-256").digest(chain.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> hostSettings = genesis != null ? Map.of("membership.mode", "governed")
                : recipe.equals("procurement") ? Map.of("effects.enabled", "true") : Map.of();
        long interval = genesis == null ? 100 : 1000;
        var base = configuration(chain, seeds.getFirst(), members, identity, hostSettings, interval, List.of());
        var contextSettings = new LinkedHashMap<>(identity.settings());
        contextSettings.putAll(hostSettings);
        var input = new BindingCatalogSession.ContextInput(chain, contextSettings,
                AppChainEffectsConfig.from(base).consensusProfile(base),
                new AppChainMembershipEpoch(0, members, 2));

        try (var environment = BindingPluginEnvironment.open(plugins)) {
            var catalog = new BindingCatalogSession(environment.providers(), input);
            BindingIrV1 ir = BindingDocumentCompiler.compile(yaml, catalog);
            assertThat(catalog.validate(ir).capabilityManifest().components()).isNotEmpty();
            List<Integer> ports = ports();
            List<AppChainConfig> configs = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                var settings = new LinkedHashMap<>(hostSettings);
                settings.put(BindingCatalogSession.IR_SETTING, hex(ir.encode()));
                int own = ports.get(index);
                configs.add(configuration(chain, seeds.get(index), members, identity, settings, interval,
                        ports.stream().filter(port -> port != own)
                                .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList()));
            }
            List<EffectView> retainedOutbox = List.of();
            Map<String, String> retainedReceipts = new LinkedHashMap<>();
            byte[] retainedRoot;
            long retainedHeight;
            byte[] receiptProofKey;
            byte[] pinnedFinalizedContext;
            StateCommitmentIdentity pinnedIdentity;
            Object pinnedManifest;
            Object pinnedConsensus;
            try (Cluster cluster = new Cluster(configs, ports, temporary, environment.providers())) {
                byte[] initialRoot = cluster.nodes[0].stateRoot();
                pinnedIdentity = cluster.nodes[0].stateCommitmentIdentity().orElseThrow();
                pinnedManifest = cluster.nodes[0].status().get("capabilityManifest");
                pinnedConsensus = cluster.nodes[0].status().get("consensusProfile");
                List<String> sourceIds = submitRecipe(recipe, genesis, cluster, seeds);
                pinnedFinalizedContext = cluster.finalizedContextPin.clone();
                long tip = cluster.nodes[0].tipHeight();
                for (var node : cluster.nodes) {
                    assertThat(node.tipHeight()).isEqualTo(tip);
                    assertThat(node.stateRoot()).isEqualTo(cluster.nodes[0].stateRoot());
                    assertThat(node.stateCommitmentIdentity()).contains(pinnedIdentity);
                    assertThat(node.status().get("consensusProfile"))
                            .isEqualTo(pinnedConsensus);
                    assertThat(node.status().get("capabilityManifest"))
                            .isEqualTo(pinnedManifest);
                    assertThat(node.block(tip).orElseThrow().cert().signatures()).hasSizeGreaterThanOrEqualTo(2);
                }
                // Replay in canonical block order, carrying the plugin engine's physical state changes forward.
                // Host metadata is not invented or needed; context/root are explicit assumptions in dry-run.
                Map<String, String> state = new TreeMap<>();
                int compared = 0;
                int comparedEffects = 0;
                for (long height = 1; height <= tip; height++) {
                    var block = cluster.nodes[0].block(height).orElseThrow();
                    assertThat(block.messages()).isNotEmpty();
                    assertThat(block.messages()).allSatisfy(message ->
                            assertThat(message.getTopic()).doesNotStartWith("~"));
                    byte[] beforeRoot = height == 1 ? initialRoot
                            : cluster.nodes[0].block(height - 1).orElseThrow().stateRoot();
                    var fixture = new BindingDryRun.Fixture(height, block.timestamp(), hex(beforeRoot), 0,
                            state.entrySet().stream().map(entry ->
                                    new BindingDryRun.Entry(entry.getKey(), entry.getValue())).toList(),
                            block.messages().stream().map(message -> new BindingDryRun.Message(
                                    hex(message.getMessageId()), hex(message.getSender()), message.getSenderSeq(),
                                    message.getExpiresAt(), message.getTopic(), hex(message.getBody()),
                                    hex(message.getAuthProof()))).toList());
                    var rehearsal = BindingDryRun.execute(catalog.validate(ir), input, fixture);
                    int effectOrdinal = 0;
                    for (var effect : rehearsal.effects()) {
                        var durable = cluster.nodes[0].effect(height, effectOrdinal).orElseThrow();
                        assertThat(effect.get("payloadHex")).isEqualTo(durable.payloadHex());
                        assertThat(effect.get("type")).isEqualTo(durable.type());
                        for (var peer : cluster.nodes) {
                            assertThat(peer.effect(height, effectOrdinal)).contains(durable);
                        }
                        effectOrdinal++;
                        comparedEffects++;
                    }
                    for (var receipt : rehearsal.receipts()) {
                        String id = (String) receipt.get("messageIdHex");
                        byte[] actual = cluster.nodes[0].query("composite/binding-receipt-v1/" + id,
                                new byte[0]).payload();
                        assertThat((String) receipt.get("receiptHex")).isEqualTo(hex(actual));
                        assertThat(BindingReceiptV1.decode(actual).accepted()).isTrue();
                        retainedReceipts.put(id, hex(actual));
                        compared++;
                    }
                    for (var change : rehearsal.stateChanges()) {
                        if (change.valueHex() == null) state.remove(change.keyHex());
                        else state.put(change.keyHex(), change.valueHex());
                    }
                }
                assertThat(compared).isEqualTo(sourceIds.size());
                assertThat(comparedEffects).isEqualTo(recipe.equals("procurement") ? 1 : 0);
                retainedOutbox = cluster.nodes[0].effects(1, 100);
                String last = sourceIds.getLast();
                receiptProofKey = cluster.nodes[0].query(
                        "composite/binding-receipt-key-v1/" + last, new byte[0]).payload();
                assertThat(state.get(hex(receiptProofKey))).isEqualTo(retainedReceipts.get(last));
                for (var node : cluster.nodes) {
                    assertThat(node.query("composite/binding-receipt-key-v1/" + last, new byte[0]).payload())
                            .containsExactly(receiptProofKey);
                    verifyReceiptProof(node, receiptProofKey, new LinkedHashSet<>(members), chain,
                            pinnedIdentity, pinnedFinalizedContext);
                }
                retainedRoot = cluster.nodes[0].stateRoot();
                retainedHeight = cluster.nodes[0].tipHeight();
                assertThat(BindingReceiptV1.decode(cluster.nodes[0]
                        .query("composite/binding-receipt-v1/" + last, new byte[0]).payload()).steps().size())
                        .isGreaterThanOrEqualTo(2);
            }
            // Every recipe reopens all three original stores, not only the outbox-bearing recipe.
            try (Cluster restarted = new Cluster(configs, ports, temporary, environment.providers())) {
                for (var node : restarted.nodes) {
                    assertThat(node.effects(1, 100)).isEqualTo(retainedOutbox);
                    assertThat(node.tipHeight()).isEqualTo(retainedHeight);
                    assertThat(node.stateRoot()).isEqualTo(retainedRoot);
                    assertThat(node.stateCommitmentIdentity()).contains(pinnedIdentity);
                    assertThat(node.status().get("capabilityManifest")).isEqualTo(pinnedManifest);
                    assertThat(node.status().get("consensusProfile")).isEqualTo(pinnedConsensus);
                    retainedReceipts.forEach((id, receipt) -> assertThat(hex(node
                            .query("composite/binding-receipt-v1/" + id, new byte[0]).payload())).isEqualTo(receipt));
                    verifyReceiptProof(node, receiptProofKey, new LinkedHashSet<>(members), chain,
                            pinnedIdentity, pinnedFinalizedContext);
                }
                // Start one member with no ledger or authenticated state. N2N replay now starts at genesis,
                // recomputes the authoritative commitment root, and validates every certified block.
                // This is distinct from retained-state restart and from the root-free CLI rehearsal.
                restarted.stop(2);
                Path freshReplay = temporary.resolve("fresh-genesis-replay");
                assertThat(Files.exists(freshReplay)).isFalse();
                restarted.start(2, freshReplay);
                await(() -> restarted.nodes[2].tipHeight() == retainedHeight);
                var replayed = restarted.nodes[2];
                assertThat(replayed.stateRoot()).isEqualTo(retainedRoot);
                assertThat(replayed.stateCommitmentIdentity()).contains(pinnedIdentity);
                assertThat(replayed.status().get("consensusProfile")).isEqualTo(pinnedConsensus);
                assertThat(replayed.status().get("capabilityManifest")).isEqualTo(pinnedManifest);
                assertThat(replayed.effects(1, 100)).isEqualTo(retainedOutbox);
                retainedReceipts.forEach((id, receipt) -> assertThat(hex(replayed
                        .query("composite/binding-receipt-v1/" + id, new byte[0]).payload())).isEqualTo(receipt));
                verifyReceiptProof(replayed, receiptProofKey, new LinkedHashSet<>(members), chain,
                        pinnedIdentity, pinnedFinalizedContext);
            }
        }
    }

    private static List<String> submitRecipe(String recipe, AuthenticatedMapContract.Genesis genesis,
                                              Cluster cluster, List<byte[]> seeds) throws Exception {
        List<String> ids = new ArrayList<>();
        if (recipe.equals("payload-admission")) {
            // A valid host-sized command whose baseline envelope cannot fit must fail at submission,
            // not disappear from the pool later or masquerade as a finalized business outcome.
            assertThatThrownBy(() -> cluster.nodes[0].submit("source.command.v1", new byte[65_536]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COMMAND_PAYLOAD_TOO_LARGE");
            // No baseline subscriber: the full host-sized command needs no body-carrying event.
            ids.add(submit(cluster, 0, "target.command.v1", new byte[65_536]));
            String topic = "source.command.v1";
            int overhead = TransitionScalars.encode(Map.of("topic", topic, "sender", new byte[32],
                    "messageId", new byte[32], "body", new byte[65000], "bodyHash", new byte[32],
                    "bodyLength", 65000L)).length - 65000;
            ids.add(submitWithFollowerCatchup(cluster, 0, topic, new byte[65_536 - overhead]));
        } else if (recipe.equals("procurement")) {
            // The recipe's eligibility guard is false for this large value, but the ordinary KV write
            // still commits. This catches the old accidental ~3.8 KiB cap on non-deriving commands.
            ids.add(submit(cluster, 0, "orders.command.v1",
                    KvRegistryContract.put(new byte[]{2}, new byte[60 * 1024])));
            ids.add(submit(cluster, 0, "suppliers.command.v1", KvRegistryContract.put(
                    KeyGenUtil.getPublicKeyFromPrivateKey(seeds.getFirst()),
                    "approved".getBytes(StandardCharsets.UTF_8))));
            ids.add(submit(cluster, 0, "orders.command.v1", KvRegistryContract.put(new byte[]{1}, new byte[]{42})));
            ids.add(submit(cluster, 0, "reviews.command.v1", ApprovalsContract.approve("01")));
            ids.add(submitWithFollowerCatchup(cluster, 1, "reviews.command.v1", ApprovalsContract.approve("01")));
        } else if (recipe.equals("attestation")) {
            byte[] payload = TransitionScalars.encode(Map.of("documentHash", new byte[32], "reference", "document-1"));
            ids.add(submit(cluster, 0, "reviews.command.v1", ApprovalsContract.propose("document-1", payload, 2, 0)));
            ids.add(submit(cluster, 0, "reviews.command.v1", ApprovalsContract.approve("document-1")));
            ids.add(submitWithFollowerCatchup(cluster, 1, "reviews.command.v1",
                    ApprovalsContract.approve("document-1")));
        } else {
            boolean dpp = recipe.startsWith("dpp");
            String collection = dpp ? "certificates" : "rounds";
            String key = dpp ? "certificate-1" : "cold-store-7/0";
            String policy = dpp ? "certification" : "round-close";
            String clause = dpp ? "independent-auditors" : "independent-publishers";
            byte[] value = dpp ? BindingCbor.encode(List.of(1L, "product-1", "independent-audit", "cert-body-a",
                    new byte[32], 1L, 0L))
                    : BindingCbor.encode(List.of(1L, 2L, 1L, 0L, 2L, List.of(), new byte[32], new byte[0]));
            var action = new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                    List.of(AuthenticatedMapContract.Mutation.put(
                            collection, key.getBytes(StandardCharsets.UTF_8), value)),
                    List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                            0, AuthenticatedMapContract.AUTH_APPROVAL, policy, 1)));
            byte[] hash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                    AuthenticatedMapContract.genesisId(genesis),
                    AuthenticatedMapAuthorizationContract.actionCommitment(action));
            String proposer = dpp ? "certifier-a" : "ops-a";
            String first = dpp ? "auditor-a" : "publisher-a";
            String independent = dpp ? "auditor-b" : "publisher-b";
            ids.add(submit(cluster, 0, "reviews.v1", actorCommand(dpp, genesis.chainId(), policy, clause,
                    proposer, ActorStatementV1.Action.PROPOSE, hash,
                    AuthenticatedMapAuthorizationContract.encodeAction(action))));
            ids.add(submit(cluster, 0, "reviews.v1", actorCommand(dpp, genesis.chainId(), policy, clause,
                    first, ActorStatementV1.Action.APPROVE, hash, new byte[0])));
            ids.add(submitWithFollowerCatchup(cluster, 0, "reviews.v1", actorCommand(
                    dpp, genesis.chainId(), policy, clause,
                    independent, ActorStatementV1.Action.APPROVE, hash, new byte[0])));
        }
        return ids;
    }

    private static byte[] actorCommand(boolean dpp, String chain, String policy, String clause, String actor,
                                         ActorStatementV1.Action action, byte[] hash, byte[] staged) throws Exception {
        String domain = dpp ? "yano-dpp-starter-demo-actor:" : "yano-attestation-feed-demo-actor:";
        byte[] seed = MessageDigest.getInstance("SHA-256").digest((domain + actor).getBytes(StandardCharsets.UTF_8));
        var statement = new ActorStatementV1(action, chain, "qualification", policy, 1,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, hash, 500, actor, 1, actor + "-k1",
                action == ActorStatementV1.Action.APPROVE ? clause : "");
        return new StagedActorCommandV1(SignedActorCommandV1.sign(statement, seed), staged).encode();
    }

    private static String submit(Cluster cluster, int node, String topic, byte[] body) throws Exception {
        String id = cluster.nodes[node].submit(topic, body);
        await(() -> Arrays.stream(cluster.nodes).filter(java.util.Objects::nonNull).allMatch(peer -> {
            try { return peer.query("composite/binding-receipt-v1/" + id, new byte[0]).payload().length > 0; }
            catch (RuntimeException notFinalized) { return false; }
        }));
        return id;
    }

    /** Stops a non-proposer, commits the cascade using the remaining quorum, then catches up its retained store. */
    private static String submitWithFollowerCatchup(Cluster cluster, int sender, String topic, byte[] body)
            throws Exception {
        int follower = -1;
        for (int index = 0; index < 3; index++) {
            String member = hex(KeyGenUtil.getPublicKeyFromPrivateKey(memberSeeds().get(index)));
            if (index != sender && !member.equals(cluster.configs.getFirst().proposerKeyHex())) follower = index;
        }
        assertThat(follower).isNotNegative();
        long previous = cluster.nodes[follower].tipHeight();
        cluster.stop(follower);
        String id = submit(cluster, sender, topic, body);
        long target = cluster.nodes[sender].tipHeight();
        assertThat(target).isGreaterThan(previous);
        // Pin the surviving fixture-owned quorum's context before the recovering peer can supply any proof/header.
        cluster.finalizedContextPin = cluster.nodes[sender].block(target).orElseThrow().consensusContextDigest();
        cluster.start(follower);
        await(() -> Arrays.stream(cluster.nodes).allMatch(node -> node.tipHeight() == target));
        byte[] expected = cluster.nodes[sender].query("composite/binding-receipt-v1/" + id, new byte[0]).payload();
        for (var node : cluster.nodes) {
            assertThat(node.stateRoot()).isEqualTo(cluster.nodes[sender].stateRoot());
            assertThat(node.query("composite/binding-receipt-v1/" + id, new byte[0]).payload()).isEqualTo(expected);
        }
        return id;
    }

    /** Verifies the receipt's certified membership proof against fixture-owned membership and initial identity pins. */
    private static void verifyReceiptProof(AppChainSubsystem node, byte[] key, Set<String> members, String chain,
                                           StateCommitmentIdentity pinnedIdentity, byte[] pinnedContext) {
        var envelope = node.stateProofEnvelope(key).orElseThrow();
        var nativeProof = envelope.proof();
        var snapshot = nativeProof.snapshot();
        var header = AppBlockHeader.from(node.block(snapshot.height()).orElseThrow());
        assertThat(header.consensusContextDigest()).isEqualTo(pinnedContext);
        var metadata = ProofVerifier.profileMetadata(pinnedIdentity.profile().id()).orElseThrow();
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
                hex(snapshot.identity().genesisId()), metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), snapshot.height(), AppChainClient.ProofPresence.PRESENT, block, certificate);
        var trust = new ProofVerifier.FinalityTrustContext(chain, metadata.id(), hex(pinnedIdentity.genesisId()),
                members, 2, hex(pinnedContext));
        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
    }

    private static AuthenticatedMapContract.Genesis productGenesis(String yaml) throws Exception {
        var document = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        for (var component : document.path("composite").path("components")) {
            if (component.path("machine").asText().equals("authenticated-map-component")) {
                return AuthenticatedMapContract.decodeGenesis(HexFormat.of()
                        .parseHex(component.path("config").path("genesis-cbor-hex").asText()));
            }
        }
        return null;
    }

    private static AppChainConfig configuration(String chain, byte[] seed, List<String> members,
                                                  StateCommitmentIdentity identity, Map<String, String> settings,
                                                  long interval, List<AppChainConfig.AppPeer> peers) {
        return AppChainConfig.builder(chain).signingKeyHex(hex(seed)).memberKeysHex(new LinkedHashSet<>(members))
                .proposerKeyHex(members.getFirst()).threshold(2).blockIntervalMs(interval).peers(peers)
                .stateCommitmentIdentity(identity).stateMachineId(BindingCatalogSession.MACHINE)
                .pluginSettings(settings).build();
    }
    private static List<byte[]> memberSeeds() {
        List<byte[]> values = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            byte[] seed = new byte[32]; seed[0] = (byte) (111 + index); values.add(seed);
        }
        return values;
    }
    private static List<Integer> ports() throws Exception {
        Set<Integer> values = new LinkedHashSet<>();
        while (values.size() < 3) try (ServerSocket socket = new ServerSocket(0)) { values.add(socket.getLocalPort()); }
        return List.copyOf(values);
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("recipe cluster did not finalize its source message");
    }

    private static final class Cluster implements AutoCloseable {
        final List<AppChainConfig> configs;
        final List<Integer> ports;
        final Path directory;
        final PluginProviderRegistry providers;
        byte[] finalizedContextPin;
        final AppChainSubsystem[] nodes = new AppChainSubsystem[3];
        final NodeServer[] servers = new NodeServer[3];
        final Thread[] threads = new Thread[3];
        Cluster(List<AppChainConfig> configs, List<Integer> ports, Path directory,
                PluginProviderRegistry providers) throws Exception {
            this.configs = configs;
            this.ports = ports;
            this.directory = directory;
            this.providers = providers;
            try {
                for (int index = 0; index < 3; index++) start(index);
                await(() -> Arrays.stream(nodes).allMatch(node -> {
                    Object peers = node.status().get("peers");
                    return peers instanceof Map<?, ?> map && !map.isEmpty()
                            && map.values().stream().allMatch(Boolean.TRUE::equals);
                }));
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void start(int index) throws Exception {
            start(index, directory);
        }
        void start(int index, Path stateDirectory) throws Exception {
            var node = new AppChainSubsystem(configs.get(index), 42, null, null,
                    stateDirectory.resolve("node-" + index).toString(), null, providers,
                    LoggerFactory.getLogger(BindingRecipesIT.class));
            nodes[index] = node;
            node.start();
            var server = new NodeServer(ports.get(index),
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(42, false, 0, false),
                    new EmptyL1(), null, null, node.serverAgentFactories());
            servers[index] = server;
            Thread thread = new Thread(server::start, "binding-recipe-server-" + index);
            threads[index] = thread;
            thread.setDaemon(true);
            thread.start();
        }
        void stop(int index) throws Exception {
            if (nodes[index] != null) { nodes[index].close(); nodes[index] = null; }
            if (servers[index] != null) { servers[index].shutdown(); servers[index] = null; }
            if (threads[index] != null) {
                threads[index].join(5000);
                assertThat(threads[index].isAlive()).isFalse();
                threads[index] = null;
            }
        }
        @Override public void close() throws Exception {
            for (int index = 0; index < 3; index++) stop(index);
        }
    }

    private static final class EmptyL1 implements ChainState {
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
