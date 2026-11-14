package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/** Existing canonical observer identity encoding, shared by standalone and Cardano attachments. */
public final class ObserverConsensusProfile {
    private ObserverConsensusProfile() { }

    public static byte[] digest(Map<String, String> pluginSettings,
                                         BiFunction<String, Map<String, String>, L1ObserverConsensusIdentity> identities,
                                         byte[] l1NetworkGenesisId) {
        Map<String, Map<String, String>> byId = observerSettings(pluginSettings);
        byte[] networkIdentity = Objects.requireNonNull(
                l1NetworkGenesisId, "l1NetworkGenesisId").clone();
        if (networkIdentity.length != 32) {
            throw new IllegalArgumentException("L1 network genesis identity must be 32 bytes");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write("yano-observer-profile-v2\0".getBytes(StandardCharsets.US_ASCII));
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write(networkIdentity);
                for (Map.Entry<String, Map<String, String>> entry : byId.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
                    String observerId = entry.getKey();
                    Map<String, String> settings = entry.getValue();
                    String type = settings.getOrDefault("type", "").trim();
                    L1ObserverConsensusIdentity identity = identities.apply(observerId, settings);
                    writeProfileString(out, observerId);
                    writeProfileString(out, type);
                    out.writeInt(identity.abiVersion());
                    writeProfileString(out, identity.claimSchema());
                    out.writeInt(identity.orderingVersion());
                    byte[] canonical = identity.canonicalIdentityBytes();
                    out.writeInt(canonical.length);
                    out.write(canonical);
                }
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static void writeProfileString(DataOutputStream out, String value)
            throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    public static Map<String, Map<String, String>> observerSettings(
            Map<String, String> pluginSettings) {
        Map<String, Map<String, String>> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : pluginSettings.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("observers.")) {
                continue;
            }
            String rest = key.substring("observers.".length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                byId.computeIfAbsent(rest.substring(0, dot), ignored -> new HashMap<>())
                        .put(rest.substring(dot + 1), entry.getValue());
            }
        }
        return byId;
    }

    public static byte[] networkGenesisId(Map<String, String> settings, long protocolMagic) {
        boolean observersConfigured = settings.keySet().stream()
                .anyMatch(key -> key.startsWith("observers."));
        String configured = settings.get(
                "observation.l1-network-genesis-id");
        if (configured == null || configured.isBlank()) {
            if (observersConfigured) {
                throw new IllegalArgumentException(
                        "L1 observers require observation.l1-network-genesis-id");
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bytes.write("yano-l1-network-no-observers-v1\0"
                        .getBytes(StandardCharsets.US_ASCII));
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    out.writeLong(protocolMagic);
                }
                return Blake2bUtil.blake2bHash256(bytes.toByteArray());
            } catch (IOException impossible) {
                throw new UncheckedIOException(impossible);
            }
        }
        byte[] identity;
        try {
            identity = HexUtil.decodeHexString(configured.trim());
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(
                    "observation.l1-network-genesis-id must be 32-byte hex", malformed);
        }
        if (identity.length != 32) {
            throw new IllegalArgumentException(
                    "observation.l1-network-genesis-id must be 32-byte hex");
        }
        return identity;
    }

}
