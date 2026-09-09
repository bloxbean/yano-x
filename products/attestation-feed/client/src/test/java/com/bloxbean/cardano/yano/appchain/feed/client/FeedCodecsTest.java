package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yano.appchain.feed.profile.FeedStarterProfile;
import com.bloxbean.cardano.yano.appchain.feed.profile.FeedValues;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.client.TrustRegistrySigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedCodecsTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] GENESIS = HEX.parseHex("11".repeat(32));

    @Test
    void roundRequestRoundTripsAndRefusesTamperingAndForeignCommands() {
        FeedValues.RoundValue record = new FeedValues.RoundValue(FeedStarterProfile.ROUND_CLOSED, 9, -1_825, 2,
                List.of("source-alpha", "source-beta"), new byte[32], new byte[32]);
        RoundRequest request = request(record, FeedStarterProfile.roundKey("coldstore-7", 7), "coldstore-7", 7);
        assertThat(request.consistent()).isTrue();
        assertThat(request.recordValue()).isEqualTo(record);
        RoundRequest travelled = RoundRequest.fromJson(request.toJson());
        assertThat(travelled).isEqualTo(request);
        assertThat(travelled.toJsonNode().path("record").path("decimal").asText()).isEqualTo("-18.25");

        ObjectNode tampered = request.toJsonNode();
        tampered.put("payloadHash", "00".repeat(32));
        assertThatThrownBy(() -> RoundRequest.fromJsonNode(tampered))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode otherRound = request.toJsonNode();
        otherRound.put("round", 8);
        assertThatThrownBy(() -> RoundRequest.fromJsonNode(otherRound))
                .isInstanceOf(IllegalArgumentException.class);
        // A command on another key or with another operation is not a round request.
        RoundRequest foreign = request(record, FeedStarterProfile.roundKey("coldstore-7", 8), "coldstore-7", 7);
        assertThat(foreign.consistent()).isFalse();
        assertThatThrownBy(foreign::recordValue).isInstanceOf(IllegalArgumentException.class);
        AuthenticatedMapContract.Command put = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.put(FeedStarterProfile.ROUNDS,
                        FeedStarterProfile.roundKey("coldstore-7", 7), record.encode()));
        var action = TrustRegistrySigner.approvalAction(put, FeedStarterProfile.ROUND_POLICY);
        RoundRequest rewrite = new RoundRequest("c", HEX.formatHex(GENESIS), FeedStarterProfile.ROUND_POLICY, 1,
                "round-1", HEX.formatHex(AuthenticatedMapContract.encodeCommand(put)),
                HEX.formatHex(TrustRegistrySigner.approvalPayloadHash(GENESIS, action)), 100, "coldstore-7", 7, "");
        assertThat(rewrite.consistent()).isFalse();
    }

    @Test
    void projectionTracksFeedsAndRecordedRounds() {
        FeedProjection projection = new FeedProjection();
        projection.apply(AuthenticatedMapContract.Mutation.putIfAbsent(FeedStarterProfile.FEEDS,
                FeedStarterProfile.feedKey("coldstore-7"), new byte[]{1}));
        projection.apply(AuthenticatedMapContract.Mutation.putIfAbsent(FeedStarterProfile.ROUNDS,
                FeedStarterProfile.roundKey("coldstore-7", 9), new byte[]{1}));
        projection.apply(AuthenticatedMapContract.Mutation.putIfAbsent(FeedStarterProfile.ROUNDS,
                FeedStarterProfile.roundKey("coldstore-7", 7), new byte[]{1}));
        projection.apply(AuthenticatedMapContract.Mutation.putIfAbsent(FeedStarterProfile.OBSERVATIONS,
                FeedStarterProfile.observationKey("coldstore-7", 7, "source-alpha"), new byte[]{1}));
        projection.apply(AuthenticatedMapContract.Mutation.put(FeedStarterProfile.ROUNDS, "junk".getBytes(), new byte[]{1}));
        assertThat(projection.feedIds()).containsExactly("coldstore-7");
        assertThat(projection.rounds("coldstore-7")).containsExactly(7L, 9L);
        projection.apply(AuthenticatedMapContract.Mutation.revoke(FeedStarterProfile.ROUNDS,
                FeedStarterProfile.roundKey("coldstore-7", 9), 1, null));
        assertThat(projection.rounds("coldstore-7")).containsExactly(7L);
        assertThat(projection.rounds("other")).isEmpty();
    }

    private static RoundRequest request(FeedValues.RoundValue record, byte[] key, String feedId, long round) {
        AuthenticatedMapContract.Command command = AuthenticatedMapContract.Command.single(
                AuthenticatedMapContract.Mutation.putIfAbsent(FeedStarterProfile.ROUNDS, key, record.encode()));
        var action = TrustRegistrySigner.approvalAction(command, FeedStarterProfile.ROUND_POLICY);
        return new RoundRequest("c", HEX.formatHex(GENESIS), FeedStarterProfile.ROUND_POLICY, 1, "round-1",
                HEX.formatHex(AuthenticatedMapContract.encodeCommand(command)),
                HEX.formatHex(TrustRegistrySigner.approvalPayloadHash(GENESIS, action)), 100, feedId, round, "");
    }
}
