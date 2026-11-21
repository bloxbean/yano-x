package org.yanoproject.x.framework.api.execution;

/**
 * A failure that is not a deterministic function of the program and its input: engine bugs, JVM
 * stack or heap exhaustion, a host bug, or a runtime that has been closed. Callers on the consensus
 * path must not turn this into a block result; the node stops applying instead of diverging.
 */
public class ExecutionEngineException extends RuntimeException {

    public ExecutionEngineException(String message) {
        super(message);
    }

    public ExecutionEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
