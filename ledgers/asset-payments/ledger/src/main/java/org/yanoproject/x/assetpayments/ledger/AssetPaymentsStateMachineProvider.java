package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.framework.api.appchain.AppChainConsensusProfile;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;

/**
 * Service-loaded provider of the {@code asset-payments} state machine (ADR-059 D14). Selected by a
 * chain's {@code state-machine: asset-payments} and configured under
 * {@code machines.asset-payments.*} (see {@link AssetPaymentsSettings}). Startup fails closed when
 * the chain's {@code l1.stability-depth} is below the profile minimum (D5).
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
        long skew = context.consensusProfile().map(AppChainConsensusProfile::blockMaxForwardSkewMs)
                .orElse(AppChainConsensusProfile.DEFAULT_BLOCK_MAX_FORWARD_SKEW_MS);
        context.consensusProfile().ifPresent(profile -> {
            if (profile.l1StabilityDepth() < settings.minStabilityDepth()) {
                throw new IllegalArgumentException("asset-payments requires l1.stability-depth >= "
                        + settings.minStabilityDepth());
            }
        });
        return new AssetPaymentsStateMachine(settings, skew);
    }
}
