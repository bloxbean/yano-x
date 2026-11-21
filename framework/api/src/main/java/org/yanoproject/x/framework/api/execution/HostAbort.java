package org.yanoproject.x.framework.api.execution;

import java.util.Objects;

/**
 * Thrown by a host function to end the current invocation deterministically.
 *
 * <p>Runtimes must stop the guest and report {@link ExecutionResult.HostAborted} with this exact
 * object, even if the guest language would let guest code catch a host exception. A guest that
 * catches the abort and returns normally must still be reported as aborted.
 */
public final class HostAbort extends RuntimeException {

    public enum Kind {
        /** The invocation's host fuel is spent. */
        HOST_FUEL_EXHAUSTED,
        /** The guest broke the ABI: bad arguments, a limit exceeded, or a call not allowed here. */
        ABI_VIOLATION
    }

    private final Kind kind;
    private final String code;

    public HostAbort(Kind kind, String code, String message) {
        super(message, null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
    }

    public Kind kind() {
        return kind;
    }

    /** Deterministic reason code from the guest ABI, for example {@code value-too-large}. */
    public String code() {
        return code;
    }
}
