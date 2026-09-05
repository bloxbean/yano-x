package com.bloxbean.cardano.yano.appchain.dpp.client;

/** Failure of a DPP starter operation, classified for the CLI's ADR-047 exit codes. */
public final class DppException extends RuntimeException {
    public enum Error {
        USAGE(2), UNAVAILABLE(3), MALFORMED_RESPONSE(3), INVALID(4);

        private final int exitCode;

        Error(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private final Error error;

    public DppException(Error error, String message) {
        super(message);
        this.error = error;
    }

    public DppException(Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public Error error() {
        return error;
    }

    public int exitCode() {
        return error.exitCode();
    }

    public static DppException usage(String message) {
        return new DppException(Error.USAGE, message);
    }

    public static DppException unavailable(String message) {
        return new DppException(Error.UNAVAILABLE, message);
    }

    public static DppException invalid(String message) {
        return new DppException(Error.INVALID, message);
    }

    public static DppException malformed(String message) {
        return new DppException(Error.MALFORMED_RESPONSE, message);
    }
}
