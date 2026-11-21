package org.yanoproject.x.execution.tck;

/** A TCK check did not hold. */
public final class TckFailure extends RuntimeException {
    public TckFailure(String message) {
        super(message);
    }

    public TckFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
