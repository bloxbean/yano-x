package org.yanoproject.x.framework.api.appchain;

import org.yanoproject.x.framework.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalized framework-owned settings that affect app-block validity or
 * deterministic state application (ADR app-layer/016).
 *
 * <p>This is intentionally narrower than a complete chain/deployment
 * manifest. Machine-specific configuration, credentials, targets and local
 * operations settings do not belong here.</p>
 *
 * <p>{@code blockMaxForwardSkewMs} bounds certified block time (ADR app-layer/039
 * D12): a follower votes for a fresh proposal only if its timestamp is at most
 * this far ahead of the voter's clock, and every block's timestamp must be at
 * least its parent's.</p>
 *
 * <p>{@code blockMaxBackwardSkewMs} is the opt-in backward bound (ADR-059 framework
 * addition 8): when positive, a follower votes for a fresh proposal only if its
 * timestamp is at least the voter's clock minus this value. A re-proposed prepared
 * value keeps its timestamp and is exempt, mirroring the forward rule. {@code 0}
 * (the default) disables the rule and leaves the canonical profile bytes and digest
 * exactly as before the field existed. An enabled bound is at least
 * {@value #MIN_BLOCK_MAX_BACKWARD_SKEW_MS} ms, so ordinary clock jitter and block
 * propagation cannot make honest proposals unvotable.</p>
 *
 * <p>{@code membershipHandoffGate} is the opt-in ADR-059 D8b membership handoff
 * ({@value #MEMBERSHIP_ANCHOR_HANDOFF_SETTING}{@code =true}) together with the id of the
 * registered gate that finalizes it ({@value #MEMBERSHIP_HANDOFF_GATE_SETTING}): governed
 * membership changes activate only after an L1-stable anchor carries the new set, as that
 * gate reports. Both change which blocks change the member set, so both are
 * consensus-committed; the empty string (the default, handoff off) leaves the canonical
 * profile bytes and digest exactly as before the field existed.</p>
 */
public record AppChainConsensusProfile(
        int schemaVersion,
        int maxMessageBytes,
        int maxBlockMessages,
        long maxBlockBytes,
        int l1StabilityDepth,
        int epochStabilityDepth,
        long blockMaxForwardSkewMs,
        boolean enforceSenderSeq,
        boolean effectsEnabled,
        int effectsMaxPerBlock,
        int effectsMaxPayloadBytes,
        long effectsMaxExpiryBlocks,
        long effectsResultWindowBlocks,
        FinalityGate effectsDefaultGate,
        EffectOutcomeCommitment effectsOutcomeCommitment,
        boolean effectsStrictReservedPrefix,
        List<String> effectResultSigners,
        long blockMaxBackwardSkewMs,
        String membershipHandoffGate
) {
    public static final int SCHEMA_VERSION = 2;
    /** Chain setting for {@link #blockMaxForwardSkewMs()} (ADR app-layer/039 D12). */
    public static final String BLOCK_MAX_FORWARD_SKEW_SETTING = "block-time.max-forward-skew-ms";
    public static final long DEFAULT_BLOCK_MAX_FORWARD_SKEW_MS = 5_000;
    public static final long MAX_BLOCK_MAX_FORWARD_SKEW_MS = 600_000;
    /** Chain setting for {@link #blockMaxBackwardSkewMs()} (ADR-059 framework addition 8). */
    public static final String BLOCK_MAX_BACKWARD_SKEW_SETTING = "block-time.max-backward-skew-ms";
    /** {@code 0}: the backward bound is off (every chain created before ADR-059). */
    public static final long DISABLED_BLOCK_MAX_BACKWARD_SKEW_MS = 0;
    /** Smallest enabled backward bound: below it honest proposals could miss the vote window. */
    public static final long MIN_BLOCK_MAX_BACKWARD_SKEW_MS = 1_000;
    public static final long MAX_BLOCK_MAX_BACKWARD_SKEW_MS = 600_000;
    /** Chain setting for {@link #membershipAnchorHandoff()} (ADR-059 D8b, framework addition 4). */
    public static final String MEMBERSHIP_ANCHOR_HANDOFF_SETTING = "membership.anchor-handoff";
    /** Chain setting naming the registered membership handoff gate (ADR-059 D8b). */
    public static final String MEMBERSHIP_HANDOFF_GATE_SETTING = "membership.anchor-handoff-gate";
    /** A gate id: lowercase, 1..64 characters. */
    private static final Pattern GATE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern MEMBER_KEY = Pattern.compile("[0-9a-f]{64}");

    /** A profile without the backward block-time bound (the pre-ADR-059 shape). */
    public AppChainConsensusProfile(int schemaVersion, int maxMessageBytes, int maxBlockMessages,
                                    long maxBlockBytes, int l1StabilityDepth, int epochStabilityDepth,
                                    long blockMaxForwardSkewMs, boolean enforceSenderSeq,
                                    boolean effectsEnabled, int effectsMaxPerBlock,
                                    int effectsMaxPayloadBytes, long effectsMaxExpiryBlocks,
                                    long effectsResultWindowBlocks, FinalityGate effectsDefaultGate,
                                    EffectOutcomeCommitment effectsOutcomeCommitment,
                                    boolean effectsStrictReservedPrefix,
                                    List<String> effectResultSigners) {
        this(schemaVersion, maxMessageBytes, maxBlockMessages, maxBlockBytes, l1StabilityDepth,
                epochStabilityDepth, blockMaxForwardSkewMs, enforceSenderSeq, effectsEnabled,
                effectsMaxPerBlock, effectsMaxPayloadBytes, effectsMaxExpiryBlocks,
                effectsResultWindowBlocks, effectsDefaultGate, effectsOutcomeCommitment,
                effectsStrictReservedPrefix, effectResultSigners,
                DISABLED_BLOCK_MAX_BACKWARD_SKEW_MS, "");
    }

    /** A profile with the backward block-time bound and without the membership handoff gate. */
    public AppChainConsensusProfile(int schemaVersion, int maxMessageBytes, int maxBlockMessages,
                                    long maxBlockBytes, int l1StabilityDepth, int epochStabilityDepth,
                                    long blockMaxForwardSkewMs, boolean enforceSenderSeq,
                                    boolean effectsEnabled, int effectsMaxPerBlock,
                                    int effectsMaxPayloadBytes, long effectsMaxExpiryBlocks,
                                    long effectsResultWindowBlocks, FinalityGate effectsDefaultGate,
                                    EffectOutcomeCommitment effectsOutcomeCommitment,
                                    boolean effectsStrictReservedPrefix,
                                    List<String> effectResultSigners, long blockMaxBackwardSkewMs) {
        this(schemaVersion, maxMessageBytes, maxBlockMessages, maxBlockBytes, l1StabilityDepth,
                epochStabilityDepth, blockMaxForwardSkewMs, enforceSenderSeq, effectsEnabled,
                effectsMaxPerBlock, effectsMaxPayloadBytes, effectsMaxExpiryBlocks,
                effectsResultWindowBlocks, effectsDefaultGate, effectsOutcomeCommitment,
                effectsStrictReservedPrefix, effectResultSigners, blockMaxBackwardSkewMs, "");
    }

    /** This profile with the backward block-time bound set ({@code 0} disables it). */
    public AppChainConsensusProfile withBlockMaxBackwardSkewMs(long value) {
        return new AppChainConsensusProfile(schemaVersion, maxMessageBytes, maxBlockMessages,
                maxBlockBytes, l1StabilityDepth, epochStabilityDepth, blockMaxForwardSkewMs,
                enforceSenderSeq, effectsEnabled, effectsMaxPerBlock, effectsMaxPayloadBytes,
                effectsMaxExpiryBlocks, effectsResultWindowBlocks, effectsDefaultGate,
                effectsOutcomeCommitment, effectsStrictReservedPrefix, effectResultSigners, value,
                membershipHandoffGate);
    }

    /**
     * This profile with the ADR-059 D8b membership handoff selecting {@code gateId}, or
     * without the handoff for the empty string.
     */
    public AppChainConsensusProfile withMembershipHandoffGate(String gateId) {
        return new AppChainConsensusProfile(schemaVersion, maxMessageBytes, maxBlockMessages,
                maxBlockBytes, l1StabilityDepth, epochStabilityDepth, blockMaxForwardSkewMs,
                enforceSenderSeq, effectsEnabled, effectsMaxPerBlock, effectsMaxPayloadBytes,
                effectsMaxExpiryBlocks, effectsResultWindowBlocks, effectsDefaultGate,
                effectsOutcomeCommitment, effectsStrictReservedPrefix, effectResultSigners,
                blockMaxBackwardSkewMs, gateId);
    }

    /** True when governed membership changes wait for an L1 anchor handoff (ADR-059 D8b). */
    public boolean membershipAnchorHandoff() {
        return !membershipHandoffGate.isEmpty();
    }

    /** True when the opt-in backward bound is configured. */
    public boolean blockBackwardSkewBounded() {
        return blockMaxBackwardSkewMs > 0;
    }

    public AppChainConsensusProfile {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("consensus profile schemaVersion must be "
                    + SCHEMA_VERSION);
        }
        if (maxMessageBytes <= 0 || maxMessageBytes > AppChainConfig.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("maxMessageBytes is outside the app-chain v1 profile");
        }
        if (maxBlockMessages <= 0 || maxBlockMessages > AppChainConfig.MAX_BLOCK_MESSAGES) {
            throw new IllegalArgumentException("maxBlockMessages is outside the app-chain v1 profile");
        }
        if (maxBlockBytes <= 0 || maxBlockBytes > AppChainConfig.MAX_BLOCK_BYTES) {
            throw new IllegalArgumentException("maxBlockBytes is outside the app-chain v1 profile");
        }
        long requiredSingleMessageBytes = (long) maxMessageBytes
                + AppChainConfig.BLOCK_ENVELOPE_HEADROOM_BYTES
                + AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES;
        if (maxBlockBytes < requiredSingleMessageBytes) {
            throw new IllegalArgumentException(
                    "maxBlockBytes does not reserve finalized envelope/certificate headroom");
        }
        if (l1StabilityDepth < 0) {
            throw new IllegalArgumentException("l1StabilityDepth must be nonnegative");
        }
        if (epochStabilityDepth < 0) {
            throw new IllegalArgumentException("epochStabilityDepth must be nonnegative");
        }
        if (blockMaxForwardSkewMs <= 0 || blockMaxForwardSkewMs > MAX_BLOCK_MAX_FORWARD_SKEW_MS) {
            throw new IllegalArgumentException("blockMaxForwardSkewMs must be in [1, "
                    + MAX_BLOCK_MAX_FORWARD_SKEW_MS + "]");
        }
        if (blockMaxBackwardSkewMs != DISABLED_BLOCK_MAX_BACKWARD_SKEW_MS
                && (blockMaxBackwardSkewMs < MIN_BLOCK_MAX_BACKWARD_SKEW_MS
                        || blockMaxBackwardSkewMs > MAX_BLOCK_MAX_BACKWARD_SKEW_MS)) {
            throw new IllegalArgumentException("blockMaxBackwardSkewMs must be 0 (disabled) or in ["
                    + MIN_BLOCK_MAX_BACKWARD_SKEW_MS + ", " + MAX_BLOCK_MAX_BACKWARD_SKEW_MS + "]");
        }
        membershipHandoffGate = membershipHandoffGate == null ? "" : membershipHandoffGate;
        if (!membershipHandoffGate.isEmpty() && !GATE_ID.matcher(membershipHandoffGate).matches()) {
            throw new IllegalArgumentException("membershipHandoffGate must be a lowercase id of 1..64 "
                    + "characters [a-z0-9._-]");
        }
        effectsDefaultGate = Objects.requireNonNull(effectsDefaultGate, "effectsDefaultGate");
        if (effectsDefaultGate == FinalityGate.CHAIN_DEFAULT) {
            throw new IllegalArgumentException(
                    "consensus profile must contain a resolved effect default gate");
        }
        effectsOutcomeCommitment = Objects.requireNonNull(
                effectsOutcomeCommitment, "effectsOutcomeCommitment");

        if (effectsEnabled) {
            if (effectsMaxPerBlock <= 0
                    || effectsMaxPerBlock > (1 << 20)
                    || effectsMaxPayloadBytes <= 0
                    || effectsMaxPayloadBytes > 16 * 1024 * 1024
                    || effectsMaxExpiryBlocks <= 0
                    || effectsResultWindowBlocks <= 0) {
                throw new IllegalArgumentException(
                        "enabled effect consensus limits must be positive and within v1 bounds");
            }
        } else if (effectsMaxPerBlock != 0
                || effectsMaxPayloadBytes != 0
                || effectsMaxExpiryBlocks != 0
                || effectsResultWindowBlocks != 0
                || effectsDefaultGate != FinalityGate.APP_FINAL
                || effectsOutcomeCommitment != EffectOutcomeCommitment.PER_EFFECT) {
            throw new IllegalArgumentException(
                    "disabled effect profile must use canonical zero limits/app-final/per-effect");
        }

        List<String> source = effectResultSigners != null ? effectResultSigners : List.of();
        if (!effectsEnabled && !source.isEmpty()) {
            throw new IllegalArgumentException("disabled effect profile cannot designate result signers");
        }
        if (source.size() > AppChainConfig.MAX_MEMBERS) {
            throw new IllegalArgumentException("effectResultSigners exceeds v1 membership bound");
        }
        Set<String> unique = new HashSet<>();
        List<String> normalized = new ArrayList<>(source.size());
        for (String signer : source) {
            String key = Objects.requireNonNull(signer, "effectResultSigner")
                    .trim().toLowerCase(Locale.ROOT);
            if (!MEMBER_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "effectResultSigner must be a 32-byte lowercase hex member key");
            }
            if (!unique.add(key)) {
                throw new IllegalArgumentException("effectResultSigners contains a duplicate key");
            }
            normalized.add(key);
        }
        normalized.sort(String::compareTo);
        effectResultSigners = List.copyOf(normalized);
    }

    /** Parse {@value #BLOCK_MAX_FORWARD_SKEW_SETTING} from chain settings, defaulting to 5,000 ms. */
    public static long blockMaxForwardSkewMs(Map<String, String> settings) {
        String value = settings != null ? settings.get(BLOCK_MAX_FORWARD_SKEW_SETTING) : null;
        if (value == null || value.isBlank()) {
            return DEFAULT_BLOCK_MAX_FORWARD_SKEW_MS;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(BLOCK_MAX_FORWARD_SKEW_SETTING + " must be an integer", e);
        }
    }

    /**
     * Parse {@value #BLOCK_MAX_BACKWARD_SKEW_SETTING} from chain settings. Unset means
     * {@code 0}: the backward bound is off and the profile is unchanged.
     */
    public static long blockMaxBackwardSkewMs(Map<String, String> settings) {
        String value = settings != null ? settings.get(BLOCK_MAX_BACKWARD_SKEW_SETTING) : null;
        if (value == null || value.isBlank()) {
            return DISABLED_BLOCK_MAX_BACKWARD_SKEW_MS;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(BLOCK_MAX_BACKWARD_SKEW_SETTING + " must be an integer", e);
        }
    }

    /**
     * Parse {@value #MEMBERSHIP_ANCHOR_HANDOFF_SETTING} from chain settings: {@code true} or
     * {@code false} (the default, also when unset); anything else is rejected.
     */
    public static boolean membershipAnchorHandoff(Map<String, String> settings) {
        String value = settings != null ? settings.get(MEMBERSHIP_ANCHOR_HANDOFF_SETTING) : null;
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
            throw new IllegalArgumentException(MEMBERSHIP_ANCHOR_HANDOFF_SETTING + " must be true or false");
        }
        return Boolean.parseBoolean(trimmed);
    }

    /**
     * The consensus-profile handoff gate from chain settings: the empty string when
     * {@value #MEMBERSHIP_ANCHOR_HANDOFF_SETTING} is off, otherwise the required
     * {@value #MEMBERSHIP_HANDOFF_GATE_SETTING} id. A gate without the handoff, or the
     * handoff without a gate, is rejected.
     */
    public static String membershipHandoffGate(Map<String, String> settings) {
        boolean handoff = membershipAnchorHandoff(settings);
        String gate = settings != null ? settings.get(MEMBERSHIP_HANDOFF_GATE_SETTING) : null;
        gate = gate == null ? "" : gate.trim();
        if (!handoff) {
            if (!gate.isEmpty()) {
                throw new IllegalArgumentException(MEMBERSHIP_HANDOFF_GATE_SETTING + " requires "
                        + MEMBERSHIP_ANCHOR_HANDOFF_SETTING + "=true");
            }
            return "";
        }
        if (gate.isEmpty()) {
            throw new IllegalArgumentException(MEMBERSHIP_ANCHOR_HANDOFF_SETTING + "=true requires "
                    + MEMBERSHIP_HANDOFF_GATE_SETTING + ": the id of the registered membership handoff "
                    + "gate that finalizes changes (the framework ships none)");
        }
        if (!GATE_ID.matcher(gate).matches()) {
            throw new IllegalArgumentException(MEMBERSHIP_HANDOFF_GATE_SETTING
                    + " must be a lowercase id of 1..64 characters [a-z0-9._-]");
        }
        return gate;
    }
}
