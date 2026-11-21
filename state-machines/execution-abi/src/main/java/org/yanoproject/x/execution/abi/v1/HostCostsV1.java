package org.yanoproject.x.execution.abi.v1;

/**
 * Host fuel costs of ABI v1, in host units. Host units are the same for every runtime, so a guest's
 * host-fuel use depends only on the calls it makes, never on the engine.
 *
 * <p>A call is charged {@link #CALL} plus {@link #PER_INPUT_BYTE} for every argument byte before it
 * runs, then the function's extra cost, then {@link #PER_OUTPUT_BYTE} for every byte returned.
 */
public final class HostCostsV1 {

    public static final long CALL = 100;
    public static final long PER_INPUT_BYTE = 1;
    public static final long PER_OUTPUT_BYTE = 1;
    /** Extra for {@code state_put} and {@code state_delete}: storage is the scarce resource. */
    public static final long STATE_WRITE = 400;
    /** Extra per value byte written by {@code state_put}, on top of the input-byte charge. */
    public static final long PER_VALUE_BYTE_WRITTEN = 2;
    public static final long EFFECT_EMIT = 2000;
    public static final long HASH = 200;
    public static final long LOG = 0;

    private HostCostsV1() {
    }
}
