package org.yanoproject.x.execution.abi.v1;

import org.yanoproject.x.framework.api.execution.HostFunction;
import org.yanoproject.x.framework.api.execution.HostFunction.Returns;

import java.util.List;

/**
 * The ABI v1 host function table, in index order. Guests import these by name from the
 * {@code yano} namespace. The table is append-only: a later ABI may add functions at the end but
 * never renumber, rename or reshape one.
 */
public final class HostTableV1 {

    public static final int STATE_GET = 0;
    public static final int STATE_PUT = 1;
    public static final int STATE_DELETE = 2;
    public static final int EFFECT_EMIT = 3;
    public static final int BLAKE2B_256 = 4;
    public static final int SHA2_256 = 5;
    public static final int LOG = 6;

    public static final List<HostFunction> FUNCTIONS = List.of(
            new HostFunction(STATE_GET, "state_get", 1, Returns.OPTIONAL_BYTES),
            new HostFunction(STATE_PUT, "state_put", 2, Returns.NOTHING),
            new HostFunction(STATE_DELETE, "state_delete", 1, Returns.NOTHING),
            new HostFunction(EFFECT_EMIT, "effect_emit", 1, Returns.NOTHING),
            new HostFunction(BLAKE2B_256, "blake2b_256", 1, Returns.BYTES),
            new HostFunction(SHA2_256, "sha2_256", 1, Returns.BYTES),
            new HostFunction(LOG, "log", 2, Returns.NOTHING));

    private HostTableV1() {
    }
}
