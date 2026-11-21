package org.yanoproject.x.framework.api.execution;

/**
 * What an execution is used for. Only {@link #CONSENSUS} is implemented (ADR-058 D16); the others are
 * reserved so that the SPI does not need to change when they are.
 */
public enum ExecutionProfile {
    /** Block transitions and anything else whose result every member must reproduce. */
    CONSENSUS,
    /** Off-consensus reads such as committed-state queries and admission checks. Reserved. */
    OBSERVER,
    /** Effect execution with external I/O through host functions. Reserved. */
    EFFECT
}
