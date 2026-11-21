package org.yanoproject.x.execution;

import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeResolver;

import java.util.Objects;
import java.util.Optional;

/**
 * Catalog entry point: {@code state-machine = execution}. The program, runtime and budgets come
 * from the chain's genesis-pinned descriptor; see {@link ExecutionSettings}. The runtime is an
 * {@code execution-runtime} plugin contribution, obtained through the host's
 * {@link ExecutionRuntimeResolver} (ADR-058 D13).
 */
public final class ExecutionStateMachineProvider implements AppStateMachineProvider {

    public static final String ID = "execution";

    private final ExecutionRuntimeResolver runtimes;

    /** Used by the catalog: runtimes come from the chain context's resolver. */
    public ExecutionStateMachineProvider() {
        this.runtimes = null;
    }

    /** For tests and embedders that supply runtime factories directly. */
    public ExecutionStateMachineProvider(ExecutionRuntimeResolver runtimes) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalArgumentException("the execution state machine needs chain settings; use create(context)");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        ExecutionSettings settings = ExecutionSettings.from(context.settings());
        ExecutionRuntimeResolver resolver = Optional.ofNullable(runtimes)
                .or(context::executionRuntimeResolver)
                .orElseThrow(() -> new IllegalStateException(
                        "this host offers no execution runtimes to state machines"));
        return new ExecutionBackedStateMachine(context.chainId(), settings,
                RuntimeRegistry.open(resolver.resolve(settings.descriptor().runtimeId()), settings.runtimeOptions()));
    }
}
