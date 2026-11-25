package org.yanoproject.x.framework.api.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical ADR-016 key, binary codec and domain-separated digest. */
public final class AppChainConsensusProfileCommitment {
    public static final int FIXED_BYTES = 65;
    /** Optional tail present only when {@link #FLAG_BACKWARD_SKEW} is set. */
    public static final int BACKWARD_SKEW_TAIL_BYTES = 8;
    /** Largest optional tail present only when {@link #FLAG_MEMBERSHIP_ANCHOR_HANDOFF} is set. */
    public static final int MAX_HANDOFF_GATE_TAIL_BYTES = 1 + 64;
    public static final int MAX_BYTES = FIXED_BYTES + AppChainConfig.MAX_MEMBERS * 32
            + BACKWARD_SKEW_TAIL_BYTES + MAX_HANDOFF_GATE_TAIL_BYTES;

    private static final byte FLAG_ENFORCE_SENDER_SEQ = 1;
    private static final byte FLAG_EFFECTS_ENABLED = 1 << 1;
    private static final byte FLAG_EFFECTS_STRICT_PREFIX = 1 << 2;
    /**
     * ADR-059 framework addition 8: the profile carries a backward block-time bound,
     * encoded as an 8-byte tail after the signer list. Unset for every profile without
     * the bound, so their canonical bytes and digests are unchanged.
     */
    private static final byte FLAG_BACKWARD_SKEW = 1 << 3;
    /**
     * ADR-059 D8b: governed membership changes wait for an L1-stable anchor handoff, as the
     * named gate reports. Encoded as a tail after any backward-skew tail: one length byte
     * (1..64) and the ASCII gate id. Unset for every profile without the handoff, whose
     * canonical bytes and digests are unchanged.
     */
    private static final byte FLAG_MEMBERSHIP_ANCHOR_HANDOFF = 1 << 4;
    private static final int KNOWN_FLAGS = FLAG_ENFORCE_SENDER_SEQ
            | FLAG_EFFECTS_ENABLED | FLAG_EFFECTS_STRICT_PREFIX | FLAG_BACKWARD_SKEW
            | FLAG_MEMBERSHIP_ANCHOR_HANDOFF;
    /** Offset of the flags byte in the fixed part. */
    private static final int FLAGS_OFFSET = 36;
    private static final byte[] MARKER_KEY = "~yano/consensus-profile/v2"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RESERVED_PREFIX = "~yano/"
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DIGEST_DOMAIN = "yano-app-chain-consensus-profile-v2\0"
            .getBytes(StandardCharsets.US_ASCII);

    private AppChainConsensusProfileCommitment() {
    }

    public static byte[] markerKey() {
        return MARKER_KEY.clone();
    }

    public static boolean isReserved(byte[] key) {
        if (key == null || key.length < RESERVED_PREFIX.length) {
            return false;
        }
        for (int i = 0; i < RESERVED_PREFIX.length; i++) {
            if (key[i] != RESERVED_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    public static byte[] encode(AppChainConsensusProfile profile) {
        Objects.requireNonNull(profile, "profile");
        List<String> signers = profile.effectResultSigners();
        boolean backwardBounded = profile.blockMaxBackwardSkewMs() > 0;
        byte[] handoffGate = profile.membershipHandoffGate().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + signers.size() * 32
                        + (backwardBounded ? BACKWARD_SKEW_TAIL_BYTES : 0)
                        + (handoffGate.length > 0 ? 1 + handoffGate.length : 0))
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(profile.schemaVersion());
        out.putInt(profile.maxMessageBytes());
        out.putInt(profile.maxBlockMessages());
        out.putLong(profile.maxBlockBytes());
        out.putInt(profile.l1StabilityDepth());
        out.putInt(profile.epochStabilityDepth());
        out.putLong(profile.blockMaxForwardSkewMs());
        int flags = 0;
        if (profile.enforceSenderSeq()) {
            flags |= FLAG_ENFORCE_SENDER_SEQ;
        }
        if (profile.effectsEnabled()) {
            flags |= FLAG_EFFECTS_ENABLED;
        }
        if (profile.effectsStrictReservedPrefix()) {
            flags |= FLAG_EFFECTS_STRICT_PREFIX;
        }
        if (backwardBounded) {
            flags |= FLAG_BACKWARD_SKEW;
        }
        if (profile.membershipAnchorHandoff()) {
            flags |= FLAG_MEMBERSHIP_ANCHOR_HANDOFF;
        }
        out.put((byte) flags);
        out.putInt(profile.effectsMaxPerBlock());
        out.putInt(profile.effectsMaxPayloadBytes());
        out.putLong(profile.effectsMaxExpiryBlocks());
        out.putLong(profile.effectsResultWindowBlocks());
        out.put(gateCode(profile.effectsDefaultGate()));
        out.put(outcomeCode(profile.effectsOutcomeCommitment()));
        out.putShort((short) signers.size());
        for (String signer : signers) {
            byte[] key = HexUtil.decodeHexString(signer);
            if (key.length != 32) {
                throw new IllegalArgumentException("effect result signer must decode to 32 bytes");
            }
            out.put(key);
        }
        if (backwardBounded) {
            out.putLong(profile.blockMaxBackwardSkewMs());
        }
        if (handoffGate.length > 0) {
            out.put((byte) handoffGate.length);
            out.put(handoffGate);
        }
        return out.array();
    }

    public static AppChainConsensusProfile decode(byte[] canonicalBytes) {
        byte[] bytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
        // Without the handoff tail the variable part is the signer list plus the optional
        // 8-byte skew tail; the handoff tail's own length byte is checked while decoding.
        int variable = bytes.length - FIXED_BYTES;
        boolean handoffTail = bytes.length >= FIXED_BYTES
                && (bytes[FLAGS_OFFSET] & FLAG_MEMBERSHIP_ANCHOR_HANDOFF) != 0;
        if (bytes.length < FIXED_BYTES || bytes.length > MAX_BYTES || !handoffTail
                && variable % 32 != 0 && (variable - BACKWARD_SKEW_TAIL_BYTES) % 32 != 0) {
            throw new IllegalArgumentException("invalid consensus profile byte length");
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            int schemaVersion = in.getInt();
            int maxMessageBytes = in.getInt();
            int maxBlockMessages = in.getInt();
            long maxBlockBytes = in.getLong();
            int l1StabilityDepth = in.getInt();
            int epochStabilityDepth = in.getInt();
            long blockMaxForwardSkewMs = in.getLong();
            int flags = Byte.toUnsignedInt(in.get());
            if ((flags & ~KNOWN_FLAGS) != 0) {
                throw new IllegalArgumentException("consensus profile contains unknown flag bits");
            }
            int effectsMaxPerBlock = in.getInt();
            int effectsMaxPayloadBytes = in.getInt();
            long effectsMaxExpiryBlocks = in.getLong();
            long effectsResultWindowBlocks = in.getLong();
            FinalityGate defaultGate = decodeGate(in.get());
            EffectOutcomeCommitment outcome = decodeOutcome(in.get());
            int signerCount = Short.toUnsignedInt(in.getShort());
            boolean backwardBounded = (flags & FLAG_BACKWARD_SKEW) != 0;
            boolean handoff = (flags & FLAG_MEMBERSHIP_ANCHOR_HANDOFF) != 0;
            int tail = backwardBounded ? BACKWARD_SKEW_TAIL_BYTES : 0;
            if (signerCount > AppChainConfig.MAX_MEMBERS
                    || (handoff ? in.remaining() < signerCount * 32 + tail + 2
                            : in.remaining() != signerCount * 32 + tail)) {
                throw new IllegalArgumentException("invalid consensus profile signer tail");
            }
            List<String> signers = new ArrayList<>(signerCount);
            for (int i = 0; i < signerCount; i++) {
                byte[] key = new byte[32];
                in.get(key);
                signers.add(HexUtil.encodeHexString(key));
            }
            long blockMaxBackwardSkewMs = backwardBounded ? in.getLong() : 0;
            if (backwardBounded && blockMaxBackwardSkewMs <= 0) {
                throw new IllegalArgumentException("backward skew flag requires a positive bound");
            }
            String handoffGate = "";
            if (handoff) {
                int length = Byte.toUnsignedInt(in.get());
                if (length < 1 || length > 64 || in.remaining() != length) {
                    throw new IllegalArgumentException("invalid consensus profile handoff gate tail");
                }
                byte[] gate = new byte[length];
                in.get(gate);
                handoffGate = new String(gate, StandardCharsets.US_ASCII);
            }
            AppChainConsensusProfile profile = new AppChainConsensusProfile(
                    schemaVersion,
                    maxMessageBytes,
                    maxBlockMessages,
                    maxBlockBytes,
                    l1StabilityDepth,
                    epochStabilityDepth,
                    blockMaxForwardSkewMs,
                    (flags & FLAG_ENFORCE_SENDER_SEQ) != 0,
                    (flags & FLAG_EFFECTS_ENABLED) != 0,
                    effectsMaxPerBlock,
                    effectsMaxPayloadBytes,
                    effectsMaxExpiryBlocks,
                    effectsResultWindowBlocks,
                    defaultGate,
                    outcome,
                    (flags & FLAG_EFFECTS_STRICT_PREFIX) != 0,
                    signers,
                    blockMaxBackwardSkewMs,
                    handoffGate);
            if (!Arrays.equals(bytes, encode(profile))) {
                throw new IllegalArgumentException("consensus profile is not canonically encoded");
            }
            return profile;
        } catch (java.nio.BufferUnderflowException malformed) {
            throw new IllegalArgumentException("truncated consensus profile", malformed);
        }
    }

    public static byte[] digest(AppChainConsensusProfile profile) {
        return digest(encode(profile));
    }

    public static byte[] digest(byte[] canonicalBytes) {
        byte[] bytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
        decode(bytes);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_DOMAIN);
            return digest.digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte gateCode(FinalityGate gate) {
        return (byte) gate.code();
    }

    private static FinalityGate decodeGate(byte code) {
        return FinalityGate.fromCode(Byte.toUnsignedInt(code));
    }

    private static byte outcomeCode(EffectOutcomeCommitment outcome) {
        return switch (outcome) {
            case PER_EFFECT -> 0;
            case PER_BLOCK -> 1;
        };
    }

    private static EffectOutcomeCommitment decodeOutcome(byte code) {
        return switch (Byte.toUnsignedInt(code)) {
            case 0 -> EffectOutcomeCommitment.PER_EFFECT;
            case 1 -> EffectOutcomeCommitment.PER_BLOCK;
            default -> throw new IllegalArgumentException("unknown effect outcome-commitment code");
        };
    }
}
