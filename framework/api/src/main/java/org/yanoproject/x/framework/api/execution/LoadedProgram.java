package org.yanoproject.x.framework.api.execution;

import java.util.Set;

/** A validated program. Thread-safe; cheap to instantiate many times. */
public interface LoadedProgram extends AutoCloseable {

    /** Entry points this program exports. Always contains {@link InstanceConfig#requiredExports()}. */
    Set<String> exports();

    /**
     * Creates a fresh instance with no state from any earlier instance.
     *
     * <p>The compute budget is fixed per instance because some engines (GraalJS) set their meter's
     * limit when the instance is created. Every invocation of the instance gets the whole budget:
     * the meter starts at zero for each one.
     *
     * @param host the host functions for this instance's invocations
     * @param computeBudget compute units allowed for each invocation, in {@link MeteringModel#unit()};
     *        at least 1
     * @throws IllegalArgumentException if {@code computeBudget} is below 1
     */
    ProgramInstance instantiate(HostCalls host, long computeBudget);

    @Override
    void close();
}
