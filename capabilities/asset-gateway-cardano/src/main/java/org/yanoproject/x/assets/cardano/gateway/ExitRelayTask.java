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
 *       its inputs and inserts for one validity window past the submission (its TTL plus a slack), or until it is
 *       provably dead.</li>
 * </ol>
 * Polling for inclusion is the payout observer's: a relayed claim leaves the relayer's view once it is {@code PAID}
 * in committed state, and its pending insert keeps it out of later passes until then.
 */
final class ExitRelayTask {
    private final NodeServices node;
    private final OwnerAssembly assembly;
    private final FeeWallet wallet;
    private volatile String lastOutcome = "idle";

    ExitRelayTask(NodeServices node, OwnerAssembly assembly, FeeWallet wallet) {
        this.node = Objects.requireNonNull(node, "node");
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    String lastOutcome() {
        return lastOutcome;
    }

    /** One pass over every vault; returns what happened, per vault. */
    String runOnce() throws Exception {
        return assembly.serialized(() -> {
            List<String> outcomes = new ArrayList<>();
            for (VaultPins pins : node.settings().pins()) outcomes.add(relay(pins));
            lastOutcome = String.join("; ", outcomes);
            return lastOutcome;
        });
    }

    private String relay(VaultPins pins) throws Exception {
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
        Optional<List<ExitRelayer.Candidate>> reserved = node.committed().reserved(pins.vaultId());
        if (reserved.isEmpty()) return "committed claims unavailable";
        if (reserved.get().isEmpty()) return "no reserved claims";

        long now = node.clock().getAsLong();
        Set<ResolvedOutput.Outpoint> held = new HashSet<>(assembly.held(now, node.l1()));
        Map<Integer, ResolvedOutput> shardInputs = new HashMap<>();
        Map<Integer, ShardMirror> mirrors = new HashMap<>();
        Set<Integer> stopped = new HashSet<>();
        List<ExitRelayer.Candidate> relayable = new ArrayList<>();
        for (ExitRelayer.Candidate candidate : reserved.get()) {
            int shard = SettlementBatchV1.shardOf(candidate.leaf().claimId());
            if (stopped.contains(shard)) continue;
            if (!mirrors.containsKey(shard)) {
                Optional<ResolvedOutput> input = node.l1().shard(pins, shard);
                Optional<ShardMirror> mirror = input.flatMap(in -> mirror(pins, shard, in));
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
            if (!used.add(batch.shardIndex())) continue; // one Exit per shard per pass; the next chains on its output
            List<ResolvedOutput> funding = wallet.funding(node.l1().at(wallet.address()), held);
            if (funding.isEmpty()) {
                relayed.add("no fee wallet funds");
                break;
            }
            List<ResolvedOutput> custody = new ArrayList<>(node.l1().at(pins.vaultAddress()));
            custody.removeIf(out -> held.contains(out.outpoint()));
            PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(pins, custody,
                    shardInputs.get(batch.shardIndex()), anchor.get().output(), exitMode.get(),
                    List.of(vaultRef.get(), shardRef.get()), new PayoutTxBuilder.Funding(funding, wallet.address()),
                    validFrom, slot + node.settings().validityWindowSlots(), pp, node.settings().maxVaultInputs());
            Optional<PayoutTxBuilder.Built> built;
            try {
                built = build(context, batch, proofs.get(), mirrors.get(batch.shardIndex()));
            } catch (PayoutTxBuilder.BuildException unbuildable) {
                // Nothing built: a plain failure, or (LimitExceeded) no claim fits one Exit.
                relayed.add("shard " + batch.shardIndex() + ": " + unbuildable.getMessage());
                continue;
            }
            byte[] signed = wallet.witness(built.get().cbor(), built.get().txId(), Map.of());
            String txHash = node.submit().apply(signed);
            if (txHash == null || !txHash.equalsIgnoreCase(built.get().txId().hex())) {
                relayed.add("submission returned another transaction id");
                continue;
            }
            // Dated from this successful submission: one validity window (the TTL) plus a slack.
            OwnerAssembly.Hold hold = OwnerAssembly.Hold.payout(built.get().transaction(), built.get().txId(),
                    pins.vaultId(), batch.shardIndex(), built.get().claimIds(),
                    OwnerAssembly.expiry(node.clock().getAsLong(), node.settings().validityWindowMs()));
            assembly.hold("exit:" + built.get().txId().hex(), hold);
            held.addAll(hold.spends());
            relayed.add("relayed " + built.get().claimIds().size() + " on shard " + batch.shardIndex() + " in "
                    + built.get().txId());
        }
        return String.join(", ", relayed);
    }

    /**
     * The {@code Exit} of the batch's first claims that fit: halved on a per-transaction limit; a single claim that
     * alone exceeds one is skipped (its user can self-relay with a larger supplement) and the next ones are tried.
     * Any other build failure is thrown to the caller, which reports it.
     */
    private Optional<PayoutTxBuilder.Built> build(PayoutTxBuilder.Context context, ExitRelayer.Batch batch,
                                                  StateProof.Source proofs, ShardMirror mirror) {
        List<ClaimLeafV1> claims = new ArrayList<>(batch.claims());
        int size = claims.size();
        PayoutTxBuilder.LimitExceeded last = null;
        while (!claims.isEmpty()) {
            try {
                return Optional.of(ExitRelayer.build(context, new ExitRelayer.Batch(batch.shardIndex(),
                        claims.subList(0, size)), proofs, mirror, node.evaluator()));
            } catch (PayoutTxBuilder.LimitExceeded tooLarge) {
                last = tooLarge;
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
    private Optional<ShardMirror> mirror(VaultPins pins, int shard, ResolvedOutput shardInput) {
        Optional<Hash32> liveRoot = shardInput.inlineDatum().flatMap(GatewayDatums::shardDatum)
                .filter(datum -> datum.shardIndex() == shard).map(GatewayDatums.ShardDatum::nullifierRoot);
        Optional<List<Hash32>> paid = node.committed().paid(pins.vaultId(), shard);
        if (liveRoot.isEmpty() || paid.isEmpty()) return Optional.empty();
        LinkedHashSet<Hash32> withOwn = new LinkedHashSet<>(paid.get());
        withOwn.addAll(assembly.inserts(pins.vaultId(), shard));
        ShardMirror optimistic = ShardMirror.of(shard, withOwn);
        if (optimistic.matches(liveRoot.get())) return Optional.of(optimistic);
        ShardMirror confirmed = ShardMirror.of(shard, paid.get());
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
