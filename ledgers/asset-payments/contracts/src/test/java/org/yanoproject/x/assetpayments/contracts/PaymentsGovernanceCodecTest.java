package org.yanoproject.x.assetpayments.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.AssetStatusChange;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Command;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.DecisionV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Proposal;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AssetStatus;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentsGovernanceCodecTest {
    static byte[] key(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }

    static final Hash32 VAULT = Hash32.of(key(0x11));

    @Test
    void commandBytesArePinned() {
        assertThat(HexFormat.of().formatHex(new Command(0, new PaymentsGovernanceV1.Pause()).encode()))
                .isEqualTo("83010081" + "01");
        assertThat(HexFormat.of().formatHex(new Command(2, new PaymentsGovernanceV1.Resume()).encode()))
                .isEqualTo("83010281" + "02");
        assertThat(HexFormat.of().formatHex(new Command(1, new AssetStatusChange(VAULT, AssetStatus.RETIRED))
                .encode())).isEqualTo("830101" + "8300" + "5820" + "11".repeat(32) + "04");
    }

    @Test
    void proposalsAndDecisionsRoundTripWithSortedDistinctApprovers() {
        byte[] command = new Command(3, new AssetStatusChange(VAULT, AssetStatus.WIND_DOWN)).encode();
        Proposal proposal = new Proposal(command, 0, 7, key(0x72), List.of(key(0x72))).with(key(0x71))
                .with(key(0x72));
        assertThat(proposal.approvers()).containsExactly(key(0x71), key(0x72));
        assertThat(proposal.proposer()).isEqualTo(key(0x72));
        List<Proposal> decoded = PaymentsGovernanceV1.decodeProposals(PaymentsGovernanceV1.encodeProposals(
                List.of(proposal)));
        assertThat(decoded).hasSize(1);
        assertThat(decoded.getFirst().command()).isEqualTo(command);
        assertThat(decoded.getFirst().approvers()).containsExactly(key(0x71), key(0x72));
        assertThat(decoded.getFirst().proposedBy(key(0x72))).isTrue();
        // One open proposal per proposing member; the proposer is always an approver.
        Proposal second = new Proposal(new Command(3, new PaymentsGovernanceV1.Pause()).encode(), 0, 8, key(0x72),
                List.of(key(0x72)));
        assertThatThrownBy(() -> PaymentsGovernanceV1.encodeProposals(List.of(proposal, second)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Proposal(command, 0, 7, key(0x73), List.of(key(0x72))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentsGovernanceV1.decodeProposals(PaymentsGovernanceV1.encodeProposals(List.of()))).isEmpty();

        DecisionV1 applied = new DecisionV1(3, command, 9, 1_000, proposal.approvers(), Optional.of(VAULT),
                AssetStatus.ACTIVE.code(), AssetStatus.WIND_DOWN.code(), PaymentsGovernanceV1.Outcome.APPLIED, "");
        assertThat(DecisionV1.decode(applied.encode())).isEqualTo(applied);
        DecisionV1 refused = new DecisionV1(4, new Command(4, new PaymentsGovernanceV1.Pause()).encode(), 9, 1_000,
                List.of(key(0x73)), Optional.empty(), 1, 1, PaymentsGovernanceV1.Outcome.REFUSED, "NOT_ACTIVE");
        assertThat(DecisionV1.decode(refused.encode())).isEqualTo(refused);
        assertThatThrownBy(() -> new DecisionV1(4, command, 9, 1_000, List.of(), Optional.empty(), 1, 2,
                PaymentsGovernanceV1.Outcome.REFUSED, "X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Proposal(command, 0, 0, key(1), List.of(key(1), key(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
