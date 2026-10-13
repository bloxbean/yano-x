package org.yanoproject.x.examples.evidence.demo;

/**
 * Runner exception whose message never reflects external or secret data. An optional reason is a fixed
 * upper-case token chosen by the runner, never a value read from a node or connector.
 */
public final class DemoException extends RuntimeException {
    private final DemoError error;
    private final String reason;

    public DemoException(DemoError error) {
        this(error, null);
    }

    public DemoException(DemoError error, String reason) {
        super(error.name(), null, false, false);
        if (reason != null && !reason.matches("[A-Z][A-Z_]{0,39}")) {
            throw new IllegalArgumentException("reason must be a fixed upper-case token");
        }
        this.error = error;
        this.reason = reason;
    }

    public DemoError error() {
        return error;
    }

    /** The fixed reason token, or null. */
    public String reason() {
        return reason;
    }
}
