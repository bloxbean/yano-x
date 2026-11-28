package org.yanoproject.x.assets.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The canonical Cardano ledger encoding of an output value, used for the value hash in
 * {@link AssetDepositClaimV1} (ADR-059 D5). The L1 transaction may serialize the same value
 * differently; this is the one encoding every member and every reconciliation tool recomputes
 * from the decoded value:
 *
 * <pre>
 * value = coin / [coin, { * policy_id => { * asset_name => positive_coin } }]
 * </pre>
 *
 * The coin-only form is used when there are no native assets. Maps are definite and sorted by
 * RFC 8949 §4.2.1 order of their encoded keys (bytewise, so asset names are length-first, which
 * equals {@link AssetId}'s order). Integers use their shortest head.
 */
public final class CardanoValueCbor {
    private CardanoValueCbor() {
    }

    /** Encodes {@code lovelace} plus native assets (ADA is not allowed among {@code assets}). */
    public static byte[] encode(BigInteger lovelace, Map<AssetId, BigInteger> assets) {
        Quantities.requireU64(lovelace, "lovelace");
        Objects.requireNonNull(assets, "assets");
        TreeMap<AssetId, BigInteger> sorted = new TreeMap<>();
        for (Map.Entry<AssetId, BigInteger> entry : assets.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isAda()) {
                throw new IllegalArgumentException("native assets only");
            }
            sorted.put(entry.getKey(), Quantities.requirePositiveU64(entry.getValue(), "asset quantity"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (sorted.isEmpty()) {
            head(out, 0, lovelace);
            return out.toByteArray();
        }
        head(out, 4, BigInteger.TWO);
        head(out, 0, lovelace);
        NavigableMap<String, List<Map.Entry<AssetId, BigInteger>>> byPolicy = new TreeMap<>();
        for (Map.Entry<AssetId, BigInteger> entry : sorted.entrySet()) {
            byPolicy.computeIfAbsent(java.util.HexFormat.of().formatHex(entry.getKey().policyId()),
                    ignored -> new ArrayList<>()).add(entry);
        }
        head(out, 5, BigInteger.valueOf(byPolicy.size()));
        // Policy ids are all 28 bytes, so hex order is their bytewise (canonical) order.
        for (List<Map.Entry<AssetId, BigInteger>> policy : byPolicy.values()) {
            bytes(out, policy.getFirst().getKey().policyId());
            head(out, 5, BigInteger.valueOf(policy.size()));
            for (Map.Entry<AssetId, BigInteger> entry : policy) {
                bytes(out, entry.getKey().assetName());
                head(out, 0, entry.getValue());
            }
        }
        return out.toByteArray();
    }

    /** {@code blake2b-256} of {@link #encode}. */
    public static Hash32 hash(BigInteger lovelace, Map<AssetId, BigInteger> assets) {
        return Hash32.of(Blake2bUtil.blake2bHash256(encode(lovelace, assets)));
    }

    private static void bytes(ByteArrayOutputStream out, byte[] value) {
        head(out, 2, BigInteger.valueOf(value.length));
        out.write(value, 0, value.length);
    }

    private static void head(ByteArrayOutputStream out, int major, BigInteger value) {
        long n = value.longValue();
        int type = major << 5;
        if (value.compareTo(BigInteger.valueOf(24)) < 0) {
            out.write(type | (int) n);
        } else if (value.bitLength() <= 8) {
            out.write(type | 24);
            out.write((int) n);
        } else if (value.bitLength() <= 16) {
            out.write(type | 25);
            writeBigEndian(out, n, 2);
        } else if (value.bitLength() <= 32) {
            out.write(type | 26);
            writeBigEndian(out, n, 4);
        } else {
            out.write(type | 27);
            writeBigEndian(out, n, 8);
        }
    }

    private static void writeBigEndian(ByteArrayOutputStream out, long value, int size) {
        for (int shift = (size - 1) * 8; shift >= 0; shift -= 8) out.write((int) (value >>> shift) & 0xff);
    }
}
