package org.yanoproject.x.framework.api.execution;

/** Deterministic reasons a guest stops abnormally. */
public enum TrapCode {
    /**
     * The guest raised an error or exception that it did not handle: a JS {@code throw}, a WASM
     * {@code unreachable} (where a Rust panic ends).
     */
    GUEST_ERROR,
    /**
     * A machine fault: a WASM integer divide by zero, an out-of-bounds memory access, a bad indirect
     * call, or a byte range passed to the host that lies outside guest memory.
     */
    GUEST_FAULT,
    /** The guest hit the deterministic memory limit. Only for {@link MemoryModel#DETERMINISTIC_LIMIT}. */
    MEMORY_LIMIT,
    /** The guest hit a deterministic call-depth limit enforced by the runtime itself. */
    CALL_DEPTH,
    /** The guest's return value is not a byte string. */
    BAD_RETURN
}
