package org.yanoproject.x.walletauth;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.walletauth.internal.StrictCbor;
import org.yanoproject.x.walletauth.internal.StrictCbor.Arr;
import org.yanoproject.x.walletauth.internal.StrictCbor.Bool;
import org.yanoproject.x.walletauth.internal.StrictCbor.Bytes;
import org.yanoproject.x.walletauth.internal.StrictCbor.Item;
import org.yanoproject.x.walletauth.internal.StrictCbor.MapItem;
import org.yanoproject.x.walletauth.internal.StrictCbor.NInt;
import org.yanoproject.x.walletauth.internal.StrictCbor.Tagged;
import org.yanoproject.x.walletauth.internal.StrictCbor.UInt;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Verifies a CIP-30 {@code signData} result: a {@code COSE_Sign1} and a {@code COSE_Key}, under
 * the byte-exact acceptance profile of ADR-059 D13 §1. On success it returns the signer's
 * credential, derived from the protected {@code address} header and bound to the key. Callers
 * must still compare that credential with the account's registered owner and decode the payload
 * as an intent ({@link WalletAuthorizer} does both).
 *
 * <p>Pinned references: CIP-0008 at cardano-foundation/CIPs {@code 86b89208}, CIP-0030 at
 * {@code 695efa05}, RFC 8152 §4.4.
 */
public final class CoseSign1Verifier {
    private static final long TAG_COSE_SIGN1 = 18;
    private static final long LABEL_ALG = 1;
    private static final long LABEL_CRIT = 2;
    private static final long LABEL_KID = 4;
    private static final String LABEL_ADDRESS = "address";
    private static final String LABEL_HASHED = "hashed";
    private static final long ALG_EDDSA = -8;
    private static final Set<Object> PROTECTED_ALLOWED = Set.of(LABEL_ALG, LABEL_KID, LABEL_ADDRESS);

    /** A verified signature. {@code payload} is exactly the signed bytes. */
    public record Verified(CardanoCredential credential, byte[] address, byte[] publicKey, byte[] payload) {
        public Verified {
            address = address.clone();
            publicKey = publicKey.clone();
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }
    }

    private CoseSign1Verifier() {
    }

    public static Verified verify(byte[] coseSign1, byte[] coseKey) {
        try {
            byte[] publicKey = parseKey(coseKey);
            Item root = StrictCbor.decode(coseSign1);
            if (root instanceof Tagged tagged) {
                if (tagged.tag() != TAG_COSE_SIGN1) throw new WalletAuthException("unexpected tag");
                root = tagged.item();
            }
            if (!(root instanceof Arr array) || array.items().size() != 4) {
                throw new WalletAuthException("COSE_Sign1 must be a 4-element array");
            }
            byte[] protectedBytes = bytes(array.items().get(0), "protected header");
            if (!(array.items().get(1) instanceof MapItem unprotected)) {
                throw new WalletAuthException("unprotected header must be a map");
            }
            if (!(array.items().get(2) instanceof Bytes payloadItem)) {
                throw new WalletAuthException("payload must be attached");
            }
            byte[] signature = bytes(array.items().get(3), "signature");
            if (signature.length != 64) throw new WalletAuthException("signature must be 64 bytes");

            byte[] address = checkProtected(protectedBytes);
            checkUnprotected(unprotected);

            CardanoCredential credential = CardanoCredential.fromAddress(address);
            byte[] keyHash = Blake2bUtil.blake2bHash224(publicKey);
            if (!Arrays.equals(keyHash, credential.keyHash())) {
                throw new WalletAuthException("key does not match the address credential");
            }
            byte[] payload = payloadItem.value();
            byte[] toVerify = sigStructure(protectedBytes, payload);
            if (!WalletEd25519.verify(signature, toVerify, publicKey)) {
                throw new WalletAuthException("signature does not verify");
            }
            return new Verified(credential, address, publicKey, payload);
        } catch (StrictCbor.Malformed malformed) {
            throw new WalletAuthException("malformed COSE: " + malformed.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new WalletAuthException(invalid.getMessage());
        }
    }

    /** Protected header: exactly {alg: -8, "address": bstr}, plus an optional kid; nothing else. */
    private static byte[] checkProtected(byte[] protectedBytes) throws StrictCbor.Malformed {
        if (protectedBytes.length == 0) throw new WalletAuthException("protected header is empty");
        if (!(StrictCbor.decode(protectedBytes) instanceof MapItem header)) {
            throw new WalletAuthException("protected header must hold a map");
        }
        if (header.entries().containsKey(LABEL_CRIT)) throw new WalletAuthException("crit is not supported");
        for (Object label : header.entries().keySet()) {
            if (!PROTECTED_ALLOWED.contains(label)) {
                throw new WalletAuthException("unexpected protected label " + label);
            }
        }
        if (!(header.entries().get(LABEL_ALG) instanceof NInt alg) || alg.value().longValue() != ALG_EDDSA) {
            throw new WalletAuthException("alg must be EdDSA (-8)");
        }
        if (!(header.entries().get(LABEL_ADDRESS) instanceof Bytes address)) {
            throw new WalletAuthException("protected address header is required");
        }
        return address.value();
    }

    /** Unprotected header: {@code hashed} absent or false; other labels are unsigned and ignored. */
    private static void checkUnprotected(MapItem unprotected) {
        Item hashed = unprotected.entries().get(LABEL_HASHED);
        if (hashed == null) return;
        if (!(hashed instanceof Bool flag)) throw new WalletAuthException("hashed must be a boolean");
        if (flag.value()) throw new WalletAuthException("hashed payloads are not accepted in v1 (Q5)");
    }

    /** COSE_Key: kty OKP(1), alg EdDSA(-8), crv Ed25519(6), x 32 bytes. kid and others ignored. */
    private static byte[] parseKey(byte[] coseKey) throws StrictCbor.Malformed {
        if (!(StrictCbor.decode(coseKey) instanceof MapItem key)) {
            throw new WalletAuthException("COSE_Key must be a map");
        }
        Map<Object, Item> entries = key.entries();
        if (!(entries.get(1L) instanceof UInt kty) || !kty.value().equals(BigInteger.ONE)) {
            throw new WalletAuthException("kty must be OKP (1)");
        }
        if (!(entries.get(3L) instanceof NInt alg) || alg.value().longValue() != ALG_EDDSA) {
            throw new WalletAuthException("key alg must be EdDSA (-8)");
        }
        if (!(entries.get(-1L) instanceof UInt crv) || !crv.value().equals(BigInteger.valueOf(6))) {
            throw new WalletAuthException("crv must be Ed25519 (6)");
        }
        if (!(entries.get(-2L) instanceof Bytes x) || x.value().length != 32) {
            throw new WalletAuthException("x must be a 32-byte public key");
        }
        return x.value();
    }

    /**
     * {@code ["Signature1", protected, h'', payload]} with the protected bytes exactly as received
     * (RFC 8152 §4.4) and empty external_aad (CIP-0030).
     */
    static byte[] sigStructure(byte[] protectedBytes, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x84);
        byte[] context = "Signature1".getBytes(StandardCharsets.US_ASCII);
        head(out, 3, context.length);
        out.writeBytes(context);
        head(out, 2, protectedBytes.length);
        out.writeBytes(protectedBytes);
        out.write(0x40);
        head(out, 2, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void head(ByteArrayOutputStream out, int major, int length) {
        if (length < 24) {
            out.write((major << 5) | length);
        } else if (length < 0x100) {
            out.write((major << 5) | 24);
            out.write(length);
        } else if (length < 0x10000) {
            out.write((major << 5) | 25);
            out.write(length >>> 8);
            out.write(length);
        } else {
            out.write((major << 5) | 26);
            out.write(length >>> 24);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
    }

    private static byte[] bytes(Item item, String name) {
        if (!(item instanceof Bytes b)) throw new WalletAuthException(name + " must be a byte string");
        return b.value();
    }
}
