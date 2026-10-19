package org.yanoproject.x.feed.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-052 §2.1 vectors for {@code feed-aggregation-v1}; the console's tests pin the same ones. */
class AggregationTest {
    private static final long EPOCH = 1_790_000_000L;
    private static final FeedValues.FeedValue FEED = FeedGenesis.demoFeed(EPOCH);
    private static final long ROUND = 7;
    private static final long IN_ROUND = FEED.roundStart(ROUND) + 10;

    @Test
    void lowerMedianOfAcceptedSourcesWithOneOutlierExcluded() {
        Aggregation.Result result = Aggregation.aggregate(FEED, ROUND, List.of(
                good("source-alpha", -1_825), good("source-beta", -1_810), good("source-gamma", -900)));
        assertThat(result.closed()).isTrue();
        // Reference: sorted (-1825, -1810, -900), lower median index 1 = -1810; permitted
        // max(50, floor(1810 * 20000 / 1e6) = 36) = 50; -900 deviates 910 → OUTLIER.
        assertThat(result.source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.OUTLIER);
        assertThat(result.acceptedSources()).containsExactly("source-alpha", "source-beta");
        // Lower median of (-1825, -1810) at index floor((2-1)/2) = 0 → -1825; nothing averages.
        assertThat(result.aggregate()).isEqualTo(-1_825);
        assertThat(result.scale()).isEqualTo(2);
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.sources()).extracting(Aggregation.SourceResult::sourceId)
                .containsExactlyElementsOf(FEED.sources());
    }

    @Test
    void oddCountTakesTheMiddleAndTiesBreakBySourceId() {
        Aggregation.Result result = Aggregation.aggregate(FEED, ROUND, List.of(
                good("source-alpha", -1_800), good("source-beta", -1_820), good("source-gamma", -1_790)));
        assertThat(result.aggregate()).isEqualTo(-1_800);
        assertThat(result.acceptedSources()).containsExactly("source-alpha", "source-beta", "source-gamma");
        Aggregation.Result tie = Aggregation.aggregate(FEED, ROUND, List.of(
                good("source-alpha", -1_800), good("source-beta", -1_800), good("source-gamma", -1_800)));
        assertThat(tie.aggregate()).isEqualTo(-1_800);
    }

    @Test
    void dispositionsExcludeBadInputsAndQuorumIsEnforced() {
        Aggregation.Result result = Aggregation.aggregate(FEED, ROUND, List.of(
                new Aggregation.Input("source-alpha", observation(-1_825, IN_ROUND), false, "source-beta", 1),
                new Aggregation.Input("source-beta", observation(-1_825, IN_ROUND), false, "source-beta", 2),
                Aggregation.Input.absent("source-gamma")));
        assertThat(result.closed()).isFalse();
        assertThat(result.statusName()).isEqualTo("NO_QUORUM");
        assertThat(result.aggregate()).isZero();
        assertThat(result.acceptedSources()).isEmpty();
        assertThat(result.source("source-alpha").disposition()).isEqualTo(Aggregation.Disposition.FOREIGN_WRITER);
        assertThat(result.source("source-beta").disposition()).isEqualTo(Aggregation.Disposition.EQUIVOCATED);
        assertThat(result.source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.ABSENT);

        Aggregation.Result windowAndRange = Aggregation.aggregate(FEED, ROUND, List.of(
                new Aggregation.Input("source-alpha", observation(-1_825, FEED.roundEnd(ROUND) + 1), false, "source-alpha", 1),
                new Aggregation.Input("source-beta", observation(-4_001, IN_ROUND), false, "source-beta", 1),
                new Aggregation.Input("source-gamma", observation(-1_825, IN_ROUND), true, "source-gamma", 2)));
        assertThat(windowAndRange.source("source-alpha").disposition()).isEqualTo(Aggregation.Disposition.WRONG_ROUND);
        assertThat(windowAndRange.source("source-beta").disposition()).isEqualTo(Aggregation.Disposition.OUT_OF_RANGE);
        assertThat(windowAndRange.source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.REVOKED);
        assertThat(windowAndRange.closed()).isFalse();

        // Two good sources meet the quorum of two; the round boundaries are inclusive.
        Aggregation.Result boundary = Aggregation.aggregate(FEED, ROUND, List.of(
                new Aggregation.Input("source-alpha", observation(-1_825, FEED.roundStart(ROUND)), false, "source-alpha", 1),
                new Aggregation.Input("source-beta", observation(-1_800, FEED.roundEnd(ROUND)), false, "source-beta", 1),
                Aggregation.Input.absent("source-gamma")));
        assertThat(boundary.closed()).isTrue();
        assertThat(boundary.aggregate()).isEqualTo(-1_825);
    }

    @Test
    void quorumLostAfterOutliersIsNoQuorum() {
        FeedValues.FeedValue strict = FEED.withSources(FEED.sources(), 3);
        Aggregation.Result result = Aggregation.aggregate(strict, ROUND, List.of(
                good("source-alpha", -1_825), good("source-beta", -1_810), good("source-gamma", -900)));
        assertThat(result.closed()).isFalse();
        assertThat(result.source("source-gamma").disposition()).isEqualTo(Aggregation.Disposition.OUTLIER);
        assertThat(result.candidateCount()).isEqualTo(3);
    }

    @Test
    void deviationUsesPpmOrAbsoluteWhicheverIsLarger() {
        assertThat(Aggregation.permittedDeviation(FEED, -1_810)).isEqualTo(java.math.BigInteger.valueOf(50));
        assertThat(Aggregation.permittedDeviation(FEED, -100_000)).isEqualTo(java.math.BigInteger.valueOf(2_000));
        FeedValues.FeedValue wide = new FeedValues.FeedValue("", "x", 0, EPOCH, 60, FEED.sources(), 1,
                FeedStarterProfile.MAX_PPM, 0, FeedStarterProfile.MIN_VALUE, FeedStarterProfile.MAX_VALUE, 0);
        assertThat(Aggregation.permittedDeviation(wide, Long.MAX_VALUE))
                .isEqualTo(java.math.BigInteger.valueOf(Long.MAX_VALUE));
        // Extreme values do not overflow: the reference is the median 0, permitted 0, so both
        // extremes are outliers by an exactly computed |±MAX - 0|, and the round still closes.
        Aggregation.Result extreme = Aggregation.aggregate(wide, ROUND, List.of(
                good("source-alpha", Long.MAX_VALUE), good("source-beta", -Long.MAX_VALUE), good("source-gamma", 0)));
        assertThat(extreme.closed()).isTrue();
        assertThat(extreme.aggregate()).isZero();
        assertThat(extreme.acceptedSources()).containsExactly("source-gamma");
        assertThat(extreme.source("source-alpha").disposition()).isEqualTo(Aggregation.Disposition.OUTLIER);
        assertThat(extreme.source("source-beta").disposition()).isEqualTo(Aggregation.Disposition.OUTLIER);
        // With a relative tolerance on a large reference the extremes are kept.
        Aggregation.Result large = Aggregation.aggregate(wide, ROUND, List.of(
                good("source-alpha", Long.MAX_VALUE), good("source-beta", Long.MAX_VALUE - 5), good("source-gamma", 0)));
        assertThat(large.aggregate()).isEqualTo(Long.MAX_VALUE - 5);
        assertThat(large.acceptedSources()).containsExactly("source-alpha", "source-beta", "source-gamma");
    }

    @Test
    void inputsMustMatchTheFeedSources() {
        assertThatThrownBy(() -> Aggregation.aggregate(FEED, ROUND, List.of(good("source-alpha", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Aggregation.aggregate(FEED, ROUND, List.of(
                good("source-beta", 1), good("source-alpha", 1), good("source-gamma", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        Aggregation.Result result = Aggregation.aggregate(FEED, ROUND, List.of(
                good("source-alpha", -1_825), good("source-beta", -1_810), good("source-gamma", -900)));
        FeedValues.RoundValue record = result.record(12, new byte[32], new byte[32]);
        assertThat(record.closed()).isTrue();
        assertThat(record.acceptedSources()).containsExactly("source-alpha", "source-beta");
    }

    private static Aggregation.Input good(String source, long value) {
        return new Aggregation.Input(source, observation(value, IN_ROUND), false, source, 1);
    }

    private static FeedValues.ObservationValue observation(long value, long observedAt) {
        return new FeedValues.ObservationValue(value, observedAt, null, "");
    }
}
