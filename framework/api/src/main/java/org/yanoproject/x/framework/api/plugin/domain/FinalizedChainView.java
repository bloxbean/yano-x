package org.yanoproject.x.framework.api.plugin.domain;

import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;

import java.util.Optional;

/** Read-only, finalized chain surface available to local read-model plugins. */
public interface FinalizedChainView {
    String chainId();

    long tipHeight();

    Optional<AppBlock> block(long height);

    AppQueryResult query(String path, byte[] request);

    Optional<StateCommitmentIdentity> stateCommitmentIdentity();

    AutoCloseable subscribe(FinalizedBlockListener listener);

    @FunctionalInterface
    interface FinalizedBlockListener {
        void onFinalized(AppBlock block, byte[] blockHash);
    }
}
