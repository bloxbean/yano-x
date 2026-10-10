package org.yanoproject.x.eutxo.contracts;

import java.util.Objects;

/**
 * A bounded trace of withdrawal confirmations the ledger ignored because it could not authenticate them, or because
 * the bridge was halted: how many, and the last one. Anyone can fabricate such a confirmation, so ignoring it keeps
 * the bridge live, and anyone can also raise the count and overwrite the last entry. It is therefore a prompt, not
 * evidence: a count that grows while claims stay pending is a reason to check those claims' settlement
 * transactions on L1, for example because vault custody tracking broke.
 */
public record EutxoIgnoredConfirmations(
        long count,
        String lastSettlementTransactionId,
        String lastReason,
        long lastHeight
) {
    public EutxoIgnoredConfirmations {
        if (count < 1) {
            throw new IllegalArgumentException("ignored confirmation count must be positive");
        }
        if (lastHeight < 0) {
            throw new IllegalArgumentException("ignored confirmation height cannot be negative");
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
        // Saturates: a diagnostic counter must never be the reason an apply fails.
        long count = previous == null ? 1
                : previous.count() == Long.MAX_VALUE ? Long.MAX_VALUE : previous.count() + 1;
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
