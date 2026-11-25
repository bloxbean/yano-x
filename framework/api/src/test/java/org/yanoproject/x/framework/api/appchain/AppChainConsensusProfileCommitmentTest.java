package org.yanoproject.x.framework.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainConsensusProfileCommitmentTest {
    private static final String SIGNER_1 = "11".repeat(32);
    private static final String SIGNER_2 = "22".repeat(32);
    // Derived outside the code under test (a one-off Python encoder of the field layout + SHA-256 over the
    // v2 domain; not kept as a script), ADR app-layer/039 D12.
    private static final String GOLDEN_BYTES = "0000000200010000000000400000000000400000"
            + "0000000500000005" + "0000000000001388"
            + "07000000800000400000000000000186a000000000000186a001000002"
            + SIGNER_1 + SIGNER_2;
    private static final String GOLDEN_DIGEST =
            "23f28f835fde84322f122ac49debacd5bea9d22cf2c03c1f6068a82be6620c05";

    @Test
    void freezesCanonicalKeyBytesAndDigest() {
        AppChainConsensusProfile profile = enabledProfile(List.of(SIGNER_2, SIGNER_1));

        byte[] encoded = AppChainConsensusProfileCommitment.encode(profile);

        assertThat(new String(AppChainConsensusProfileCommitment.markerKey(),
                java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("~yano/consensus-profile/v2");
        assertThat(HexUtil.encodeHexString(encoded)).isEqualTo(GOLDEN_BYTES);
        assertThat(HexUtil.encodeHexString(
                AppChainConsensusProfileCommitment.digest(profile))).isEqualTo(GOLDEN_DIGEST);
        assertThat(AppChainConsensusProfileCommitment.decode(encoded)).isEqualTo(profile);
        assertThat(profile.effectResultSigners()).containsExactly(SIGNER_1, SIGNER_2);
    }

    @Test
    void rejectsNonCanonicalOrMalformedBytes() {
        byte[] encoded = AppChainConsensusProfileCommitment.encode(
                enabledProfile(List.of(SIGNER_1, SIGNER_2)));

        byte[] unknownFlags = encoded.clone();
        unknownFlags[36] |= (byte) 0x80;
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(unknownFlags))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown flag");

        byte[] reversedSigners = encoded.clone();
        System.arraycopy(encoded, 65 + 32, reversedSigners, 65, 32);
        System.arraycopy(encoded, 65, reversedSigners, 65 + 32, 32);
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(reversedSigners))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonically encoded");

        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte length");
    }

    @Test
    void enforcesCanonicalDisabledEffectProfile() {
        AppChainConsensusProfile disabled = new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                65_536, 64, 4_194_304, 0, 0, 5_000, false,
                false, 0, 0, 0, 0,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of());

        assertThat(AppChainConsensusProfileCommitment.decode(
                AppChainConsensusProfileCommitment.encode(disabled))).isEqualTo(disabled);
        assertThatThrownBy(() -> new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                65_536, 64, 4_194_304, 0, 0, 5_000, false,
                false, 1, 0, 0, 0,
                FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled effect profile");
    }

    @Test
    void boundsAndParsesBlockMaxForwardSkew() {
        assertThat(AppChainConsensusProfile.blockMaxForwardSkewMs(Map.of())).isEqualTo(5_000);
        assertThat(AppChainConsensusProfile.blockMaxForwardSkewMs(
                Map.of("block-time.max-forward-skew-ms", " 750 "))).isEqualTo(750);
        assertThatThrownBy(() -> AppChainConsensusProfile.blockMaxForwardSkewMs(
                Map.of("block-time.max-forward-skew-ms", "fast")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block-time.max-forward-skew-ms");
        for (long invalid : new long[] {0, -1, 600_001}) {
            assertThatThrownBy(() -> new AppChainConsensusProfile(
                    AppChainConsensusProfile.SCHEMA_VERSION,
                    65_536, 64, 4_194_304, 0, 0, invalid, false,
                    false, 0, 0, 0, 0,
                    FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT,
                    true, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blockMaxForwardSkewMs");
        }
    }

    /** ADR-059 framework addition 8: an opt-in, flag-gated tail; unset profiles keep their bytes. */
    @Test
    void backwardSkewIsAFlagGatedTailThatLeavesUnboundedProfilesUnchanged() {
        AppChainConsensusProfile unbounded = enabledProfile(List.of(SIGNER_1, SIGNER_2));
        assertThat(unbounded.blockMaxBackwardSkewMs()).isZero();
        assertThat(unbounded.blockBackwardSkewBounded()).isFalse();
        assertThat(HexUtil.encodeHexString(AppChainConsensusProfileCommitment.encode(
                unbounded.withBlockMaxBackwardSkewMs(0)))).isEqualTo(GOLDEN_BYTES);

        AppChainConsensusProfile bounded = unbounded.withBlockMaxBackwardSkewMs(30_000);
        byte[] encoded = AppChainConsensusProfileCommitment.encode(bounded);
        String expected = GOLDEN_BYTES.substring(0, 72) + "0f" + GOLDEN_BYTES.substring(74)
                + "0000000000007530";
        assertThat(HexUtil.encodeHexString(encoded)).isEqualTo(expected);
        assertThat(AppChainConsensusProfileCommitment.decode(encoded)).isEqualTo(bounded);
        assertThat(AppChainConsensusProfileCommitment.digest(bounded))
                .isNotEqualTo(AppChainConsensusProfileCommitment.digest(unbounded));

        byte[] zeroTail = encoded.clone();
        java.util.Arrays.fill(zeroTail, zeroTail.length - 8, zeroTail.length, (byte) 0);
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(zeroTail))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] flagWithoutTail = HexUtil.decodeHexString(GOLDEN_BYTES);
        flagWithoutTail[36] |= 0x08;
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(flagWithoutTail))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AppChainConsensusProfile.blockMaxBackwardSkewMs(Map.of())).isZero();
        assertThat(AppChainConsensusProfile.blockMaxBackwardSkewMs(
                Map.of("block-time.max-backward-skew-ms", " 750 "))).isEqualTo(750);
        assertThatThrownBy(() -> AppChainConsensusProfile.blockMaxBackwardSkewMs(
                Map.of("block-time.max-backward-skew-ms", "slow")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("block-time.max-backward-skew-ms");
        for (long invalid : new long[] {-1, 1, 999, 600_001}) {
            assertThatThrownBy(() -> unbounded.withBlockMaxBackwardSkewMs(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blockMaxBackwardSkewMs");
        }
        assertThat(unbounded.withBlockMaxBackwardSkewMs(1_000).blockMaxBackwardSkewMs()).isEqualTo(1_000);
    }

    /**
     * ADR-059 D8b: the membership handoff and its gate id are a flag plus a length-prefixed
     * tail; profiles without the handoff keep their bytes.
     */
    @Test
    void membershipHandoffGateIsAConsensusTailThatLeavesUngatedProfilesUnchanged() {
        AppChainConsensusProfile ungated = enabledProfile(List.of(SIGNER_1, SIGNER_2));
        assertThat(ungated.membershipAnchorHandoff()).isFalse();
        assertThat(ungated.membershipHandoffGate()).isEmpty();
        assertThat(HexUtil.encodeHexString(AppChainConsensusProfileCommitment.encode(
                ungated.withMembershipHandoffGate("")))).isEqualTo(GOLDEN_BYTES);

        AppChainConsensusProfile gated = ungated.withMembershipHandoffGate("asset-anchor-v1");
        assertThat(gated.membershipAnchorHandoff()).isTrue();
        byte[] encoded = AppChainConsensusProfileCommitment.encode(gated);
        String gateTail = "0f" + HexUtil.encodeHexString("asset-anchor-v1".getBytes(StandardCharsets.US_ASCII));
        String expected = GOLDEN_BYTES.substring(0, 72) + "17" + GOLDEN_BYTES.substring(74) + gateTail;
        assertThat(HexUtil.encodeHexString(encoded)).isEqualTo(expected);
        assertThat(AppChainConsensusProfileCommitment.decode(encoded)).isEqualTo(gated);
        assertThat(AppChainConsensusProfileCommitment.digest(gated))
                .isNotEqualTo(AppChainConsensusProfileCommitment.digest(ungated));
        assertThat(AppChainConsensusProfileCommitment.digest(gated)).isNotEqualTo(
                AppChainConsensusProfileCommitment.digest(ungated.withMembershipHandoffGate("other-gate")));

        // Both opt-in fields together: the skew tail first, then the gate tail.
        AppChainConsensusProfile both = gated.withBlockMaxBackwardSkewMs(30_000);
        byte[] bothBytes = AppChainConsensusProfileCommitment.encode(both);
        assertThat(HexUtil.encodeHexString(bothBytes)).isEqualTo(GOLDEN_BYTES.substring(0, 72) + "1f"
                + GOLDEN_BYTES.substring(74) + "0000000000007530" + gateTail);
        assertThat(AppChainConsensusProfileCommitment.decode(bothBytes)).isEqualTo(both);
        assertThat(both.withMembershipHandoffGate("").withBlockMaxBackwardSkewMs(0)).isEqualTo(ungated);

        // Malformed gate tails: flag without a tail, a short or long length, an invalid id.
        byte[] flagOnly = HexUtil.decodeHexString(GOLDEN_BYTES);
        flagOnly[36] |= 0x10;
        assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(flagOnly))
                .isInstanceOf(IllegalArgumentException.class);
        for (String badTail : new String[] {"00", "10" + gateTail.substring(2), "0e" + gateTail.substring(2),
                "01" + "41"}) {
            byte[] bad = HexUtil.decodeHexString(GOLDEN_BYTES.substring(0, 72) + "17"
                    + GOLDEN_BYTES.substring(74) + badTail);
            assertThatThrownBy(() -> AppChainConsensusProfileCommitment.decode(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ungated.withMembershipHandoffGate("Bad Gate"))
                .isInstanceOf(IllegalArgumentException.class);

        // Settings: the gate is required with the handoff and rejected without it.
        assertThat(AppChainConsensusProfile.membershipAnchorHandoff(Map.of())).isFalse();
        assertThat(AppChainConsensusProfile.membershipHandoffGate(Map.of())).isEmpty();
        assertThat(AppChainConsensusProfile.membershipHandoffGate(Map.of(
                "membership.anchor-handoff", " TRUE ", "membership.anchor-handoff-gate", " asset-anchor-v1 ")))
                .isEqualTo("asset-anchor-v1");
        assertThatThrownBy(() -> AppChainConsensusProfile.membershipHandoffGate(
                Map.of("membership.anchor-handoff", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("membership.anchor-handoff-gate");
        assertThatThrownBy(() -> AppChainConsensusProfile.membershipHandoffGate(
                Map.of("membership.anchor-handoff-gate", "asset-anchor-v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("membership.anchor-handoff=true");
        assertThatThrownBy(() -> AppChainConsensusProfile.membershipAnchorHandoff(
                Map.of("membership.anchor-handoff", "yes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("membership.anchor-handoff");
    }

    @Test
    void contextDefaultRemainsCompatibleAndReservedPrefixIsExact() {
        AppStateMachineContext oldStyle = new AppStateMachineContext() {
            @Override
            public String chainId() {
                return "chain";
            }

            @Override
            public Map<String, String> settings() {
                return Map.of();
            }
        };

        assertThat(oldStyle.consensusProfile()).isEmpty();
        assertThat(AppChainConsensusProfileCommitment.isReserved(
                "~yano/anything".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).isTrue();
        assertThat(AppChainConsensusProfileCommitment.isReserved(
                "~yan/anything".getBytes(java.nio.charset.StandardCharsets.US_ASCII))).isFalse();
    }

    private static AppChainConsensusProfile enabledProfile(List<String> signers) {
        return new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                65_536,
                64,
                4_194_304,
                5,
                5,
                5_000,
                true,
                true,
                128,
                16_384,
                100_000,
                100_000,
                FinalityGate.L1_ANCHORED,
                EffectOutcomeCommitment.PER_EFFECT,
                true,
                signers);
    }
}
