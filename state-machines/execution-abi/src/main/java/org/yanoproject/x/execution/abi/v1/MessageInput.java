package org.yanoproject.x.execution.abi.v1;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * One app message as the guest sees it. {@code body} is the verified payload view when the topic
 * registry supplied one, otherwise the raw message body.
 *
 * @param blockIndex the message's original index in the app block; 0 for admission checks
 */
public record MessageInput(byte[] messageId, String topic, byte[] sender, long senderSeq,
                           long expiresAt, byte[] body, long blockIndex) {

    public MessageInput {
        messageId = messageId.clone();
        Objects.requireNonNull(topic, "topic");
        sender = sender.clone();
        body = body.clone();
        if (senderSeq < 0 || expiresAt < 0 || blockIndex < 0) {
            throw new IllegalArgumentException("invalid message input");
        }
    }

    Map<Long, Object> toCbor() {
        return Map.of(0L, messageId.clone(), 1L, topic, 2L, sender.clone(), 3L, senderSeq,
                4L, expiresAt, 5L, body.clone(), 6L, blockIndex);
    }

    static MessageInput fromCbor(Object value) {
        Fields f = Fields.of("message", value, Fields.keys(0, 1, 2, 3, 4, 5, 6));
        return new MessageInput(f.bytes(0), f.text(1), f.bytes(2), f.uint(3), f.uint(4), f.bytes(5), f.uint(6));
    }

    @Override public byte[] messageId() { return messageId.clone(); }
    @Override public byte[] sender() { return sender.clone(); }
    @Override public byte[] body() { return body.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof MessageInput m && Arrays.equals(messageId, m.messageId) && topic.equals(m.topic)
                && Arrays.equals(sender, m.sender) && senderSeq == m.senderSeq && expiresAt == m.expiresAt
                && Arrays.equals(body, m.body) && blockIndex == m.blockIndex;
    }

    @Override public int hashCode() {
        return Arrays.hashCode(messageId);
    }
}
