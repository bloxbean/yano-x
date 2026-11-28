package org.yanoproject.x.framework.cardano;

import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Chain-wide script-anchor profile ({@code anchor-profile.*}, ADR-059 framework
 * additions 3 and 5). Every member reads it, whether or not it leads anchoring, so it
 * lives in chain settings rather than in {@code anchor.*}. All of it is opt-in: with no
 * {@code anchor-profile.*} key a chain anchors exactly as before (v1, identity adopted
 * from the first verified sign request).
 *
 * <ul>
 *   <li>{@value #VERSION}: {@code 1} (default) or {@code 2}, the anchor-v2 validator and
 *       datum with heartbeats and a bounded {@code updatedAtMs}.</li>
 *   <li>{@value #MAX_WINDOW_MS}: the v2 validator parameter, default 600000.</li>
 *   <li>{@value #SLOT_ZERO}, {@value #SLOT_ZERO_TIME_MS}, {@value #SLOT_LENGTH_MS}: the
 *       network's slot-to-POSIX conversion used to bind {@code updatedAtMs} to the validity
 *       upper bound. Mainnet, preprod and preview have built-in values; any other network
 *       must set all three for v2.</li>
 *   <li>{@code anchor-profile.pinned.*}: the pinned anchor identity (all seven keys or
 *       none). Followers then never adopt an identity from a sign request, and startup
 *       fails closed on a mismatch.</li>
 * </ul>
 */
record AnchorProfileSettings(int version, long maxWindowMs, SlotTime slotTime,
                             PinnedIdentity pinned) {

    static final String PREFIX = "anchor-profile.";
    static final String VERSION = "anchor-profile.version";
    static final String MAX_WINDOW_MS = "anchor-profile.max-window-ms";
    static final String SLOT_ZERO = "anchor-profile.slot-zero";
    static final String SLOT_ZERO_TIME_MS = "anchor-profile.slot-zero-time-ms";
    static final String SLOT_LENGTH_MS = "anchor-profile.slot-length-ms";
    static final String PINNED_THREAD_POLICY_ID = "anchor-profile.pinned.thread-policy-id";
    static final String PINNED_THREAD_ASSET_NAME = "anchor-profile.pinned.thread-asset-name";
    static final String PINNED_SCRIPT_HASH = "anchor-profile.pinned.script-hash";
    static final String PINNED_GENESIS_ID = "anchor-profile.pinned.genesis-id";
    static final String PINNED_APPLICATION_ID = "anchor-profile.pinned.application-id";
    static final String PINNED_COMMITMENT_PROFILE = "anchor-profile.pinned.commitment-profile";
    static final String PINNED_FORMAT_FINGERPRINT = "anchor-profile.pinned.format-fingerprint";
    static final List<String> PINNED_KEYS = List.of(PINNED_THREAD_POLICY_ID, PINNED_THREAD_ASSET_NAME,
            PINNED_SCRIPT_HASH, PINNED_GENESIS_ID, PINNED_APPLICATION_ID, PINNED_COMMITMENT_PROFILE,
            PINNED_FORMAT_FINGERPRINT);
    /** Every accepted key; the parser treats the namespace as strict. */
    static final List<String> KEYS = List.of(VERSION, MAX_WINDOW_MS, SLOT_ZERO, SLOT_ZERO_TIME_MS,
            SLOT_LENGTH_MS, PINNED_THREAD_POLICY_ID, PINNED_THREAD_ASSET_NAME, PINNED_SCRIPT_HASH,
            PINNED_GENESIS_ID, PINNED_APPLICATION_ID, PINNED_COMMITMENT_PROFILE,
            PINNED_FORMAT_FINGERPRINT);

    static final long DEFAULT_MAX_WINDOW_MS = 600_000L;
    static final long MAINNET_MAGIC = 764_824_073L;
    static final long PREPROD_MAGIC = 1L;
    static final long PREVIEW_MAGIC = 2L;

    AnchorProfileSettings {
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException(VERSION + " must be 1 or 2");
        }
        if (maxWindowMs < 1) {
            throw new IllegalArgumentException(MAX_WINDOW_MS + " must be positive");
        }
        if (version == 2 && slotTime == null) {
            throw new IllegalArgumentException("anchor-profile.version=2 on this network requires "
                    + SLOT_ZERO + ", " + SLOT_ZERO_TIME_MS + " and " + SLOT_LENGTH_MS);
        }
    }

    /** The pre-ADR-059 behaviour: v1, no pin. */
    static AnchorProfileSettings defaults() {
        return new AnchorProfileSettings(1, DEFAULT_MAX_WINDOW_MS, null, null);
    }

    boolean v2() {
        return version == 2;
    }

    static AnchorProfileSettings from(Map<String, String> settings, long protocolMagic) {
        Map<String, String> values = settings == null ? Map.of() : settings;
        for (String key : values.keySet()) {
            if (key.startsWith(PREFIX) && !KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown anchor profile setting: " + key);
            }
        }
        int version = (int) longValue(values, VERSION, 1);
        long maxWindowMs = longValue(values, MAX_WINDOW_MS, DEFAULT_MAX_WINDOW_MS);
        SlotTime slotTime = slotTime(values, protocolMagic);
        PinnedIdentity pinned = PinnedIdentity.from(values);
        return new AnchorProfileSettings(version, maxWindowMs, slotTime, pinned);
    }

    private static SlotTime slotTime(Map<String, String> values, long protocolMagic) {
        boolean anyExplicit = values.containsKey(SLOT_ZERO) || values.containsKey(SLOT_ZERO_TIME_MS)
                || values.containsKey(SLOT_LENGTH_MS);
        if (anyExplicit) {
            if (!values.containsKey(SLOT_ZERO) || !values.containsKey(SLOT_ZERO_TIME_MS)
                    || !values.containsKey(SLOT_LENGTH_MS)) {
                throw new IllegalArgumentException(SLOT_ZERO + ", " + SLOT_ZERO_TIME_MS + " and "
                        + SLOT_LENGTH_MS + " must be set together");
            }
            return new SlotTime(longValue(values, SLOT_ZERO, 0), longValue(values, SLOT_ZERO_TIME_MS, 0),
                    longValue(values, SLOT_LENGTH_MS, 0));
        }
        return SlotTime.known(protocolMagic);
    }

    private static long longValue(Map<String, String> values, String key, long defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(key + " must be an integer", failure);
        }
    }

    /**
     * Shelley-era slot-to-POSIX conversion, the one the ledger applies to a transaction's
     * validity interval: {@code zeroSlotPosixMs + (slot − zeroSlot) · slotLengthMs}.
     */
    record SlotTime(long zeroSlot, long zeroSlotPosixMs, long slotLengthMs) {
        SlotTime {
            if (zeroSlot < 0 || zeroSlotPosixMs < 0 || slotLengthMs < 1 || slotLengthMs > 60_000) {
                throw new IllegalArgumentException("Invalid anchor-profile slot time");
            }
        }

        /** Built-in values for the public networks; null for any other magic. */
        static SlotTime known(long protocolMagic) {
            if (protocolMagic == MAINNET_MAGIC) {
                return new SlotTime(4_492_800L, 1_596_059_091_000L, 1_000L);
            }
            if (protocolMagic == PREPROD_MAGIC) {
                return new SlotTime(86_400L, 1_655_769_600_000L, 1_000L);
            }
            if (protocolMagic == PREVIEW_MAGIC) {
                return new SlotTime(0L, 1_666_656_000_000L, 1_000L);
            }
            return null;
        }

        long toPosixMs(long slot) {
            if (slot < zeroSlot) {
                throw new IllegalArgumentException("Slot precedes the anchor-profile slot-time origin");
            }
            return Math.addExact(zeroSlotPosixMs, Math.multiplyExact(slot - zeroSlot, slotLengthMs));
        }

        /** The julc evaluator's slot configuration for the same conversion. */
        org.julclang.clientlib.eval.SlotConfig julcSlotConfig() {
            return new org.julclang.clientlib.eval.SlotConfig(zeroSlot, zeroSlotPosixMs, slotLengthMs);
        }
    }

    /**
     * The pinned anchor identity (ADR-059 D8b). Hex values are lowercase; the thread asset
     * name is hex too (0..32 bytes, empty for legacy chains).
     */
    record PinnedIdentity(byte[] threadPolicyId, byte[] threadAssetName, byte[] scriptHash,
                          byte[] genesisId, String applicationId, String commitmentProfile,
                          byte[] formatFingerprint) {
        PinnedIdentity {
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(commitmentProfile, "commitmentProfile");
            if (threadPolicyId == null || threadPolicyId.length != 28
                    || threadAssetName == null || threadAssetName.length > 32
                    || scriptHash == null || scriptHash.length != 28
                    || genesisId == null || genesisId.length != 32
                    || formatFingerprint == null || formatFingerprint.length != 32
                    || applicationId.isBlank() || commitmentProfile.isBlank()) {
                throw new IllegalArgumentException("Invalid pinned anchor identity");
            }
            threadPolicyId = threadPolicyId.clone();
            threadAssetName = threadAssetName.clone();
            scriptHash = scriptHash.clone();
            genesisId = genesisId.clone();
            formatFingerprint = formatFingerprint.clone();
        }

        String threadPolicyHex() {
            return HexFormat.of().formatHex(threadPolicyId);
        }

        String threadAssetNameHex() {
            return HexFormat.of().formatHex(threadAssetName);
        }

        static PinnedIdentity from(Map<String, String> values) {
            long present = PINNED_KEYS.stream().filter(values::containsKey).count();
            if (present == 0) {
                return null;
            }
            if (present != PINNED_KEYS.size()) {
                throw new IllegalArgumentException("A pinned anchor identity needs every key: " + PINNED_KEYS);
            }
            try {
                return new PinnedIdentity(hex(values, PINNED_THREAD_POLICY_ID),
                        hex(values, PINNED_THREAD_ASSET_NAME), hex(values, PINNED_SCRIPT_HASH),
                        hex(values, PINNED_GENESIS_ID), values.get(PINNED_APPLICATION_ID).trim(),
                        values.get(PINNED_COMMITMENT_PROFILE).trim(),
                        hex(values, PINNED_FORMAT_FINGERPRINT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid pinned anchor identity: " + invalid.getMessage(),
                        invalid);
            }
        }

        private static byte[] hex(Map<String, String> values, String key) {
            String value = values.get(key).trim().toLowerCase(Locale.ROOT);
            if (!value.matches("([0-9a-f]{2})*")) {
                throw new IllegalArgumentException(key + " must be hex");
            }
            return HexFormat.of().parseHex(value);
        }

        @Override
        public String toString() {
            return "PinnedIdentity[policy=" + threadPolicyHex() + ", assetName=" + threadAssetNameHex()
                    + ", scriptHash=" + HexFormat.of().formatHex(scriptHash)
                    + ", applicationId=" + applicationId + ", commitmentProfile=" + commitmentProfile + "]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PinnedIdentity that
                    && java.util.Arrays.equals(threadPolicyId, that.threadPolicyId)
                    && java.util.Arrays.equals(threadAssetName, that.threadAssetName)
                    && java.util.Arrays.equals(scriptHash, that.scriptHash)
                    && java.util.Arrays.equals(genesisId, that.genesisId)
                    && applicationId.equals(that.applicationId)
                    && commitmentProfile.equals(that.commitmentProfile)
                    && java.util.Arrays.equals(formatFingerprint, that.formatFingerprint);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(scriptHash) * 31 + applicationId.hashCode();
        }
    }
}
