package org.yanoproject.x.framework.api.execution;

/**
 * Entry point of a runtime implementation: the provider of the {@code execution-runtime} plugin
 * contribution kind.
 *
 * <p>Declare it in the bundle manifest ({@code "kind": "execution-runtime"}, with the
 * {@link #runtimeId()} as the contribution name) and in
 * {@code META-INF/services/org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory}. The host
 * hands it to execution-backed state machines through {@link ExecutionRuntimeResolver}.
 */
public interface ExecutionRuntimeFactory {

    /**
     * Stable runtime identifier, for example {@code graaljs} or {@code chicory}. It is pinned in
     * genesis descriptors, so it must never change for a given engine family.
     * Grammar: {@code [a-z][a-z0-9.-]{0,63}}.
     */
    String runtimeId();

    /**
     * Opens a runtime. Opening may be expensive (an engine, a compiler cache); callers share one
     * runtime per node and close it on shutdown.
     *
     * @throws ExecutionEngineException if the engine cannot start on this JVM
     */
    ExecutionRuntime open(RuntimeOptions options);
}
