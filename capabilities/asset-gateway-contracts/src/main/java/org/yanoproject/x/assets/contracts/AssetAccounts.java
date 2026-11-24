package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic multi-asset accounting for account-style consumers (ADR-059 D12).
 *
 * <p>It is a library, not a state machine: a consumer calls it from its own {@code apply()}, so
 * business state and balances commit in one transition. All arithmetic is checked: u64 for
 * balances, reservations and credits, u128 for lifetime counters.
 *
 * <p><b>Layout.</b> Under the consumer's namespace {@code prefix}:
 * <ul>
 *   <li>{@code prefix ‖ "x/" ‖ vaultId ‖ holderId} → {@link ExitLeafV1}. This is both the
 *       account's balance in that vault and its exit-mode entitlement (D8g §5):
 *       {@code amount} is the available balance, {@code vaultLovelace} the min-ADA credit.</li>
 *   <li>{@code prefix ‖ "c/" ‖ vaultId} → {@link VaultCounters}.</li>
 * </ul>
 *
 * <p><b>Rules.</b> Only {@link #credit} increases total liabilities, and it requires a deposit
 * admission. {@link #transfer} preserves every total. {@link #reserve} moves value from a holder
 * into the vault's reserved counters, and {@link #consumeReserved} moves reserved value to paid
 * when a payout is confirmed. In v1 nothing returns reserved value to a balance (D7).
 *
 * <p><b>Invariant I4</b>, checked by {@link #checkInvariant}, for each vault and each unit:
 * {@code credited − paid = available + reserved}.
 */
public final class AssetAccounts {

    /** The minimal key-value view the library needs; adapters wrap the app-ledger writer. */
    public interface Store {
        Optional<byte[]> get(byte[] key);

        void put(byte[] key, byte[] value);

        void delete(byte[] key);
    }

    private static final byte[] EXIT_SEGMENT = "x/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COUNTER_SEGMENT = "c/".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PREFIX_BYTES = 64;

    private final byte[] prefix;
    private final Store store;

    public AssetAccounts(byte[] prefix, Store store) {
        if (prefix == null || prefix.length == 0 || prefix.length > MAX_PREFIX_BYTES) {
            throw new IllegalArgumentException("prefix must be 1-64 bytes");
        }
        this.prefix = prefix.clone();
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The exact state key of a holder's exit leaf. Vault scripts derive the same bytes. */
    public byte[] exitLeafKey(Hash32 vaultId, byte[] holderId) {
        requireHolder(holderId);
        return concat(prefix, EXIT_SEGMENT, vaultId.bytes(), holderId);
    }

    public byte[] countersKey(Hash32 vaultId) {
        return concat(prefix, COUNTER_SEGMENT, vaultId.bytes());
    }

    public Optional<ExitLeafV1> balance(Hash32 vaultId, byte[] holderId) {
        return store.get(exitLeafKey(vaultId, holderId)).map(ExitLeafV1::decode);
    }

    public VaultCounters counters(Hash32 vaultId) {
        return store.get(countersKey(vaultId)).map(VaultCounters::decode).orElse(VaultCounters.ZERO);
    }

    /**
     * Credits an admitted deposit: {@code amount} of the vault's asset and, for a native-token
     * vault, the deposit's incidental lovelace as the holder's min-ADA credit (D11).
     */
    public void credit(Hash32 vaultId, byte[] holderId, byte[] holderKeyHash, BigInteger amount,
                       BigInteger minAdaCredit) {
        Quantities.requireU64(amount, "amount");
        Quantities.requireU64(minAdaCredit, "min-ADA credit");
        if (amount.signum() == 0 && minAdaCredit.signum() == 0) {
            throw new IllegalArgumentException("a credit must be positive");
        }
        ExitLeafV1 current = balance(vaultId, holderId)
                .orElseGet(() -> new ExitLeafV1(holderKeyHash, vaultId, BigInteger.ZERO, BigInteger.ZERO));
        ExitLeafV1 next = new ExitLeafV1(holderKeyHash, vaultId,
                Quantities.addU64(current.amount(), amount, "balance"),
                Quantities.addU64(current.vaultLovelace(), minAdaCredit, "min-ADA credit"));
        VaultCounters counters = counters(vaultId);
        VaultCounters nextCounters = new VaultCounters(
                Quantities.addU128(counters.credited(), amount, "credited"),
                counters.paid(),
                Quantities.addU128(counters.available(), amount, "available"),
                counters.reserved(),
                Quantities.addU128(counters.lovelaceCredited(), minAdaCredit, "lovelace credited"),
                counters.lovelacePaid(),
                Quantities.addU128(counters.lovelaceAvailable(), minAdaCredit, "lovelace available"),
                counters.lovelaceReserved());
        store.put(exitLeafKey(vaultId, holderId), next.encode());
        store.put(countersKey(vaultId), nextCounters.encode());
    }

    /**
     * Moves {@code amount} of one vault's asset between holders. Totals are unchanged. The
     * recipient's key hash is needed only if it has no leaf yet.
     */
    public void transfer(Hash32 vaultId, byte[] fromHolder, byte[] toHolder,
                         byte[] toHolderKeyHash, BigInteger amount) {
        Quantities.requirePositiveU64(amount, "amount");
        if (java.util.Arrays.equals(fromHolder, toHolder)) {
            throw new IllegalArgumentException("sender and recipient must differ");
        }
        ExitLeafV1 from = balance(vaultId, fromHolder)
                .orElseThrow(() -> new ArithmeticException("insufficient balance"));
        ExitLeafV1 to = balance(vaultId, toHolder)
                .orElseGet(() -> new ExitLeafV1(toHolderKeyHash, vaultId, BigInteger.ZERO, BigInteger.ZERO));
        ExitLeafV1 nextFrom = from.withAmounts(Quantities.subU64(from.amount(), amount, "balance"),
                from.vaultLovelace());
        ExitLeafV1 nextTo = to.withAmounts(Quantities.addU64(to.amount(), amount, "balance"),
                to.vaultLovelace());
        writeLeaf(vaultId, fromHolder, nextFrom);
        writeLeaf(vaultId, toHolder, nextTo);
    }

    /**
     * Reserves a withdrawal: {@code amount} of the asset and {@code vaultLovelace} of the
     * holder's min-ADA credit leave the holder and become reserved for one claim.
     */
    public void reserve(Hash32 vaultId, byte[] holderId, BigInteger amount, BigInteger vaultLovelace) {
        Quantities.requireU64(amount, "amount");
        Quantities.requireU64(vaultLovelace, "vault lovelace");
        if (amount.signum() == 0 && vaultLovelace.signum() == 0) {
            throw new IllegalArgumentException("a reservation must be positive");
        }
        ExitLeafV1 leaf = balance(vaultId, holderId)
                .orElseThrow(() -> new ArithmeticException("insufficient balance"));
        ExitLeafV1 next = leaf.withAmounts(
                Quantities.subU64(leaf.amount(), amount, "balance"),
                Quantities.subU64(leaf.vaultLovelace(), vaultLovelace, "min-ADA credit"));
        VaultCounters counters = counters(vaultId);
        VaultCounters nextCounters = new VaultCounters(
                counters.credited(), counters.paid(),
                Quantities.subU128(counters.available(), amount, "available"),
                Quantities.addU128(counters.reserved(), amount, "reserved"),
                counters.lovelaceCredited(), counters.lovelacePaid(),
                Quantities.subU128(counters.lovelaceAvailable(), vaultLovelace, "lovelace available"),
                Quantities.addU128(counters.lovelaceReserved(), vaultLovelace, "lovelace reserved"));
        writeLeaf(vaultId, holderId, next);
        store.put(countersKey(vaultId), nextCounters.encode());
    }

    /** Records a confirmed payout of a reserved claim. */
    public void consumeReserved(Hash32 vaultId, BigInteger amount, BigInteger vaultLovelace) {
        Quantities.requireU64(amount, "amount");
        Quantities.requireU64(vaultLovelace, "vault lovelace");
        VaultCounters counters = counters(vaultId);
        VaultCounters next = new VaultCounters(
                counters.credited(),
                Quantities.addU128(counters.paid(), amount, "paid"),
                counters.available(),
                Quantities.subU128(counters.reserved(), amount, "reserved"),
                counters.lovelaceCredited(),
                Quantities.addU128(counters.lovelacePaid(), vaultLovelace, "lovelace paid"),
                counters.lovelaceAvailable(),
                Quantities.subU128(counters.lovelaceReserved(), vaultLovelace, "lovelace reserved"));
        store.put(countersKey(vaultId), next.encode());
    }

    /** I4 for both units of one vault. Throws {@link IllegalStateException} on violation. */
    public void checkInvariant(Hash32 vaultId) {
        VaultCounters c = counters(vaultId);
        if (!c.credited().subtract(c.paid()).equals(c.available().add(c.reserved()))) {
            throw new IllegalStateException("I4 violated for the vault asset of " + vaultId);
        }
        if (!c.lovelaceCredited().subtract(c.lovelacePaid())
                .equals(c.lovelaceAvailable().add(c.lovelaceReserved()))) {
            throw new IllegalStateException("I4 violated for lovelace of " + vaultId);
        }
    }

    private void writeLeaf(Hash32 vaultId, byte[] holderId, ExitLeafV1 leaf) {
        byte[] key = exitLeafKey(vaultId, holderId);
        if (leaf.isEmpty()) {
            store.delete(key);
        } else {
            store.put(key, leaf.encode());
        }
    }

    private static void requireHolder(byte[] holderId) {
        if (holderId == null || holderId.length == 0 || holderId.length > GatewayIds.MAX_HOLDER_ID_BYTES) {
            throw new IllegalArgumentException("holder id must be 1-64 bytes");
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /**
     * Per-vault counters. The first four count the vault's asset; the last four count lovelace
     * held as min-ADA credits (for the ADA vault the asset is lovelace and the last four stay 0).
     * Wire form: an array of eight 16-byte big-endian byte strings. CBOR integers stop at
     * 2^64 − 1 and larger ones need a tagged bignum, which the strict subset forbids, so u128
     * counters always use this fixed-width form.
     */
    public record VaultCounters(BigInteger credited, BigInteger paid, BigInteger available,
                                BigInteger reserved, BigInteger lovelaceCredited,
                                BigInteger lovelacePaid, BigInteger lovelaceAvailable,
                                BigInteger lovelaceReserved) {
        public static final VaultCounters ZERO = new VaultCounters(BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO);

        public VaultCounters {
            for (BigInteger value : List.of(credited, paid, available, reserved, lovelaceCredited,
                    lovelacePaid, lovelaceAvailable, lovelaceReserved)) {
                Quantities.requireU128(value, "counter");
            }
        }

        public byte[] encode() {
            Array array = new Array();
            for (BigInteger value : List.of(credited, paid, available, reserved, lovelaceCredited,
                    lovelacePaid, lovelaceAvailable, lovelaceReserved)) {
                array.add(AssetCbor.bstr(u128Bytes(value)));
            }
            return AssetCbor.encode(array);
        }

        public static VaultCounters decode(byte[] cbor) {
            List<DataItem> items = AssetCbor.decodeArray(cbor, 8).getDataItems();
            BigInteger[] values = new BigInteger[8];
            for (int i = 0; i < 8; i++) values[i] = new BigInteger(1, AssetCbor.bytes(items.get(i), 16, 16));
            return new VaultCounters(values[0], values[1], values[2], values[3], values[4], values[5],
                    values[6], values[7]);
        }

        private static byte[] u128Bytes(BigInteger value) {
            byte[] raw = value.toByteArray();
            byte[] out = new byte[16];
            int copy = Math.min(raw.length, 16);
            System.arraycopy(raw, raw.length - copy, out, 16 - copy, copy);
            return out;
        }
    }
}
