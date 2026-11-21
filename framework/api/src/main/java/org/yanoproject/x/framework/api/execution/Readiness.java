package org.yanoproject.x.framework.api.execution;

import java.util.Objects;

/** Whether a runtime can serve a profile on this node, and why not. */
public record Readiness(Status status, String reason) {

    public enum Status { READY, DEGRADED, UNAVAILABLE }

    public Readiness {
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
    }

    public static Readiness ready() {
        return new Readiness(Status.READY, "");
    }

    public static Readiness degraded(String reason) {
        return new Readiness(Status.DEGRADED, reason);
    }

    public static Readiness unavailable(String reason) {
        return new Readiness(Status.UNAVAILABLE, reason);
    }
}
