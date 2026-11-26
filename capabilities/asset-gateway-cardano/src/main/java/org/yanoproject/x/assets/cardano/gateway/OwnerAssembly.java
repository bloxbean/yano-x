package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The concurrency contract of ADR-059 D9 ("What the owner builds", concurrency), shared by every L1 assembly of one
 * node: the {@code Settle} executor, the {@code Accept} batcher and the exit relayer.
 * <ul>
 *   <li>One lock serializes every assembly (L1 read, selection, build, co-sign, submit), so two of them never select
 *       the same fee-wallet or vault output.</li>
 *   <li>Each submission still pending holds the outpoints it spends: nothing selects them again until it closes, and
 *       a payout whose shard input a pending submission spends waits for its inclusion.</li>
 *   <li>The nullifier inserts of pending payouts are the optimistic part of the shard mirror (D8h §6).</li>
 * </ul>
 * A {@code Settle} hold lasts until its effect closes; an {@code Accept} or {@code Exit} hold until its inclusion
 * timeout, by which time it is either on L1 (its inputs gone from the view) or never will be.
 */
final class OwnerAssembly {
    /** One pending submission: what it spends, and for a payout its vault, shard and inserted ids. */
    record Hold(Hash32 txId, Set<ResolvedOutput.Outpoint> spends, Hash32 vaultId, int shard, List<Hash32> inserts,
                long expiresAtMs) {
        Hold {
            Objects.requireNonNull(txId, "tx id");
            spends = Set.copyOf(spends);
            inserts = List.copyOf(inserts);
        }

        /** A hold of a transaction that inserts no nullifier ({@code Accept}). */
        static Hold spending(Hash32 txId, Set<ResolvedOutput.Outpoint> spends, long expiresAtMs) {
            return new Hold(txId, spends, null, -1, List.of(), expiresAtMs);
        }
    }

    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final Map<String, Hold> holds = new HashMap<>();

    /** Runs {@code assembly} under the one lock. */
    <T> T serialized(Callable<T> assembly) throws Exception {
        synchronized (lock) {
            return assembly.call();
        }
    }

    void hold(String key, Hold hold) {
        synchronized (lock) {
            holds.put(key, hold);
        }
    }

    void release(String key) {
        synchronized (lock) {
            holds.remove(key);
        }
    }

    /** Every outpoint a pending submission spends, after dropping the holds past their expiry. */
    Set<ResolvedOutput.Outpoint> held(long nowMs) {
        synchronized (lock) {
            holds.values().removeIf(hold -> hold.expiresAtMs() < nowMs);
            Set<ResolvedOutput.Outpoint> spends = new HashSet<>();
            holds.values().forEach(hold -> spends.addAll(hold.spends()));
            return spends;
        }
    }

    /** The ids that pending payouts insert into one shard of one vault, in submission order. */
    LinkedHashSet<Hash32> inserts(Hash32 vaultId, int shard) {
        synchronized (lock) {
            LinkedHashSet<Hash32> ids = new LinkedHashSet<>();
            for (Hold hold : holds.values()) {
                if (vaultId.equals(hold.vaultId()) && hold.shard() == shard) ids.addAll(hold.inserts());
            }
            return ids;
        }
    }

    int size() {
        synchronized (lock) {
            return holds.size();
        }
    }
}
