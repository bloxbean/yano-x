package org.yanoproject.x.framework.api.execution;

import java.util.Objects;

/**
 * How a runtime meters guest compute. Compute units are runtime-specific: a GraalJS statement and
 * a WASM instruction are not comparable, so genesis budgets are expressed in the pinned runtime's
 * own unit. Host-function costs are metered separately by the host, in ABI-defined units that are
 * the same for every runtime.
 *
 * @param unit stable unit name, for example {@code graaljs.statement} or {@code wasm.instruction}
 * @param exactExhaustion true if the guest stops at exactly the budget, so the exhaustion point is
 *        itself deterministic (required for {@link ExecutionProfile#CONSENSUS})
 * @param reportsUsage true if {@link ExecutionResult#computeUsed()} is available. Usage is never
 *        consensus data, because not every engine can report it.
 */
public record MeteringModel(String unit, boolean exactExhaustion, boolean reportsUsage) {

    public MeteringModel {
        Objects.requireNonNull(unit, "unit");
        if (!unit.matches("[a-z][a-z0-9.-]{0,63}")) {
            throw new IllegalArgumentException("invalid metering unit: " + unit);
        }
    }
}
