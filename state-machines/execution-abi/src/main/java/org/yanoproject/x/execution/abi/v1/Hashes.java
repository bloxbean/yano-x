package org.yanoproject.x.execution.abi.v1;

import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hash functions of ABI v1. */
public final class Hashes {

    private Hashes() {
    }

    public static byte[] blake2b256(byte[] data) {
        Blake2bDigest digest = new Blake2bDigest(256);
        digest.update(data, 0, data.length);
        byte[] out = new byte[32];
        digest.doFinal(out, 0);
        return out;
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
