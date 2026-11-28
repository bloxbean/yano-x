package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.framework.api.appchain.AppChainConsensusProfile;
import org.yanoproject.x.framework.api.appchain.AppChainMembershipView;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;

/**
 * Service-loaded provider of the {@code asset-payments} state machine (ADR-059 D14). Selected by a
 * chain's {@code state-machine: asset-payments} and configured under
 * {@code machines.asset-payments.*} (see {@link AssetPaymentsSettings}). Startup fails closed without
 * the chain's consensus profile, or when its {@code l1.stability-depth} is below the profile minimum
 * (D5). Reserved claims are batched into {@code asset.settlement.batch} effects only when the
 * profile enables effects; without them claims stay payable by the proof {@code Exit} (D8). With effects, a batch
 * holds at most what {@code effects.max-payload-bytes} carries, and startup fails when the batch expiry lies outside
 * the chain's effect expiry window.
 */
public final class AssetPaymentsStateMachineProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return AssetPaymentsContract.STATE_MACHINE_ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalArgumentException("asset-payments requires its machines.asset-payments.* configuration");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        AssetPaymentsSettings settings = AssetPaymentsSettings.parse(context.chainId(), context.settings());
        AppChainConsensusProfile profile = context.consensusProfile().orElseThrow(() ->
                new IllegalArgumentException("asset-payments requires the chain's consensus profile"));
        if (profile.l1StabilityDepth() < settings.minStabilityDepth()) {
            throw new IllegalArgumentException("asset-payments requires l1.stability-depth >= "
                    + settings.minStabilityDepth());
        }
        // D7 step 4: settlement batches are effects, so they are emitted only on a chain with effects enabled.
        // The profile is consensus configuration, so every member makes the same choice.
        // Governed commands are decided against the membership epoch at each block's height (D14).
        AppChainMembershipView membership = context.membershipView().orElse(null);
        if (!profile.effectsEnabled()) {
            return new AssetPaymentsStateMachine(settings, profile.blockMaxForwardSkewMs(), false,
                    settings.settlement().batchSize(), membership);
        }
        AssetPaymentsSettings.Settlement settlement = settings.settlement();
        int payloadCap = SettlementBatchV1.maxClaimsWithin(profile.effectsMaxPayloadBytes());
        if (payloadCap < 1) {
            throw new IllegalArgumentException("effects.max-payload-bytes (" + profile.effectsMaxPayloadBytes()
                    + ") cannot carry one asset.settlement.batch");
        }
        long expiryWindow = Math.min(profile.effectsMaxExpiryBlocks(), profile.effectsResultWindowBlocks());
        if (settlement.expiryBlocks() > expiryWindow) {
            // The runtime would refuse every batch effect at emission, so no claim would ever be batched.
            throw new IllegalArgumentException("machines.asset-payments.settlement.expiry-blocks ("
                    + settlement.expiryBlocks() + ") exceeds the chain's effect expiry window (" + expiryWindow + ")");
        }
        return new AssetPaymentsStateMachine(settings, profile.blockMaxForwardSkewMs(), true,
                Math.min(settlement.batchSize(), payloadCap), membership);
    }
}
