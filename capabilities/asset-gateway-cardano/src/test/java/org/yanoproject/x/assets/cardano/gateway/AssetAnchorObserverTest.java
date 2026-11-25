package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assets.cardano.gateway.SettlementFixtures.*;

/**
 * ADR-059 D8b and D8h §3 fixtures for {@code asset-anchor-v1}: the bootstrap, advances and heartbeats of the pinned
 * anchor are reported with their anchored state; a datum the vault would not accept is reported as such; a spoofed
 * output (no thread token, or the token off the anchor script) is never reported (I2).
 */
class AssetAnchorObserverTest {
    static final String OBSERVER = "anchors";
    static final String THREAD_POLICY = "c1".repeat(28);
    static final byte[] THREAD_NAME = "anchor".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ANCHOR_HASH = filled(28, 0x21);
    static final String FINGERPRINT = "fa".repeat(32);
    static final String ANCHOR = enterprise(ANCHOR_HASH);
    static final Hash32 MEMBER_A = id(0x0a);
    static final Hash32 MEMBER_B = id(0x0b);
    static final Hash32 MEMBER_C = id(0x0c);

    static Map<String, String> anchorSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("type", AssetAnchorObserverProvider.TYPE);
        settings.put("anchor.thread-policy-id", THREAD_POLICY);
        settings.put("anchor.thread-asset-name", HexFormat.of().formatHex(THREAD_NAME));
        settings.put("anchor.script-hash", HexFormat.of().formatHex(ANCHOR_HASH));
        settings.put("anchor.genesis-id", GENESIS);
        settings.put("anchor.application-id", "asset-payments");
        settings.put("anchor.commitment-profile", "mpf-blake2b256-v1");
        settings.put("anchor.format-fingerprint", FINGERPRINT);
        return settings;
    }

    /** An anchor datum v2 in the on-chain ABI. */
    static byte[] anchorDatum(long version, String genesis, long height, int threshold, long updatedAtMs,
                              Hash32... members) {
        byte[][] keys = new byte[members.length][];
        for (int i = 0; i < members.length; i++) keys[i] = bytes(members[i].bytes());
        List<byte[]> fields = new ArrayList<>(List.of(integer(version), bytes(CHAIN.getBytes(StandardCharsets.UTF_8)),
                bytes(HexFormat.of().parseHex(genesis)), bytes("asset-payments".getBytes(StandardCharsets.UTF_8)),
                bytes("mpf-blake2b256-v1".getBytes(StandardCharsets.UTF_8)),
                bytes(HexFormat.of().parseHex(FINGERPRINT)),
                integer(height), bytes(filled(32, 0xbb)), bytes(filled(32, 0x55)), list(keys), integer(threshold)));
        if (version == 2) fields.add(integer(updatedAtMs));
        return constr(0, fields.toArray(byte[][]::new));
    }

    static byte[] anchorDatum(long height, int threshold, long updatedAtMs, Hash32... members) {
        return anchorDatum(2, GENESIS, height, threshold, updatedAtMs, members);
    }

    final AssetAnchorObserver observer = new AssetAnchorObserver(OBSERVER, anchorSettings());

    List<L1Observation> observe(TransactionBody... txs) {
        List<Witnesses> witnesses = new ArrayList<>();
        for (TransactionBody ignored : txs) witnesses.add(Witnesses.builder().build());
        Block block = block(List.of(), List.of(txs), witnesses);
        return observer.observe(SLOT, BLOCK_HASH, block);
    }

    static AnchorObservationV1 claim(L1Observation observation) {
        return AnchorObservationV1.decode(observation.claim());
    }

    @Test
    void theBootstrapAnAdvanceAndAHeartbeatAreReportedWithTheirAnchoredState() {
        TransactionBody bootstrap = tx(0x71, List.of(token(THREAD_POLICY, THREAD_NAME, 1)),
                output(ALICE, null, lovelace(9_000_000)),
                output(ANCHOR, anchorDatum(0, 1, 1_000, MEMBER_A), lovelace(2_000_000),
                        token(THREAD_POLICY, THREAD_NAME, 1)));
        TransactionBody advance = tx(0x72, List.of(),
                output(ANCHOR, anchorDatum(12, 2, 2_000, MEMBER_A, MEMBER_B, MEMBER_C), lovelace(2_000_000),
                        token(THREAD_POLICY, THREAD_NAME, 1)));
        TransactionBody heartbeat = tx(0x73, List.of(),
                output(ANCHOR, anchorDatum(12, 2, 3_000, MEMBER_A, MEMBER_B, MEMBER_C), lovelace(2_000_000),
                        token(THREAD_POLICY, THREAD_NAME, 1)));

        List<L1Observation> observations = observe(bootstrap, advance, heartbeat);

        assertThat(observations).hasSize(3);
        assertThat(observations.getFirst().eventOrdinal()).isEqualTo(1);
        AnchorObservationV1 first = claim(observations.get(0));
        assertThat(first.minted()).isTrue();
        assertThat(first.anchored()).hasValueSatisfying(anchored -> {
            assertThat(anchored.height()).isZero();
            assertThat(anchored.memberKeys()).containsExactly(MEMBER_A);
        });
        AnchorObservationV1 second = claim(observations.get(1));
        assertThat(second.minted()).isFalse();
        assertThat(second.anchored()).hasValueSatisfying(anchored -> {
            assertThat(anchored.height()).isEqualTo(12);
            assertThat(anchored.threshold()).isEqualTo(2);
            assertThat(anchored.updatedAtMs()).isEqualTo(2_000);
            assertThat(anchored.stateRoot()).isEqualTo(Hash32.of(filled(32, 0x55)));
            assertThat(anchored.memberKeysHex()).containsExactly(MEMBER_A.hex(), MEMBER_B.hex(), MEMBER_C.hex());
        });
        // A heartbeat is identified by (height, updatedAtMs).
        assertThat(claim(observations.get(2)).anchored().orElseThrow().updatedAtMs()).isEqualTo(3_000);
    }

    @Test
    void aThreadAuthenticatedDatumTheVaultWouldNotAcceptIsReportedUnaccepted() {
        List<byte[]> datums = List.of(
                anchorDatum(1, GENESIS, 5, 1, 0, MEMBER_A),                 // v1: no updatedAtMs
                anchorDatum(2, "6d".repeat(32), 5, 1, 0, MEMBER_A),         // another genesis
                anchorDatum(5, 1, 0, MEMBER_B, MEMBER_A),                   // members not ascending
                anchorDatum(5, 1, 0, MEMBER_A, MEMBER_A),                   // duplicate member
                anchorDatum(5, 3, 0, MEMBER_A, MEMBER_B),                   // threshold above the count
                anchorDatum(5, 0, 0, MEMBER_A),                             // zero threshold
                constr(0, integer(2)),                                      // truncated
                new byte[] {(byte) 0xff});                                  // not Plutus Data
        int hash = 0x74;
        for (byte[] datum : datums) {
            List<L1Observation> observations = observe(tx(hash++, List.of(),
                    output(ANCHOR, datum, lovelace(2_000_000), token(THREAD_POLICY, THREAD_NAME, 1))));
            assertThat(observations).singleElement().satisfies(observation -> assertThat(claim(observation).status())
                    .isEqualTo(AnchorObservationV1.Status.UNACCEPTED));
        }
        // No inline datum at all.
        assertThat(claim(observe(tx(0x7f, List.of(), output(ANCHOR, null, lovelace(2_000_000),
                token(THREAD_POLICY, THREAD_NAME, 1)))).getFirst()).anchored()).isEmpty();
    }

    @Test
    void theDatumIsJudgedDecodedSoAnIndefiniteEncodingIsAcceptedAlike() {
        byte[] definite = anchorDatum(7, 1, 10, MEMBER_A, MEMBER_B);
        byte[] indefinite = definite.clone();
        // d8 79 8c ... -> d8 79 9f ... ff: the outer field list in indefinite form.
        indefinite[2] = (byte) 0x9f;
        byte[] withBreak = new byte[indefinite.length + 1];
        System.arraycopy(indefinite, 0, withBreak, 0, indefinite.length);
        withBreak[indefinite.length] = (byte) 0xff;
        AnchorObservationV1 a = claim(observe(tx(0x80, List.of(), output(ANCHOR, definite, lovelace(2_000_000),
                token(THREAD_POLICY, THREAD_NAME, 1)))).getFirst());
        AnchorObservationV1 b = claim(observe(tx(0x80, List.of(), output(ANCHOR, withBreak, lovelace(2_000_000),
                token(THREAD_POLICY, THREAD_NAME, 1)))).getFirst());
        assertThat(b.anchored()).isEqualTo(a.anchored()).isPresent();
    }

    @Test
    void spoofedAnchorOutputsAreNeverReported() {
        String keyAddress = AddressProvider.getEntAddress(Credential.fromKey(ANCHOR_HASH), Networks.testnet())
                .getAddress();
        byte[] datum = anchorDatum(9, 1, 1, MEMBER_A);
        List<L1Observation> observations = observe(
                // A valid-looking datum at the anchor script without the thread token.
                tx(0x81, List.of(), output(ANCHOR, datum, lovelace(2_000_000))),
                // The token name of another policy.
                tx(0x82, List.of(), output(ANCHOR, datum, lovelace(2_000_000), token("c2".repeat(28), THREAD_NAME,
                        1))),
                // Another name under the thread policy.
                tx(0x83, List.of(), output(ANCHOR, datum, lovelace(2_000_000), token(THREAD_POLICY, new byte[0], 1))),
                // The thread token at a key address carrying the anchor hash as a key hash.
                tx(0x84, List.of(), output(keyAddress, datum, lovelace(2_000_000),
                        token(THREAD_POLICY, THREAD_NAME, 1))),
                // The thread token at another script.
                tx(0x85, List.of(), output(USDM_VAULT, datum, lovelace(2_000_000),
                        token(THREAD_POLICY, THREAD_NAME, 1))));
        assertThat(observations).isEmpty();

        // A phase-2-invalid anchor transaction never happened.
        Block invalid = block(List.of(0), List.of(tx(0x86, List.of(), output(ANCHOR, datum, lovelace(2_000_000),
                token(THREAD_POLICY, THREAD_NAME, 1)))), List.of(Witnesses.builder().build()));
        assertThat(observer.observe(SLOT, BLOCK_HASH, invalid)).isEmpty();
    }

    @Test
    void settingsPinTheAnchorIdentityAndFeedTheConsensusIdentity() {
        Map<String, String> otherProfile = new TreeMap<>(anchorSettings());
        otherProfile.put("anchor.commitment-profile", "jmt-v1");
        assertThatThrownBy(() -> new AssetAnchorObserver(OBSERVER, otherProfile))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> unknown = new TreeMap<>(anchorSettings());
        unknown.put("anchor.chain-id", CHAIN);
        assertThatThrownBy(() -> new AssetAnchorObserver(OBSERVER, unknown))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> otherFingerprint = new TreeMap<>(anchorSettings());
        otherFingerprint.put("anchor.format-fingerprint", "fb".repeat(32));
        assertThat(new AssetAnchorObserver(OBSERVER, otherFingerprint).consensusIdentity().canonicalIdentityBytes())
                .isNotEqualTo(observer.consensusIdentity().canonicalIdentityBytes());
        assertThat(observer.consensusIdentity().claimSchema()).isEqualTo(AnchorObservationV1.CLAIM_SCHEMA);
    }
}
