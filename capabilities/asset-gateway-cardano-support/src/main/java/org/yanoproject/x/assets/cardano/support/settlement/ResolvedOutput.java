package org.yanoproject.x.assets.cardano.support.settlement;

import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One L1 output as a node's own UTxO view resolves it, in protocol-neutral form, so the settlement builder and the
 * co-sign body check are pure functions of their inputs (ADR-059 D8h §1, D9). The runtime adapts the host's UTxO view
 * to this record; tests build it directly.
 *
 * @param address         the raw Shelley address bytes
 * @param assets          native assets only (never lovelace), quantities positive
 * @param inlineDatum     the inline datum's CBOR, when the output has one
 * @param referenceScript the reference script the output carries, when it has one
 */
public record ResolvedOutput(Outpoint outpoint, byte[] address, BigInteger lovelace,
                             NavigableMap<AssetId, BigInteger> assets, Optional<byte[]> inlineDatum,
                             Optional<ReferenceScript> referenceScript) {

    /** A transaction input. Canonical order is the ledger's: transaction id bytes, then index. */
    public record Outpoint(Hash32 txId, int index) implements Comparable<Outpoint> {
        public Outpoint {
            Objects.requireNonNull(txId, "tx id");
            if (index < 0) throw new IllegalArgumentException("output index is non-negative");
        }

        public String txIdHex() {
            return txId.hex();
        }

        @Override
        public int compareTo(Outpoint other) {
            int byTx = txId.compareTo(other.txId);
            return byTx != 0 ? byTx : Integer.compare(index, other.index);
        }

        @Override
        public String toString() {
            return txId.hex() + "#" + index;
        }
    }

    /**
     * A reference script carried by an output: its hash, and the size of its CBOR-wrapped bytes (for the Conway
     * reference-script fee). {@code bytes} is the script's double-CBOR, when known.
     */
    public record ReferenceScript(byte[] scriptHash, int size, Optional<byte[]> bytes) {
        public ReferenceScript {
            if (scriptHash == null || scriptHash.length != 28) throw new IllegalArgumentException("28-byte hash");
            if (size < 0) throw new IllegalArgumentException("size is non-negative");
            scriptHash = scriptHash.clone();
            bytes = bytes.map(byte[]::clone);
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }
    }

    public ResolvedOutput {
        Objects.requireNonNull(outpoint, "outpoint");
        if (address == null || address.length == 0) throw new IllegalArgumentException("address is required");
        Quantities.requireU64(lovelace, "lovelace");
        TreeMap<AssetId, BigInteger> copy = new TreeMap<>();
        assets.forEach((asset, quantity) -> {
            if (asset.isAda()) throw new IllegalArgumentException("lovelace is not a native asset");
            if (quantity.signum() <= 0) throw new IllegalArgumentException("asset quantities are positive");
            copy.put(asset, quantity);
        });
        assets = java.util.Collections.unmodifiableNavigableMap(copy);
        address = address.clone();
        inlineDatum = inlineDatum.map(byte[]::clone);
        Objects.requireNonNull(referenceScript, "reference script");
    }

    @Override
    public byte[] address() {
        return address.clone();
    }

    @Override
    public Optional<byte[]> inlineDatum() {
        return inlineDatum.map(byte[]::clone);
    }

    public BigInteger quantityOf(AssetId asset) {
        return asset.isAda() ? lovelace : assets.getOrDefault(asset, BigInteger.ZERO);
    }

    /** The 28-byte payment credential of a Shelley address, and whether it is a script. */
    public record PaymentCredential(byte[] hash, boolean script) {
    }

    /** The payment credential, or empty for a Byron or reward address. */
    public Optional<PaymentCredential> paymentCredential() {
        return paymentCredential(address);
    }

    public static Optional<PaymentCredential> paymentCredential(byte[] raw) {
        if (raw == null || raw.length < 29) return Optional.empty();
        int type = (raw[0] & 0xff) >> 4;
        if (type > 7) return Optional.empty();
        return Optional.of(new PaymentCredential(Arrays.copyOfRange(raw, 1, 29), (type & 1) == 1));
    }

    public boolean sameAddress(byte[] raw) {
        return Arrays.equals(address, raw);
    }

    @Override
    public String toString() {
        return "ResolvedOutput[" + outpoint + " " + HexFormat.of().formatHex(address) + " " + lovelace + " "
                + assets + "]";
    }
}
