package com.bloxbean.cardano.yano.appchain.trust.client;

/** Failure talking to or interpreting a registry node. */
public final class TrustRegistryException extends RuntimeException {
    public enum Error { UNAVAILABLE, MALFORMED_RESPONSE, NOT_FINALIZED, INVALID }

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
