package org.yanoproject.x.feed.profile;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.yanoproject.x.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@code feed-datum-candidate-v1}, ADR-052 §2.2: what the deferred Cardano publication executor
 * would publish for a closed round, as Plutus data (constructor 0, CBOR tag 121):
 * {@code [sha256(chainId), sha256(feedId), round, aggregate, scale, roundEnd, closedAtHeight,
 * stateRoot, acceptedCount, aggregationCode]}. It is not ADR app-layer/012's {@code OracleLiveV1}
 * (no thread token, publication policy, sequence, or slots); its hash is recorded in the round
 * record so that a future executor has something exact to bind to.
 */
public final class FeedDatum {
    public static final String DATUM_ID = "feed-datum-candidate-v1";
    public static final long PLUTUS_CONSTRUCTOR_0_TAG = 121;

    private FeedDatum() {
    }

    public record Fields(String chainId, String feedId, long round, long aggregate, int scale,
                         long roundEnd, long closedAtHeight, byte[] stateRoot, int acceptedCount) {
        public Fields {
            Objects.requireNonNull(chainId, "chainId");
            FeedStarterProfile.requireFeedId(feedId);
            FeedStarterProfile.requireRound(round);
            if (scale < 0 || scale > FeedStarterProfile.MAX_SCALE) {
                throw new IllegalArgumentException("scale is outside bounds");
            }
            if (roundEnd < 0 || closedAtHeight < 1) {
                throw new IllegalArgumentException("roundEnd and closedAtHeight must be positive");
            }
            stateRoot = Objects.requireNonNull(stateRoot, "stateRoot").clone();
            if (stateRoot.length != 32) {
                throw new IllegalArgumentException("stateRoot must be 32 bytes");
            }
            if (acceptedCount < 1 || acceptedCount > FeedStarterProfile.MAX_SOURCES) {
                throw new IllegalArgumentException("acceptedCount is outside bounds");
            }
        }

        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }

    /** The datum's canonical CBOR bytes. */
    public static byte[] encode(Fields fields) {
        Array data = new Array();
        data.add(new ByteString(FeedValues.sha256(fields.chainId().getBytes(StandardCharsets.UTF_8))));
        data.add(new ByteString(FeedValues.sha256(fields.feedId().getBytes(StandardCharsets.UTF_8))));
        data.add(new UnsignedInteger(fields.round()));
        data.add(FeedValues.integer(fields.aggregate()));
        data.add(new UnsignedInteger(fields.scale()));
        data.add(new UnsignedInteger(fields.roundEnd()));
        data.add(new UnsignedInteger(fields.closedAtHeight()));
        data.add(new ByteString(fields.stateRoot()));
        data.add(new UnsignedInteger(fields.acceptedCount()));
        data.add(new UnsignedInteger(Aggregation.AGGREGATION_CODE));
        data.setTag(new Tag(PLUTUS_CONSTRUCTOR_0_TAG));
        return StdlibContractCbor.encode(data);
    }

    public static byte[] hash(Fields fields) {
        return FeedValues.sha256(encode(fields));
    }
}
