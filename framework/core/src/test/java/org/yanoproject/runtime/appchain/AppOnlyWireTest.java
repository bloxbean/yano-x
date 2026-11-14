package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.network.server.ServerConnectionListener;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncListener;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgInitAck;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessages;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.codec.AppBlockCodec;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real TCP/mux/CBOR coverage on the framework's SDK-free runtime classpath. */
@Timeout(30)
class AppOnlyWireTest {
    @Test
    void appProtocolsAndKeepaliveWorkWithoutCardanoSdk(@TempDir Path directory) throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        assertThatThrownBy(() -> loader.loadClass("com.bloxbean.cardano.client.plutus.spec.PlutusData"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> loader.loadClass("com.bloxbean.cardano.client.transaction.spec.Transaction"))
                .isInstanceOf(ClassNotFoundException.class);
        var signer = new AppMessageSigner("01".repeat(32));
        byte[] body = {1, 2, 3};
        long expiry = System.currentTimeMillis() / 1000 + 300;
        byte[] signed = AppMessage.signedBodyBytes("wire", "test", signer.publicKey(), 1, expiry, body);
        AppMessage message = AppMessage.builder().chainId("wire").topic("test")
                .sender(signer.publicKey()).senderSeq(1).expiresAt(expiry).body(body)
                .messageId(AppMessage.computeMessageId("wire", "test", signer.publicKey(), 1, expiry, body))
                .authScheme(0).authProof(signer.sign(signed)).build();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "wire", 1, new byte[32], 0,
                new byte[0], System.currentTimeMillis(), new byte[32], new byte[32],
                List.of(message), signer.publicKey(), FinalityCert.empty());
        byte[] encoded = AppBlockCodec.serialize(block);
        Path persisted = directory.resolve("block.cbor");
        Files.write(persisted, encoded);
        var received = new CompletableFuture<AppMessage>();
        var negotiated = new CompletableFuture<Long>();
        var ready = new CompletableFuture<Void>();
        var fetched = new CompletableFuture<List<byte[]>>();
        var heartbeat = new CompletableFuture<Integer>();
        var config = AppMsgSubmissionConfig.builder().chainIds(Set.of("wire")).build();
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        NodeServer server = NodeServer.appOnly(port, 424242, List.of(() -> {
            var agent = new AppMsgSubmissionServerAgent(config);
            agent.addListener(new AppMsgSubmissionListener() {
                @Override public void handleReplyMessages(MsgReplyMessages reply) {
                    reply.getMessages().forEach(received::complete);
                }
            });
            return agent;
        }, () -> new AppChainSyncServerAgent((chain, from, to) -> {
            try {
                if (!chain.equals("wire") || from != 1 || to != 1) {
                    throw new AssertionError("Unexpected catch-up request");
                }
                return new AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(Files.readAllBytes(persisted)), 1);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        })));
        server.setConnectionListener(new ServerConnectionListener() {
            @Override public void onHandshakeComplete(Channel channel, AcceptVersion accepted) {
                negotiated.complete((long) accepted.getVersionNumber());
            }
        });
        var gossip = new AppMsgSubmissionAgent(config);
        gossip.addListener(new AppMsgSubmissionListener() {
            @Override public void handleInitAck(MsgInitAck ack) { ready.complete(null); }
            @Override public void handleRequestMessageIds(MsgRequestMessageIds request) { gossip.sendNextMessage(); }
            @Override public void handleRequestMessages(MsgRequestMessages request) { gossip.sendNextMessage(); }
        });
        var sync = new AppChainSyncClientAgent();
        sync.addListener(new AppChainSyncListener() {
            @Override public void blocksReceived(List<byte[]> blocks, long tip) {
                if (tip == 1) fetched.complete(blocks);
                else fetched.completeExceptionally(new AssertionError("Wrong tip: " + tip));
            }
        });
        var keepalive = new KeepAliveAgent(true);
        keepalive.addListener(response -> heartbeat.complete(response.getCookie()));
        var handshake = new HandshakeAgent(N2NVersionTableConstant.appOnly(424242, true), true);
        handshake.addListener(new HandshakeAgentListener() {
            @Override public void handshakeOk() { gossip.sendNextMessage(); }
        });
        var client = new TCPNodeClient("127.0.0.1", port, handshake, gossip, sync, keepalive);
        Thread serverThread = Thread.ofPlatform().name("lean-app-server").unstarted(server::start);
        Thread clientThread = Thread.ofPlatform().name("lean-app-client").unstarted(client::start);
        try {
            serverThread.start();
            clientThread.start();
            assertThat(negotiated.get(10, TimeUnit.SECONDS)).isEqualTo(101);
            ready.get(10, TimeUnit.SECONDS);
            assertThat(gossip.enqueueMessage(message)).isTrue();
            AppMessage delivered = received.get(10, TimeUnit.SECONDS);
            assertThat(delivered.hasValidMessageId()).isTrue();
            assertThat(AppMessageSigner.verify(delivered.getAuthProof(), delivered.signedBodyBytes(),
                    delivered.getSender())).isTrue();
            assertThat(delivered.getBody()).isEqualTo(body);
            assertThat(sync.requestRange("wire", 1, 1)).isTrue();
            List<byte[]> blocks = fetched.get(10, TimeUnit.SECONDS);
            assertThat(blocks).hasSize(1);
            assertThat(blocks.getFirst()).isEqualTo(encoded);
            assertThat(AppBlockCodec.deserialize(blocks.getFirst()).messages()).containsExactly(message);
            keepalive.sendKeepAlive(245);
            assertThat(heartbeat.get(10, TimeUnit.SECONDS)).isEqualTo(245);
        } finally {
            client.shutdown();
            server.shutdown();
            clientThread.interrupt();
            serverThread.interrupt();
            clientThread.join(5000);
            serverThread.join(5000);
            assertThat(clientThread.isAlive()).isFalse();
            assertThat(serverThread.isAlive()).isFalse();
        }
    }
}
