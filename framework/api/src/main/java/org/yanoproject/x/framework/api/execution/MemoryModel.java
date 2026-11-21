package org.yanoproject.x.framework.api.execution;

/** How a runtime bounds guest memory. */
public enum MemoryModel {
    /**
     * The runtime enforces {@link InstanceConfig#memoryLimitBytes()} deterministically: running out
     * is a {@link ExecutionResult.Trapped} with {@link TrapCode#MEMORY_LIMIT} at the same point on
     * every node. WASM linear memory with a fixed page maximum is the example.
     */
    DETERMINISTIC_LIMIT,
    /**
     * The runtime cannot bound memory deterministically (a garbage-collected guest heap). Running
     * out must surface as {@link ExecutionEngineException}, never as a result. Compute metering
     * bounds allocation indirectly.
     */
    NOT_ENFORCED
}
