package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * A Cardano asset identity (ADR-059 D3): a 28-byte minting policy id and an asset name of 0-32
 * raw bytes, or ADA, which is the unique pair (empty policy, empty name). Names are bytes, never
 * tickers or hex text.
 *
 * <p>Wire form: {@code [policy_id: bstr, asset_name: bstr]}. There is one ordering, used for both
 * business logic and the wire: bytewise order of that canonical encoding. CBOR prefixes a byte
 * string with its length, so the order is length-first: under one policy the name {@code ff}
 * sorts before {@code 00 00}, and ADA sorts before every native asset.
 */
public final class AssetId implements Comparable<AssetId> {
    public static final int POLICY_ID_BYTES = 28;
    public static final int MAX_ASSET_NAME_BYTES = 32;

    public static final AssetId ADA = new AssetId(new byte[0], new byte[0]);

    private final byte[] policyId;
    private final byte[] assetName;
    private final byte[] encoded;

    private AssetId(byte[] policyId, byte[] assetName) {
        this.policyId = policyId;
        this.assetName = assetName;
        Array array = new Array();
        array.add(AssetCbor.bstr(policyId));
        array.add(AssetCbor.bstr(assetName));
        this.encoded = AssetCbor.encode(array);
    }

    /** A native asset. The policy id must be 28 bytes; the name may be empty. */
    public static AssetId of(byte[] policyId, byte[] assetName) {
        if (policyId == null || assetName == null) {
            throw new IllegalArgumentException("policy id and asset name are required");
        }
        if (policyId.length == 0) {
            if (assetName.length != 0) {
                throw new IllegalArgumentException("an empty policy id requires an empty asset name");
            }
            return ADA;
        }
        if (policyId.length != POLICY_ID_BYTES) {
            throw new IllegalArgumentException("policy id must be 28 bytes");
        }
        if (assetName.length > MAX_ASSET_NAME_BYTES) {
            throw new IllegalArgumentException("asset name must be at most 32 bytes");
        }
        return new AssetId(policyId.clone(), assetName.clone());
    }

    public static AssetId ofHex(String policyIdHex, String assetNameHex) {
        HexFormat hex = HexFormat.of();
        return of(hex.parseHex(policyIdHex), hex.parseHex(assetNameHex));
    }

    public static AssetId decode(byte[] cbor) {
        return fromItem(AssetCbor.decodeArray(cbor, 2));
    }

    static AssetId fromItem(DataItem item) {
        Array array = AssetCbor.array(item, 2, 2);
        byte[] policy = AssetCbor.bytes(array.getDataItems().get(0), 0, POLICY_ID_BYTES);
        byte[] name = AssetCbor.bytes(array.getDataItems().get(1), 0, MAX_ASSET_NAME_BYTES);
        try {
            return of(policy, name);
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    Array toItem() {
        Array array = new Array();
        array.add(AssetCbor.bstr(policyId));
        array.add(AssetCbor.bstr(assetName));
        return array;
    }

    public boolean isAda() {
        return policyId.length == 0;
    }

    public byte[] policyId() {
        return policyId.clone();
    }

    public byte[] assetName() {
        return assetName.clone();
    }

    public byte[] encode() {
        return encoded.clone();
    }

    @Override
    public int compareTo(AssetId other) {
        return Arrays.compareUnsigned(encoded, other.encoded);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssetId that && Arrays.equals(encoded, that.encoded);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encoded);
    }

    @Override
    public String toString() {
        if (isAda()) return "lovelace";
        HexFormat hex = HexFormat.of();
        return hex.formatHex(policyId) + "." + hex.formatHex(assetName);
    }
}
