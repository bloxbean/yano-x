package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.yanoproject.x.assets.cardano.support.settlement.AcceptTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.GatewayPlutus;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.StateProof;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The owner's {@code Accept} batcher (ADR-059 D8f: "relayers batch it routinely"). No ledger effect asks for an
 * {@code Accept}, because credit never waits for one (D6); the owner runs one pass per vault on a timer.
 *
 * <p>One pass, under the node's {@link OwnerAssembly} lock, for each vault whose exit-mode thread is {@code NORMAL}
 * (proved acceptance in exit mode is M4a):
 * <ol>
 *   <li>take the deposit-shaped outputs at the vault address that no pending submission spends, and keep those whose
 *       record in committed state is {@code CREDITED} for this vault at or below the live anchor's height, in
 *       outpoint order, at most {@code accept.max-batch};</li>
 *   <li>build the co-signed {@code Accept} (halving on a per-transaction limit) and run the co-sign round, with the
 *       {@code Settle} executor's signer rotation: this node first when it is a member, then the members that answered
 *       the previous round, then the untried ones;</li>
 *   <li>when the round does not complete, fall back to the proved {@code Accept} of at most
 *       {@code accept.max-proved-batch} deposits, with proofs from the node at the live anchor's height (D8b);
 *       otherwise wait for the next pass;</li>
 *   <li>witness with the fee wallet, submit, and hold the spent outpoints for one validity window past the
 *       submission (its TTL plus a slack), or until the transaction is provably dead (D9 concurrency).</li>
 * </ol>
 * An {@code Accept} only moves credited value into spendable custody: it changes no ledger state, and its continuation
 * becomes custody a later {@code Settle} selects (V10).
 */
final class DepositAcceptor {
    private final NodeServices node;
    private final AssetCosignService cosign;
    private final byte[] memberPublicKey;
    private final OwnerAssembly assembly;
    private final FeeWallet wallet;
    /** Per vault: who answered the last incomplete round and who was tried. Guarded by the assembly lock. */
    private final Map<Hash32, AssetSettlementExecutor.SignerMemory> signerMemory = new HashMap<>();
    private volatile String lastOutcome = "idle";

    DepositAcceptor(NodeServices node, AssetCosignService cosign, byte[] memberPublicKey, OwnerAssembly assembly,
                    FeeWallet wallet) {
        this.node = Objects.requireNonNull(node, "node");
        this.cosign = Objects.requireNonNull(cosign, "cosign");
        this.memberPublicKey = memberPublicKey.clone();
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
            for (VaultPins pins : node.settings().pins()) outcomes.add(accept(pins));
            lastOutcome = String.join("; ", outcomes);
            return lastOutcome;
        });
    }

    private String accept(VaultPins pins) throws Exception {
        Optional<NodeL1View.Anchored> anchor = node.l1().anchor(pins);
        if (anchor.isEmpty()) return "no live anchor";
        Optional<ResolvedOutput> exitMode = node.l1().exitMode(pins);
        if (exitMode.isEmpty() || !normal(exitMode.get())) return "exit mode: acceptance by proof is M4a";
        Optional<ResolvedOutput> vaultRef = node.l1().resolve(node.settings().scriptRefs().get(pins.vaultId()).vault());
        if (vaultRef.isEmpty()) return "no vault reference script";
        long now = node.clock().getAsLong();
        Set<ResolvedOutput.Outpoint> held = assembly.held(now, node.l1());
        List<ResolvedOutput> deposits = creditedDeposits(pins, anchor.get().datum().height(), held);
        if (deposits.isEmpty()) return "nothing to accept";
        ProtocolParams pp = node.protocolParams().get();
        if (pp == null) return "no protocol parameters";
        List<ResolvedOutput> funding = wallet.funding(node.l1().at(wallet.address()), held);
        if (funding.isEmpty()) return "no fee wallet funds";
        AcceptTxBuilder.Context context = context(pins, anchor.get(), exitMode.get(), vaultRef.get(), funding, pp);

        // The co-signed Accept, the everyday path.
        AssetSettlementExecutor.SignerMemory memory = signerMemory.getOrDefault(pins.vaultId(),
                AssetSettlementExecutor.SignerMemory.NONE);
        List<byte[]> signerKeys = AssetSettlementExecutor.signers(memberPublicKey, anchor.get(), memory);
        Attempt cosigned = build(deposits, node.settings().tasks().acceptBatch(),
                batch -> AcceptTxBuilder.cosigned(context, batch,
                        signerKeys.stream().map(Blake2bUtil::blake2bHash224).toList(), node.evaluator()));
        String roundNote = "no co-signed Accept: " + cosigned.failure();
        if (cosigned.built() != null) {
            Set<String> requested = new LinkedHashSet<>();
            signerKeys.forEach(key -> requested.add(HexFormat.of().formatHex(key)));
            Optional<Map<String, byte[]>> signatures = cosign.collect(cosigned.built().cbor(), requested);
            if (signatures.isPresent() && signatures.get().keySet().containsAll(requested)) {
                signerMemory.remove(pins.vaultId());
                return submit(cosigned.built(), signatures.get(), "accepted (co-signed) ");
            }
            if (signatures.isPresent()) {
                signerMemory.put(pins.vaultId(), AssetSettlementExecutor.afterRound(memberPublicKey, memory,
                        requested, signatures.get().keySet(), anchor.get().datum().memberKeys()));
                roundNote = "co-sign round incomplete";
            } else {
                roundNote = "the owner's own Accept check refused";
            }
        }

        // The proved fallback: anyone may submit it; the script checks every proof against the anchored root. Its
        // validity interval starts from now, after the co-sign round's wait (Codex P2-4).
        Optional<StateProof.Source> proofs = node.anchoredProofs(anchor.get());
        if (proofs.isEmpty()) return roundNote + "; the live anchor has no anchored height";
        AcceptTxBuilder.Context provedContext = context(pins, anchor.get(), exitMode.get(), vaultRef.get(), funding,
                pp);
        Attempt proved = build(deposits, node.settings().tasks().acceptProvedBatch(),
                batch -> AcceptTxBuilder.proved(provedContext, batch, proofs.get(), node.evaluator()));
        if (proved.built() == null) return roundNote + "; no proved Accept: " + proved.failure();
        return submit(proved.built(), Map.of(), "accepted (proved) ");
    }

    /** The build context, its validity interval from this node's clock now: one validity window from the tip. */
    private AcceptTxBuilder.Context context(VaultPins pins, NodeL1View.Anchored anchor, ResolvedOutput exitMode,
                                            ResolvedOutput vaultRef, List<ResolvedOutput> funding, ProtocolParams pp) {
        long slot = node.settings().slots().slotAt(node.clock().getAsLong());
        return new AcceptTxBuilder.Context(pins, anchor.output(), exitMode, List.of(vaultRef),
                new PayoutTxBuilder.Funding(funding, wallet.address()), Math.max(0, slot - 60),
                slot + node.settings().validityWindowSlots(), pp);
    }

    /**
     * The deposit-shaped outputs of the vault, not held by a pending submission, recorded {@code CREDITED} for this
     * vault and outpoint in committed state at or below {@code anchorHeight}, in outpoint order.
     */
    private List<ResolvedOutput> creditedDeposits(VaultPins pins, long anchorHeight,
                                                  Set<ResolvedOutput.Outpoint> held) {
        List<ResolvedOutput> credited = new ArrayList<>();
        for (ResolvedOutput out : node.l1().at(pins.vaultAddress())) {
            if (held.contains(out.outpoint()) || !AcceptTxBuilder.depositShaped(out, pins)) continue;
            Hash32 depositId = AcceptTxBuilder.depositId(pins, out.outpoint());
            Optional<DepositRecordV1> record = node.committed().deposit(depositId);
            if (record.isPresent() && record.get().status() == DepositRecordV1.Status.CREDITED
                    && record.get().vaultId().equals(pins.vaultId()) && record.get().height() <= anchorHeight
                    && record.get().txId().equals(out.outpoint().txId())
                    && record.get().outputIndex() == out.outpoint().index()) {
                credited.add(out);
            }
        }
        credited.sort((a, b) -> a.outpoint().compareTo(b.outpoint()));
        return credited;
    }

    /** A built {@code Accept}, or why none was built. */
    record Attempt(AcceptTxBuilder.Built built, String failure) {
    }

    /** The first of {@code deposits} that fit: at most {@code cap}, halved on a per-transaction limit. */
    private static Attempt build(List<ResolvedOutput> deposits, int cap,
                                 java.util.function.Function<List<ResolvedOutput>, AcceptTxBuilder.Built> builder) {
        int size = Math.min(cap, deposits.size());
        String failure = "nothing to build";
        while (size >= 1) {
            try {
                return new Attempt(builder.apply(deposits.subList(0, size)), null);
            } catch (PayoutTxBuilder.LimitExceeded tooLarge) {
                failure = tooLarge.getMessage();
                size = size / 2;
            } catch (PayoutTxBuilder.BuildException unbuildable) {
                return new Attempt(null, unbuildable.getMessage());
            }
        }
        return new Attempt(null, failure);
    }

    private String submit(AcceptTxBuilder.Built built, Map<String, byte[]> signatures, String note)
            throws Exception {
        byte[] signed = wallet.witness(built.cbor(), built.txId(), signatures);
        String txHash = node.submit().apply(signed);
        if (txHash == null || !txHash.equalsIgnoreCase(built.txId().hex())) {
            return "submission returned another transaction id";
        }
        // Dated from this successful submission: one validity window (the TTL) plus a slack.
        assembly.hold("accept:" + built.txId().hex(), OwnerAssembly.Hold.spending(built.transaction(), built.txId(),
                OwnerAssembly.expiry(node.clock().getAsLong(), node.settings().validityWindowMs())));
        return note + built.depositIds().size() + " deposits in " + built.txId();
    }

    static boolean normal(ResolvedOutput exitMode) {
        return exitMode.inlineDatum().map(datum -> {
            try {
                return GatewayPlutus.isNormalMode(PlutusData.deserialize(datum));
            } catch (Exception malformed) {
                return false;
            }
        }).orElse(false);
    }
}
