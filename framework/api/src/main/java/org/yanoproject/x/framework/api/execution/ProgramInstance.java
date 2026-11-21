package org.yanoproject.x.framework.api.execution;

/**
 * One guest instance. Not thread-safe.
 *
 * <p>An instance may serve several invocations; guest memory persists between them, and callers
 * that need isolation create a new instance. After any result other than
 * {@link ExecutionResult.Completed}, the instance is poisoned and every later {@link #invoke}
 * throws {@link IllegalStateException}.
 */
public interface ProgramInstance extends AutoCloseable {

    /**
     * Calls an exported entry point under {@link #computeBudget()}, metered from zero.
     *
     * @param export entry point name defined by the guest ABI
     * @param input the entry point's input bytes
     * @return the deterministic outcome
     * @throws ExecutionEngineException for any non-deterministic failure
     * @throws IllegalArgumentException if {@code export} is not exported
     * @throws IllegalStateException if the instance is poisoned or closed
     */
    ExecutionResult invoke(String export, byte[] input);

    /** The compute budget each invocation gets, fixed when the instance was created. */
    long computeBudget();

    /** True until a non-completed result or {@link #close()}. */
    boolean usable();

    @Override
    void close();
}
