package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import org.yanoproject.api.EpochBoundary;
import org.yanoproject.api.EpochSnapshot;
import org.yanoproject.api.EpochSnapshotReader;
import org.yanoproject.x.framework.api.appchain.l1view.GovernanceActionType;
import org.yanoproject.x.framework.api.appchain.l1view.GovernanceProposalStatus;
import org.yanoproject.x.framework.api.appchain.l1view.GovernanceProposalStatusReason;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochBoundary;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochState;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochStateProvider;
import org.yanoproject.x.framework.api.appchain.l1view.ProtocolParamsView;
import org.yanoproject.x.framework.cardano.api.l1view.ProtocolParamsCanonicalCodec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bridge from the host's {@link EpochSnapshotReader} to the app-chain epoch SPI. It maps types only: the
 * governance action type through the exhaustive switch that the ledger state used, status and reason by name,
 * and protocol parameters through the canonical codec.
 */
final class CardanoEpochStateProvider implements L1EpochStateProvider {
    private final EpochSnapshotReader reader;

    CardanoEpochStateProvider(EpochSnapshotReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override public boolean persistent() { return reader.persistent(); }
    @Override public int snapshotRetentionEpochs() { return reader.snapshotRetentionEpochs(); }
    @Override public long epochAtSlot(long slot) { return reader.epochAtSlot(slot); }
    @Override public long firstObservableEpoch() { return reader.firstObservableEpoch(); }

    @Override
    public List<L1EpochBoundary> completedBoundaries(long afterNewEpoch, int limit) {
        return reader.completedBoundaries(afterNewEpoch, limit).stream()
                .map(boundary -> new L1EpochBoundary(boundary.previousEpoch(), boundary.newEpoch(),
                        boundary.slot(), boundary.blockHash(), boundary.blockNumber()))
                .toList();
    }

    @Override
    public Optional<L1EpochState> open(L1EpochBoundary boundary) {
        return reader.open(new EpochBoundary(boundary.previousEpoch(), boundary.newEpoch(),
                        boundary.boundarySlot(), boundary.boundaryBlockHash(), boundary.boundaryBlockNumber()))
                .map(State::new);
    }

    static GovernanceActionType actionType(
            GovActionType type) {
        return switch (type) {
            case PARAMETER_CHANGE_ACTION ->
                    GovernanceActionType.PARAMETER_CHANGE;
            case HARD_FORK_INITIATION_ACTION ->
                    GovernanceActionType.HARD_FORK_INITIATION;
            case TREASURY_WITHDRAWALS_ACTION ->
                    GovernanceActionType.TREASURY_WITHDRAWALS;
            case NO_CONFIDENCE ->
                    GovernanceActionType.NO_CONFIDENCE;
            case UPDATE_COMMITTEE ->
                    GovernanceActionType.UPDATE_COMMITTEE;
            case NEW_CONSTITUTION ->
                    GovernanceActionType.NEW_CONSTITUTION;
            case INFO_ACTION ->
                    GovernanceActionType.INFO_ACTION;
        };
    }

    static GovernanceProposalStatus status(org.yanoproject.api.governance.GovernanceProposalStatus status) {
        return byName(GovernanceProposalStatus.class, status);
    }

    static GovernanceProposalStatusReason reason(
            org.yanoproject.api.governance.GovernanceProposalStatusReason reason) {
        return byName(GovernanceProposalStatusReason.class, reason);
    }

    /** An unmappable Yano constant fails the governance view closed instead of being dropped. */
    private static <E extends Enum<E>> E byName(Class<E> type, Enum<?> value) {
        Objects.requireNonNull(value, type.getSimpleName());
        try {
            return Enum.valueOf(type, value.name());
        } catch (IllegalArgumentException unmapped) {
            throw new IllegalStateException("Host governance value " + value.getDeclaringClass().getName() + "."
                    + value.name() + " has no app-chain " + type.getSimpleName(), unmapped);
        }
    }

    private record State(EpochSnapshot snapshot) implements L1EpochState {
        @Override public long previousEpoch() { return snapshot.previousEpoch(); }
        @Override public long newEpoch() { return snapshot.newEpoch(); }

        @Override
        public ProtocolParamsView protocolParams(long effectiveEpoch) {
            return new ProtocolParamsView(effectiveEpoch,
                    ProtocolParamsCanonicalCodec.encode(snapshot.protocolParams(effectiveEpoch)));
        }

        @Override public boolean hasStakeSnapshot(long epoch) { return snapshot.hasStakeSnapshot(epoch); }

        @Override
        public void forEachStakeEntry(long epoch, StakeEntryConsumer consumer) {
            snapshot.forEachStakeEntry(epoch, consumer::accept);
        }

        @Override
        public boolean hasProposalStatusSnapshot(long epoch) {
            return snapshot.hasProposalStatusSnapshot(epoch);
        }

        @Override
        public boolean hasDRepDistributionSnapshot(long epoch) {
            return snapshot.hasDRepDistributionSnapshot(epoch);
        }

        @Override
        public void forEachProposalStatus(long epoch, ProposalStatusConsumer consumer) {
            snapshot.forEachProposalStatus(epoch, (transactionId, index, actionType, status, reason,
                                                   proposedEpoch, expiresAfterEpoch) ->
                    consumer.accept(transactionId, index, actionType(actionType), status(status), reason(reason),
                            proposedEpoch, expiresAfterEpoch));
        }

        @Override
        public void forEachDRepDistributionEntry(long epoch, DRepDistributionConsumer consumer) {
            snapshot.forEachDRepDistributionEntry(epoch, consumer::accept);
        }

        @Override public void close() { snapshot.close(); }
    }
}
