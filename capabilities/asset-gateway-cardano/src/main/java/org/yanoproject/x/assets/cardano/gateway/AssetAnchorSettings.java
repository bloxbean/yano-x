package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.contracts.GatewayDatums;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.bounded;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.hex;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.optional;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.required;

/**
 * The consensus settings of one {@code asset-anchor-v1} observer (ADR-059 D8b): the pinned anchor identity, read from
 * {@code yano.app-chain.observers.<id>.*}. They mirror the chain's {@code anchor-profile.pinned.*} keys:
 *
 * <pre>
 * anchor.thread-policy-id      56 lowercase hex
 * anchor.thread-asset-name     0..64 lowercase hex
 * anchor.script-hash           56 lowercase hex
 * anchor.genesis-id            64 lowercase hex
 * anchor.application-id        the chain's application (state-machine) id
 * anchor.commitment-profile    mpf-blake2b256-v1 (the only profile of anchored-claim-vault-v1)
 * anchor.format-fingerprint    64 lowercase hex
 * min-stability-depth          1..100000, default 1
 * </pre>
 */
record AssetAnchorSettings(byte[] threadPolicyId, byte[] threadAssetName, byte[] scriptHash, byte[] genesisId,
                           String applicationId, String commitmentProfile, byte[] formatFingerprint,
                           int minStabilityDepth) {
    static final Set<String> KEYS = Set.of("type", "anchor.thread-policy-id", "anchor.thread-asset-name",
            "anchor.script-hash", "anchor.genesis-id", "anchor.application-id", "anchor.commitment-profile",
            "anchor.format-fingerprint", "min-stability-depth");

    AssetAnchorSettings {
        threadPolicyId = threadPolicyId.clone();
        threadAssetName = threadAssetName.clone();
        scriptHash = scriptHash.clone();
        genesisId = genesisId.clone();
        formatFingerprint = formatFingerprint.clone();
    }

    static AssetAnchorSettings parse(Map<String, String> settings) {
        for (String key : settings.keySet()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown anchor observer setting " + key);
        }
        String name = settings.getOrDefault("anchor.thread-asset-name", "").trim();
        if (name.length() > 64 || name.length() % 2 != 0 || !name.equals(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("anchor.thread-asset-name must be 0-32 bytes of lowercase hex");
        }
        String profile = required(settings, "anchor.commitment-profile");
        if (!GatewayDatums.COMMITMENT_PROFILE.equals(profile)) {
            throw new IllegalArgumentException("anchor.commitment-profile must be " + GatewayDatums.COMMITMENT_PROFILE
                    + " (ADR-059 D8b)");
        }
        String application = required(settings, "anchor.application-id");
        if (application.getBytes(StandardCharsets.UTF_8).length > 64) {
            throw new IllegalArgumentException("anchor.application-id is too long");
        }
        return new AssetAnchorSettings(
                hex(required(settings, "anchor.thread-policy-id"), 28, "anchor.thread-policy-id"),
                HexFormat.of().parseHex(name),
                hex(required(settings, "anchor.script-hash"), 28, "anchor.script-hash"),
                hex(required(settings, "anchor.genesis-id"), 32, "anchor.genesis-id"),
                application, profile,
                hex(required(settings, "anchor.format-fingerprint"), 32, "anchor.format-fingerprint"),
                (int) bounded(optional(settings, "min-stability-depth", "1"), 1, 100_000, "min-stability-depth"));
    }

    String threadPolicyHex() {
        return HexFormat.of().formatHex(threadPolicyId);
    }

    /** The anchor identity as the chain's {@code anchor-profile.pinned.*} settings would name it. */
    Map<String, String> pinnedIdentity() {
        Map<String, String> pinned = new LinkedHashMap<>();
        pinned.put("thread-policy-id", threadPolicyHex());
        pinned.put("thread-asset-name", HexFormat.of().formatHex(threadAssetName));
        pinned.put("script-hash", HexFormat.of().formatHex(scriptHash));
        pinned.put("genesis-id", HexFormat.of().formatHex(genesisId));
        pinned.put("application-id", applicationId);
        pinned.put("commitment-profile", commitmentProfile);
        pinned.put("format-fingerprint", HexFormat.of().formatHex(formatFingerprint));
        return pinned;
    }

    List<String> identityFields() {
        List<String> out = new ArrayList<>();
        pinnedIdentity().forEach((key, value) -> out.add("anchor." + key + "=" + value));
        out.add("min-stability-depth=" + minStabilityDepth);
        return out;
    }
}
