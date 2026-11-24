package org.yanoproject.x.assets.contracts;

/** The kind of a claim leaf (ADR-059 D7, D8d). The ordinal is the wire code. */
public enum ClaimKind {
    /** A user withdrawal. The only kind `Settle` may pay. */
    USER_WITHDRAWAL(0),
    /** A governed sweep of one named unattributed UTxO. `Exit` only, never in exit mode. */
    SWEEP_UNATTRIBUTED(1),
    /** A governed sweep of operational ADA above the floor. `Exit` only, never in exit mode. */
    SWEEP_OPERATIONAL(2),
    /** A governed migration to a registered target vault (M4b). `Exit` only, never in exit mode. */
    MIGRATE(3);

    private final int code;

    ClaimKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean governed() {
        return this != USER_WITHDRAWAL;
    }

    public static ClaimKind fromCode(long code) {
        for (ClaimKind kind : values()) if (kind.code == code) return kind;
        throw new IllegalArgumentException("unknown claim kind " + code);
    }
}
