package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationsTracker;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The operational face of one periodic settlement task (ADR-059 D15a): the {@code Accept} batcher, the exit relayer
 * or the reconciliation. It owns no effect type, like the co-sign-only product; its only role is the framework's
 * executor snapshot, which the app-chain status lists under {@code executorOperations} and the metrics endpoint
 * exports as {@code yano_appchain_effects_executor_*} with the product's id. The task's timer belongs to the
 * executor product that owns the tasks, so closing this product does nothing.
 *
 * <p>Each pass is recorded once: a pass that submitted something is a success; a pass that could not act (no anchor,
 * no funds, an incomplete co-sign round, an unavailable view) is a retryable failure; a pass that threw is a retryable
 * {@code INTERNAL} failure; a pass with nothing to do is not counted.
 */
final class TaskProduct implements AppEffectExecutor {
    static final String ACCEPT = "asset-settlement-accept";
    static final String EXIT_RELAY = "asset-settlement-exit-relay";
    static final String RECONCILIATION = "asset-reconciliation";

    /** What one pass did. */
    enum Pass { ACTED, IDLE, WAITING, FAILED, CONFLICT }

    private final String id;
    private final EffectExecutorOperationsTracker tracker = new EffectExecutorOperationsTracker();

    TaskProduct(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    void record(Pass pass) {
        EffectExecution execution = switch (pass) {
            case ACTED -> EffectExecution.confirmed(new byte[0]);
            case IDLE -> null;
            case WAITING -> EffectExecution.retry(Duration.ZERO);
            case FAILED -> EffectExecution.failed("INTERNAL_ERROR", true);
            case CONFLICT -> EffectExecution.failed("DESTINATION_CONFLICT", false);
        };
        if (execution != null) tracker.observe(() -> execution);
    }

    /** One pass's per-vault outcomes, the most significant first: acted, failed, waiting, else idle. */
    static Pass worst(List<String> outcomes, Predicate<String> acted, Predicate<String> idle) {
        boolean failed = false;
        boolean waiting = false;
        boolean any = false;
        for (String outcome : outcomes) {
            if (acted.test(outcome)) any = true;
            else if (outcome.contains(" failed: ") || outcome.contains("submission returned another")) failed = true;
            else if (!idle.test(outcome)) waiting = true;
        }
        return any ? Pass.ACTED : failed ? Pass.FAILED : waiting ? Pass.WAITING : Pass.IDLE;
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        return tracker.snapshot();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of();
    }

    @Override
    public boolean supports(String effectType) {
        return false;
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
        throw new IllegalStateException("the " + id + " task product owns no effect type");
    }
}
