package org.yanoproject.x.explorer;

import java.util.Objects;

/** One finalized message with its body captured at ingest. */
public record IndexedMessage(
        long height,
        int index,
        String messageIdHex,
        String topic,
        String senderHex,
        long senderSeq,
        long expiresAt,
        String bodyHex,
        int authScheme,
        String authProofHex,
        State state
) {
    /** How much of the message the index holds. */
    public enum State {
        /** Full envelope from the canonical block. */
        FULL,
        /** A retention tombstone: the node stripped the body and the auth proof. */
        TOMBSTONE,
        /** Fields from the node's JSON view; the canonical envelope was not obtained. */
        JSON
    }

    public IndexedMessage {
        if (height < 1 || index < 0) throw new IllegalArgumentException("invalid message position");
        messageIdHex = Objects.requireNonNull(messageIdHex, "messageIdHex");
        if (!messageIdHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("messageIdHex must be 32-byte canonical hex");
        }
        topic = Objects.requireNonNullElse(topic, "");
        senderHex = Objects.requireNonNullElse(senderHex, "");
        bodyHex = Objects.requireNonNullElse(bodyHex, "");
        authProofHex = Objects.requireNonNullElse(authProofHex, "");
        state = Objects.requireNonNull(state, "state");
    }

    public byte[] body() {
        return java.util.HexFormat.of().parseHex(bodyHex);
    }
}
