package org.yanoproject.x.explorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Pins a derived index to one chain, application, and commitment identity so a database can
 * never be opened for another chain (ADR-050 §2.1).
 *
 * @param chainId          the app chain
 * @param applicationId    the capability manifest's application id (state machine id)
 * @param profile          the state commitment profile id
 * @param stateGenesisIdHex the application-profile-bound state genesis id from {@code /state/identity}
 * @param manifestDigestHex the capability manifest digest as the node reports it, or empty
 */
public record IndexIdentity(String chainId, String applicationId, String profile,
                            String stateGenesisIdHex, String manifestDigestHex) {
    private static final HexFormat HEX = HexFormat.of();

    public IndexIdentity {
        chainId = bounded(chainId, "chainId", 128);
        applicationId = bounded(applicationId, "applicationId", 128);
        profile = bounded(profile, "profile", 64);
        stateGenesisIdHex = Objects.requireNonNull(stateGenesisIdHex, "stateGenesisIdHex");
        if (!stateGenesisIdHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("stateGenesisIdHex must be 32-byte canonical hex");
        }
        manifestDigestHex = manifestDigestHex == null ? "" : manifestDigestHex.trim();
        if (manifestDigestHex.length() > 128 || !manifestDigestHex.matches("[0-9a-f]*")) {
            throw new IllegalArgumentException("manifestDigestHex must be lowercase hex");
        }
    }

    /** SHA-256 over the canonical identity lines; stored with the database and compared on open. */
    public String digest() {
        String canonical = "yano-x-explorer-identity-v1\n" + chainId + "\n" + applicationId + "\n"
                + profile + "\n" + stateGenesisIdHex + "\n" + manifestDigestHex + "\n";
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String bounded(String value, String field, int maximum) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " must contain 1-" + maximum + " characters");
        }
        return normalized;
    }
}
