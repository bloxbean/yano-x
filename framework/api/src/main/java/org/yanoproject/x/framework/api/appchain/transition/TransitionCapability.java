package org.yanoproject.x.framework.api.appchain.transition;

/** A deterministic, side-effect-free command evaluator over explicit facts. */
@FunctionalInterface
public interface TransitionCapability<C, F> {
    TransitionDecision decide(C command, TransitionContext context, F facts);
}
