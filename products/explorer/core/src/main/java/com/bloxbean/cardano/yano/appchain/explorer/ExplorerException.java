package com.bloxbean.cardano.yano.appchain.explorer;

/** Failure of the explorer with the ADR-047 exit-code family it maps to. */
public final class ExplorerException extends RuntimeException {
    public enum Error {
        /** Bad caller input: exit 2. */
        USAGE,
        /** Node or database unreachable, or evidence not retained: exit 3. */
        UNAVAILABLE,
        /** Node data or a bundle that does not verify: exit 4. */
        INVALID,
        /** The database belongs to another chain or identity: exit 4. */
        IDENTITY_MISMATCH,
        /** A node response the decoders reject: exit 3. */
        MALFORMED_RESPONSE
    }

    private final Error error;

    public ExplorerException(Error error, String message) {
        this(error, message, null);
    }

    public ExplorerException(Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public Error error() {
        return error;
    }

    public int exitCode() {
        return switch (error) {
            case USAGE -> 2;
            case UNAVAILABLE, MALFORMED_RESPONSE -> 3;
            case INVALID, IDENTITY_MISMATCH -> 4;
        };
    }
}
