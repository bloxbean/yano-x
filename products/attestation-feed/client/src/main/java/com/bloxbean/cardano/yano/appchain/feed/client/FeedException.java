package com.bloxbean.cardano.yano.appchain.feed.client;

/** Failure of an attestation feed starter operation, classified for the CLI's ADR-047 exit codes. */
public final class FeedException extends RuntimeException {
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

    public FeedException(Error error, String message) {
        super(message);
        this.error = error;
    }

    public FeedException(Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public Error error() {
        return error;
    }

    public int exitCode() {
        return error.exitCode();
    }

    public static FeedException usage(String message) {
        return new FeedException(Error.USAGE, message);
    }

    public static FeedException unavailable(String message) {
        return new FeedException(Error.UNAVAILABLE, message);
    }

    public static FeedException invalid(String message) {
        return new FeedException(Error.INVALID, message);
    }

    public static FeedException malformed(String message) {
        return new FeedException(Error.MALFORMED_RESPONSE, message);
    }
}
