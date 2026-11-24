package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The immutable claim leaf a vault script proves against the anchored root (ADR-059 D7, V2).
 *
 * <p>Wire form: {@code [1, claim_id, vault_id, kind, asset_value, vault_lovelace, recipient,
 * reserved_height, reserved_at_ms]}. Status lives under a separate key; this leaf never changes
 * and is never deleted. The script recomputes these exact bytes from the redeemer's claim fields
 * and compares them with the proof's value, and also requires {@code vaultId} to equal its own
 * pinned vault (I27).
 */
public record ClaimLeafV1(Hash32 claimId, Hash32 vaultId, ClaimKind kind, AssetValue value,
                          BigInteger vaultLovelace, byte[] recipient, long reservedHeight,
                          long reservedAtMs) {
    public static final int VERSION = 1;

    public ClaimLeafV1 {
        Objects.requireNonNull(claimId, "claim id");
        Objects.requireNonNull(vaultId, "vault id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Quantities.requireU64(vaultLovelace, "vault lovelace");
        if (recipient == null || recipient.length == 0
                || recipient.length > GatewayIds.MAX_ADDRESS_BYTES) {
            throw new IllegalArgumentException("recipient must be 1-128 bytes");
        }
        if (reservedHeight < 0 || reservedAtMs < 0) {
            throw new IllegalArgumentException("reservation height and time are non-negative");
        }
        if (value.isEmpty() && vaultLovelace.signum() == 0) {
            throw new IllegalArgumentException("a claim pays something");
        }
        recipient = recipient.clone();
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(claimId.bytes()));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.uint(kind.code()));
        array.add(value.toItem());
        array.add(AssetCbor.uint(vaultLovelace));
        array.add(AssetCbor.bstr(recipient));
        array.add(AssetCbor.uint(reservedHeight));
        array.add(AssetCbor.uint(reservedAtMs));
        return AssetCbor.encode(array);
    }

    public static ClaimLeafV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 9).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("claim leaf version");
        try {
            return new ClaimLeafV1(
                    Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                    Hash32.of(AssetCbor.bytes(items.get(2), 32, 32)),
                    ClaimKind.fromCode(AssetCbor.uintLong(items.get(3))),
                    AssetValue.fromItem(items.get(4)),
                    AssetCbor.uint(items.get(5), Quantities.MAX_U64),
                    AssetCbor.bytes(items.get(6), 1, GatewayIds.MAX_ADDRESS_BYTES),
                    AssetCbor.uintLong(items.get(7)),
                    AssetCbor.uintLong(items.get(8)));
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    @Override
    public byte[] recipient() {
        return recipient.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClaimLeafV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
