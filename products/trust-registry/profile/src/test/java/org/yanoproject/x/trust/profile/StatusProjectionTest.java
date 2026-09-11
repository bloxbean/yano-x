package org.yanoproject.x.trust.profile;

import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusProjectionTest {

    @Test
    void bitstringIsMostSignificantBitFirstAndHashedRaw() {
        StatusBitstring bits = new StatusBitstring(TrustRegistryProfile.MIN_BIT_LENGTH);
        bits.set(0, true);
        bits.set(9, true);
        byte[] raw = bits.bytes();
        assertThat(raw).hasSize(16_384);
        assertThat(raw[0]).isEqualTo((byte) 0x80);
        assertThat(raw[1]).isEqualTo((byte) 0x40);
        assertThat(bits.get(9)).isTrue();
        assertThat(bits.get(8)).isFalse();
        assertThat(bits.setCount()).isEqualTo(2);

        String encoded = bits.encodedList();
        assertThat(encoded).startsWith("u").doesNotContain("=").doesNotContain("+");
        StatusBitstring decoded = StatusBitstring.decodeEncodedList(
                encoded, TrustRegistryProfile.MIN_BIT_LENGTH);
        assertThat(decoded).isEqualTo(bits);
        assertThat(decoded.sha256Hex()).isEqualTo(bits.sha256Hex());
        assertThat(bits.sha256Hex()).isNotEqualTo(new StatusBitstring(
                TrustRegistryProfile.MIN_BIT_LENGTH).sha256Hex());
        assertThatThrownBy(() -> StatusBitstring.decodeEncodedList(
                "z" + encoded.substring(1), TrustRegistryProfile.MIN_BIT_LENGTH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatusBitstring.decodeEncodedList(
                encoded, TrustRegistryProfile.MIN_BIT_LENGTH + 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectionFoldsStatusWritesAndTreatsRevokeAsTerminal() {
        StatusProjection projection = new StatusProjection();
        assertThat(projection.apply(3, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.SUBJECTS, "s".getBytes(), new byte[]{1}))).isFalse();
        assertThat(projection.apply(3, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey("list-1", 5),
                new TrustRegistryValues.StatusValue(1, 1).encode()))).isTrue();
        projection.apply(4, AuthenticatedMapContract.Mutation.compareAndSet(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey("list-1", 5),
                new TrustRegistryValues.StatusValue(0, 0).encode(), 1, null));
        projection.apply(5, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey("list-1", 8),
                new TrustRegistryValues.StatusValue(0, 0).encode()));
        projection.apply(6, AuthenticatedMapContract.Mutation.revoke(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey("list-1", 8), 1, null));
        projection.apply(6, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey("list-2", 0),
                new TrustRegistryValues.StatusValue(1, 0).encode()));

        assertThat(projection.listIds()).containsExactly("list-1", "list-2");
        assertThat(projection.bits("list-1")).containsEntry(5L, false).containsEntry(8L, true);
        assertThat(projection.lastMutationHeight("list-1")).isEqualTo(6);
        assertThat(projection.lastMutationHeight("missing")).isEqualTo(0);
        assertThat(projection.mutationCount()).isEqualTo(5);
        StatusBitstring bits = projection.bitstring("list-1", TrustRegistryProfile.MIN_BIT_LENGTH);
        assertThat(bits.get(5)).isFalse();
        assertThat(bits.get(8)).isTrue();
        assertThat(bits.setCount()).isEqualTo(1);
        assertThat(projection.bitstring("list-2", TrustRegistryProfile.MIN_BIT_LENGTH).get(0))
                .isTrue();
    }

    @Test
    void trqpAnswersFollowPresenceFrameworkAuthorizationAndValidity() {
        TrustRegistryValues.IssuerValue issuer = new TrustRegistryValues.IssuerValue(
                "framework-a", List.of("issue:credential"), 10, 20);
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_ABSENT, null,
                "framework-a", "issue:credential", 15).authorized()).isFalse();
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_REVOKED, null,
                "framework-a", "issue:credential", 15).authorized()).isFalse();
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_ACTIVE, issuer,
                "framework-b", "issue:credential", 15).authorized()).isFalse();
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_ACTIVE, issuer,
                "framework-a", "revoke:credential", 15).authorized()).isFalse();
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_ACTIVE, issuer,
                "framework-a", "issue:credential", 9).reason()).contains("not yet valid");
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_ACTIVE, issuer,
                "framework-a", "issue:credential", 21).reason()).contains("expired");
        assertThat(TrqpEvaluator.evaluate(AuthenticatedMapContract.PRESENCE_ACTIVE, issuer,
                "framework-a", "issue:credential", 15).authorized()).isTrue();
    }
}
