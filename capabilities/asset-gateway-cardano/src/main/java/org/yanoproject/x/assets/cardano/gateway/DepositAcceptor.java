package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.yanoproject.x.assets.cardano.support.settlement.AcceptTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.GatewayPlutus;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableSettlement;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableVaultTxAssembler;
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
 * The {@code Accept} batcher (ADR-059 D8f: "relayers batch it routinely"). No ledger effect asks for an
 * {@code Accept}, because credit never waits for one (D6); the owner runs one pass per vault on a timer. A non-owner
 * with {@code exit-relayer.enabled} runs the proved half only ({@link #provedOnly}), with its own fee wallet and
 * assembly, so relayers keep custody flowing into the vault while the owner is down (Fable P2-2).
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
 *   <li>witness with the fee wallet, persist its TTL slot, submit, and hold the spent outpoints until the node's L1
 *       view shows the transaction landed or past its TTL slot (D9 concurrency).</li>
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
    /**
     * Per vault: the first page of the next pass's window of vault outputs (Codex P2-7). Each pass reads one window
     * of {@link NodeL1View#MAX_PAGES} pages and moves on, wrapping to the first page after the last, so every output
     * of a vault with more than one window of outputs is eventually reached. Guarded by the assembly lock.
     */
    private final Map<Hash32, Integer> scanFrom = new HashMap<>();
    private volatile String lastOutcome = "idle";
    /** The status and metrics of this task (D15a). */
    private final TaskProduct product = new TaskProduct(TaskProduct.ACCEPT);

    DepositAcceptor(NodeServices node, AssetCosignService cosign, byte[] memberPublicKey, OwnerAssembly assembly,
                    FeeWallet wallet) {
        this.node = Objects.requireNonNull(node, "node");
        this.cosign = cosign;
        this.memberPublicKey = cosign == null ? null : memberPublicKey.clone();
        this.assembly = Objects.requireNonNull(assembly, "assembly");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    /** A batcher that never runs a co-sign round: it submits proved {@code Accept}s only, as any relayer may. */
    static DepositAcceptor provedOnly(NodeServices node, OwnerAssembly assembly, FeeWallet wallet) {
        return new DepositAcceptor(node, null, null, assembly, wallet);
    }

    String lastOutcome() {
        return lastOutcome;
    }

    TaskProduct product() {
        return product;
    }

    /** Whether one vault's outcome submitted an {@code Accept}, or found nothing to accept. */
    static boolean acted(String outcome) {
        return outcome.startsWith("accepted ");
    }

    static boolean idle(String outcome) {
        return outcome.equals("nothing to accept");
    }

    /** One pass over every vault; returns what happened, per vault. One vault's failure does not stop the others. */
    String runOnce() throws Exception {
        return assembly.serialized(() -> {
            if (assembly.warmingUp(node.clock().getAsLong(), node.l1())) {
                product.record(TaskProduct.Pass.WAITING);
                return lastOutcome = OwnerAssembly.WARMING_UP;
            }
            List<String> outcomes = new ArrayList<>();
            for (VaultPins pins : node.settings().pins()) {
                try {
                    outcomes.add(accept(pins));
                } catch (InterruptedException interrupted) {
                    throw interrupted;
                } catch (Exception failure) {
                    outcomes.add("vault " + pins.vaultId() + " failed: " + failure);
                }
            }
            product.record(TaskProduct.worst(outcomes, DepositAcceptor::acted, DepositAcceptor::idle));
            lastOutcome = String.join("; ", outcomes);
            return lastOutcome;
        });
    }

    private String accept(VaultPins pins) throws Exception {
        Optional<NodeL1View.Anchored> anchor = node.l1().anchor(pins);
        if (anchor.isEmpty()) return "no live anchor";
        Optional<ResolvedOutput> exitMode = node.l1().exitMode(pins);
        if (exitMode.isEmpty() || !normal(exitMode.get())) return "exit mode: acceptance by proof is M4a";
        AssetSettlementSettings.ScriptRefs refs = node.settings().scriptRefs().get(pins.vaultId());
        Optional<ResolvedOutput> vaultRef = node.l1().resolve(refs.vault());
        if (vaultRef.isEmpty()) return "no vault reference script";
        // ADR-061: a CIP-113 vault's Accept runs the deployment's scripts too, under the live wiring.
        List<ResolvedOutput> scriptRefs = List.of(vaultRef.get());
        ProgrammableSettlement.Cip113 cip113 = null;
        if (pins.cip113()) {
            Optional<List<ResolvedOutput>> all = Cip113Runtime.referenceScripts(pins, node.l1(), refs, false);
            if (all.isEmpty()) return "no CIP-113 reference scripts";
            scriptRefs = all.get();
            try {
                cip113 = Cip113Runtime.context(pins, node.l1(), node.committed()).orElse(null);
            } catch (ProgrammableVaultTxAssembler.Drift drift) {
                return "CIP-113 wiring drift: " + drift.getMessage();
            }
            if (cip113 == null) return "the live CIP-113 params UTxO is unavailable";
        }
        long now = node.clock().getAsLong();
        Set<ResolvedOutput.Outpoint> held = assembly.held(node.l1());
        List<ResolvedOutput> deposits = creditedDeposits(pins, anchor.get().datum().height(), held, now);
        if (deposits.isEmpty()) return "nothing to accept";
        ProtocolParams pp = node.protocolParams().get();
        if (pp == null) return "no protocol parameters";
        List<ResolvedOutput> funding = wallet.funding(node.l1().at(wallet.address()), held);
        if (funding.isEmpty()) return "no fee wallet funds";

        // The co-signed Accept, the everyday path; a relayer that is not the owner only proves (Fable P2-2).
        String roundNote = "proved only";
        if (cosign != null) {
            AcceptTxBuilder.Context context = context(pins, anchor.get(), exitMode.get(), scriptRefs, funding, pp);
            ProgrammableSettlement.Cip113 programmable = cip113;
            AssetSettlementExecutor.SignerMemory memory = signerMemory.getOrDefault(pins.vaultId(),
                    AssetSettlementExecutor.SignerMemory.NONE);
            List<byte[]> signerKeys = AssetSettlementExecutor.signers(memberPublicKey, anchor.get(), memory);
            List<byte[]> required = signerKeys.stream().map(Blake2bUtil::blake2bHash224).toList();
            Attempt cosigned = build(deposits, node.settings().tasks().acceptBatch(),
                    batch -> programmable != null
                            ? ProgrammableSettlement.acceptCosigned(context, programmable, batch, required,
                            node.evaluator())
                            : AcceptTxBuilder.cosigned(context, batch, required, node.evaluator()));
            roundNote = "no co-signed Accept: " + cosigned.failure();
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
        }

        // The proved fallback: anyone may submit it; the script checks every proof against the anchored root. Its
        // validity interval starts from now, after the co-sign round's wait (Codex P2-4).
        if (pins.cip113()) return roundNote + "; a proved CIP-113 Accept is not built by this release";
        Optional<StateProof.Source> proofs = node.anchoredProofs(anchor.get());
        if (proofs.isEmpty()) return roundNote + "; the live anchor has no anchored height";
        AcceptTxBuilder.Context provedContext = context(pins, anchor.get(), exitMode.get(), scriptRefs, funding, pp);
        Attempt proved = build(deposits, node.settings().tasks().acceptProvedBatch(),
                batch -> AcceptTxBuilder.proved(provedContext, batch, proofs.get(), node.evaluator()));
        if (proved.built() == null) return roundNote + "; no proved Accept: " + proved.failure();
        return submit(proved.built(), Map.of(), "accepted (proved) ");
    }

    /** The build context, its validity interval from this node's clock now: one validity window from the tip. */
    private AcceptTxBuilder.Context context(VaultPins pins, NodeL1View.Anchored anchor, ResolvedOutput exitMode,
                                            List<ResolvedOutput> scriptRefs, List<ResolvedOutput> funding,
                                            ProtocolParams pp) {
        long slot = node.settings().slots().slotAt(node.clock().getAsLong());
        return new AcceptTxBuilder.Context(pins, anchor.output(), exitMode, scriptRefs,
                new PayoutTxBuilder.Funding(funding, wallet.address()), Math.max(0, slot - 60),
                slot + node.settings().validityWindowSlots(), pp);
    }

    /**
     * The deposit-shaped outputs of the vault in this pass's window, not held by a pending submission, recorded
     * {@code CREDITED} for this vault and outpoint in committed state at or below {@code anchorHeight}, in outpoint
     * order. A relaying non-owner keeps only deposits that could have been {@code CREDITED} at least
     * {@code accept.relayer-delay-ms} ago, counting from the L1 output's time plus the stability window, so it proves
     * what a healthy owner has left (Fable P3-2, reviewer3 M4S3a-F2).
     */
    private List<ResolvedOutput> creditedDeposits(VaultPins pins, long anchorHeight,
                                                  Set<ResolvedOutput.Outpoint> held, long now) {
        long youngest = cosign == null ? now - node.settings().stabilityWindow().toMillis()
                - node.settings().tasks().relayerAcceptDelayMs() : Long.MAX_VALUE;
        int from = scanFrom.getOrDefault(pins.vaultId(), 1);
        NodeL1View.Window window = node.l1().window(pins.vaultAddress(), from);
        scanFrom.put(pins.vaultId(), window.more() ? from + NodeL1View.MAX_PAGES : 1);
        List<ResolvedOutput> credited = new ArrayList<>();
        Set<Hash32> ids = new java.util.HashSet<>();
        for (ResolvedOutput out : window.outputs()) {
            if (held.contains(out.outpoint())) continue;
            // ADR-061 D6, D10 rule 8: a CIP-113 deposit is looked up by its origin-keyed DepositId and accepted at
            // whichever live outpoint carries it; a native one by its outpoint.
            Optional<Hash32> id = pins.cip113() ? ProgrammableSettlement.depositId(pins, out)
                    : AcceptTxBuilder.depositShaped(out, pins)
                    ? Optional.of(AcceptTxBuilder.depositId(pins, out.outpoint())) : Optional.empty();
            if (id.isEmpty() || !ids.add(id.get())) continue;
            Optional<DepositRecordV1> record = node.committed().deposit(id.get());
            boolean sameOutput = pins.cip113() || (record.isPresent()
                    && record.get().txId().equals(out.outpoint().txId())
                    && record.get().outputIndex() == out.outpoint().index());
            if (record.isPresent() && record.get().status() == DepositRecordV1.Status.CREDITED
                    && record.get().vaultId().equals(pins.vaultId()) && record.get().height() <= anchorHeight
                    && sameOutput && node.timeOf(record.get().slot()) <= youngest) {
                credited.add(out);
            }
        }
        credited.sort((a, b) -> a.outpoint().compareTo(b.outpoint()));
        return credited;
    }

    /** A built {@code Accept}, or why none was built. */
    record Attempt(AcceptTxBuilder.Built built, String failure) {
    }

    /** The first of {@code deposits} that fit: at most {@code cap}, halved on a per-transaction limit or short funding. */
    static Attempt build(List<ResolvedOutput> deposits, int cap,
                                 java.util.function.Function<List<ResolvedOutput>, AcceptTxBuilder.Built> builder) {
        int size = Math.min(cap, deposits.size());
        String failure = "nothing to build";
        while (size >= 1) {
            try {
                return new Attempt(builder.apply(deposits.subList(0, size)), null);
            } catch (PayoutTxBuilder.LimitExceeded | PayoutTxBuilder.FundingShort tooLarge) {
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
        assembly.beforeSubmit(built.transaction().getBody().getTtl());
        String txHash = node.submit().apply(signed);
        if (txHash == null || !txHash.equalsIgnoreCase(built.txId().hex())) {
            return "submission returned another transaction id";
        }
        // Held until the L1 view shows it landed or past its TTL slot.
        assembly.hold("accept:" + built.txId().hex(), OwnerAssembly.Pending.spending(built.transaction(),
                built.txId()));
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
