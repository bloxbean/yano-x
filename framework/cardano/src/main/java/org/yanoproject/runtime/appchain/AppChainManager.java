package org.yanoproject.runtime.appchain;

import org.yanoproject.api.appchain.l1view.L1EpochStateProvider;
import org.slf4j.Logger;
import org.yanoproject.api.host.N2NExtensionRegistry;

import java.util.List;
import java.util.Objects;

/** Optional Cardano specialization retaining the established manager constructor and wiring API. */
public final class AppChainManager extends AppLedgerManager {
    private final List<AppChainSubsystem> cardanoChains;

    public AppChainManager(List<AppChainSubsystem> subsystems, Logger log) {
        super(subsystems, log);
        cardanoChains = List.copyOf(subsystems);
    }

    public void registerProtocols(N2NExtensionRegistry registry) {
        serverAgentRegistrations().forEach(registry::registerInbound);
    }

    /** Wire the host's epoch-pinned ledger view after ledger-state assembly completes. */
    public void wireL1EpochState(L1EpochStateProvider provider) {
        Objects.requireNonNull(provider, "provider");
        cardanoChains.forEach(chain -> chain.wireL1EpochState(provider));
    }
}
