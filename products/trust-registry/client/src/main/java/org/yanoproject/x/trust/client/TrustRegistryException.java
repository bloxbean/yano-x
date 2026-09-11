package org.yanoproject.x.trust.client;

/** Failure talking to or interpreting a registry node. */
public final class TrustRegistryException extends RuntimeException {
    /** USAGE is the caller's mistake before anything was submitted; INVALID is a refused write. */
    public enum Error { UNAVAILABLE, MALFORMED_RESPONSE, NOT_FINALIZED, INVALID, USAGE }

    public static TrustRegistryException usage(String message) {
        return new TrustRegistryException(Error.USAGE, message);
    }

    public static TrustRegistryException invalid(String message) {
        return new TrustRegistryException(Error.INVALID, message);
    }

    public static TrustRegistryException unavailable(String message) {
        return new TrustRegistryException(Error.UNAVAILABLE, message);
    }

    private final Error error;

    public TrustRegistryException(Error error, String message) {
        super(message);
        this.error = error;
    }

    public TrustRegistryException(Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
