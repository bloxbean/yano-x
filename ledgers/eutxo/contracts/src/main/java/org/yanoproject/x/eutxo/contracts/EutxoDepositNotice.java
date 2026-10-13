package org.yanoproject.x.eutxo.contracts;

import java.util.Objects;

/**
 * What happened to a stable vault deposit the bridge did not credit as asked. The ledger records one notice per
 * accepted L1 outpoint, never more, so a deposit whose value stays in the vault, or whose L2 key binding was not
 * applied, has a committed trace instead of disappearing silently.
 */
public record EutxoDepositNotice(
        EutxoOutpoint acceptedOutpoint,
        Outcome outcome,
        String reason,
        long height
) {
    /** UNCREDITED: the value stays in the vault. CREDITED_WITHOUT_KEY_BINDING: credited, no L2 key registered. */
    public enum Outcome {
        UNCREDITED,
        CREDITED_WITHOUT_KEY_BINDING
    }

    public EutxoDepositNotice {
        Objects.requireNonNull(acceptedOutpoint, "acceptedOutpoint");
        Objects.requireNonNull(outcome, "outcome");
        reason = EutxoIgnoredConfirmations.reasonCode(reason);
        if (height < 0) {
            throw new IllegalArgumentException("notice height cannot be negative");
        }
    }

    public byte[] encode() {
        return EutxoCbor.encodeDepositNotice(this);
    }

    public static EutxoDepositNotice decode(byte[] bytes) {
        return EutxoCbor.decodeDepositNotice(bytes);
    }
}
