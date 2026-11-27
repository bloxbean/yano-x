package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.contracts.Hash32;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * is dead. An input hold is released only when this node's applied L1 tip slot is past the transaction's TTL slot.
 * A transaction that landed has spent those inputs anyway (its change outputs are new outpoints), and keeping the hold
 * to its TTL keeps a rollback of its own inclusion safe. A view that cannot answer, or a stale view whose tip does not
 * move, keeps the hold. The pending inserts of a payout stay in the mirror until committed state accounts for them
 * (every claim {@code PAID} or {@code PAID_MISMATCH}) or the transaction can never land: the tip is past its TTL and a
 * readable view definitely holds none of its outputs, spent or unspent. A read that cannot answer never retires an
 * insert.
 *
 * <p><b>Startup grace.</b> Holds live in memory. Before each submission, the transaction's TTL slot is persisted,
 * durably, to a node-local file per chain and fee wallet ({@code pending-ttl-file}); after a start, the node assembles
 * nothing new until its applied L1 view is past the persisted slot, by when every transaction submitted before the
 * restart has landed or can never land. Without a valid file (none, unreadable, negative, or past the tip by more than
 * the longest permitted window plus the slack) it waits the longest permitted validity window plus {@link #SLACK_MS}
 * of wall-clock time and until its applied tip is past the start slot plus the longest permitted window. A
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
    /** The slot of the start, and the longest permitted validity window and the slack, in slots. */
    private final long startSlot;
    private final long maxWindowSlots;
    private final long slackSlots;
    private final Optional<Path> ttlFile;
    /** The TTL slot persisted before the start, when the file was readable. */
    private final OptionalLong persistedTtl;
    private volatile boolean ready;

    /**
     * The assembly of a node started at {@code startedAtMs} on the slot clock {@code slots}, persisting TTLs to
     * {@code ttlFile} when given.
     */
    OwnerAssembly(long startedAtMs, AssetSettlementSettings.SlotClock slots, Optional<Path> ttlFile) {
        this.startedAtMs = startedAtMs;
        this.startSlot = slots.slotAt(startedAtMs);
        this.maxWindowSlots = ceilDiv(AssetSettlementSettings.MAX_VALIDITY_WINDOW_MS, slots.slotLengthMs());
        this.slackSlots = ceilDiv(SLACK_MS, slots.slotLengthMs());
        this.ttlFile = Objects.requireNonNull(ttlFile, "ttl file");
        this.persistedTtl = ttlFile.flatMap(OwnerAssembly::read).stream().mapToLong(Long::longValue).findFirst();
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /**
     * The TTL file of one chain and fee wallet under the configured base path: {@code <base>.<chainId>.<wallet>},
     * where {@code wallet} is the first 4 bytes of the fee wallet's key hash in hex, so two chains or wallets on one
     * node never lower each other's slot (Codex).
     */
    static Optional<Path> ttlFileFor(Optional<Path> base, String chainId, byte[] walletAddress) {
        String chain = chainId.replaceAll("[^A-Za-z0-9._-]", "_");
        String wallet = java.util.HexFormat.of().formatHex(walletAddress, 1, 5);
        return base.map(path -> path.resolveSibling(path.getFileName() + "." + chain + "." + wallet));
    }

    private static Optional<Long> read(Path file) {
        try {
            return Optional.of(Long.parseLong(Files.readString(file, StandardCharsets.US_ASCII).trim()));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty(); // no file yet, or unreadable: the fallback grace applies
        }
    }

    /**
     * True during the startup grace: until the applied L1 view is past the TTL slot persisted before the start, or,
     * without a valid one, until both the longest permitted window plus the slack has passed and the applied tip is
     * past the start slot plus the longest permitted window. A view whose tip cannot be read keeps the grace.
     */
    boolean warmingUp(long nowMs, NodeL1View l1) {
        if (ready) return false;
        OptionalLong applied = l1.appliedTipSlot();
        if (applied.isEmpty()) return true;
        long tip = applied.getAsLong();
        boolean over;
        if (persistedTtl.isPresent() && valid(persistedTtl.getAsLong(), tip)) {
            over = tip > persistedTtl.getAsLong();
        } else {
            over = nowMs >= startedAtMs + AssetSettlementSettings.MAX_VALIDITY_WINDOW_MS + SLACK_MS
                    && tip > startSlot + maxWindowSlots;
        }
        if (over) ready = true;
        return !over;
    }

    /** A stored TTL slot is valid unless negative or further past the applied tip than any TTL can be. */
    private boolean valid(long ttlSlot, long tip) {
        return ttlSlot >= 0 && ttlSlot - tip <= maxWindowSlots + slackSlots;
    }

    /**
     * Persists {@code ttlSlot}, as the larger of it and the file's, durably before its transaction is submitted, so a
     * restart waits it out: the temporary file is synced, moved into place atomically, and its directory synced where
     * the platform allows. A failure to write throws: the caller must not submit.
     */
    void beforeSubmit(long ttlSlot) {
        if (ttlFile.isEmpty()) return;
        synchronized (lock) {
            Path file = ttlFile.get();
            long onDisk = read(file).filter(stored -> stored >= 0).orElse(-1L);
            if (ttlSlot <= onDisk) return;
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(Long.toString(ttlSlot).getBytes(StandardCharsets.US_ASCII)));
                    channel.force(true);
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                if (parent != null) syncDirectory(parent);
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot persist the pending TTL to " + file, failure);
            }
        }
    }

    /** Syncs a directory entry where the platform supports opening a directory; elsewhere the move must do. */
    private static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Not supported here (for example on Windows).
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

    /** Every outpoint a pending submission spends, after releasing the holds whose TTL slot {@code l1} is past. */
    Set<ResolvedOutput.Outpoint> held(NodeL1View l1) {
        synchronized (lock) {
            OptionalLong tip = l1.appliedTipSlot();
            var it = holds.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Pending> entry = it.next();
                if (pastTtl(entry.getValue(), tip)) {
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
     * those of transactions that can never land (past their TTL, and definitely absent from a readable view). If the
     * host has pruned a landed payout's spent outputs, it reads absent and its inserts are retired early; the mirror
     * then misses the live root, falls back to confirmed inserts only and waits for the payout observer. That costs
     * liveness only.
     */
    LinkedHashSet<Hash32> inserts(Hash32 vaultId, int shard, Collection<Hash32> paid, NodeL1View l1) {
        synchronized (lock) {
            Set<Hash32> accounted = new HashSet<>(paid);
            OptionalLong tip = l1.appliedTipSlot();
            inserting.values().removeIf(pending -> vaultId.equals(pending.vaultId()) && pending.shard() == shard
                    && (accounted.containsAll(pending.inserts()) || (pastTtl(pending, tip) && absent(pending, l1))));
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
     * The view definitely holds none of the transaction's outputs, spent or unspent: every read answers
     * {@link NodeL1View.Read#ABSENT}. One output present, or one read that cannot answer, is not absence.
     */
    private static boolean absent(Pending pending, NodeL1View l1) {
        for (int index = 0; index < pending.outputs(); index++) {
            if (l1.known(new ResolvedOutput.Outpoint(pending.txId(), index)) != NodeL1View.Read.ABSENT) return false;
        }
        return true;
    }

    int size() {
        synchronized (lock) {
            return holds.size();
        }
    }
}
