/**
 * Execution runtime SPI (ADR-058).
 *
 * <p>A runtime moves bytes between a guest program and the host. It knows how to load a program,
 * create instances, call an exported entry point with an input, meter the guest's own work, and
 * expose host functions to the guest by name. It knows nothing about blocks, state or effects:
 * the entry-point names, the input and output encodings and the host functions are defined by a
 * guest ABI (see {@code docs/execution/guest-abi-v1.md}), and their meaning is implemented by the host.
 *
 * <p>A runtime is a plugin contribution of kind {@code execution-runtime} (trust tier CONSENSUS):
 * its bundle declares the {@link org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory} in
 * its manifest, and the execution state machine obtains it only through the host's
 * {@link org.yanoproject.x.framework.api.execution.ExecutionRuntimeResolver}. A runtime proves it meets
 * the contract below by passing the execution TCK ({@code yano-x-execution-tck}).
 *
 * <p>Determinism contract for implementations:
 * <ul>
 *   <li>Every {@link org.yanoproject.x.framework.api.execution.ExecutionResult} must be a pure function of
 *       the program, the input, the compute budget and the host-call results. Anything that is
 *       not (JVM stack depth, heap exhaustion, internal engine errors) must be thrown as
 *       {@link org.yanoproject.x.framework.api.execution.ExecutionEngineException} and never reported as a
 *       result.</li>
 *   <li>Everything that can change results (engine version, metering unit, semantic options) must
 *       appear in {@link org.yanoproject.x.framework.api.execution.RuntimeDescriptor#fingerprintComponents()}.
 *       Node-local {@link org.yanoproject.x.framework.api.execution.RuntimeOptions} may tune performance
 *       only.</li>
 *   <li>A {@link org.yanoproject.x.framework.api.execution.HostAbort} thrown by a host function ends the
 *       invocation. The guest must not be able to observe or catch it.</li>
 * </ul>
 */
package org.yanoproject.x.framework.api.execution;
