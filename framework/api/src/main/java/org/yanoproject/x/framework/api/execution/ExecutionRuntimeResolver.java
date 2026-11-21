package org.yanoproject.x.framework.api.execution;

/**
 * Host-owned bridge that resolves an installed execution runtime through the selected plugin
 * catalog (ADR-058 D13). It returns only a runtime contributed under the {@code execution-runtime}
 * kind by a manifested bundle that the operator explicitly allow-listed, because a runtime decides
 * state roots and runs programs the operator did not write.
 *
 * <p>The returned factory is the catalog's facade: every call into it, its runtimes, programs and
 * instances runs under the owning bundle's callback scope. The genesis pin is the engine fingerprint,
 * which only an open runtime can report ({@link ExecutionRuntime#descriptor()}), so the caller checks
 * it right after {@link ExecutionRuntimeFactory#open(RuntimeOptions)} and before any execution.</p>
 *
 * <p>State machines cannot use this bridge to enumerate plugins, read node configuration or reach any
 * other host service. Never call it during deterministic execution.</p>
 */
@FunctionalInterface
public interface ExecutionRuntimeResolver {

    /**
     * Resolves the runtime whose {@link ExecutionRuntimeFactory#runtimeId()} is {@code runtimeId}.
     *
     * @throws IllegalStateException if no such runtime is installed, or if its bundle is not
     *         manifested or not explicitly allow-listed
     */
    ExecutionRuntimeFactory resolve(String runtimeId);
}
