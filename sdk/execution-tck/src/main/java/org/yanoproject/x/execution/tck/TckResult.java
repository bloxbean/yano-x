package org.yanoproject.x.execution.tck;

/** Outcome of one TCK check. A {@link Status#WARN} is a known gap that does not fail conformance. */
public record TckResult(String id, Status status, String detail) {
    public enum Status { PASS, WARN, FAIL }
}
