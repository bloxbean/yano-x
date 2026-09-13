package org.yanoproject.x.feed.profile;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code feed-aggregation-v1}, ADR-052 §2.1: ADR app-layer/012 §8.4 reduced to one reporter per
 * source and no prior-round state. A pure function of the feed record and one observation
 * input per configured source, all read at one height; every verifier recomputes it and
 * compares with what the consortium recorded.
 */
public final class Aggregation {
    public static final String RULE_ID = "feed-aggregation-v1";
    public static final int AGGREGATION_CODE = 1;
    private static final BigInteger PPM = BigInteger.valueOf(FeedStarterProfile.MAX_PPM);

    private Aggregation() {
    }

    public enum Disposition {
        ACCEPTED, OUTLIER, ABSENT, REVOKED, FOREIGN_WRITER, EQUIVOCATED, WRONG_ROUND, OUT_OF_RANGE
    }

    /**
     * One configured source's observation as read from the chain: {@code value} is null when the
     * key is absent; {@code revoked} marks a tombstone; {@code writerActorId} is the actor the
     * entry's authorization evidence names (null when unknown, which counts as foreign);
     * {@code revision} is the entry's revision (1 for a single write).
     */
    public record Input(String sourceId, FeedValues.ObservationValue value, boolean revoked,
                        String writerActorId, long revision) {
        public Input {
            FeedStarterProfile.requireSourceId(sourceId);
        }

        public static Input absent(String sourceId) {
            return new Input(sourceId, null, false, null, 0);
        }
    }

    public record SourceResult(String sourceId, Disposition disposition, Long value, Long observedAt) {
        public boolean accepted() {
            return disposition == Disposition.ACCEPTED;
        }
    }

    public record Result(int status, long aggregate, int scale, List<String> acceptedSources,
                         List<SourceResult> sources, int candidateCount) {
        public boolean closed() {
            return status == FeedStarterProfile.ROUND_CLOSED;
        }

        public String statusName() {
            return FeedStarterProfile.roundStatusName(status);
        }

        public SourceResult source(String sourceId) {
            return sources.stream().filter(source -> source.sourceId().equals(sourceId))
                    .findFirst().orElse(null);
        }

        /** The round record this result would produce, given the policy and datum hashes. */
        public FeedValues.RoundValue record(long closedAtHeight, byte[] policySha256, byte[] datumSha256) {
            return new FeedValues.RoundValue(status, closedAtHeight, aggregate, scale, acceptedSources,
                    policySha256, closed() ? datumSha256 : new byte[0]);
        }
    }

    /**
     * Aggregates {@code round} of {@code feed} from exactly one input per configured source, in
     * the feed's source order.
     */
    public static Result aggregate(FeedValues.FeedValue feed, long round, List<Input> inputs) {
        Objects.requireNonNull(feed, "feed");
        FeedStarterProfile.requireRound(round);
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() != feed.sources().size()) {
            throw new IllegalArgumentException("one input per configured source is required");
        }
        long start = feed.roundStart(round);
        long end = feed.roundEnd(round);
        List<SourceResult> results = new ArrayList<>(inputs.size());
        List<SourceResult> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            Input input = inputs.get(index);
            String expected = feed.sources().get(index);
            if (!input.sourceId().equals(expected) || !seen.add(expected)) {
                throw new IllegalArgumentException("inputs must follow the feed's source order");
            }
            SourceResult result = dispose(feed, input, start, end);
            results.add(result);
            if (result.disposition() == Disposition.ACCEPTED) {
                candidates.add(result);
            }
        }
        int candidateCount = candidates.size();
        if (candidateCount < feed.minimumSources()) {
            return noQuorum(feed, results, candidateCount);
        }
        candidates.sort(BY_VALUE_THEN_ID);
        long reference = lowerMedian(candidates);
        BigInteger permitted = permittedDeviation(feed, reference);
        List<SourceResult> retained = new ArrayList<>();
        for (SourceResult candidate : candidates) {
            BigInteger deviation = BigInteger.valueOf(candidate.value())
                    .subtract(BigInteger.valueOf(reference)).abs();
            if (deviation.compareTo(permitted) > 0) {
                results.set(indexOf(results, candidate.sourceId()), new SourceResult(
                        candidate.sourceId(), Disposition.OUTLIER, candidate.value(), candidate.observedAt()));
            } else {
                retained.add(candidate);
            }
        }
        if (retained.size() < feed.minimumSources()) {
            return noQuorum(feed, results, candidateCount);
        }
        long aggregate = lowerMedian(retained);
        List<String> accepted = retained.stream().map(SourceResult::sourceId).sorted().toList();
        return new Result(FeedStarterProfile.ROUND_CLOSED, aggregate, feed.scale(), accepted,
                List.copyOf(results), candidateCount);
    }

    /** {@code max(maximumDeviationAbsolute, floor(|m| × maximumDeviationPpm / 1,000,000))}. */
    public static BigInteger permittedDeviation(FeedValues.FeedValue feed, long reference) {
        BigInteger relative = BigInteger.valueOf(reference).abs()
                .multiply(BigInteger.valueOf(feed.maximumDeviationPpm())).divide(PPM);
        return relative.max(BigInteger.valueOf(feed.maximumDeviationAbsolute()));
    }

    /** The value at index {@code floor((n − 1) / 2)} of a list sorted by (value, sourceId). */
    static long lowerMedian(List<SourceResult> sorted) {
        return sorted.get((sorted.size() - 1) / 2).value();
    }

    private static final Comparator<SourceResult> BY_VALUE_THEN_ID =
            Comparator.comparingLong(SourceResult::value).thenComparing(SourceResult::sourceId);

    private static SourceResult dispose(FeedValues.FeedValue feed, Input input, long start, long end) {
        String source = input.sourceId();
        if (input.revoked()) {
            return new SourceResult(source, Disposition.REVOKED, null, null);
        }
        if (input.value() == null) {
            return new SourceResult(source, Disposition.ABSENT, null, null);
        }
        long value = input.value().value();
        long observedAt = input.value().observedAt();
        if (input.writerActorId() == null || !input.writerActorId().equals(source)) {
            return new SourceResult(source, Disposition.FOREIGN_WRITER, value, observedAt);
        }
        if (input.revision() != 1) {
            return new SourceResult(source, Disposition.EQUIVOCATED, value, observedAt);
        }
        if (observedAt < start || observedAt > end) {
            return new SourceResult(source, Disposition.WRONG_ROUND, value, observedAt);
        }
        if (!feed.inRange(value)) {
            return new SourceResult(source, Disposition.OUT_OF_RANGE, value, observedAt);
        }
        return new SourceResult(source, Disposition.ACCEPTED, value, observedAt);
    }

    private static Result noQuorum(FeedValues.FeedValue feed, List<SourceResult> results, int candidateCount) {
        return new Result(FeedStarterProfile.ROUND_NO_QUORUM, 0, feed.scale(), List.of(),
                List.copyOf(results), candidateCount);
    }

    private static int indexOf(List<SourceResult> results, String sourceId) {
        for (int index = 0; index < results.size(); index++) {
            if (results.get(index).sourceId().equals(sourceId)) return index;
        }
        throw new IllegalStateException("source vanished: " + sourceId);
    }
}
