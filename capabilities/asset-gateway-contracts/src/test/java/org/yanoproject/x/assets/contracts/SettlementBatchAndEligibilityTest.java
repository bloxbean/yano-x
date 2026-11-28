package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The batch effect payload, the settlement query codec and the derived {@code EXIT_ELIGIBLE} view (ADR-059 D7, D9). */
class SettlementBatchAndEligibilityTest {
    static final Hash32 VAULT = Hash32.of(filled(32, 0x11));
    static final long DAY = 86_400_000L;

    static Hash32 id(int shard, int seed) {
        byte[] bytes = filled(32, seed);
        bytes[31] = (byte) ((bytes[31] & 0xf0) | shard);
        return Hash32.of(bytes);
    }

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    @Test
    void batchRoundTripsAndRejectsForeignShardsAndDuplicates() {
        SettlementBatchV1 batch = new SettlementBatchV1(VAULT, 5, List.of(id(5, 1), id(5, 2)));
        assertThat(SettlementBatchV1.decode(batch.encode())).isEqualTo(batch);
        assertThatThrownBy(() -> new SettlementBatchV1(VAULT, 5, List.of(id(4, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementBatchV1(VAULT, 5, List.of(id(5, 1), id(5, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementBatchV1(VAULT, 5, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SettlementBatchV1.sequenceOf(SettlementBatchV1.scope(42))).isEqualTo(42);
        assertThat(SettlementBatchV1.sequenceOf("asset-settlement/007")).isEqualTo(-1);
        assertThat(SettlementBatchV1.sequenceOf("other/1")).isEqualTo(-1);
    }

    /** Review P2-6: the ledger derives its batch cap from the effect payload limit, so the size must be exact. */
    @Test
    void theEncodedSizeIsExactAndBoundsTheClaimsAPayloadCarries() {
        for (int claims : List.of(1, 16, 23, 24, 64)) {
            List<Hash32> ids = new java.util.ArrayList<>();
            for (int i = 0; i < claims; i++) ids.add(id(7, i + 1));
            assertThat(new SettlementBatchV1(VAULT, 7, ids).encode()).hasSize(SettlementBatchV1.encodedSize(claims));
        }
        assertThat(SettlementBatchV1.maxClaimsWithin(SettlementBatchV1.encodedSize(16))).isEqualTo(16);
        assertThat(SettlementBatchV1.maxClaimsWithin(SettlementBatchV1.encodedSize(16) - 1)).isEqualTo(15);
        assertThat(SettlementBatchV1.maxClaimsWithin(70)).isZero();
        assertThat(SettlementBatchV1.maxClaimsWithin(1 << 20)).isEqualTo(SettlementBatchV1.MAX_CLAIMS);
    }

    @Test
    void claimQueryAnswersOnlyAMatchingLeafAndStatus() {
        Hash32 claimId = id(3, 9);
        ClaimLeafV1 leaf = new ClaimLeafV1(claimId, VAULT, ClaimKind.USER_WITHDRAWAL, AssetValue.EMPTY,
                BigInteger.valueOf(2_000_000), new byte[] {0x60, 1}, 7, 1_000);
        byte[] status = SettlementRecords.ClaimStatusV1.reserved(claimId, 7).encode();
        byte[] response = SettlementQueries.encodeClaim(Optional.of(leaf.encode()), Optional.of(status));
        assertThat(SettlementQueries.decodeClaim(claimId, response).orElseThrow().leaf()).isEqualTo(leaf);
        assertThat(SettlementQueries.decodeClaim(id(3, 8), response)).isEmpty();
        assertThat(SettlementQueries.decodeClaim(claimId,
                SettlementQueries.encodeClaim(Optional.of(leaf.encode()), Optional.empty()))).isEmpty();
        assertThat(SettlementQueries.decodeClaim(claimId, new byte[] {1, 2})).isEmpty();
    }

    @Test
    void depositQueryAnswersOnlyAMatchingRecord() {
        Hash32 tx = id(0, 0x41);
        Hash32 depositId = GatewayIds.depositId(VAULT, tx, 2);
        DepositRecordV1 record = new DepositRecordV1(depositId, VAULT, tx, 2, 4_000, 77, VaultOutputShape.DEPOSIT,
                DepositRecordV1.Status.CREDITED, DepositAdmission.Reason.NONE, new byte[28], BigInteger.TEN,
                BigInteger.valueOf(1_500_000), 0, id(0, 0x42), BigInteger.TEN, BigInteger.valueOf(1_500_000));
        assertThat(SettlementQueries.decodeDeposit(depositId, record.encode())).contains(record);
        assertThat(SettlementQueries.decodeDeposit(GatewayIds.depositId(VAULT, tx, 3), record.encode())).isEmpty();
        assertThat(SettlementQueries.decodeDeposit(depositId, new byte[0])).isEmpty();
        assertThat(SettlementQueries.decodeDeposit(depositId, new byte[] {1, 2})).isEmpty();
    }

    @Test
    void exitEligibilityFollowsAnchoringDelayStatusAndMaximumAge() {
        long reservedAt = 1_000_000L;
        ClaimLeafV1 user = new ClaimLeafV1(id(1, 1), VAULT, ClaimKind.USER_WITHDRAWAL, AssetValue.EMPTY,
                BigInteger.ONE, new byte[] {0x60}, 10, reservedAt);
        DomainStatusV1 active = new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 0, 0, DAY);
        DomainStatusV1 paused = new DomainStatusV1(DomainStatusV1.Status.PAUSED, 0, 0, DAY);
        long maxPause = 7 * DAY;
        assertThat(ClaimEligibility.view(user, ClaimStatus.RESERVED, 9, active, reservedAt + DAY, maxPause))
                .as("not anchored").isEqualTo(ClaimEligibility.View.SETTLEMENT_PENDING);
        assertThat(ClaimEligibility.view(user, ClaimStatus.RESERVED, 10, active, reservedAt + DAY - 1, maxPause))
                .as("delay not elapsed").isEqualTo(ClaimEligibility.View.SETTLEMENT_PENDING);
        assertThat(ClaimEligibility.view(user, ClaimStatus.RESERVED, 10, active, reservedAt + DAY, maxPause))
                .isEqualTo(ClaimEligibility.View.EXIT_ELIGIBLE);
        assertThat(ClaimEligibility.view(user, ClaimStatus.RESERVED, 10, paused, reservedAt + DAY, maxPause))
                .as("braked").isEqualTo(ClaimEligibility.View.SETTLEMENT_PENDING);
        assertThat(ClaimEligibility.view(user, ClaimStatus.RESERVED, 10, paused, reservedAt + DAY + maxPause,
                maxPause)).as("aged user claim").isEqualTo(ClaimEligibility.View.EXIT_ELIGIBLE);
        ClaimLeafV1 governed = new ClaimLeafV1(id(1, 2), VAULT, ClaimKind.SWEEP_OPERATIONAL, AssetValue.EMPTY,
                BigInteger.ONE, new byte[] {0x60}, 10, reservedAt);
        assertThat(ClaimEligibility.view(governed, ClaimStatus.RESERVED, 10, paused, Long.MAX_VALUE, maxPause))
                .as("governed claims are always braked").isEqualTo(ClaimEligibility.View.SETTLEMENT_PENDING);
        assertThat(ClaimEligibility.view(user, ClaimStatus.PAID, 10, active, Long.MAX_VALUE, maxPause))
                .isEqualTo(ClaimEligibility.View.RESOLVED);
    }
}
