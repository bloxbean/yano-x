package org.yanoproject.x.framework.api.execution;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Deterministic outcome of one invocation. {@link #computeUsed()} is diagnostic only: not every
 * engine can report it, so it never becomes consensus data.
 */
public sealed interface ExecutionResult {

    OptionalLong computeUsed();

    /** The entry point returned normally. The output has not been validated against the ABI yet. */
    record Completed(byte[] output, OptionalLong computeUsed) implements ExecutionResult {
        public Completed {
            output = Objects.requireNonNull(output, "output").clone();
            Objects.requireNonNull(computeUsed, "computeUsed");
        }

        @Override
        public byte[] output() {
            return output.clone();
        }
    }

    /** The guest stopped abnormally. {@code detail} is diagnostic text and may differ between nodes. */
    record Trapped(TrapCode code, String detail, OptionalLong computeUsed) implements ExecutionResult {
        public Trapped {
            Objects.requireNonNull(code, "code");
            detail = detail == null ? "" : detail;
            Objects.requireNonNull(computeUsed, "computeUsed");
        }
    }

    /** The compute budget ran out. */
    record OutOfCompute(long budget, OptionalLong computeUsed) implements ExecutionResult {
        public OutOfCompute {
            Objects.requireNonNull(computeUsed, "computeUsed");
        }
    }

    /** A host function threw {@link HostAbort}. */
    record HostAborted(HostAbort abort, OptionalLong computeUsed) implements ExecutionResult {
        public HostAborted {
            Objects.requireNonNull(abort, "abort");
            Objects.requireNonNull(computeUsed, "computeUsed");
        }
    }
}
