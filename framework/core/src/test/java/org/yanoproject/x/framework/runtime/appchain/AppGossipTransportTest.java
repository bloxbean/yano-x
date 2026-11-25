package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgValidator;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-060: transport sizing, priority lane, verified-envelope cache, parallel validation, pool-full retry. */
class AppGossipTransportTest {
    private static final String CHAIN = "gossip-chain";

    @Test
    void chainTransportUsesTheWideWindowAndPoolSizedQueues() {
        AppMsgSubmissionConfig sized = AppGossipTransport.sized(config(50_000, 4 * 1024 * 1024)).build();
        assertThat(sized.getBatchSize()).isEqualTo(2_048);
        assertThat(sized.getMaxUnacknowledged()).isEqualTo(2_048);
        assertThat(sized.getMaxBodiesBytesPerRequest()).isEqualTo(2 * 1024 * 1024);
        assertThat(sized.getMaxPendingMessages()).isEqualTo(50_000);
        assertThat(sized.getMaxPendingPriorityMessages()).isEqualTo(4_096);
        assertThat(sized.getMaxPendingPriorityBytes()).isEqualTo(16L * 1024 * 1024);

        AppMsgSubmissionConfig small = AppGossipTransport.sized(config(500, 100_000)).build();
        assertThat(small.getMaxPendingMessages()).isEqualTo(10_000);
    }

    @Test
    void sharedSessionCapacitiesAreSummedOverChains() {
        AppMsgSubmissionConfig union = AppGossipTransport.sizedForAll(
                List.of(config(50_000, 100_000), config(20_000, 200_000))).build();
        assertThat(union.getMaxPendingMessages()).isEqualTo(70_000);
        assertThat(union.getMaxPendingPriorityMessages()).isEqualTo(8_192);
        assertThat(union.getMaxPendingPriorityBytes()).isEqualTo(1_200_000);
    }

    @Test
    void onlyConsensusAndAnchorTopicsUseThePriorityLane() {
        var priority = AppGossipTransport.sized(config(10_000, 100_000)).build().getPriority();
        AppMessageSigner signer = new AppMessageSigner("11".repeat(32));
        assertThat(priority.test(signed(signer, "~consensus/commit", 1, "v"))).isTrue();
        assertThat(priority.test(signed(signer, "~consensus/propose", 2, "b"))).isTrue();
        assertThat(priority.test(signed(signer, "~anchor/cosign", 3, "a"))).isTrue();
        assertThat(priority.test(signed(signer, "~bridge/settle", 4, "s"))).isFalse();
        assertThat(priority.test(signed(signer, "~topics/registry", 5, "r"))).isFalse();
        assertThat(priority.test(signed(signer, "orders", 6, "o"))).isFalse();
    }

    @Test
    void envelopeCacheNeverAcceptsAForgedSignatureForAKnownId() {
        AppMessageSigner signer = new AppMessageSigner("12".repeat(32));
        AppMessageSigner other = new AppMessageSigner("13".repeat(32));
        AppMessage genuine = signed(signer, "orders", 1, "genuine");
        assertThat(AppMessageSigner.verifyEnvelope(genuine)).isTrue();
        assertThat(AppMessageSigner.verifyEnvelope(copy(genuine, genuine.getAuthProof()))).isTrue();

        byte[] flipped = genuine.getAuthProof().clone();
        flipped[10] ^= 1;
        assertThat(AppMessageSigner.verifyEnvelope(copy(genuine, flipped))).isFalse();
        assertThat(AppMessageSigner.verifyEnvelope(
                copy(genuine, other.sign(genuine.signedBodyBytes())))).isFalse();
        // A failed verification is not remembered: the same forged copy fails again.
        assertThat(AppMessageSigner.verifyEnvelope(copy(genuine, flipped))).isFalse();
    }

    @Test
    void envelopeCacheRechecksTheContentDerivedId() {
        AppMessageSigner signer = new AppMessageSigner("14".repeat(32));
        AppMessage genuine = signed(signer, "orders", 1, "body");
        assertThat(AppMessageSigner.verifyEnvelope(genuine)).isTrue();
        AppMessage tampered = AppMessage.builder()
                .messageId(genuine.getMessageId())
                .chainId(CHAIN).topic("orders").sender(genuine.getSender()).senderSeq(1)
                .expiresAt(genuine.getExpiresAt())
                .body("other".getBytes(StandardCharsets.UTF_8))
                .authScheme(genuine.getAuthScheme())
                .authProof(genuine.getAuthProof())
                .build();
        assertThat(AppMessageSigner.verifyEnvelope(tampered)).isFalse();
    }

    @Test
    void parallelValidationKeepsInputOrder() {
        AppMsgValidator validator = AppGossipTransport.parallel(m -> m.getSenderSeq() % 3 == 0
                ? AppMsgValidator.Result.reject("seq " + m.getSenderSeq())
                : AppMsgValidator.Result.accept());
        AppMessageSigner signer = new AppMessageSigner("15".repeat(32));
        for (int size : new int[]{5, 300}) {
            List<AppMessage> messages = new ArrayList<>();
            for (int i = 1; i <= size; i++) {
                messages.add(signed(signer, "orders", i, "m" + i));
            }
            List<AppMsgValidator.Result> results = validator.validateAll(messages);
            assertThat(results).hasSize(size);
            for (int i = 0; i < size; i++) {
                assertThat(results.get(i).isAccepted()).isEqualTo((i + 1) % 3 != 0);
            }
        }
    }

    @Test
    void poolFullRetryIsAdmittedOnceWithoutRelay() {
        SeenMessageIds seen = new SeenMessageIds(1_000);
        assertThat(seen.markSeen("aa", Long.MAX_VALUE)).isTrue();
        assertThat(seen.markSeen("aa", Long.MAX_VALUE)).isFalse();
        seen.forgetForRetry("aa", Long.MAX_VALUE);
        assertThat(seen.markSeen("aa", Long.MAX_VALUE)).isTrue();
        assertThat(seen.consumeRetry("aa")).isTrue();
        assertThat(seen.consumeRetry("aa")).isFalse();
        assertThat(seen.consumeRetry("bb")).isFalse();
    }

    @Test
    void verifiedEnvelopeCacheEvictsLeastRecentlyUsed() {
        VerifiedEnvelopeCache cache = new VerifiedEnvelopeCache(16 * 16);
        byte[] proof = new byte[64];
        for (int i = 0; i < 10_000; i++) {
            byte[] id = new byte[32];
            id[0] = (byte) i;
            id[1] = (byte) (i >> 8);
            cache.add(id, proof);
        }
        assertThat(cache.size()).isLessThanOrEqualTo(16 * 16);
        byte[] last = new byte[32];
        last[0] = (byte) 9_999;
        last[1] = (byte) (9_999 >> 8);
        assertThat(cache.contains(last, proof)).isTrue();
        byte[] first = new byte[32];
        assertThat(cache.contains(first, proof)).isFalse();
    }

    private static AppChainConfig config(int poolMax, long blockMaxBytes) {
        AppMessageSigner signer = new AppMessageSigner("16".repeat(32));
        return AppChainConfig.builder(CHAIN)
                .signingKeyHex("16".repeat(32))
                .memberKeysHex(Set.of(signer.publicKeyHex()))
                .proposerKeyHex(signer.publicKeyHex())
                .threshold(1)
                .maxBlockMessages(Math.min(poolMax, 100))
                .poolMaxMessages(poolMax)
                .blockMaxBytes(blockMaxBytes)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
    }

    private static AppMessage copy(AppMessage message, byte[] authProof) {
        return AppMessage.builder()
                .messageId(message.getMessageId())
                .chainId(message.getChainId()).topic(message.getTopic()).sender(message.getSender())
                .senderSeq(message.getSenderSeq()).expiresAt(message.getExpiresAt()).body(message.getBody())
                .authScheme(message.getAuthScheme()).authProof(authProof)
                .build();
    }

    private static AppMessage signed(AppMessageSigner signer, String topic, long seq, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        long expiresAt = 4_000_000_000L;
        byte[] signedBody = AppMessage.signedBodyBytes(CHAIN, topic, signer.publicKey(), seq, expiresAt, body);
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(CHAIN, topic, signer.publicKey(), seq, expiresAt, body))
                .chainId(CHAIN).topic(topic).sender(signer.publicKey()).senderSeq(seq).expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }
}
