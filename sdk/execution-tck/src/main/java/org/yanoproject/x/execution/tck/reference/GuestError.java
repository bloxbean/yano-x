package org.yanoproject.x.execution.tck.reference;

/** Thrown by a reference guest to stop with an unhandled guest error, like a JS throw or WASM trap. */
public class GuestError extends RuntimeException {
    public GuestError(String message) {
        super(message);
    }
}
