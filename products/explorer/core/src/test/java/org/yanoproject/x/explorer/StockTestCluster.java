package org.yanoproject.x.explorer;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.stdlib.StdlibStateMachineProviders;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestStateCommitments;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * In-process multi-member cluster running one stock stdlib machine (doc-trail, kv-registry,
 * approvals, or balances), wired exactly as the Attest tests wire their doc-trail cluster, with a
 * direct provider registry keyed by machine id.
 */
final class StockTestCluster implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(StockTestCluster.class);
    private static final long MAGIC = 42;

    private final List<AppChainSubsystem> subsystems = new ArrayList<>();
    private final List<NodeServer> servers = new ArrayList<>();
    private final List<String> memberKeysHex = new ArrayList<>();
    private final String chainId;
    private final int threshold;

    private StockTestCluster(String chainId, int threshold) {
        this.chainId = chainId;
        this.threshold = threshold;
    }

    static StockTestCluster start(String chainId, String machineId, int nodeCount) throws Exception {
        Path tempDir = Files.createTempDirectory("explorer-cluster-");
        int threshold = nodeCount / 2 + 1;
        StockTestCluster cluster = new StockTestCluster(chainId, threshold);
        SecureRandom random = new SecureRandom();
        List<byte[]> seeds = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            byte[] seed = new byte[32];
            random.nextBytes(seed);
            seeds.add(seed);
            cluster.memberKeysHex.add(HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
        }
        Set<String> members = Set.copyOf(cluster.memberKeysHex);
        String proposer = cluster.memberKeysHex.getFirst();
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            ports.add(freePort());
        }
        for (int i = 0; i < nodeCount; i++) {
            List<AppChainConfig.AppPeer> peers = new ArrayList<>();
            for (int j = 0; j < nodeCount; j++) {
                if (j != i) {
                    peers.add(new AppChainConfig.AppPeer("localhost", ports.get(j)));
                }
            }
            AppChainConfig config = AppChainConfig.builder(chainId)
                    .signingKeyHex(HexUtil.encodeHexString(seeds.get(i)))
                    .stateCommitmentIdentity(AppChainTestStateCommitments.mpf(chainId))
                    .memberKeysHex(members)
                    .peers(peers)
                    .proposerKeyHex(proposer)
                    .threshold(threshold)
                    .blockIntervalMs(300)
                    .stateMachineId(machineId)
                    .build();
            AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                    tempDir.resolve("ledger-" + i).toString(), null, registry(), log);
            cluster.subsystems.add(subsystem);
            NodeServer server = new NodeServer(ports.get(i),
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                    new MinimalChainState(), null, null, subsystem.serverAgentFactories());
            cluster.servers.add(server);
            Thread thread = new Thread(server::start, "explorer-cluster-server-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        Thread.sleep(800);
        for (AppChainSubsystem subsystem : cluster.subsystems) {
            subsystem.start();
        }
        cluster.await("cluster connectivity", 30_000, () ->
                cluster.subsystems.stream().allMatch(gateway -> {
                    Object peers = gateway.status().get("peers");
                    return peers instanceof Map<?, ?> peerMap
                            && peerMap.size() == nodeCount - 1
                            && peerMap.values().stream().allMatch(Boolean.TRUE::equals);
                }));
        return cluster;
    }

    String chainId() {
        return chainId;
    }

    int threshold() {
        return threshold;
    }

    AppChainGateway node(int index) {
        return subsystems.get(index);
    }

    List<String> memberKeysHex() {
        return List.copyOf(memberKeysHex);
    }

    /** Submits through node 0 and waits until every member finalized the message. */
    String submit(String topic, byte[] body) throws InterruptedException {
        String messageId = node(0).submit(topic, body);
        awaitFinalized(messageId);
        return messageId;
    }

    void awaitFinalized(String messageIdHex) throws InterruptedException {
        await("finalized " + messageIdHex, 30_000, () -> subsystems.stream()
                .allMatch(gateway -> gateway.messageInclusionProof(
                        HexUtil.decodeHexString(messageIdHex)).isPresent()));
    }

    void await(String description, long timeoutMillis, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    @Override
    public void close() {
        for (AppChainSubsystem subsystem : subsystems) {
            try {
                subsystem.stop();
            } catch (Exception ignored) {
                // best effort shutdown
            }
        }
        for (NodeServer server : servers) {
            try {
                server.shutdown();
            } catch (Exception ignored) {
                // best effort shutdown
            }
        }
    }

    private static PluginProviderRegistry registry() {
        List<AppStateMachineProvider> providers = List.of(
                new StdlibStateMachineProviders.DocTrailProvider(),
                new StdlibStateMachineProviders.KvRegistryProvider(),
                new StdlibStateMachineProviders.ApprovalsProvider(),
                new StdlibStateMachineProviders.BalancesProvider());
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                if (providerType == AppStateMachineProvider.class) {
                    for (AppStateMachineProvider provider : providers) {
                        if (provider.id().equals(selector)) return Optional.of(providerType.cast(provider));
                    }
                }
                return Optional.empty();
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                return providerType == AppStateMachineProvider.class
                        ? providers.stream().map(AppStateMachineProvider::id).toList() : List.of();
            }
        };
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Minimal ChainState: the cluster exercises the app layer only. */
    private static final class MinimalChainState implements ChainState {
        @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) { }
        @Override public byte[] getBlock(byte[] blockHash) { return null; }
        @Override public boolean hasBlock(byte[] blockHash) { return false; }
        @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) { }
        @Override public byte[] getBlockHeader(byte[] blockHash) { return null; }
        @Override public byte[] getBlockByNumber(Long blockNumber) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long blockNumber) { return null; }
        @Override public Point findNextBlock(Point currentPoint) { return null; }
        @Override public Point findNextBlockHeader(Point currentPoint) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return Collections.emptyList(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long blockNumber) { return null; }
        @Override public void rollbackTo(Long slot) { }
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
