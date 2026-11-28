package org.yanoproject.x.assetpayments.ledger;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Node-local diagnostics of the {@code asset-payments} ledger (ADR-059 D15a), exposed through
 * {@code operationalStatus()} and never read by any transition. Every count is of what this node executed: a block
 * height that runs again (a round that did not finalize, then the finalized one) replaces its counts instead of adding
 * to them, and a restart starts from zero.
 *
 * <p>Counters are keyed {@code <area>.<what>[.<reason>]}, for example {@code deposits.uncredited.DEPOSITS_PAUSED},
 * {@code payouts.mismatch.RECIPIENT} or {@code dropped.deposit}; reasons are the contracts' enum names, so the key set
 * is bounded. {@code paidLatencyMs} is the reservation-to-{@code PAID} time of each confirmed claim, from the claim
 * leaf's {@code reservedAtMs} to the confirming block's timestamp (both block times). The per-vault backlog and the
 * domain status are read from state after each executed block.
 */
final class LedgerDiagnostics {
    private final TreeMap<String, Long> totals = new TreeMap<>();
    private final TreeMap<String, Long> pending = new TreeMap<>();
    private final long[] latencyTotals = new long[3];
    private final long[] latencyPending = new long[3];
    private long pendingHeight = -1;
    private boolean sealed;
    private Map<String, Object> vaults = Map.of();
    private String domainStatus = "UNKNOWN";

    /** Starts (or continues) the counts of one block height; a height that already completed runs anew. */
    synchronized void begin(long height) {
        if (height > pendingHeight) {
            fold();
        } else if (sealed || height < pendingHeight) {
            pending.clear();
            java.util.Arrays.fill(latencyPending, 0);
        }
        pendingHeight = height;
        sealed = false;
    }

    /** The block finished; its backlog and domain status as of its end. */
    synchronized void end(Map<String, Object> vaultBacklog, String domain) {
        sealed = true;
        vaults = Map.copyOf(vaultBacklog);
        domainStatus = domain;
    }

    synchronized void count(String key) {
        pending.merge(key, 1L, Long::sum);
    }

    synchronized void paidAfter(long latencyMs) {
        long latency = Math.max(0, latencyMs);
        latencyPending[0]++;
        latencyPending[1] = saturatedAdd(latencyPending[1], latency);
        latencyPending[2] = Math.max(latencyPending[2], latency);
    }

    synchronized Map<String, Object> snapshot() {
        TreeMap<String, Long> counters = new TreeMap<>(totals);
        pending.forEach((key, value) -> counters.merge(key, value, LedgerDiagnostics::saturatedAdd));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("scope", "node-local; counts of executed blocks since start, a re-executed height replaces its own");
        status.put("height", pendingHeight);
        status.put("counters", Map.copyOf(counters));
        status.put("paidLatencyMs", Map.of(
                "count", latencyTotals[0] + latencyPending[0],
                "sum", saturatedAdd(latencyTotals[1], latencyPending[1]),
                "max", Math.max(latencyTotals[2], latencyPending[2])));
        status.put("domainStatus", domainStatus);
        status.put("vaults", vaults);
        return status;
    }

    /** {@code reserved} and {@code reservedLovelace} as strings, so u128 values survive JSON. */
    static Map<String, Object> backlog(String ticker, BigInteger reserved, BigInteger reservedLovelace,
                                       BigInteger available, long queuedClaims) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ticker", ticker);
        entry.put("reserved", reserved.toString());
        entry.put("reservedLovelace", reservedLovelace.toString());
        entry.put("available", available.toString());
        entry.put("queuedClaims", queuedClaims);
        return entry;
    }

    private void fold() {
        pending.forEach((key, value) -> totals.merge(key, value, LedgerDiagnostics::saturatedAdd));
        pending.clear();
        latencyTotals[0] += latencyPending[0];
        latencyTotals[1] = saturatedAdd(latencyTotals[1], latencyPending[1]);
        latencyTotals[2] = Math.max(latencyTotals[2], latencyPending[2]);
        java.util.Arrays.fill(latencyPending, 0);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
