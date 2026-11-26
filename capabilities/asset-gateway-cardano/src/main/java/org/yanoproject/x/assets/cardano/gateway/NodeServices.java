package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.yanoproject.x.assets.cardano.support.settlement.NodeStateProofs;
import org.yanoproject.x.assets.cardano.support.settlement.StateProof;
import org.yanoproject.x.framework.api.appchain.state.StateProofEnvelope;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the periodic settlement tasks read and use of their node: settings, the L1 view, committed state, protocol
 * parameters, the phase-2 evaluator, submission, the wall clock, and the node's state proofs with the L1-stable anchor
 * frontier (ADR-059 D8b, D9).
 */
record NodeServices(AssetSettlementSettings settings, NodeL1View l1, CommittedClaims committed,
                    Supplier<ProtocolParams> protocolParams, TransactionEvaluator evaluator,
                    Function<byte[], String> submit, LongSupplier clock, NodeStateProofs.AtHeight proofs,
                    LongSupplier stableAnchoredHeight) {
    NodeServices {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(l1, "l1");
        Objects.requireNonNull(committed, "committed");
        Objects.requireNonNull(protocolParams, "protocol params");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(proofs, "proofs");
        Objects.requireNonNull(stableAnchoredHeight, "stable anchored height");
    }

    /**
     * The node's proof service as {@link NodeStateProofs.AtHeight}: a present (inclusion) proof of the key at exactly
     * the requested finalized height, with the root it is proved under. An absent or tombstoned key, or a proof the
     * node no longer retains, is empty.
     */
    static NodeStateProofs.AtHeight proofsOf(AppChainEffectContext context) {
        return (height, key) -> {
            Optional<StateProofEnvelope> envelope = context.stateProofAtHeight(height, key);
            return envelope.map(StateProofEnvelope::proof)
                    .filter(proof -> proof.presence()
                            == org.yanoproject.x.framework.api.appchain.state.StateProof.Presence.PRESENT
                            && proof.snapshot().height() == height)
                    .map(proof -> new NodeStateProofs.NativeProof(proof.snapshot().height(),
                            proof.snapshot().stateRoot(), proof.canonicalKey(), proof.value(), proof.nativeProof()));
        };
    }

    /**
     * The state proofs under the referenced anchor's root (D8b): only once that anchor is L1-stable, so its height is
     * at or below the node's L1-stable anchor frontier and therefore within the proof floor. An anchor not yet stable
     * has no source: the caller waits.
     */
    Optional<StateProof.Source> anchoredProofs(NodeL1View.Anchored anchor) {
        long height = anchor.datum().height();
        long stable;
        try {
            stable = stableAnchoredHeight.getAsLong();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
        if (height < 1 || height > stable) return Optional.empty();
        return Optional.of(NodeStateProofs.at(height, anchor.datum().stateRoot().bytes(), proofs));
    }

    /** The L1 time of {@code slot} on the pinned slot clock. */
    long timeOf(long slot) {
        return settings.slots().timeOf(slot);
    }
}
