package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainEngineSignatureBatchTest {
    private static final String CHAIN = "signature-batch-chain";

    @Test
    void smallBatchVerifiedOnTheCallingThreadMatchesOneByOneVerification() {
        assertBatchMatchesOneByOne(5);
    }

    @Test
    void largeBatchVerifiedInParallelMatchesOneByOneVerification() {
        assertBatchMatchesOneByOne(200);
    }

    private static void assertBatchMatchesOneByOne(int size) {
        AppMessageSigner signer = new AppMessageSigner("07".repeat(32));
        AppMessageSigner other = new AppMessageSigner("08".repeat(32));
        List<AppMessage> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            AppMessage message = signed(signer, i + 1);
            if (i % 7 == 3) {
                byte[] proof = message.getAuthProof().clone();
                proof[0] ^= 1;
                message = copy(message, message.getSender(), proof);
            } else if (i % 11 == 5) {
                message = copy(message, message.getSender(), other.sign(message.signedBodyBytes()));
            } else if (i % 13 == 6) {
                message = copy(message, new byte[31], message.getAuthProof());
            }
            messages.add(message);
        }

        Set<AppMessage> signed = AppChainEngine.signedEnvelopes(messages);

        for (AppMessage message : messages) {
            boolean expected = message.getSender().length == 32 && AppMessageSigner.verify(
                    message.getAuthProof(), message.signedBodyBytes(), message.getSender());
            assertThat(signed.contains(message)).as("message %d", message.getSenderSeq()).isEqualTo(expected);
        }
        assertThat(signed).hasSizeLessThan(size).isNotEmpty();
    }

    private static AppMessage copy(AppMessage message, byte[] sender, byte[] authProof) {
        return AppMessage.builder()
                .messageId(message.getMessageId())
                .chainId(message.getChainId())
                .topic(message.getTopic())
                .sender(sender)
                .senderSeq(message.getSenderSeq())
                .expiresAt(message.getExpiresAt())
                .body(message.getBody())
                .authScheme(message.getAuthScheme())
                .authProof(authProof)
                .build();
    }

    private static AppMessage signed(AppMessageSigner signer, long senderSeq) {
        String topic = "test";
        byte[] body = ("payload-" + senderSeq).getBytes(StandardCharsets.UTF_8);
        long expiresAt = 4_000_000_000L;
        byte[] signedBody = AppMessage.signedBodyBytes(CHAIN, topic, signer.publicKey(), senderSeq, expiresAt, body);
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(CHAIN, topic, signer.publicKey(), senderSeq, expiresAt, body))
                .chainId(CHAIN)
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(senderSeq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }
}
