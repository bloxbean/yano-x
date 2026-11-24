package org.yanoproject.x.walletauth;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * The signing credential implied by a CIP-30 {@code signData} address (ADR-059 D13 §2). Per
 * CIP-0030 l.354: the payment key signs for base, enterprise and pointer addresses; the staking
 * key signs for reward addresses.
 *
 * <ul>
 *   <li>Base (types 0, 2), pointer (4) and enterprise (6) with a <b>key</b> payment part →
 *       {@link Kind#PAYMENT_KEY}. Type 2, a key payment part with a script stake part, is
 *       accepted.</li>
 *   <li>Reward with a key stake part (14) → {@link Kind#STAKE_KEY}.</li>
 *   <li>Script payment parts (1, 3, 5, 7), script reward (15), Byron (8) and anything else are
 *       rejected.</li>
 * </ul>
 */
public record CardanoCredential(Kind kind, byte[] keyHash, int addressNetworkId) {
    public static final int KEY_HASH_BYTES = 28;

    public enum Kind {
        PAYMENT_KEY(0), STAKE_KEY(1);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public CardanoCredential {
        if (kind == null || keyHash == null || keyHash.length != KEY_HASH_BYTES
                || addressNetworkId < 0 || addressNetworkId > 15) {
            throw new IllegalArgumentException("invalid credential");
        }
        keyHash = keyHash.clone();
    }

    /** Derives the credential from raw Shelley address bytes, or throws for any unsupported form. */
    public static CardanoCredential fromAddress(byte[] address) {
        if (address == null || address.length < 1 + KEY_HASH_BYTES) {
            throw new IllegalArgumentException("address too short");
        }
        int header = address[0] & 0xff;
        int type = header >>> 4;
        int network = header & 0x0f;
        Kind kind;
        int expectedLength;
        switch (type) {
            case 0, 2 -> {
                kind = Kind.PAYMENT_KEY;
                expectedLength = 1 + 2 * KEY_HASH_BYTES;
            }
            case 6 -> {
                kind = Kind.PAYMENT_KEY;
                expectedLength = 1 + KEY_HASH_BYTES;
            }
            case 4 -> {
                kind = Kind.PAYMENT_KEY;
                expectedLength = -1; // pointer: variable-length tail
            }
            case 14 -> {
                kind = Kind.STAKE_KEY;
                expectedLength = 1 + KEY_HASH_BYTES;
            }
            default -> throw new IllegalArgumentException(
                    "address type " + type + " has no key credential CIP-30 can sign with");
        }
        if (expectedLength > 0 && address.length != expectedLength) {
            throw new IllegalArgumentException("address length does not match its type");
        }
        if (expectedLength < 0 && (address.length < 1 + KEY_HASH_BYTES + 3 || address.length > 1 + KEY_HASH_BYTES + 30)) {
            throw new IllegalArgumentException("pointer address length out of range");
        }
        return new CardanoCredential(kind, Arrays.copyOfRange(address, 1, 1 + KEY_HASH_BYTES), network);
    }

    @Override
    public byte[] keyHash() {
        return keyHash.clone();
    }

    public boolean sameAs(CardanoCredential other) {
        return other != null && kind == other.kind && Arrays.equals(keyHash, other.keyHash);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CardanoCredential that && sameAs(that) && addressNetworkId == that.addressNetworkId;
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + Arrays.hashCode(keyHash) + addressNetworkId;
    }

    @Override
    public String toString() {
        return kind + ":" + HexFormat.of().formatHex(keyHash) + "@net" + addressNetworkId;
    }
}
