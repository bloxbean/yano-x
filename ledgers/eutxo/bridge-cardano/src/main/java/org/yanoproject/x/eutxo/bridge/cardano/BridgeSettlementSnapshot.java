package org.yanoproject.x.eutxo.bridge.cardano;

/** Bounded node-local counters suitable for health, metrics, and console adapters. */
public record BridgeSettlementSnapshot(
        long reconcileAttempts,
        long signedTransactions,
        long submissions,
        long retrySameTransaction,
        long signerFailures,
        long parked,
        long confirmed
) {
}
