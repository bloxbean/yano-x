package org.yanoproject.x.framework.api.execution;

import java.util.Objects;

/**
 * Shape of one host function, as a runtime needs it to build the guest binding. The meaning, the
 * argument limits and the fuel cost belong to the host.
 *
 * <p>Every argument is a byte string; integers and booleans are encoded by the ABI. This keeps the
 * runtime generic: adding a host function to an ABI never changes a runtime, only the table.
 *
 * @param index position in {@link InstanceConfig#hostFunctions()}
 * @param name import name, for example {@code state_get}. Grammar: {@code [a-z][a-z0-9_]{0,31}}.
 * @param arity number of byte-string arguments, 0 to 4
 * @param returns what the function returns to the guest
 */
public record HostFunction(int index, String name, int arity, Returns returns) {

    public enum Returns {
        /** Nothing. */
        NOTHING,
        /** Always a byte string. */
        BYTES,
        /** A byte string or "absent". {@link HostCalls#call} returns {@code null} for absent. */
        OPTIONAL_BYTES
    }

    public HostFunction {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returns, "returns");
        if (!name.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("invalid host function name: " + name);
        }
        if (arity < 0 || arity > 4) {
            throw new IllegalArgumentException("host function arity must be 0..4");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
    }
}
