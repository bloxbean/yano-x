package com.bloxbean.cardano.yano.appchain.feed.profile;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedStarterProfileTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] DIGEST = HEX.parseHex("ab".repeat(32));
    private static final FeedValues.FeedValue FEED = FeedGenesis.demoFeed(1_790_000_000L);

    @Test
    void schemasAdmitCanonicalValuesAndRejectOthers() {
        byte[] feed = FEED.encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.feedSchema(), feed)).isTrue();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.observationSchema(), feed)).isFalse();
        // Sixteen sources are the bound; seventeen are refused by the record and the schema.
        List<String> sixteen = java.util.stream.IntStream.range(0, 16).mapToObj(i -> "s" + i).toList();
        byte[] wide = FEED.withSources(sixteen, 16).encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.feedSchema(), wide)).isTrue();
        assertThatThrownBy(() -> FEED.withSources(
                java.util.stream.IntStream.range(0, 17).mapToObj(i -> "s" + i).toList(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FEED.withSources(List.of("a", "a"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FEED.withSources(List.of("a"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        // A paused feed is a valid value; status 2 is refused by the schema.
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.feedSchema(),
                FEED.withStatus(FeedStarterProfile.FEED_PAUSED).encode())).isTrue();
        byte[] statusTwo = feed.clone();
        assertThat(statusTwo[statusTwo.length - 1]).isEqualTo((byte) 0x00);
        statusTwo[statusTwo.length - 1] = 0x02;
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.feedSchema(), statusTwo)).isFalse();

        byte[] negative = new FeedValues.ObservationValue(-1_825, 1_790_000_010L, null, "").encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.observationSchema(), negative)).isTrue();
        byte[] withEvidence = new FeedValues.ObservationValue(Long.MAX_VALUE, 1, DIGEST, "frame 7").encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.observationSchema(), withEvidence)).isTrue();
        byte[] smallest = new FeedValues.ObservationValue(-Long.MAX_VALUE, 1, null, "").encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.observationSchema(), smallest)).isTrue();
        assertThatThrownBy(() -> new FeedValues.ObservationValue(Long.MIN_VALUE, 1, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
        // A hand-built -2^63 (CBOR 0x3b ff..ff) is refused by the schema's bound.
        byte[] tooSmall = HEX.parseHex("8501" + "3bffffffffffffffff" + "01" + "40" + "60");
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.observationSchema(), tooSmall)).isFalse();
        assertThatThrownBy(() -> FeedValues.ObservationValue.decode(tooSmall))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] closed = new FeedValues.RoundValue(FeedStarterProfile.ROUND_CLOSED, 12, -1_825, 2,
                List.of("source-alpha", "source-beta"), DIGEST, DIGEST).encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.roundSchema(), closed)).isTrue();
        byte[] noQuorum = new FeedValues.RoundValue(FeedStarterProfile.ROUND_NO_QUORUM, 13, 0, 2,
                List.of(), DIGEST, null).encode();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.roundSchema(), noQuorum)).isTrue();
        assertThat(AuthenticatedMapSchema.accepts(FeedStarterProfile.roundSchema(), negative)).isFalse();
        assertThatThrownBy(() -> new FeedValues.RoundValue(FeedStarterProfile.ROUND_CLOSED, 1, 0, 0,
                List.of(), DIGEST, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeedValues.RoundValue(FeedStarterProfile.ROUND_NO_QUORUM, 1, 0, 0,
                List.of(), DIGEST, DIGEST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesRoundTripAndRejectNonCanonicalBytes() {
        assertThat(FeedValues.FeedValue.decode(FEED.encode())).isEqualTo(FEED);
        assertThat(FEED.statusName()).isEqualTo("ACTIVE");
        FeedValues.ObservationValue observation = new FeedValues.ObservationValue(-1_825, 1_790_000_010L, DIGEST, "n");
        assertThat(FeedValues.ObservationValue.decode(observation.encode())).isEqualTo(observation);
        FeedValues.RoundValue round = new FeedValues.RoundValue(FeedStarterProfile.ROUND_CLOSED, 12, -1_825, 2,
                List.of("source-alpha", "source-beta"), DIGEST, DIGEST);
        assertThat(FeedValues.RoundValue.decode(round.encode())).isEqualTo(round);
        assertThat(round.statusName()).isEqualTo("CLOSED");
        // A non-minimal integer header for the scale is not canonical.
        byte[] encoded = observation.encode();
        int at = 2 + 3 + 1; // array header, -1825 (0x39 0x07 0x20), then observedAt's 0x1a header
        assertThat(encoded[at - 1]).isEqualTo((byte) 0x1a);
        byte[] nonCanonical = new byte[encoded.length + 4];
        System.arraycopy(encoded, 0, nonCanonical, 0, at - 1);
        nonCanonical[at - 1] = 0x1b;
        System.arraycopy(encoded, at, nonCanonical, at + 4, encoded.length - at);
        assertThatThrownBy(() -> FeedValues.ObservationValue.decode(nonCanonical))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedValues.decimal(-1_825, 2)).isEqualTo("-18.25");
        assertThat(FeedValues.decimal(5, 2)).isEqualTo("0.05");
        assertThat(FeedValues.decimal(123, 0)).isEqualTo("123");
        assertThat(FeedValues.decimal(0, 3)).isEqualTo("0.000");
    }

    @Test
    void keysAreFeedScopedAndBounded() {
        assertThat(FeedStarterProfile.text(FeedStarterProfile.feedKey("coldstore-7"))).isEqualTo("coldstore-7");
        byte[] observationKey = FeedStarterProfile.observationKey("coldstore-7", 42, "source-alpha");
        assertThat(FeedStarterProfile.parseObservationKey(observationKey))
                .isEqualTo(new FeedStarterProfile.ObservationKey("coldstore-7", 42, "source-alpha"));
        byte[] roundKey = FeedStarterProfile.roundKey("coldstore-7", 42);
        assertThat(FeedStarterProfile.parseRoundKey(roundKey))
                .isEqualTo(new FeedStarterProfile.RoundKey("coldstore-7", 42));
        assertThat(FeedStarterProfile.feedIdOf(FeedStarterProfile.ROUNDS, roundKey)).isEqualTo("coldstore-7");
        assertThat(FeedStarterProfile.feedIdOf(FeedStarterProfile.OBSERVATIONS, "junk".getBytes())).isNull();
        assertThat(FeedStarterProfile.observationKey("x".repeat(32), Long.MAX_VALUE, "s" + "x".repeat(62)))
                .hasSize(32 + 1 + 19 + 1 + 63);
        assertThatThrownBy(() -> FeedStarterProfile.feedKey("Cold"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedStarterProfile.feedKey("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedStarterProfile.parseRoundKey("f/007".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedStarterProfile.parseRound("9223372036854775808"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedStarterProfile.parseRound("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> FeedStarterProfile.observationKey("f", -1, "s"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calendarMapsTimesToRounds() {
        assertThat(FEED.roundOf(1_790_000_000L)).isZero();
        assertThat(FEED.roundOf(1_790_000_059L)).isZero();
        assertThat(FEED.roundOf(1_790_000_060L)).isEqualTo(1);
        assertThat(FEED.roundStart(7)).isEqualTo(1_790_000_420L);
        assertThat(FEED.roundEnd(7)).isEqualTo(1_790_000_479L);
        assertThatThrownBy(() -> FEED.roundOf(1_789_999_999L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FEED.roundStart(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void datumIsPlutusConstructorZeroWithPinnedBytes() {
        FeedDatum.Fields fields = new FeedDatum.Fields("attestation-feed-chain", "coldstore-7", 7,
                -1_825, 2, 1_790_000_479L, 12, DIGEST, 2);
        byte[] datum = FeedDatum.encode(fields);
        // Tag 121 (0xd8 0x79) over a ten-item array.
        assertThat(HEX.formatHex(datum)).startsWith("d8798a5820");
        assertThat(FeedDatum.encode(fields)).isEqualTo(datum);
        assertThat(FeedDatum.hash(fields)).hasSize(32);
        assertThat(FeedDatum.hash(new FeedDatum.Fields("attestation-feed-chain", "coldstore-7", 7,
                -1_826, 2, 1_790_000_479L, 12, DIGEST, 2))).isNotEqualTo(FeedDatum.hash(fields));
        // Vector pinned for the console's browser-side encoder.
        assertThat(HEX.formatHex(datum)).isEqualTo("d8798a5820"
                + HEX.formatHex(FeedValues.sha256("attestation-feed-chain".getBytes())) + "5820"
                + HEX.formatHex(FeedValues.sha256("coldstore-7".getBytes()))
                + "07" + "390720" + "02" + "1a6ab13d5f" + "0c" + "5820" + HEX.formatHex(DIGEST) + "02" + "01");
    }

    @Test
    void statusesAndPoliciesAreNamed() {
        assertThat(FeedStarterProfile.feedStatusCode("paused")).isEqualTo(FeedStarterProfile.FEED_PAUSED);
        assertThatThrownBy(() -> FeedStarterProfile.feedStatusCode("CLOSED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedStarterProfile.roundStatusName(FeedStarterProfile.ROUND_NO_QUORUM)).isEqualTo("NO_QUORUM");
        assertThat(FeedStarterProfile.policyOf(FeedStarterProfile.ROUNDS)).isEqualTo(FeedStarterProfile.ROUND_POLICY);
        assertThat(FeedStarterProfile.roleOf(FeedStarterProfile.OBSERVATIONS)).isEqualTo("source");
        assertThatThrownBy(() -> FeedStarterProfile.roleOf(FeedStarterProfile.ROUNDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedStarterProfile.collections()).extracting(descriptor -> descriptor.id())
                .containsExactlyElementsOf(FeedStarterProfile.COLLECTION_IDS);
        assertThat(FeedStarterProfile.roundPolicy().clauses()).hasSize(1);
        assertThat(FeedStarterProfile.directPolicies()).hasSize(2);
    }
}
