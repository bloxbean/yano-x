package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A consumer-neutral exit entitlement (ADR-059 D8g §5): all value in one vault that is
 * attributable to one holder and not in a claim. In exit mode, {@code BalanceExit} proves this
 * leaf and pays {@code amount} of the vault's asset plus at least {@code vaultLovelace}.
 *
 * <p>Wire form: {@code [1, holder_key_hash, vault_id, amount, vault_lovelace]}. For the payment
 * ledger this leaf <em>is</em> the account-and-vault balance leaf: {@code amount} is the available
 * balance (plus any locked compartments) and {@code vaultLovelace} is the min-ADA credit.
 */
public record ExitLeafV1(byte[] holderKeyHash, Hash32 vaultId, BigInteger amount,
                         BigInteger vaultLovelace) {
    public static final int VERSION = 1;
    public static final int KEY_HASH_BYTES = 28;

    public ExitLeafV1 {
        if (holderKeyHash == null || holderKeyHash.length != KEY_HASH_BYTES) {
            throw new IllegalArgumentException("holder key hash must be 28 bytes");
        }
        Objects.requireNonNull(vaultId, "vault id");
        Quantities.requireU64(amount, "amount");
        Quantities.requireU64(vaultLovelace, "vault lovelace");
        holderKeyHash = holderKeyHash.clone();
    }

    public boolean isEmpty() {
        return amount.signum() == 0 && vaultLovelace.signum() == 0;
    }

    public ExitLeafV1 withAmounts(BigInteger newAmount, BigInteger newVaultLovelace) {
        return new ExitLeafV1(holderKeyHash, vaultId, newAmount, newVaultLovelace);
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(holderKeyHash));
        array.add(AssetCbor.bstr(vaultId.bytes()));
        array.add(AssetCbor.uint(amount));
        array.add(AssetCbor.uint(vaultLovelace));
        return AssetCbor.encode(array);
    }

    public static ExitLeafV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 5).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("exit leaf version");
        return new ExitLeafV1(
                AssetCbor.bytes(items.get(1), KEY_HASH_BYTES, KEY_HASH_BYTES),
                Hash32.of(AssetCbor.bytes(items.get(2), 32, 32)),
                AssetCbor.uint(items.get(3), Quantities.MAX_U64),
                AssetCbor.uint(items.get(4), Quantities.MAX_U64));
    }

    @Override
    public byte[] holderKeyHash() {
        return holderKeyHash.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExitLeafV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
