package org.yanoproject.x.framework.api.execution;

/**
 * Host side of the guest binding, supplied by the caller for each instance. The host validates
 * arguments, charges host fuel, and performs the call.
 *
 * <p>Implementations throw {@link HostAbort} to end the invocation (host fuel exhausted, an
 * argument over a limit, a function not allowed in the current entry point). Any other exception is
 * a host bug and the runtime must surface it as {@link ExecutionEngineException}.
 */
@FunctionalInterface
public interface HostCalls {

    /**
     * Calls host function {@code index} with exactly {@code arity} arguments.
     *
     * @return the result; {@code null} for {@link HostFunction.Returns#NOTHING} and for an absent
     *         {@link HostFunction.Returns#OPTIONAL_BYTES} value. The runtime must copy the returned
     *         bytes into the guest and must not retain the array.
     */
    byte[] call(int index, byte[][] args);
}
