package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.WalletAuthorizer;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A test wallet: an Ed25519 key, its enterprise address on network 0, and CIP-30 {@code signData}. */
record TestWallet(byte[] seed, byte[] publicKey, byte[] address, CardanoCredential credential) {
    static TestWallet of(int seedByte) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) seedByte);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        byte[] address = new byte[29];
        address[0] = 0x60;
        System.arraycopy(Blake2bUtil.blake2bHash224(publicKey), 0, address, 1, 28);
        return new TestWallet(seed, publicKey, address, CardanoCredential.fromAddress(address));
    }

    byte[] accountId() {
        return WalletAuthorizer.accountId(credential);
    }

    byte[] keyHash() {
        return credential.keyHash();
    }

    /** {@code [COSE_Sign1, COSE_Key]} as CIP-30 {@code signData} returns them. */
    byte[][] signData(byte[] payload) {
        byte[] protectedHeader = cat(new byte[] {(byte) 0xa2, 0x01, 0x27, 0x67},
                "address".getBytes(StandardCharsets.US_ASCII), bstrHead(address.length), address);
        byte[] sigStructure = cat(new byte[] {(byte) 0x84, 0x6a}, "Signature1".getBytes(StandardCharsets.US_ASCII),
                bstrHead(protectedHeader.length), protectedHeader, new byte[] {0x40}, bstrHead(payload.length), payload);
        byte[] signature = WalletEd25519.sign(sigStructure, seed);
        byte[] sign1 = cat(new byte[] {(byte) 0x84}, bstrHead(protectedHeader.length), protectedHeader,
                new byte[] {(byte) 0xa0}, bstrHead(payload.length), payload, bstrHead(64), signature);
        byte[] key = cat(new byte[] {(byte) 0xa4, 0x01, 0x01, 0x03, 0x27, 0x20, 0x06, 0x21, 0x58, 0x20}, publicKey);
        return new byte[][] {sign1, key};
    }

    static byte[] bstrHead(int length) {
        if (length < 24) return new byte[] {(byte) (0x40 | length)};
        if (length < 256) return new byte[] {0x58, (byte) length};
        return new byte[] {0x59, (byte) (length >>> 8), (byte) length};
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
