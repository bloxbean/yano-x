package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

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
 * Every hold is finite. It expires one validity window plus {@link #SLACK_MS} after its successful submission: the
 * transaction's TTL is at most one validity window past its assembly, which precedes the submission, so by then it is
 * on L1 (its inputs gone from the view) or never will be. A hold is released earlier once its transaction is provably
 * dead: a reference input (the anchor) or a spent input no longer resolves while none of the transaction's own outputs
 * does. A {@code Settle} hold is also released when its effect closes.
 *
 * <p>Holds live in memory only. After a start (or restart), the node therefore assembles nothing for one hold's
 * lifetime ({@link #warmingUp}): a transaction submitted before the restart was submitted before the start, so by then
 * it has landed (its inputs gone from the view) or expired, and nothing it spends can be selected while it is still
 * pending. A {@code Settle} effect submitted before the restart is still probed from its persisted reference.
 */
final class OwnerAssembly {
    /** How long past its transaction's TTL a hold lasts: the L1 view's lag and the clock skew to the L1 slot clock. */
    static final long SLACK_MS = 120_000;
    /** The outcome of an assembly refused during the startup grace. */
    static final String WARMING_UP = "startup grace: waiting out transactions submitted before the start";

    /** One pending submission: what it spends and references, its output count, and for a payout its inserts. */
    record Hold(Hash32 txId, Set<ResolvedOutput.Outpoint> spends, Set<ResolvedOutput.Outpoint> references,
                int outputs, Hash32 vaultId, int shard, List<Hash32> inserts, long expiresAtMs) {
        Hold {
            Objects.requireNonNull(txId, "tx id");
            spends = Set.copyOf(spends);
            references = Set.copyOf(references);
            inserts = List.copyOf(inserts);
        }

        /** The hold of a submitted payout ({@code Settle} or {@code Exit}) of {@code inserts} on one shard. */
        static Hold payout(Transaction tx, Hash32 txId, Hash32 vaultId, int shard, List<Hash32> inserts,
                           long expiresAtMs) {
            return new Hold(txId, FeeWallet.spends(tx), references(tx), tx.getBody().getOutputs().size(), vaultId,
                    shard, inserts, expiresAtMs);
        }

        /** The hold of a submitted transaction that inserts no nullifier ({@code Accept}). */
        static Hold spending(Transaction tx, Hash32 txId, long expiresAtMs) {
            return payout(tx, txId, null, -1, List.of(), expiresAtMs);
        }

        private static Set<ResolvedOutput.Outpoint> references(Transaction tx) {
            Set<ResolvedOutput.Outpoint> references = new HashSet<>();
            List<TransactionInput> inputs = tx.getBody().getReferenceInputs();
            if (inputs == null) return references;
            for (TransactionInput input : inputs) {
                references.add(new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()), input.getIndex()));
            }
            return references;
        }
    }

    /** The end of the startup grace: no assembly starts before it. */
    private final long readyAtMs;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final Map<String, Hold> holds = new LinkedHashMap<>();
    /** Told, under the lock, the key of every hold dropped by expiry or death (not by {@link #release}). */
    private volatile Consumer<String> onDrop = key -> { };

    /** The assembly of a node started at {@code startedAtMs}, warming up for one hold's lifetime. */
    OwnerAssembly(long startedAtMs, long validityWindowMs) {
        this.readyAtMs = expiry(startedAtMs, validityWindowMs);
    }

    /** True during the startup grace: no new transaction may be assembled yet. */
    boolean warmingUp(long nowMs) {
        return nowMs < readyAtMs;
    }

    /** The expiry of a hold whose transaction was submitted at {@code submittedAtMs}, see the class comment. */
    static long expiry(long submittedAtMs, long validityWindowMs) {
        return submittedAtMs + validityWindowMs + SLACK_MS;
    }

    /** Runs {@code assembly} under the one lock. */
    <T> T serialized(Callable<T> assembly) throws Exception {
        synchronized (lock) {
            return assembly.call();
        }
    }

    /** Sets who is told of each hold dropped by expiry or death: the {@code Settle} executor evicts its journal. */
    void onDrop(Consumer<String> listener) {
        this.onDrop = Objects.requireNonNull(listener, "listener");
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

    /**
     * Every outpoint a pending submission spends, after dropping the holds past their expiry and those whose
     * transaction is provably dead in {@code l1}.
     */
    Set<ResolvedOutput.Outpoint> held(long nowMs, NodeL1View l1) {
        synchronized (lock) {
            var dropped = holds.entrySet().iterator();
            while (dropped.hasNext()) {
                Map.Entry<String, Hold> entry = dropped.next();
                if (entry.getValue().expiresAtMs() < nowMs || dead(entry.getValue(), l1)) {
                    dropped.remove();
                    onDrop.accept(entry.getKey());
                }
            }
            Set<ResolvedOutput.Outpoint> spends = new HashSet<>();
            holds.values().forEach(hold -> spends.addAll(hold.spends()));
            return spends;
        }
    }

    /**
     * True when the held transaction can never land: none of its outputs is in the view, and a reference input or a
     * spent input no longer is. An outpoint the view cannot answer for keeps the hold.
     */
    private static boolean dead(Hold hold, NodeL1View l1) {
        for (int index = 0; index < hold.outputs(); index++) {
            Optional<Boolean> unspent = l1.unspent(new ResolvedOutput.Outpoint(hold.txId(), index));
            if (unspent.isEmpty() || unspent.get()) return false; // on L1, or unknown
        }
        Set<ResolvedOutput.Outpoint> needed = new HashSet<>(hold.references());
        needed.addAll(hold.spends());
        boolean gone = false;
        for (ResolvedOutput.Outpoint outpoint : needed) {
            Optional<Boolean> unspent = l1.unspent(outpoint);
            if (unspent.isEmpty()) return false;
            if (!unspent.get()) gone = true;
        }
        return gone;
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
