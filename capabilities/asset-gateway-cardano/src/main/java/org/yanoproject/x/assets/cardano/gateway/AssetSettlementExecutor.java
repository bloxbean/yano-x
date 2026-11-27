package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.ShardMirror;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The owner's {@code asset.settlement.batch} executor (ADR-059 D9): it turns one committed batch into one co-signed
 * {@code Settle}, submits it, and reports the attempt. Its results are telemetry for the effect runtime only: the
 * consumer's ledger closes or re-batches on them, and only the payout observer moves a claim to {@code PAID} (I12).
 *
 * <p>One attempt:
 * <ol>
 *   <li>read every claim of the batch from committed state and keep the {@code RESERVED} ones (the payload names ids
 *       only, D7); none left: {@code Confirmed};</li>
 *   <li>wait ({@code Retry}) until the live, pinned anchor covers every claim's reservation (V12) and the committed
 *       domain status is {@code ACTIVE};</li>
 *   <li>wait ({@code Retry}) while a pending submission of this node still spends the live shard input; else rebuild
 *       the shard mirror from the paid claims plus the inserts of this node's pending submissions on the shard and
 *       check it against the live shard root, leaving the optimistic part out on a mismatch (D8h §6);</li>
 *   <li>leave out every claim whose payout would need more than {@code max-supplement-per-payout} from the fee
 *       wallet (D11): it stays {@code RESERVED} and is paid by {@code Exit}, where the relayer or the user supplements
 *       it; a batch of only such claims waits ({@code Retry}) until the effect expires;</li>
 *   <li>take at most {@code max-settle-batch} claims, halving on a per-transaction limit (size, units, budget or
 *       vault inputs); a single claim that alone exceeds a limit is left {@code RESERVED} (for {@code Exit}) and the
 *       next claims are tried. The rest stay {@code RESERVED} and the ledger re-queues them when this batch
 *       closes;</li>
 *   <li>name the required signers: this node when it is an anchor member, then, on a retry, the members that
 *       answered the previous round, and the next untried members in key order for the rest, so a member that does
 *       not answer cannot block a quorum that can (V9);</li>
 *   <li>build the {@code Settle} from fee-wallet and vault outputs that no pending submission spends (none left:
 *       {@code Retry}), self-check it with the members' body check, run the
 *       co-sign round (fail closed on a missing signer), add the witnesses and submit through the node; the
 *       submission's reference is the transaction id, its deadline and which claims of the batch it pays, which the
 *       runtime persists;</li>
 *   <li>on a later attempt, probe: once every claim of the transaction is {@code PAID} by this transaction in
 *       committed state (the payout observer confirmed it, a stability depth after inclusion), {@code Confirmed};
 *       once any of them is resolved by another transaction, or as {@code PAID_MISMATCH}, a non-retryable
 *       {@code Failed} (superseded); past the deadline, a non-retryable {@code Failed}. Each closes the batch, and the
 *       ledger re-queues every claim of it still {@code RESERVED}, so a Settle that was included and then rolled back
 *       is batched again.</li>
 * </ol>
 * The journal is a cache, not a trust root: after a restart, committed status and the on-chain shard root decide what
 * is still payable, and the persisted deadline still bounds the wait.
 *
 * <p>The effect runtime runs several attempts at once (across shards, and on one shard across blocks), so one lock
 * serializes every attempt of this executor. A single owner's L1 assembly is serial anyway: every {@code Settle}
 * spends the one fee wallet and continues the vault's custody. Each pending submission's spent outpoints stay out of
 * later selections until the L1 view shows the transaction landed or past its TTL slot, and no attempt builds over a
 * shard input a pending submission spends. The lock
 * and the holds are the node's {@link OwnerAssembly}, which the {@code Accept} batcher and an exit relayer running on
 * this node share, so none of them spends what another one's pending transaction spends.
 */
final class AssetSettlementExecutor implements AppEffectExecutor {
    static final String ID = "asset-settlement";
    static final Duration WAIT_FOR_ANCHOR = Duration.ofSeconds(20);
    /** The retry pace of a batch whose claims are all left for {@code Exit}: it closes by expiry, not in a loop. */
    static final Duration LEFT_FOR_EXIT = Duration.ofMinutes(10);

    private final AssetSettlementSettings settings;
    private final NodeL1View l1;
    private final CommittedClaims committed;
    private final AssetCosignService cosign;
    private final Supplier<ProtocolParams> protocolParams;
    private final TransactionEvaluator evaluator;
    private final Function<byte[], String> submit;
    private final LongSupplier clock;
    private final byte[] memberPublicKey;
    private final FeeWallet wallet;
    /**
     * Serializes attempts (reading the L1 view, selecting inputs, building, co-signing, submitting and probing) with
     * this node's other assemblies, and holds what pending submissions spend.
     */
    private final OwnerAssembly assembly;
    /**
     * This node's submitted, unconfirmed Settles by effect, until the effect closes. The spent outpoints and inserts
     * are held in {@link #assembly} under {@link #holdKey}, apart from the journal: an entry is also evicted when its
     * input hold is released on L1 evidence, never waiting for a probe, because the effect runtime may stop probing an
     * expired effect. A later probe recovers the entry from the persisted reference. Guarded by the assembly lock.
     */
    private final Map<String, Journal> journal = new HashMap<>();
    /** The periodic tasks this executor product owns ({@code Accept}, exit relaying), stopped on close. */
    private volatile AutoCloseable tasks;
    /** The most effects whose signer memory is kept; the least recently used is dropped (an expired batch's). */
    static final int SIGNER_MEMORY_CAP = 256;
    /**
     * Per effect: the members that answered its last incomplete round, and every member tried in the current pass. An
     * entry is dropped when the effect ends here (submitted, confirmed or definitively failed) and, for an effect that
     * ends by expiry instead, by the LRU cap.
     */
    private final Map<String, SignerMemory> signerMemory = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SignerMemory> eldest) {
                    return size() > SIGNER_MEMORY_CAP;
                }
            });
    private volatile String lastOutcome = "idle";

    /** The other members that answered the last round of an effect, and the members tried since the last reset. */
    record SignerMemory(Set<Hash32> answered, Set<Hash32> tried) {
        static final SignerMemory NONE = new SignerMemory(Set.of(), Set.of());
    }

    /**
     * A submitted Settle: its transaction, the claims it pays (also as their positions in the batch), the deadline
     * for their confirmation, and the outpoints it spends (vault, shard and funding inputs; not persisted, so a
     * recovered entry has none).
     */
    record Journal(Hash32 txId, Hash32 vaultId, int shard, List<Hash32> claimIds, long deadlineMs, long positions,
                   Set<ResolvedOutput.Outpoint> spends) {
        static final int REF_BYTES = Hash32.BYTES + 2 * Long.BYTES;

        Journal {
            claimIds = List.copyOf(claimIds);
            spends = Set.copyOf(spends);
        }

        /** The entry of {@code paid}, a subset of {@code batch}'s claims, with their positions as a bitmap. */
        static Journal of(Hash32 txId, SettlementBatchV1 batch, List<Hash32> paid, long deadlineMs,
                          Set<ResolvedOutput.Outpoint> spends) {
            long positions = 0;
            for (Hash32 claimId : paid) positions |= 1L << batch.claimIds().indexOf(claimId);
            return new Journal(txId, batch.vaultId(), batch.shardIndex(), paid, deadlineMs, positions, spends);
        }

        /**
         * The runtime-persisted reference: the transaction id, the deadline (big-endian milliseconds), and the bitmap
         * of the batch positions it pays (bit {@code i} for claim {@code i}; a batch holds at most 64 claims).
         */
        byte[] ref() {
            return ByteBuffer.allocate(REF_BYTES).put(txId.bytes()).putLong(deadlineMs).putLong(positions).array();
        }

        /** The journal entry a restart recovers from the persisted reference: the same claims and deadline. */
        static Optional<Journal> recover(byte[] ref, SettlementBatchV1 batch) {
            if (ref == null || ref.length != REF_BYTES) return Optional.empty();
            ByteBuffer buffer = ByteBuffer.wrap(ref);
            byte[] txId = new byte[Hash32.BYTES];
            buffer.get(txId);
            long deadline = buffer.getLong();
            long positions = buffer.getLong();
            List<Hash32> paid = new ArrayList<>();
            for (int i = 0; i < batch.claimIds().size(); i++) {
                if ((positions >>> i & 1) == 1) paid.add(batch.claimIds().get(i));
            }
            // A 64-claim batch uses every bit, and Java shifts a long by its distance mod 64,
            // so excess bits are checked only below 64 claims.
            boolean excess = batch.claimIds().size() < Long.SIZE && positions >>> batch.claimIds().size() != 0;
            if (paid.isEmpty() || excess) return Optional.empty();
            return Optional.of(new Journal(Hash32.of(txId), batch.vaultId(), batch.shardIndex(), paid, deadline,
                    positions, Set.of()));
        }
    }

    AssetSettlementExecutor(AssetSettlementSettings settings, NodeL1View l1, CommittedClaims committed,
                            AssetCosignService cosign, Supplier<ProtocolParams> protocolParams,
                            TransactionEvaluator evaluator, Function<byte[], String> submit, LongSupplier clock,
                            byte[] memberPublicKey, OwnerAssembly assembly, FeeWallet wallet) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.l1 = Objects.requireNonNull(l1, "l1");
        this.committed = Objects.requireNonNull(committed, "committed");
        this.cosign = Objects.requireNonNull(cosign, "cosign");
        this.protocolParams = Objects.requireNonNull(protocolParams, "protocol params");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.submit = Objects.requireNonNull(submit, "submit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.memberPublicKey = memberPublicKey.clone();
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        assembly.onRelease(holdKey -> {
            if (holdKey.startsWith(SETTLE_HOLD)) journal.remove(holdKey.substring(SETTLE_HOLD.length()));
        });
    }

    private static final String SETTLE_HOLD = "settle:";

    /** The key of a Settle's hold in the shared assembly. */
    static String holdKey(String effectKey) {
        return SETTLE_HOLD + effectKey;
    }

    int journalSize() {
        return journal.size();
    }

    private DepositAcceptor acceptor;
    private ExitRelayTask relayer;

    /** The owner's {@code Accept} batcher and exit relayer, which share this executor's assembly and fee wallet. */
    void wire(DepositAcceptor acceptor, ExitRelayTask relayer) {
        this.acceptor = acceptor;
        this.relayer = relayer;
    }

    Optional<DepositAcceptor> acceptor() {
        return Optional.ofNullable(acceptor);
    }

    Optional<ExitRelayTask> relayer() {
        return Optional.ofNullable(relayer);
    }

    /** Gives this product the periodic tasks it stops on close. */
    void ownTasks(AutoCloseable tasks) {
        this.tasks = tasks;
    }

    @Override
    public void close() {
        AutoCloseable owned = tasks;
        if (owned == null) return;
        try {
            owned.close();
        } catch (Exception ignored) {
            // Bounded shutdown: the scheduler is stopped whatever the close reports.
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(SettlementBatchV1.EFFECT_TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return SettlementBatchV1.EFFECT_TYPE.equals(effectType);
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        return AssetSettlementExecutorFactory.cosignSnapshot(cosign);
    }

    String lastOutcome() {
        return lastOutcome;
    }

    byte[] feeAddress() {
        return wallet.address();
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception {
        String key = HexFormat.of().formatHex(effect.idHash());
        EffectExecution result = assembly.serialized(() -> attempt(ctx, effect, key));
        boolean terminal = result instanceof EffectExecution.Confirmed
                || (result instanceof EffectExecution.Failed failed && !failed.retryable());
        if (terminal) signerMemory.remove(key);
        return result;
    }

    private EffectExecution attempt(EffectExecutionContext ctx, PendingEffect effect, String key) throws Exception {
        SettlementBatchV1 batch;
        try {
            batch = SettlementBatchV1.decode(effect.payload());
        } catch (RuntimeException malformed) {
            return outcome(EffectExecution.failed("malformed settlement batch", false), "malformed");
        }
        Journal entry = journal.get(key);
        if (entry == null) {
            // A restart lost the journal: probe the transaction the runtime recorded, against its original deadline.
            entry = Journal.recover(ctx.submittedRef(), batch).orElse(null);
        }
        if (entry != null) return probe(key, entry);
        if (assembly.warmingUp(clock.getAsLong(), l1)) {
            // Holds are in memory: until any transaction submitted before a restart has landed or expired, nothing
            // new is assembled. A pending Settle is still probed above, from its persisted reference.
            return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), OwnerAssembly.WARMING_UP);
        }

        VaultPins pins = settings.vault(batch.vaultId()).orElse(null);
        if (pins == null) return outcome(EffectExecution.failed("the batch names an unknown vault", false), "vault");
        List<ClaimLeafV1> claims = new ArrayList<>();
        for (Hash32 claimId : batch.claimIds()) {
            Optional<SettlementQueries.ClaimView> view = committed.claim(claimId);
            if (view.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "claim unavailable");
            if (view.get().status().status() == ClaimStatus.RESERVED) claims.add(view.get().leaf());
        }
        if (claims.isEmpty()) return outcome(EffectExecution.confirmed(new byte[0]), "nothing reserved");
        Optional<NodeL1View.Anchored> anchor = l1.anchor(pins);
        if (anchor.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "no live anchor");
        for (ClaimLeafV1 leaf : claims) {
            if (leaf.reservedHeight() > anchor.get().datum().height()) {
                return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "waiting for an anchor (V12)");
            }
        }
        if (committed.domainStatus().map(DomainStatusV1::status).orElse(null) != DomainStatusV1.Status.ACTIVE) {
            return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "domain not ACTIVE");
        }
        Optional<ResolvedOutput> exitMode = l1.exitMode(pins);
        Optional<ResolvedOutput> shardInput = l1.shard(pins, batch.shardIndex());
        AssetSettlementSettings.ScriptRefs refs = settings.scriptRefs().get(pins.vaultId());
        Optional<ResolvedOutput> vaultRef = l1.resolve(refs.vault());
        Optional<ResolvedOutput> shardRef = l1.resolve(refs.shard());
        if (exitMode.isEmpty() || shardInput.isEmpty() || vaultRef.isEmpty() || shardRef.isEmpty()) {
            return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "L1 inputs unavailable");
        }
        Set<ResolvedOutput.Outpoint> pendingSpends = assembly.held(l1);
        if (pendingSpends.contains(shardInput.get().outpoint())) {
            // A pending payout spends this shard input and is not yet included: a second one would conflict with it.
            // Chaining on its predicted shard output (D8h §6) is a later optimization; until then the batch waits.
            return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "awaiting the pending payout on this shard");
        }
        Optional<ShardMirror> mirror = mirror(pins, batch.shardIndex(), shardInput.get());
        if (mirror.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "shard mirror mismatch");
        claims.removeIf(leaf -> mirror.get().contains(leaf.claimId())); // paid on L1, confirmation pending
        if (claims.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "awaiting confirmation");
        ProtocolParams pp = protocolParams.get();
        if (pp == null) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "no protocol parameters");
        // D11: the fee wallet tops up a payout's min-UTxO by at most max-supplement-per-payout; a claim that needs
        // more is paid by Exit, so a user cannot drain the operator wallet with unfunded token withdrawals.
        claims.removeIf(leaf -> PayoutTxBuilder.payoutSupplement(pins, leaf, pp)
                .compareTo(settings.maxSupplementPerPayout()) > 0);
        if (claims.isEmpty()) return outcome(EffectExecution.retry(LEFT_FOR_EXIT), "left for Exit (D11)");

        List<byte[]> signerKeys = signers(anchor.get(), signerMemory.getOrDefault(key, SignerMemory.NONE));
        // Outputs a pending submission spends are not selected again; their release is its close (or their
        // disappearance from the L1 view, once it is included).
        // Custody pages through the vault address until it covers the capped batch (Codex P1, r22).
        List<ResolvedOutput> vaultUtxos = l1.custodyFor(pins, pendingSpends,
                claims.subList(0, Math.min(claims.size(), pins.maxSettleBatch())));
        boolean held = !pendingSpends.isEmpty();
        List<ResolvedOutput> funding = wallet.funding(l1.at(wallet.address()), pendingSpends);
        if (funding.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "no fee wallet funds");
        long slot = settings.slots().slotAt(clock.getAsLong());
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(pins, vaultUtxos,
                shardInput.get(), anchor.get().output(), exitMode.get(), List.of(vaultRef.get(), shardRef.get()),
                new PayoutTxBuilder.Funding(funding, wallet.address()), Math.max(0, slot - 60),
                slot + settings.validityWindowSlots(), pp, settings.maxVaultInputs());
        // The executor's cap, whatever the ledger's batch size; the rest stays RESERVED and is re-queued at close.
        // A claim that alone exceeds a per-transaction limit is left RESERVED for Exit, and the next ones are tried.
        int size = Math.min(claims.size(), pins.maxSettleBatch());
        PayoutTxBuilder.Built built;
        while (true) {
            if (claims.isEmpty()) {
                return outcome(EffectExecution.retry(LEFT_FOR_EXIT), "no claim fits one transaction");
            }
            try {
                built = PayoutTxBuilder.settle(context, claims.subList(0, size), mirror.get(),
                        signerKeys.stream().map(Blake2bUtil::blake2bHash224).toList(), evaluator);
                break;
            } catch (PayoutTxBuilder.LimitExceeded tooLarge) {
                if (size == 1) {
                    claims.removeFirst();
                    size = Math.min(claims.size(), pins.maxSettleBatch());
                } else {
                    size = size / 2;
                }
            } catch (PayoutTxBuilder.BuildException unbuildable) {
                if (held) {
                    // Not enough custody or funds outside the pending submissions: wait for them to close.
                    return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "inputs held by a pending Settle");
                }
                return outcome(EffectExecution.failed("settle build: " + unbuildable.getMessage(), true), "build");
            }
        }
        Set<String> requested = new LinkedHashSet<>();
        signerKeys.forEach(signer -> requested.add(HexFormat.of().formatHex(signer)));
        Optional<Map<String, byte[]>> signatures = cosign.collect(built.cbor(), requested);
        if (signatures.isEmpty()) {
            return outcome(EffectExecution.failed("the owner's own body check refused the batch", false), "refused");
        }
        if (!signatures.get().keySet().containsAll(requested)) {
            remember(key, requested, signatures.get().keySet(), anchor.get().datum().memberKeys());
            return outcome(EffectExecution.failed("co-sign round incomplete", true), "round incomplete");
        }
        signerMemory.remove(key);
        byte[] signed = wallet.witness(built.cbor(), built.txId(), signatures.get());
        assembly.beforeSubmit(built.transaction().getBody().getTtl());
        String txHash = submit.apply(signed);
        if (txHash == null || !txHash.equalsIgnoreCase(built.txId().hex())) {
            return outcome(EffectExecution.failed("submission returned another transaction id", true), "submit");
        }
        long submittedAt = clock.getAsLong();
        Set<ResolvedOutput.Outpoint> spends = FeeWallet.spends(built.transaction());
        Journal submitted = Journal.of(built.txId(), batch, built.claimIds(),
                submittedAt + settings.inclusionTimeout().toMillis(), spends);
        journal.put(key, submitted);
        // Held until the L1 view shows it landed or past its TTL; its inserts until committed state accounts for them.
        assembly.hold(holdKey(key), OwnerAssembly.Pending.payout(built.transaction(), built.txId(), batch.vaultId(),
                batch.shardIndex(), built.claimIds()));
        return outcome(EffectExecution.submitted(submitted.ref()), "submitted " + built.txId());
    }

    /**
     * A submitted Settle is confirmed by committed state only: once every one of its claims is {@code PAID} by this
     * very transaction, the payout observer has confirmed it a stability depth after inclusion. Tip inclusion proves
     * nothing, since the block can roll back. A claim resolved by another transaction (a competing {@code Exit}) or
     * as {@code PAID_MISMATCH} means this transaction is superseded: it fails at once. Past the deadline it fails, too.
     * Either way the ledger re-queues whatever is still {@code RESERVED}.
     */
    private void closeJournal(String key) {
        // The input hold is not the journal's: it stays until the L1 view shows the transaction landed or past its
        // TTL, whatever the effect's outcome (r22).
        journal.remove(key);
    }

    private EffectExecution probe(String key, Journal entry) {
        boolean pending = false;
        boolean superseded = false;
        for (Hash32 claimId : entry.claimIds()) {
            Optional<SettlementQueries.ClaimView> view = committed.claim(claimId);
            if (view.isEmpty() || view.get().status().status() == ClaimStatus.RESERVED) {
                pending = true;
            } else if (view.get().status().status() != ClaimStatus.PAID
                    || !view.get().status().resolvedTxId().equals(Optional.of(entry.txId()))) {
                superseded = true;
            }
        }
        if (superseded) {
            closeJournal(key);
            return outcome(EffectExecution.failed("superseded: a claim was resolved by another payout", false),
                    "superseded " + entry.txId());
        }
        if (!pending) {
            closeJournal(key);
            return outcome(EffectExecution.confirmed(entry.txId().bytes()), "confirmed " + entry.txId());
        }
        if (clock.getAsLong() > entry.deadlineMs()) {
            closeJournal(key);
            // Not confirmed: never included, or included and rolled back. The ledger re-batches the claims; a later
            // inclusion still confirms through the payout observer, and a second payment is impossible (I6).
            return outcome(EffectExecution.failed("settlement transaction not confirmed by its deadline", false),
                    "timed out");
        }
        return outcome(EffectExecution.submitted(entry.ref()), "awaiting confirmation of " + entry.txId());
    }

    /**
     * The mirror: confirmed inserts plus the inserts of this node's pending payouts on the shard (its Settles and the
     * Exits of a relayer sharing its assembly), falling back to confirmed only (D8h §6). The caller has already
     * waited out a pending submission whose shard input is still live, so a fallback means a rollback or a
     * third-party {@code Exit}; the pending entries stay until they close.
     */
    private Optional<ShardMirror> mirror(VaultPins pins, int shard, ResolvedOutput shardInput) {
        Optional<Hash32> liveRoot = shardInput.inlineDatum()
                .flatMap(org.yanoproject.x.assets.contracts.GatewayDatums::shardDatum)
                .map(org.yanoproject.x.assets.contracts.GatewayDatums.ShardDatum::nullifierRoot);
        Optional<List<Hash32>> paid = committed.paid(pins.vaultId(), shard);
        if (liveRoot.isEmpty() || paid.isEmpty()) return Optional.empty();
        LinkedHashSet<Hash32> withOwn = new LinkedHashSet<>(paid.get());
        withOwn.addAll(assembly.inserts(pins.vaultId(), shard, paid.get(), l1));
        ShardMirror optimisticMirror = ShardMirror.of(shard, withOwn);
        if (optimisticMirror.matches(liveRoot.get())) return Optional.of(optimisticMirror);
        ShardMirror confirmed = ShardMirror.of(shard, paid.get());
        return confirmed.matches(liveRoot.get()) ? Optional.of(confirmed) : Optional.empty();
    }

    /**
     * The required signers (V9): this node first when it is an anchor member; then the other members that answered
     * this effect's previous round; then, for the signers still missing, the next members in key order not tried
     * since the last reset (all tried: the reset starts over). A member that does not answer is replaced on the next
     * attempt while the ones that answered stay, so any healthy quorum is reached. The co-sign round accepts replies
     * only from the members named here.
     */
    List<byte[]> signers(NodeL1View.Anchored anchor, SignerMemory memory) {
        return signers(memberPublicKey, anchor, memory);
    }

    /** {@link #signers(NodeL1View.Anchored, SignerMemory)} for the node whose member key is {@code memberPublicKey}. */
    static List<byte[]> signers(byte[] memberPublicKey, NodeL1View.Anchored anchor, SignerMemory memory) {
        List<byte[]> keys = new ArrayList<>();
        Hash32 self = Hash32.of(memberPublicKey);
        List<Hash32> others = new ArrayList<>();
        for (Hash32 member : anchor.datum().memberKeys()) {
            if (member.equals(self)) {
                keys.add(memberPublicKey.clone());
            } else {
                others.add(member);
            }
        }
        int needed = anchor.datum().threshold() - keys.size();
        List<Hash32> chosen = new ArrayList<>(others.stream().filter(memory.answered()::contains).limit(needed)
                .toList());
        // The members not yet tried in this pass come first; past them a new pass starts in key order.
        List<Hash32> untried = new ArrayList<>(others.stream()
                .filter(m -> !chosen.contains(m) && !memory.tried().contains(m)).toList());
        others.stream().filter(m -> !chosen.contains(m) && !untried.contains(m)).forEach(untried::add);
        for (Hash32 member : untried) {
            if (chosen.size() >= needed) break;
            chosen.add(member);
        }
        chosen.forEach(member -> keys.add(member.bytes()));
        return keys;
    }

    /**
     * After an incomplete round: who answered, and that every requested member has now been tried. Once every other
     * member of {@code memberKeys} has been tried, the pass ends and the next one starts from the responders alone, so
     * a member that missed a request in an earlier pass is asked again.
     */
    void remember(String key, Set<String> requested, Set<String> answered, List<Hash32> memberKeys) {
        signerMemory.put(key, afterRound(memberPublicKey, signerMemory.getOrDefault(key, SignerMemory.NONE), requested,
                answered, memberKeys));
    }

    /** The memory after an incomplete round, as {@link #remember} records it. */
    static SignerMemory afterRound(byte[] memberPublicKey, SignerMemory before, Set<String> requested,
                                   Set<String> answered, List<Hash32> memberKeys) {
        Set<Hash32> tried = new LinkedHashSet<>(before.tried());
        Set<Hash32> replied = new LinkedHashSet<>();
        for (String member : requested) {
            Hash32 id = Hash32.ofHex(member);
            tried.add(id);
            if (answered.contains(member)) replied.add(id);
        }
        Hash32 self = Hash32.of(memberPublicKey);
        boolean passDone = memberKeys.stream().filter(m -> !m.equals(self)).allMatch(tried::contains);
        return new SignerMemory(Set.copyOf(replied), Set.copyOf(passDone ? replied : tried));
    }

    int signerMemorySize() {
        return signerMemory.size();
    }

    private EffectExecution outcome(EffectExecution execution, String note) {
        lastOutcome = note;
        return execution;
    }
}
