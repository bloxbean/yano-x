package org.yanoproject.x.appchain.formats;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Validation shared by the three files. */
public final class Names {
    public static final List<String> NETWORKS = List.of("devnet", "preview", "preprod", "mainnet");
    public static final int MAX_MEMBERS = 32;
    public static final int MAX_CHAINS = 32;

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}");
    private static final Pattern INVITE = Pattern.compile("[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9.-]{1,253}:[0-9]{1,5}");
    private static final Pattern HTTP = Pattern.compile("https?://[A-Za-z0-9.-]{1,253}:[0-9]{1,5}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Names() {
    }

    public static String name(String value, String what) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new FormatException(what + " '" + value
                    + "' must start with a lowercase letter and use only a-z, 0-9 and '-' (at most 63)");
        }
        return value;
    }

    public static String chainId(String value) {
        if (value == null || !CHAIN_ID.matcher(value).matches()) {
            throw new FormatException("Chain id '" + value + "' must use letters, digits, '.', '_', '~' or '-'");
        }
        return value;
    }

    public static String network(String value) {
        if (!NETWORKS.contains(value)) {
            throw new FormatException("Network '" + value + "' must be one of " + String.join(", ", NETWORKS));
        }
        return value;
    }

    public static String invite(String value) {
        if (value == null || !INVITE.matcher(value).matches()) {
            throw new FormatException("Invite code '" + value + "' must look like 7c1e-52aa-9d03");
        }
        return value;
    }

    public static String newInvite() {
        byte[] nonce = new byte[6];
        RANDOM.nextBytes(nonce);
        return Canonical.grouped(HexFormat.of().formatHex(nonce));
    }

    /** {@code host:port} other members use for node-to-node traffic. */
    public static String nodeAddress(String value) {
        if (value == null || !NODE.matcher(value).matches() || port(value) < 1 || port(value) > 65535) {
            throw new FormatException("Node address '" + value + "' must look like node-a.example.com:13337");
        }
        return value;
    }

    /** {@code http(s)://host:port} used by status to read the node's public API. */
    public static String httpAddress(String value) {
        if (value == null || !HTTP.matcher(value).matches() || port(value) < 1 || port(value) > 65535) {
            throw new FormatException("HTTP address '" + value + "' must look like http://node-a.example.com:8080");
        }
        return value;
    }

    public static int port(String address) {
        try {
            return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    public static String host(String address) {
        String withoutScheme = address.replaceFirst("^https?://", "");
        return withoutScheme.substring(0, withoutScheme.lastIndexOf(':'));
    }
}
