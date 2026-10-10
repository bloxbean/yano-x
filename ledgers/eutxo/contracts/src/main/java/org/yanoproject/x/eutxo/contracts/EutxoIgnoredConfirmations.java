package org.yanoproject.x.eutxo.contracts;

import java.util.Objects;

/**
 * A bounded trace of withdrawal confirmations the ledger ignored because it could not authenticate them: how many,
 * and the last one. Anyone can fabricate such a confirmation, so ignoring it keeps the bridge live; a count that
 * keeps growing while claims stay pending is how an operator sees that genuine settlements are being ignored too,
 * for example because vault custody tracking broke.
 */
public record EutxoIgnoredConfirmations(
        long count,
        String lastSettlementTransactionId,
        String lastReason,
        long lastHeight
) {
    public EutxoIgnoredConfirmations {
        if (count < 1 || lastHeight < 0) {
            throw new IllegalArgumentException("ignored confirmation count must be positive");
        }
        lastSettlementTransactionId = Objects.requireNonNull(
                lastSettlementTransactionId, "lastSettlementTransactionId").trim();
        if (!lastSettlementTransactionId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("settlement transaction id must be 32-byte lowercase hex");
        }
        lastReason = reasonCode(lastReason);
    }

    /** The next summary after one more ignored confirmation. */
    public static EutxoIgnoredConfirmations next(
            EutxoIgnoredConfirmations previous,
            String settlementTransactionId,
            String reason,
            long height
    ) {
        long count = previous == null ? 1 : Math.addExact(previous.count(), 1);
        return new EutxoIgnoredConfirmations(count, settlementTransactionId, reason, height);
    }

    static String reasonCode(String reason) {
        String normalized = Objects.requireNonNull(reason, "reason").trim();
        if (!normalized.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("reason must be an upper-case code");
        }
        return normalized;
    }

    public byte[] encode() {
        return EutxoCbor.encodeIgnoredConfirmations(this);
    }

    public static EutxoIgnoredConfirmations decode(byte[] bytes) {
        return EutxoCbor.decodeIgnoredConfirmations(bytes);
    }
}
