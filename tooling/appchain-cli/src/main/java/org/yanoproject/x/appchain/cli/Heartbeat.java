package org.yanoproject.x.appchain.cli;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * Small messages that make an idle chain produce blocks, so a membership change can activate. Each one is a
 * real command (the same ones the single-host launcher uses), so approve asks before sending them.
 */
final class Heartbeat {
    static final String TOPIC = "appchain.heartbeat";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Heartbeat() {
    }

    /** What one heartbeat does on {@code machine}, or {@code null} when it cannot be advanced. */
    static String effect(String machine) {
        return switch (machine) {
            case "ordered-log" -> "appends a permanent heartbeat entry";
            case "kv-registry" -> "deletes an absent random key";
            case "approvals" -> "rejects an unknown random item";
            default -> null;
        };
    }

    /**
     * Whether a chain accepts heartbeats: its topic registry, when it has one, must accept unregistered topics.
     * Read from the chain, because member governance can change it after genesis.
     */
    static boolean allowed(NodeClient client, String path) {
        return client.get(path + "/topics/config")
                .map(config -> !config.path("enabled").asBoolean(false)
                        || !config.path("config").path("settings").path("unregisteredReject").asBoolean(false))
                .orElse(true);
    }

    static Map<String, String> message(String machine) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String label = TOPIC + "." + HexFormat.of().formatHex(nonce);
        byte[] text = label.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        switch (machine) {
            case "ordered-log" -> body.writeBytes(text);
            case "kv-registry" -> {
                body.write(0x83);
                body.write(0x01);
                head(body, 2, text.length);
                body.writeBytes(text);
                head(body, 2, 0);
            }
            case "approvals" -> {
                body.write(0x82);
                body.write(0x02);
                head(body, 3, text.length);
                body.writeBytes(text);
            }
            default -> throw new IllegalArgumentException(machine);
        }
        return Map.of("topic", TOPIC, "bodyHex", HexFormat.of().formatHex(body.toByteArray()));
    }

    private static void head(ByteArrayOutputStream out, int major, int length) {
        if (length < 24) {
            out.write(major * 32 + length);
        } else {
            out.write(major * 32 + 24);
            out.write(length);
        }
    }
}
