package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A canonical bundle of assets (ADR-059 D3): a map from {@link AssetId} to a positive unsigned
 * 64-bit quantity, with no zero entries and at most {@link #MAX_ENTRIES} entries.
 *
 * <p>Wire form: {@code [* [policy_id, asset_name, quantity]]}, sorted by the canonical
 * {@link AssetId} order. Arrays are used instead of a CBOR map so the encoding has exactly one
 * form without relying on map-key sorting.
 */
public final class AssetValue {
    public static final int MAX_ENTRIES = 16;
    public static final AssetValue EMPTY = new AssetValue(new TreeMap<>());

    private final NavigableMap<AssetId, BigInteger> entries;

    private AssetValue(NavigableMap<AssetId, BigInteger> entries) {
        this.entries = Collections.unmodifiableNavigableMap(entries);
    }

    public static AssetValue of(Map<AssetId, BigInteger> quantities) {
        TreeMap<AssetId, BigInteger> sorted = new TreeMap<>();
        for (Map.Entry<AssetId, BigInteger> entry : quantities.entrySet()) {
            if (entry.getKey() == null) throw new IllegalArgumentException("asset id is required");
            BigInteger quantity = Quantities.requireU64(entry.getValue(), "quantity");
            if (quantity.signum() != 0) sorted.put(entry.getKey(), quantity);
        }
        if (sorted.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("an asset value holds at most " + MAX_ENTRIES + " assets");
        }
        return new AssetValue(sorted);
    }

    public static AssetValue single(AssetId asset, BigInteger quantity) {
        return of(Map.of(asset, quantity));
    }

    public static AssetValue lovelace(BigInteger quantity) {
        return single(AssetId.ADA, quantity);
    }

    public static AssetValue decode(byte[] cbor) {
        return fromItem(AssetCbor.decodeArray(cbor));
    }

    static AssetValue fromItem(DataItem item) {
        Array array = AssetCbor.array(item, 0, MAX_ENTRIES);
        TreeMap<AssetId, BigInteger> parsed = new TreeMap<>();
        AssetId previous = null;
        for (DataItem element : array.getDataItems()) {
            Array triple = AssetCbor.array(element, 3, 3);
            Array pair = new Array();
            pair.add(triple.getDataItems().get(0));
            pair.add(triple.getDataItems().get(1));
            AssetId asset = AssetId.fromItem(pair);
            BigInteger quantity = AssetCbor.uint(triple.getDataItems().get(2), Quantities.MAX_U64);
            if (quantity.signum() == 0) throw AssetCbor.malformed("zero quantity");
            if (previous != null && previous.compareTo(asset) >= 0) {
                throw AssetCbor.malformed("assets are not strictly ascending");
            }
            parsed.put(asset, quantity);
            previous = asset;
        }
        return new AssetValue(parsed);
    }

    Array toItem() {
        Array array = new Array();
        for (Map.Entry<AssetId, BigInteger> entry : entries.entrySet()) {
            Array triple = new Array();
            triple.add(AssetCbor.bstr(entry.getKey().policyId()));
            triple.add(AssetCbor.bstr(entry.getKey().assetName()));
            triple.add(AssetCbor.uint(entry.getValue()));
            array.add(triple);
        }
        return array;
    }

    public byte[] encode() {
        return AssetCbor.encode(toItem());
    }

    public BigInteger quantityOf(AssetId asset) {
        return entries.getOrDefault(asset, BigInteger.ZERO);
    }

    public NavigableMap<AssetId, BigInteger> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True when the value holds {@code asset} and nothing else. */
    public boolean isOnly(AssetId asset) {
        return entries.size() == 1 && entries.containsKey(asset);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssetValue that && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return entries.toString();
    }
}
