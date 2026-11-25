package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.contracts.AssetId;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Strict parsing of observer settings values, shared by the gateway's observers and gate. */
final class SettingsValues {
    private SettingsValues() {
    }

    /** {@code lovelace}, or {@code <56 hex policy>.<0..64 hex name>} in lowercase. */
    static AssetId asset(String value, String field) {
        if ("lovelace".equals(value)) return AssetId.ADA;
        int dot = value.indexOf('.');
        if (dot != 56 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be lovelace or <policy hex>.<asset name hex>");
        }
        try {
            return AssetId.of(HexFormat.of().parseHex(value.substring(0, dot)),
                    HexFormat.of().parseHex(value.substring(dot + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + ": " + invalid.getMessage(), invalid);
        }
    }

    static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }

    static String optional(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static long bounded(String value, long min, long max, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(field + " is out of range");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be an integer", invalid);
        }
    }

    static byte[] hex(String value, int bytes, String field) {
        if (value.length() != bytes * 2 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be " + bytes + "-byte lowercase hex");
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be " + bytes + "-byte lowercase hex", invalid);
        }
    }
}
