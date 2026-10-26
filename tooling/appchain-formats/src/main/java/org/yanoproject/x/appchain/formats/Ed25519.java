package org.yanoproject.x.appchain.formats;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Member keys: a 32-byte Ed25519 seed, as the node's {@code signing-key} expects. */
public final class Ed25519 {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{64}");

    private Ed25519() {
    }

    public static String newSeed() {
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        return HexFormat.of().formatHex(seed);
    }

    public static String publicKey(String seed) {
        return HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(parse(seed, "key")));
    }

    public static String sign(String seed, byte[] message) {
        return HexFormat.of().formatHex(
                CryptoConfiguration.INSTANCE.getSigningProvider().sign(message, parse(seed, "key")));
    }

    public static boolean verify(String publicKey, byte[] message, String signature) {
        try {
            return CryptoConfiguration.INSTANCE.getSigningProvider().verify(
                    HexFormat.of().parseHex(signature), message, parse(publicKey, "public key"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean isKey(String value) {
        return value != null && HEX_32.matcher(value).matches();
    }

    private static byte[] parse(String value, String what) {
        if (!isKey(value)) throw new FormatException("A " + what + " must be 64 lowercase hex characters");
        return HexFormat.of().parseHex(value);
    }
}
