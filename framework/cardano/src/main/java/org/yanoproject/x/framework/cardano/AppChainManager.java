package org.yanoproject.x.framework.cardano;

import org.slf4j.Logger;
import org.yanoproject.runtime.kernel.SubsystemContext;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochStateProvider;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Optional Cardano specialization retaining the established manager constructor and wiring API. */
public final class AppChainManager extends AppLedgerManager {
    private final List<AppChainSubsystem> cardanoChains;
    private final Supplier<Optional<L1EpochStateProvider>> epochState;

    public AppChainManager(List<AppChainSubsystem> subsystems, Logger log) {
        this(subsystems, log, Optional::empty);
    }

    /**
     * @param epochState the host's epoch-pinned ledger view, resolved once at kernel initialization: the host
     *                   wires it after ledger-state assembly, and an empty result leaves epoch observation
     *                   unavailable
     */
    public AppChainManager(List<AppChainSubsystem> subsystems, Logger log,
                           Supplier<Optional<L1EpochStateProvider>> epochState) {
        super(subsystems, log);
        cardanoChains = List.copyOf(subsystems);
        this.epochState = Objects.requireNonNull(epochState, "epochState");
    }

    @Override
    public void init(SubsystemContext context) {
        super.init(context);
        epochState.get().ifPresent(this::wireL1EpochState);
    }

    /** Wire the host's epoch-pinned ledger view after ledger-state assembly completes. */
    public void wireL1EpochState(L1EpochStateProvider provider) {
        Objects.requireNonNull(provider, "provider");
        cardanoChains.forEach(chain -> chain.wireL1EpochState(provider));
    }
}
