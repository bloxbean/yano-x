package org.yanoproject.x.framework.api.execution;

/**
 * An open execution engine. Thread-safe: programs may be loaded and instances created and used
 * concurrently, but one {@link ProgramInstance} is used by one thread at a time.
 */
public interface ExecutionRuntime extends AutoCloseable {

    /** Identity, metering model and fingerprint inputs of this engine. Constant for its lifetime. */
    RuntimeDescriptor descriptor();

    /**
     * Whether this runtime, on this JVM and with these options, is fit to serve a profile.
     * For example a Truffle runtime on an interpreter-only JVM is {@link Readiness.Status#DEGRADED}
     * for {@link ExecutionProfile#CONSENSUS}. Callers decide whether to refuse a degraded runtime.
     */
    Readiness readiness(ExecutionProfile profile);

    /**
     * Validates and prepares a program. Validation is deterministic: the same artifact is either
     * accepted or rejected with the same code on every node running the same engine fingerprint.
     *
     * @throws ProgramRejectedException if the artifact is malformed, uses a forbidden feature (for
     *         example floating point in WASM), imports an unknown host function, or exceeds a limit
     */
    LoadedProgram load(ProgramArtifact artifact, InstanceConfig config) throws ProgramRejectedException;

    @Override
    void close();
}
