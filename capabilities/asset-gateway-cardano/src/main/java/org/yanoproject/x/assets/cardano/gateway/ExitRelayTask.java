package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.yanoproject.x.assets.cardano.support.settlement.ExitRelayer;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.ShardMirror;
import org.yanoproject.x.assets.cardano.support.settlement.StateProof;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The permissionless exit relayer of ADR-059 D9, run on a timer by any node that enables it: it pays
 * {@code EXIT_ELIGIBLE} claims (D7) by proof {@code Exit} with no custody key. Its power is liveness only: the scripts
 * check every claim field against the anchored leaf (V1-V3), so it cannot pay a wrong amount, a wrong recipient, or
 * twice.
 *
 * <p>One pass, under its {@link OwnerAssembly} lock (the owner's own, on the owner), per vault:
 * <ol>
 *   <li>refuse unless the exit-mode thread is {@code NORMAL}: exit-mode relaying is M4a;</li>
 *   <li>reference the live anchor, whose height lies within the node's proof floor (D8b: the floor keeps every
 *       height from the L1-stable anchor's on); every proof is the node's own at exactly that height, under that
 *       anchor's root;</li>
 *   <li>read the anchored domain status from its proof, and the vault's {@code RESERVED} claims from committed
 *       state; plan the eligible ones by shard, dropping any whose nullifier the shard mirror (confirmed payouts plus
 *       this node's pending ones) already holds, and stopping a shard whose mirror does not match its live root;</li>
 *   <li>for each shard whose live shard input no pending submission spends, build the {@code Exit} from vault custody
 *       and fee-wallet outputs no pending submission spends (halving on a per-transaction limit), submit it, and hold
 *       its inputs until the node's L1 view shows it landed or past its TTL slot, and its inserts until committed
 *       state accounts for them or it can never land.</li>
 * </ol>
 * Polling for inclusion is the payout observer's: a relayed claim leaves the relayer's view once it is {@code PAID}
 * in committed state, and its pending insert keeps it out of later passes until then.
 */
final class ExitRelayTask {
    private final NodeServices node;
    private final OwnerAssembly assembly;
    private final FeeWallet wallet;
    private volatile String lastOutcome = "idle";
    /** The status and metrics of this task (D15a). */
    private final TaskProduct product = new TaskProduct(TaskProduct.EXIT_RELAY);

    ExitRelayTask(NodeServices node, OwnerAssembly assembly, FeeWallet wallet) {
        this.node = Objects.requireNonNull(node, "node");
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    String lastOutcome() {
        return lastOutcome;
    }

    TaskProduct product() {
        return product;
    }

    /** Whether one vault's outcome relayed an {@code Exit}, or found nothing to relay. */
    static boolean acted(String outcome) {
        return outcome.startsWith("relayed ") || outcome.contains(", relayed ");
    }

    static boolean idle(String outcome) {
        return outcome.equals("no reserved claims") || outcome.equals("no claim is EXIT_ELIGIBLE")
                || outcome.startsWith("nothing to relay");
    }

    /**
     * One pass over every vault; returns what happened, per vault. One vault's failure is reported and does not stop
     * the others (Fable P3-2); the committed claim index is walked at most once per pass, for every vault (P3-4).
     */
    String runOnce() throws Exception {
        return assembly.serialized(() -> {
            if (assembly.warmingUp(node.clock().getAsLong(), node.l1())) {
                product.record(TaskProduct.Pass.WAITING);
                return lastOutcome = OwnerAssembly.WARMING_UP;
            }
            List<String> outcomes = new ArrayList<>();
            AtomicReference<Optional<CommittedClaims.Index>> index = new AtomicReference<>();
            Supplier<Optional<CommittedClaims.Index>> claims = () -> {
                if (index.get() == null) index.set(node.committed().index());
                return index.get();
            };
            for (VaultPins pins : node.settings().pins()) {
                try {
                    outcomes.add(relay(pins, claims));
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    outcomes.add("vault " + pins.vaultId() + " failed: " + failure);
                }
            }
            product.record(TaskProduct.worst(outcomes, ExitRelayTask::acted, ExitRelayTask::idle));
            lastOutcome = String.join("; ", outcomes);
            return lastOutcome;
        });
    }

    private String relay(VaultPins pins, Supplier<Optional<CommittedClaims.Index>> claims) throws Exception {
        // ADR-061: a CIP-113 vault's proof Exit is assembled by the SDK and third-party builders; this release's
        // relayer does not build it yet.
        if (pins.cip113()) return "nothing to relay: a CIP-113 proof Exit is not relayed by this release";
        Optional<ResolvedOutput> exitMode = node.l1().exitMode(pins);
        if (exitMode.isEmpty()) return "no exit-mode thread";
        if (!DepositAcceptor.normal(exitMode.get())) return ExitRelayer.EXIT_MODE_REFUSAL;
        Optional<NodeL1View.Anchored> anchor = node.l1().anchor(pins);
        if (anchor.isEmpty()) return "no live anchor";
        Optional<StateProof.Source> proofs = node.anchoredProofs(anchor.get());
        if (proofs.isEmpty()) return "the live anchor has no anchored height";
        Optional<DomainStatusV1> anchoredStatus = proofs.get().inclusion(pins.domainStatusKey())
                .flatMap(proof -> decodeStatus(proof.value()));
        if (anchoredStatus.isEmpty()) return "no status proof at the anchored height";
        Optional<CommittedClaims.Index> index = claims.get();
        if (index.isEmpty()) return "committed claims unavailable";
        List<ExitRelayer.Candidate> reserved = index.get().reserved(pins.vaultId());
        if (reserved.isEmpty()) return "no reserved claims";

        long now = node.clock().getAsLong();
        Set<ResolvedOutput.Outpoint> held = new HashSet<>(assembly.held(node.l1()));
        Map<Integer, ResolvedOutput> shardInputs = new HashMap<>();
        Map<Integer, ShardMirror> mirrors = new HashMap<>();
        Set<Integer> stopped = new HashSet<>();
        List<ExitRelayer.Candidate> relayable = new ArrayList<>();
        for (ExitRelayer.Candidate candidate : reserved) {
            int shard = SettlementBatchV1.shardOf(candidate.leaf().claimId());
            if (stopped.contains(shard)) continue;
            if (!mirrors.containsKey(shard)) {
                Optional<ResolvedOutput> input = node.l1().shard(pins, shard);
                Optional<ShardMirror> mirror = input.flatMap(in -> mirror(pins, shard, in, index.get()));
                if (mirror.isEmpty() || held.contains(input.get().outpoint())) {
                    // A mirror that misses the live root stops this shard (D9 step 2); a pending payout's shard input
                    // waits for its inclusion.
                    stopped.add(shard);
                    continue;
                }
                shardInputs.put(shard, input.get());
                mirrors.put(shard, mirror.get());
            }
            relayable.add(candidate);
        }
        long slot = node.settings().slots().slotAt(now);
        long validFrom = Math.max(0, slot - 60);
        List<ExitRelayer.Batch> batches = ExitRelayer.plan(relayable, pins, exitMode.get(),
                anchor.get().datum().height(), anchoredStatus.get(), node.timeOf(validFrom), mirrors::get);
        if (batches.isEmpty()) return "no claim is EXIT_ELIGIBLE";
        ProtocolParams pp = node.protocolParams().get();
        if (pp == null) return "no protocol parameters";
        AssetSettlementSettings.ScriptRefs refs = node.settings().scriptRefs().get(pins.vaultId());
        Optional<ResolvedOutput> vaultRef = node.l1().resolve(refs.vault());
        Optional<ResolvedOutput> shardRef = node.l1().resolve(refs.shard());
        if (vaultRef.isEmpty() || shardRef.isEmpty()) return "no reference scripts";

        List<String> relayed = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (ExitRelayer.Batch batch : batches) {
            // One Exit per shard per pass, the next chains on its output; a batch that fails leaves the shard to the
            // shard's next batch.
            if (used.contains(batch.shardIndex())) continue;
            List<ResolvedOutput> funding = wallet.funding(node.l1().at(wallet.address()), held);
            if (funding.isEmpty()) {
                relayed.add("no fee wallet funds");
                break;
            }
            // Custody from every page of the vault address (Codex P1, r22).
            List<ResolvedOutput> custody = node.l1().custody(pins, held);
            PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(pins, custody,
                    shardInputs.get(batch.shardIndex()), anchor.get().output(), exitMode.get(),
                    List.of(vaultRef.get(), shardRef.get()), new PayoutTxBuilder.Funding(funding, wallet.address()),
                    validFrom, slot + node.settings().validityWindowSlots(), pp, node.settings().maxVaultInputs());
            Optional<PayoutTxBuilder.Built> built;
            try {
                built = build(context, batch, proofs.get(), mirrors.get(batch.shardIndex()));
            } catch (PayoutTxBuilder.BuildException unbuildable) {
                // Nothing built: a plain failure, or no claim of the batch fits one Exit.
                relayed.add("shard " + batch.shardIndex() + ": " + unbuildable.getMessage());
                continue;
            }
            byte[] signed = wallet.witness(built.get().cbor(), built.get().txId(), Map.of());
            assembly.beforeSubmit(built.get().transaction().getBody().getTtl());
            String txHash = node.submit().apply(signed);
            if (txHash == null || !txHash.equalsIgnoreCase(built.get().txId().hex())) {
                relayed.add("submission returned another transaction id");
                continue;
            }
            // Held until the L1 view shows it landed or past its TTL; its inserts until committed state has them.
            OwnerAssembly.Pending hold = OwnerAssembly.Pending.payout(built.get().transaction(), built.get().txId(),
                    pins.vaultId(), batch.shardIndex(), built.get().claimIds());
            assembly.hold("exit:" + built.get().txId().hex(), hold);
            held.addAll(hold.spends());
            used.add(batch.shardIndex());
            relayed.add("relayed " + built.get().claimIds().size() + " on shard " + batch.shardIndex() + " in "
                    + built.get().txId());
        }
        return String.join(", ", relayed);
    }

    /**
     * The {@code Exit} of the batch's first claims that fit: halved on a per-transaction limit, on custody that does
     * not cover the batch (Codex P1-2: free custody after holds may pay a smaller one), or on fee funding or collateral
     * that does not cover it (a smaller batch needs less fee and supplement); a single claim that alone
     * does not fit is skipped (its user can self-relay with a larger supplement, or it waits for custody) and the next
     * ones are tried. Any other build failure is thrown to the caller, which reports it.
     */
    private Optional<PayoutTxBuilder.Built> build(PayoutTxBuilder.Context context, ExitRelayer.Batch batch,
                                                  StateProof.Source proofs, ShardMirror mirror) {
        List<ClaimLeafV1> claims = new ArrayList<>(batch.claims());
        int size = claims.size();
        PayoutTxBuilder.BuildException last = null;
        while (!claims.isEmpty()) {
            try {
                return Optional.of(ExitRelayer.build(context, new ExitRelayer.Batch(batch.shardIndex(),
                        claims.subList(0, size)), proofs, mirror, node.evaluator()));
            } catch (PayoutTxBuilder.LimitExceeded | PayoutTxBuilder.CustodyShort | PayoutTxBuilder.FundingShort
                     doesNotFit) {
                last = doesNotFit;
                if (size == 1) {
                    claims.removeFirst();
                    size = claims.size();
                } else {
                    size = size / 2;
                }
            }
        }
        if (last != null) throw last;
        return Optional.empty();
    }

    /**
     * The shard mirror (D8h §6): confirmed inserts plus this node's pending ones on the shard, falling back to
     * confirmed only; empty when neither matches the live shard root.
     */
    private Optional<ShardMirror> mirror(VaultPins pins, int shard, ResolvedOutput shardInput,
                                         CommittedClaims.Index index) {
        Optional<Hash32> liveRoot = shardInput.inlineDatum().flatMap(GatewayDatums::shardDatum)
                .filter(datum -> datum.shardIndex() == shard).map(GatewayDatums.ShardDatum::nullifierRoot);
        if (liveRoot.isEmpty()) return Optional.empty();
        List<Hash32> paid = index.paid(pins.vaultId(), shard);
        LinkedHashSet<Hash32> withOwn = new LinkedHashSet<>(paid);
        withOwn.addAll(assembly.inserts(pins.vaultId(), shard, paid, node.l1()));
        ShardMirror optimistic = ShardMirror.of(shard, withOwn);
        if (optimistic.matches(liveRoot.get())) return Optional.of(optimistic);
        ShardMirror confirmed = ShardMirror.of(shard, paid);
        return confirmed.matches(liveRoot.get()) ? Optional.of(confirmed) : Optional.empty();
    }

    private static Optional<DomainStatusV1> decodeStatus(byte[] value) {
        try {
            return Optional.of(DomainStatusV1.decode(value));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
