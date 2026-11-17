package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.EpochBoundary;
import org.yanoproject.api.EpochSnapshot;
import org.yanoproject.api.EpochSnapshotReader;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.x.framework.api.appchain.l1view.GovernanceActionType;
import org.yanoproject.x.framework.api.appchain.l1view.GovernanceProposalStatus;
import org.yanoproject.x.framework.api.appchain.l1view.GovernanceProposalStatusReason;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochBoundary;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoEpochStateProviderTest {
    private static final Map<GovActionType, GovernanceActionType> ACTION_TYPES = new EnumMap<>(Map.of(
            GovActionType.PARAMETER_CHANGE_ACTION, GovernanceActionType.PARAMETER_CHANGE,
            GovActionType.HARD_FORK_INITIATION_ACTION, GovernanceActionType.HARD_FORK_INITIATION,
            GovActionType.TREASURY_WITHDRAWALS_ACTION, GovernanceActionType.TREASURY_WITHDRAWALS,
            GovActionType.NO_CONFIDENCE, GovernanceActionType.NO_CONFIDENCE,
            GovActionType.UPDATE_COMMITTEE, GovernanceActionType.UPDATE_COMMITTEE,
            GovActionType.NEW_CONSTITUTION, GovernanceActionType.NEW_CONSTITUTION,
            GovActionType.INFO_ACTION, GovernanceActionType.INFO_ACTION));

    @Test
    void everyYaciActionTypeMapsThroughTheMovedSwitchWithCorrespondingOrdinals() {
        assertThat(ACTION_TYPES.keySet()).containsExactly(GovActionType.values());
        for (GovActionType type : GovActionType.values()) {
            GovernanceActionType mapped = CardanoEpochStateProvider.actionType(type);
            assertThat(mapped).isEqualTo(ACTION_TYPES.get(type));
            assertThat(mapped.ordinal()).isEqualTo(type.ordinal());
        }
        assertThat(GovernanceActionType.values()).hasSameSizeAs(GovActionType.values());
    }

    @Test
    void everyHostStatusAndReasonMapsByNameInIdenticalOrder() {
        assertThat(names(GovernanceProposalStatus.values()))
                .containsExactlyElementsOf(names(org.yanoproject.api.governance.GovernanceProposalStatus.values()));
        assertThat(names(GovernanceProposalStatusReason.values()))
                .containsExactlyElementsOf(names(org.yanoproject.api.governance.GovernanceProposalStatusReason.values()));
        for (var status : org.yanoproject.api.governance.GovernanceProposalStatus.values()) {
            assertThat(CardanoEpochStateProvider.status(status).name()).isEqualTo(status.name());
            assertThat(CardanoEpochStateProvider.status(status).ordinal()).isEqualTo(status.ordinal());
        }
        for (var reason : org.yanoproject.api.governance.GovernanceProposalStatusReason.values()) {
            assertThat(CardanoEpochStateProvider.reason(reason).name()).isEqualTo(reason.name());
            assertThat(CardanoEpochStateProvider.reason(reason).ordinal()).isEqualTo(reason.ordinal());
        }
    }

    @Test
    void bridgeMapsBoundariesAndGovernanceRowsAndDelegatesSnapshotLifetime() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 7);
        EpochBoundary hostBoundary = new EpochBoundary(4, 5, 432_000, hash, 1234);
        AtomicReference<EpochBoundary> opened = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        EpochSnapshot snapshot = new EpochSnapshot() {
            public long previousEpoch() { return 4; }
            public long newEpoch() { return 5; }
            public ProtocolParamsSnapshot protocolParams(long effectiveEpoch) { throw new AssertionError(); }
            public boolean hasStakeSnapshot(long epoch) { return epoch == 4; }
            public void forEachStakeEntry(long epoch, StakeEntryConsumer consumer) {
                consumer.accept(0, new byte[28], BigInteger.TEN, new byte[28]);
            }
            public boolean hasProposalStatusSnapshot(long epoch) { return epoch == 5; }
            public void forEachProposalStatus(long epoch, ProposalStatusConsumer consumer) {
                consumer.accept(new byte[32], 1, GovActionType.TREASURY_WITHDRAWALS_ACTION,
                        org.yanoproject.api.governance.GovernanceProposalStatus.RATIFIED,
                        org.yanoproject.api.governance.GovernanceProposalStatusReason.RATIFIED, 3, 9);
            }
            public boolean hasDRepDistributionSnapshot(long epoch) { return epoch == 5; }
            public void forEachDRepDistributionEntry(long epoch, DRepDistributionConsumer consumer) {
                consumer.accept(1, new byte[28], BigInteger.ONE);
            }
            public void close() { closed.set(true); }
        };
        EpochSnapshotReader reader = new EpochSnapshotReader() {
            public boolean persistent() { return true; }
            public int snapshotRetentionEpochs() { return 50; }
            public long epochAtSlot(long slot) { return slot / 86_400; }
            public long firstObservableEpoch() { return 4; }
            public List<EpochBoundary> completedBoundaries(long afterNewEpoch, int limit) {
                return List.of(hostBoundary);
            }
            public Optional<EpochSnapshot> open(EpochBoundary boundary) {
                opened.set(boundary);
                return Optional.of(snapshot);
            }
        };
        CardanoEpochStateProvider provider = new CardanoEpochStateProvider(reader);

        assertThat(provider.persistent()).isTrue();
        assertThat(provider.snapshotRetentionEpochs()).isEqualTo(50);
        assertThat(provider.epochAtSlot(432_000)).isEqualTo(5);
        assertThat(provider.firstObservableEpoch()).isEqualTo(4);
        L1EpochBoundary boundary = provider.completedBoundaries(3, 10).getFirst();
        assertThat(boundary).isEqualTo(new L1EpochBoundary(4, 5, 432_000, hash, 1234));

        try (L1EpochState state = provider.open(boundary).orElseThrow()) {
            assertThat(opened.get()).isEqualTo(hostBoundary);
            assertThat(state.previousEpoch()).isEqualTo(4);
            assertThat(state.newEpoch()).isEqualTo(5);
            assertThat(state.hasStakeSnapshot(4)).isTrue();
            assertThat(state.hasProposalStatusSnapshot(5)).isTrue();
            assertThat(state.hasDRepDistributionSnapshot(5)).isTrue();
            List<Object> rows = new ArrayList<>();
            state.forEachProposalStatus(5, (tx, index, type, status, reason, proposed, expires) ->
                    rows.add(List.of(index, type, status, reason, proposed, expires)));
            assertThat(rows).containsExactly(List.of(1, GovernanceActionType.TREASURY_WITHDRAWALS,
                    GovernanceProposalStatus.RATIFIED, GovernanceProposalStatusReason.RATIFIED, 3L, 9L));
            List<BigInteger> coins = new ArrayList<>();
            state.forEachStakeEntry(4, (type, cred, coin, pool) -> coins.add(coin));
            state.forEachDRepDistributionEntry(5, (type, drep, coin) -> coins.add(coin));
            assertThat(coins).containsExactly(BigInteger.TEN, BigInteger.ONE);
            assertThat(closed).isFalse();
        }
        assertThat(closed).isTrue();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
