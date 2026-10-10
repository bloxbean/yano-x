package org.yanoproject.x.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import org.yanoproject.api.CanonicalBlockReference;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppBlockHeader;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.api.appchain.effects.EffectId;
import org.yanoproject.api.appchain.observation.ObservationAttestation;
import org.yanoproject.api.appchain.observation.ObservationCandidate;
import org.yanoproject.api.appchain.observation.ObservationDefinition;
import org.yanoproject.api.appchain.observation.ObservationHashes;
import org.yanoproject.api.appchain.observation.ObservationMerkleEvidence;
import org.yanoproject.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.api.appchain.observation.ObservationProvider;
import org.yanoproject.api.appchain.observation.ObservationProviderFactory;
import org.yanoproject.api.appchain.observation.ObservationReporterMode;
import org.yanoproject.api.appchain.observation.ObservationResult;
import org.yanoproject.api.appchain.observation.ObservationSourceConfiguration;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.runtime.blockproducer.DevnetBlockBuilder;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real host runtime and signatures; Conway blocks in an in-memory L1 chain state, no network transactions. */
@Timeout(120)
class ShipmentWorkflowRuntimeTest {
    private static final String CHAIN = "shipment-runtime";
    private static final byte[] ESCROW = enterpriseAddress(0x31);
    private static final byte[] MERCHANT = enterpriseAddress(0x32);
    private static final String ADAPTER = "fixture-shipment-merkle-v1";
    private static final byte[] MEMBER_SEED = HexUtil.decodeHexString("61".repeat(32));
    private static final byte[] ATTESTOR_SEED = HexUtil.decodeHexString("71".repeat(32));
    private static final byte[] MEMBER = KeyGenUtil.getPublicKeyFromPrivateKey(MEMBER_SEED);
    private static final byte[] ATTESTOR = KeyGenUtil.getPublicKeyFromPrivateKey(ATTESTOR_SEED);

    @Test void singleNodePaymentProofReleaseSettlementAndDiskRestart(@TempDir Path directory) throws Exception {
        paymentProofReleaseSettlementAndDiskRestart(1, directory);
    }

    @Test void threeNodePaymentProofReleaseSettlementAndDiskRestart(@TempDir Path directory) throws Exception {
        paymentProofReleaseSettlementAndDiskRestart(3, directory);
    }

    private void paymentProofReleaseSettlementAndDiskRestart(int nodeCount, Path directory) throws Exception {
        List<byte[]> seeds = new ArrayList<>();
        Set<String> members = new LinkedHashSet<>();
        Set<Integer> reservedPorts = new LinkedHashSet<>();
        for (int i = 0; i < nodeCount; i++) {
            byte[] seed = hash(0x61 + i);
            seeds.add(seed);
            members.add(HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
            while (reservedPorts.size() <= i) {
                try (ServerSocket socket = new ServerSocket(0)) { reservedPorts.add(socket.getLocalPort()); }
            }
        }
        List<Integer> ports = List.copyOf(reservedPorts);
        ObservationDefinition definition = new ObservationDefinition(1, "shipment-delivery", 1,
                hash(1), hash(2), hash(3), hash(4), ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.reporterSetDigest(members.stream().map(HexUtil::decodeHexString).toList()),
                0, nodeCount, 1, false,
                ADAPTER, ObservationSourceConfiguration.attestedSourceDigest("carrier", List.of(ATTESTOR)), "identity-v1",
                ObservationMerkleEvidence.VERIFIER_ID, "exact-value-quorum-v1", hash(6), hash(7),
                "one-source-v1", "source-version-v1", "inline-v1", 1, 1024, 1024, 1024, nodeCount, 1);
        ObservationProfileV1 profile = new ObservationProfileV1(1, true, 1, 1, 1, 1, 1, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, nodeCount, 1,
                4096, 1024, 16384, 10, 32768, 1, 20, 3);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("observations.profile-cbor-hex", HexUtil.encodeHexString(profile.encode()));
        settings.put("observations.attestors.shipment-delivery", HexUtil.encodeHexString(ATTESTOR));
        settings.put("observations.providers.shipment-delivery.type", ADAPTER);
        settings.put("observations.providers.shipment-delivery.source-id", "carrier");
        settings.put("observers.shipment-payment.type", "address-deposit");
        settings.put("observers.shipment-payment.address", new Address(ESCROW).toBech32());
        settings.put("observers.shipment-settlement.type", "address-deposit");
        settings.put("observers.shipment-settlement.address", new Address(MERCHANT).toBech32());
        settings.put("machines.shipment-workflow-reference-v1.minimum-payment-lovelace", "10");
        settings.put("machines.shipment-workflow-reference-v1.release-lovelace", "5");
        settings.put("observation.l1-network-genesis-id", "01".repeat(32));
        settings.put("effects.enabled", "true");
        settings.put("effects.external.enabled", "true");
        settings.put("effects.default-gate", "app-final");
        settings.put("effects.executor.enabled", "true");
        settings.put("effects.result.signers", HexUtil.encodeHexString(MEMBER));
        List<AppChainConfig> configs = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            int selfPort = ports.get(i);
            configs.add(AppChainConfig.builder(CHAIN).signingKeyHex(HexUtil.encodeHexString(seeds.get(i)))
                    .memberKeysHex(members).proposerKeyHex(HexUtil.encodeHexString(MEMBER))
                    .threshold(nodeCount).blockIntervalMs(150).l1StabilityDepth(1)
                    .peers(ports.stream().filter(port -> port != selfPort)
                            .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList())
                    .stateCommitmentIdentity(StdlibTestStateCommitments.mpf(CHAIN))
                    .stateMachineId(ShipmentWorkflowReferenceStateMachine.ID).pluginSettings(settings).build());
        }
        L1Chain l1 = new L1Chain();
        byte[] root;
        byte[] workflow;
        long height;
        try (Cluster cluster = new Cluster(configs, ports, directory, l1)) {
            AppChainSubsystem node = cluster.nodes.getFirst();
            cluster.publish(payment(10, ESCROW, 10));
            cluster.publish(null);
            await(() -> phase(node) != 0);
            if (phase(node) == 1) node.submit(ShipmentWorkflowReferenceStateMachine.ADVANCE_TOPIC, new byte[]{1});
            await(() -> phase(node) == 2);
            ObservationResult result = ObservationResult.decode(field(node, 4));
            assertThat(result.value()).isEqualTo("DELIVERED".getBytes(StandardCharsets.US_ASCII));
            assertThat(result.certificateDigest()).hasSize(32);
            assertThat(result.reporterCount()).isEqualTo(nodeCount);
            EffectId effect = EffectId.parse(new String(field(node, 5), StandardCharsets.UTF_8));
            assertThat(node.effect(effect.height(), effect.ordinal())).isPresent();
            await(() -> !node.claimEffects("fixture-executor", Set.of("cardano.payment"), 1, 60).isEmpty());
            byte[] settlement = payment(11, MERCHANT, 5);
            assertThat(node.reportEffect("fixture-executor", effect.height(), effect.ordinal(), true,
                    transactionHash(settlement).getBytes(StandardCharsets.US_ASCII), null)).isTrue();
            await(() -> phase(node) == 3);
            cluster.publish(settlement);
            cluster.publish(null);
            await(() -> cluster.nodes.stream().allMatch(peer -> phase(peer) == 4));
            workflow = node.query("workflow", new byte[0]).payload();
            root = node.stateRoot();
            height = node.tipHeight();
            for (AppChainSubsystem peer : cluster.nodes) {
                assertThat(peer.tipHeight()).isEqualTo(height);
                assertThat(peer.stateRoot()).isEqualTo(root);
                assertThat(peer.query("workflow", new byte[0]).payload()).isEqualTo(workflow);
                assertThat(peer.stateCommitmentIdentity()).isEqualTo(node.stateCommitmentIdentity());
                assertThat(peer.status().get("consensusProfile")).isEqualTo(node.status().get("consensusProfile"));
                assertThat(peer.status().get("capabilityManifest")).isEqualTo(node.status().get("capabilityManifest"));
                assertThat(peer.block(height).orElseThrow().cert().signatures()).hasSize(nodeCount);
                byte[] phaseKey = "shipment-reference/phase".getBytes(StandardCharsets.UTF_8);
                assertThat(peer.stateValue(phaseKey)).hasValueSatisfying(value -> assertThat(value).containsExactly(4));
                assertThat(peer.stateProofEnvelope(phaseKey)).isPresent();
                verifySdkProof(peer, phaseKey, members, nodeCount);
            }
        }
        try (Cluster cluster = new Cluster(configs, ports, directory, l1)) {
            for (AppChainSubsystem node : cluster.nodes) {
                assertThat(node.tipHeight()).isEqualTo(height);
                assertThat(node.stateRoot()).isEqualTo(root);
                assertThat(node.query("workflow", new byte[0]).payload()).isEqualTo(workflow);
            }
        }
    }

    private static void verifySdkProof(AppChainSubsystem node, byte[] key, Set<String> members, int threshold) {
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
                        new AppChainClient.FinalitySignature(hex(signature.signer()), hex(signature.signature()))).toList());
        var proof = new AppChainClient.Proof(hex(key), CHAIN, hex(snapshot.stateRoot()), hex(nativeProof.nativeProof()),
                hex(nativeProof.value()), null, snapshot.height(), envelope.proofSchemaVersion(), metadata.id(),
                metadata.backend(), metadata.commitmentFormatId(), metadata.formatFingerprintHex(), hex(identity.genesisId()),
                metadata.proofEncodingId(), metadata.nativeVersioning(), metadata.physicalDelete(), snapshot.height(),
                AppChainClient.ProofPresence.PRESENT, block, certificate);
        // This node is owned by the fixture. Its context is a trusted test pin,
        // not a recipe for deriving client trust from an untrusted REST response.
        var trust = new ProofVerifier.FinalityTrustContext(CHAIN, metadata.id(), hex(identity.genesisId()),
                members, threshold, hex(header.consensusContextDigest()));
        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
        var wrongContext = new ProofVerifier.FinalityTrustContext(CHAIN, metadata.id(), hex(identity.genesisId()),
                members, threshold, hex(hash(99)));
        assertThat(ProofVerifier.verifyCertified(proof, wrongContext)).isFalse();
    }

    private static String hex(byte[] value) { return HexUtil.encodeHexString(value); }

    private static class Cluster implements AutoCloseable {
        final List<AppChainSubsystem> nodes = new ArrayList<>();
        final List<SimpleEventBus> buses = new ArrayList<>();
        final List<NodeServer> servers = new ArrayList<>();
        final List<Thread> serverThreads = new ArrayList<>();
        final L1Chain l1;

        Cluster(List<AppChainConfig> configs, List<Integer> ports, Path directory, L1Chain l1) throws Exception {
            this.l1 = l1;
            try {
                for (int i = 0; i < configs.size(); i++) {
                    SimpleEventBus bus = new SimpleEventBus();
                    buses.add(bus);
                    AppChainSubsystem node = start(configs.get(i), directory.resolve("node-" + i), bus, l1);
                    nodes.add(node);
                    if (configs.size() > 1) {
                        NodeServer server = new NodeServer(ports.get(i),
                                N2NVersionTableConstant.v11AndAboveWithAppLayer(42, false, 0, false),
                                new EmptyL1ChainState(), null, null, node.serverAgentFactories());
                        servers.add(server);
                        Thread thread = new Thread(server::start, "shipment-fixture-server-" + i);
                        thread.setDaemon(true);
                        serverThreads.add(thread);
                        thread.start();
                    }
                }
                if (configs.size() > 1) await(() -> nodes.stream().allMatch(node -> {
                    Object peers = node.status().get("peers");
                    return peers instanceof Map<?, ?> map && !map.isEmpty()
                            && map.values().stream().allMatch(Boolean.TRUE::equals);
                }));
                // A fresh chain's delivery loop starts at the L1 tip it first reads; publishing earlier skips blocks.
                await(() -> nodes.stream().allMatch(node ->
                        node.status().get("l1Delivery") instanceof Map<?, ?> delivery
                                && delivery.containsKey("cursorBlock")));
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /** Appends one block, holding {@code transaction} when it is not null, and wakes every member. */
        void publish(byte[] transaction) {
            l1.append(transaction);
            // Node L1 events carry nothing the delivery loop reads; they only wake it (app-layer ADR-038).
            for (SimpleEventBus bus : buses) bus.publish(new BlockAppliedEvent(null, 0, 0, "00".repeat(32), null),
                    EventMetadata.builder().build(), PublishOptions.builder().build());
        }

        @Override public void close() throws Exception {
            RuntimeException failure = null;
            for (AppChainSubsystem node : nodes) {
                try { node.close(); } catch (RuntimeException cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            for (NodeServer server : servers) server.shutdown();
            for (Thread thread : serverThreads) {
                thread.join(5000);
                assertThat(thread.isAlive()).as("N2N fixture server terminated").isFalse();
            }
            if (failure != null) throw failure;
        }
    }

    /** N2N transport fixture only; Cardano observations come from {@link L1Chain}. */
    private static class EmptyL1ChainState implements ChainState {
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

    private static AppChainSubsystem start(AppChainConfig config, Path directory, SimpleEventBus bus, L1Chain l1) {
        PluginProviderRegistry base = StdlibTestPluginProviders.registry();
        ObservationProviderFactory factory = new ObservationProviderFactory() {
            @Override public String type() { return ADAPTER; }
            @Override public ObservationProvider create(String id, Map<String, String> settings) {
                return request -> {
                    byte[] value = "DELIVERED".getBytes(StandardCharsets.US_ASCII);
                    byte[] source = "carrier".getBytes(StandardCharsets.US_ASCII);
                    byte[] leaf = ObservationMerkleEvidence.leafHash(request.round().parametersDigest(), source, value);
                    byte[] sibling = hash(21);
                    byte[] root = ObservationMerkleEvidence.branchHash(sibling, leaf);
                    long anchor = request.round().dueAnchor();
                    ObservationAttestation unsigned = new ObservationAttestation(1, request.definition().digest(),
                            request.round().subscriptionId(), request.round().roundNumber(), ATTESTOR, source, root,
                            new byte[]{1}, 0, anchor, new byte[64]);
                    ObservationAttestation signed = new ObservationAttestation(1, request.definition().digest(),
                            request.round().subscriptionId(), request.round().roundNumber(), ATTESTOR, source, root,
                            new byte[]{1}, 0, anchor, sign(unsigned.signingDigest(), ATTESTOR_SEED));
                    byte[] evidence = new ObservationMerkleEvidence(1, signed, value, 1, List.of(sibling)).encode();
                    return new ObservationCandidate(source, value, evidence, new byte[]{1}, 0, anchor);
                };
            }
        };
        PluginProviderRegistry registry = new PluginProviderRegistry() {
            @Override public <P> Optional<P> find(Class<P> type, String selector) {
                return type == ObservationProviderFactory.class && ADAPTER.equals(selector)
                        ? Optional.of(type.cast(factory)) : base.find(type, selector);
            }
            @Override public <P> List<String> names(Class<P> type) {
                return type == ObservationProviderFactory.class ? List.of(ADAPTER) : base.names(type);
            }
        };
        AppChainSubsystem node = new AppChainSubsystem(config, 42, bus, null, directory.toString(), null,
                registry, LoggerFactory.getLogger(ShipmentWorkflowRuntimeTest.class));
        node.wireL1Chain(l1.reader(), null);
        node.start();
        return node;
    }

    /** The shared L1 of every member: real Conway blocks, one per slot, starting with an empty genesis block. */
    private static final class L1Chain {
        private final InMemoryChainState chain = new InMemoryChainState();
        private final DevnetBlockBuilder builder = new DevnetBlockBuilder();
        private byte[] tipHash;
        private long nextNumber;

        L1Chain() {
            append(null);
        }

        synchronized void append(byte[] transaction) {
            long number = nextNumber++;
            var block = builder.buildBlock(number, number, tipHash,
                    transaction == null ? List.of() : List.of(transaction));
            chain.storeBlockHeader(block.blockHash(), number, number, block.wrappedHeaderCbor());
            chain.storeBlock(block.blockHash(), number, number, block.blockCbor());
            tipHash = block.blockHash();
        }

        ChainBlockReader reader() {
            return new ChainBlockReader() {
                @Override public ChainTip getLocalTip() { return chain.getTip(); }
                @Override public byte[] getBlockByNumber(long blockNumber) {
                    return chain.getBlockByNumber(blockNumber);
                }
                @Override public Era getBlockEra(long blockNumber) { return chain.getBlockEra(blockNumber); }
                @Override public Optional<CanonicalBlockReference> getCanonicalBlockReference(long blockNumber) {
                    return chain.getCanonicalBlockReference(blockNumber);
                }
                @Override public Optional<CanonicalBlockReference> getCanonicalBlockReferenceAtSlot(long slot) {
                    return chain.getCanonicalBlockReferenceAtSlot(slot);
                }
                @Override public OptionalLong canonicalMutationSequence() {
                    return chain.canonicalMutationSequence();
                }
            };
        }
    }

    /** A minimal transaction ({@code [body, witnesses, true, null]}) paying {@code lovelace} to {@code address}. */
    private static byte[] payment(int nonce, byte[] address, long lovelace) {
        co.nstant.in.cbor.model.Map body = new co.nstant.in.cbor.model.Map();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(nonce));
        Array inputs = new Array();
        inputs.add(input);
        body.put(new UnsignedInteger(0), inputs);
        co.nstant.in.cbor.model.Map output = new co.nstant.in.cbor.model.Map();
        output.put(new UnsignedInteger(0), new ByteString(address));
        output.put(new UnsignedInteger(1), new UnsignedInteger(lovelace));
        Array outputs = new Array();
        outputs.add(output);
        body.put(new UnsignedInteger(1), outputs);
        body.put(new UnsignedInteger(2), new UnsignedInteger(200_000));
        Array transaction = new Array();
        transaction.add(body);
        transaction.add(new co.nstant.in.cbor.model.Map());
        transaction.add(SimpleValue.TRUE);
        transaction.add(SimpleValue.NULL);
        return CborSerializationUtil.serialize(transaction);
    }

    /** The Cardano transaction id: Blake2b-256 of the body CBOR. */
    private static String transactionHash(byte[] transaction) {
        Array decoded = (Array) CborSerializationUtil.deserializeOne(transaction);
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(
                CborSerializationUtil.serialize(decoded.getDataItems().getFirst())));
    }

    /** A testnet enterprise address (header 0x60) whose 28-byte key hash repeats {@code fill}. */
    private static byte[] enterpriseAddress(int fill) {
        byte[] address = new byte[29];
        address[0] = 0x60;
        Arrays.fill(address, 1, address.length, (byte) fill);
        return address;
    }

    private static byte[] sign(byte[] bytes, byte[] seed) {
        return CryptoConfiguration.INSTANCE.getSigningProvider().sign(bytes, seed);
    }
    private static Array view(AppChainSubsystem node) {
        return (Array) CborSerializationUtil.deserializeOne(node.query("workflow", new byte[0]).payload());
    }
    private static int phase(AppChainSubsystem node) {
        return ((UnsignedInteger) view(node).getDataItems().get(1)).getValue().intValueExact();
    }
    private static byte[] field(AppChainSubsystem node, int index) {
        return ((ByteString) view(node).getDataItems().get(index)).getBytes();
    }
    private static byte[] hash(int value) { return HexUtil.decodeHexString(String.format("%02x", value).repeat(32)); }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Shipment runtime did not reach expected phase");
    }
}
