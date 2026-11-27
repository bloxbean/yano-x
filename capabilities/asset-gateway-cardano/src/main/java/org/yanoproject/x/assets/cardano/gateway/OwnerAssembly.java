package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.contracts.Hash32;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * The concurrency contract of ADR-059 D9 ("What the owner builds", concurrency), shared by every L1 assembly of one
 * node: the {@code Settle} executor, the {@code Accept} batcher and the exit relayer.
 * <ul>
 *   <li>One lock serializes every assembly (L1 read, selection, build, co-sign, submit), so two of them never select
 *       the same fee-wallet or vault output.</li>
 *   <li>Each submission still pending holds the outpoints it spends: nothing selects them again, and a payout whose
 *       shard input a pending submission spends waits for its inclusion.</li>
 *   <li>The nullifier inserts of pending payouts are the optimistic part of the shard mirror (D8h §6).</li>
 * </ul>
 *
 * <p><b>Release only on L1 evidence</b> (r22). Nothing is released by the wall clock or by a guess that a transaction
 * is dead. An input hold is released when this node's applied L1 view shows either that the transaction can never
 * land (its applied tip slot is past the transaction's TTL slot) or that it has landed (one of its outputs is in the
 * view, spent or unspent). A view that cannot answer, or a stale view whose tip does not move, keeps the hold. The
 * pending inserts of a payout stay in the mirror until committed state accounts for them (every claim
 * {@code PAID} or {@code PAID_MISMATCH}) or the transaction can never land (the tip is past its TTL and it is not in
 * the view); they never expire with the input hold.
 *
 * <p><b>Startup grace.</b> Holds live in memory. Before each submission, the transaction's TTL slot is persisted to a
 * node-local file ({@code pending-ttl-file}); after a start, the node assembles nothing new until its applied L1 view
 * is past the persisted slot, by when every transaction submitted before the restart has landed or can never land.
 * Without a readable file it waits the longest permitted validity window plus {@link #SLACK_MS} instead. A
 * {@code Settle} submitted before the restart is still probed from its persisted reference.
 */
final class OwnerAssembly {
    /** The no-file startup grace past the longest permitted validity window: L1 view lag and clock skew. */
    static final long SLACK_MS = 120_000;
    /** The outcome of an assembly refused during the startup grace. */
    static final String WARMING_UP = "startup grace: waiting out transactions submitted before the start";

    /** One submitted transaction: what it spends, its output count and TTL slot, and for a payout its inserts. */
    record Pending(Hash32 txId, Set<ResolvedOutput.Outpoint> spends, int outputs, long ttlSlot, Hash32 vaultId,
                   int shard, List<Hash32> inserts) {
        Pending {
            Objects.requireNonNull(txId, "tx id");
            spends = Set.copyOf(spends);
            inserts = List.copyOf(inserts);
        }

        /** A submitted payout ({@code Settle} or {@code Exit}) of {@code inserts} on one shard. */
        static Pending payout(Transaction tx, Hash32 txId, Hash32 vaultId, int shard, List<Hash32> inserts) {
            return new Pending(txId, FeeWallet.spends(tx), tx.getBody().getOutputs().size(), tx.getBody().getTtl(),
                    vaultId, shard, inserts);
        }

        /** A submitted transaction that inserts no nullifier ({@code Accept}). */
        static Pending spending(Transaction tx, Hash32 txId) {
            return payout(tx, txId, null, -1, List.of());
        }
    }

    private final Object lock = new Object();
    /** Input holds by key. Guarded by {@link #lock}. */
    private final Map<String, Pending> holds = new LinkedHashMap<>();
    /** Payouts whose inserts are not yet accounted for, by transaction id. Guarded by {@link #lock}. */
    private final Map<Hash32, Pending> inserting = new LinkedHashMap<>();
    /** Told, under the lock, the key of every input hold released on L1 evidence. */
    private volatile Consumer<String> onRelease = key -> { };

    private final long startedAtMs;
    private final Optional<Path> ttlFile;
    /** The TTL slot persisted before the start, when the file was readable. */
    private final OptionalLong persistedTtl;
    /** The highest TTL slot written to the file. Guarded by {@link #lock}. */
    private long writtenTtl;
    private volatile boolean ready;

    /** The assembly of a node started at {@code startedAtMs}, persisting TTLs to {@code ttlFile} when given. */
    OwnerAssembly(long startedAtMs, Optional<Path> ttlFile) {
        this.startedAtMs = startedAtMs;
        this.ttlFile = Objects.requireNonNull(ttlFile, "ttl file");
        this.persistedTtl = ttlFile.flatMap(OwnerAssembly::read).stream().mapToLong(Long::longValue).findFirst();
        this.writtenTtl = persistedTtl.orElse(-1);
    }

    private static Optional<Long> read(Path file) {
        try {
            return Optional.of(Long.parseLong(Files.readString(file, StandardCharsets.US_ASCII).trim()));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty(); // no file yet, or unreadable: the fixed grace applies
        }
    }

    /**
     * True during the startup grace: until the applied L1 view is past the TTL slot persisted before the start, or,
     * without one, for the longest permitted validity window plus the slack.
     */
    boolean warmingUp(long nowMs, NodeL1View l1) {
        if (ready) return false;
        boolean over;
        if (persistedTtl.isPresent()) {
            OptionalLong tip = l1.appliedTipSlot();
            over = tip.isPresent() && tip.getAsLong() > persistedTtl.getAsLong();
        } else {
            over = nowMs >= startedAtMs + AssetSettlementSettings.MAX_VALIDITY_WINDOW_MS + SLACK_MS;
        }
        if (over) ready = true;
        return !over;
    }

    /**
     * Persists {@code ttlSlot} before its transaction is submitted, so a restart waits it out. A failure to write
     * throws: the caller must not submit.
     */
    void beforeSubmit(long ttlSlot) {
        if (ttlFile.isEmpty()) return;
        synchronized (lock) {
            if (ttlSlot <= writtenTtl) return;
            Path file = ttlFile.get();
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temp, Long.toString(ttlSlot), StandardCharsets.US_ASCII);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot persist the pending TTL to " + file, failure);
            }
            writtenTtl = ttlSlot;
        }
    }

    /** Runs {@code assembly} under the one lock. */
    <T> T serialized(Callable<T> assembly) throws Exception {
        synchronized (lock) {
            return assembly.call();
        }
    }

    /** Sets who is told of each input hold released: the {@code Settle} executor evicts its journal entry. */
    void onRelease(Consumer<String> listener) {
        this.onRelease = Objects.requireNonNull(listener, "listener");
    }

    /** Holds what {@code pending} spends, and its inserts if it is a payout. */
    void hold(String key, Pending pending) {
        synchronized (lock) {
            holds.put(key, pending);
            if (!pending.inserts().isEmpty()) inserting.put(pending.txId(), pending);
        }
    }

    /** Every outpoint a pending submission spends, after releasing the holds that {@code l1} shows are over. */
    Set<ResolvedOutput.Outpoint> held(NodeL1View l1) {
        synchronized (lock) {
            OptionalLong tip = l1.appliedTipSlot();
            var it = holds.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Pending> entry = it.next();
                if (pastTtl(entry.getValue(), tip) || landed(entry.getValue(), l1)) {
                    it.remove();
                    onRelease.accept(entry.getKey());
                }
            }
            Set<ResolvedOutput.Outpoint> spends = new HashSet<>();
            holds.values().forEach(hold -> spends.addAll(hold.spends()));
            return spends;
        }
    }

    /**
     * The ids that pending payouts insert into one shard of one vault, in submission order, after dropping those
     * that committed state accounts for ({@code paid}: the shard's {@code PAID} and {@code PAID_MISMATCH} claims) and
     * those of transactions that can never land.
     */
    LinkedHashSet<Hash32> inserts(Hash32 vaultId, int shard, Collection<Hash32> paid, NodeL1View l1) {
        synchronized (lock) {
            Set<Hash32> accounted = new HashSet<>(paid);
            OptionalLong tip = l1.appliedTipSlot();
            inserting.values().removeIf(pending -> vaultId.equals(pending.vaultId()) && pending.shard() == shard
                    && (accounted.containsAll(pending.inserts()) || (pastTtl(pending, tip) && !landed(pending, l1))));
            LinkedHashSet<Hash32> ids = new LinkedHashSet<>();
            for (Pending pending : inserting.values()) {
                if (vaultId.equals(pending.vaultId()) && pending.shard() == shard) ids.addAll(pending.inserts());
            }
            return ids;
        }
    }

    /** The applied tip is past the TTL slot: the transaction can never land (any more). */
    private static boolean pastTtl(Pending pending, OptionalLong tip) {
        return tip.isPresent() && tip.getAsLong() > pending.ttlSlot();
    }

    /**
     * The transaction is on L1: one of its outputs is known to the view, spent or unspent. That read also answers
     * "its id is known", so a transaction whose outputs were all spent since still counts.
     */
    private static boolean landed(Pending pending, NodeL1View l1) {
        for (int index = 0; index < pending.outputs(); index++) {
            if (l1.known(new ResolvedOutput.Outpoint(pending.txId(), index)) == NodeL1View.Read.PRESENT) return true;
        }
        return false;
    }

    int size() {
        synchronized (lock) {
            return holds.size();
        }
    }
}
