package org.yanoproject.x.assetpayments.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * State records of the {@code asset-payments} ledger (ADR-059 D14). Every record is one array in
 * the strict contract subset with a leading version, decoded only from its exact canonical bytes.
 */
public final class PaymentsRecords {
    private PaymentsRecords() {
    }

    /**
     * Registry status (D14). Genesis sets it; only a governed {@code ASSET_STATUS} command ({@link PaymentsGovernanceV1})
     * changes it. {@code WIND_DOWN} allows withdrawals and settlement only; {@code RETIRED} is terminal.
     */
    public enum AssetStatus {
        ACTIVE(0), DEPOSITS_PAUSED(1), TRANSFERS_PAUSED(2), WIND_DOWN(3), RETIRED(4);

        private final int code;

        AssetStatus(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public boolean depositsEnabled() {
            return this == ACTIVE || this == TRANSFERS_PAUSED;
        }

        public boolean transfersEnabled() {
            return this == ACTIVE || this == DEPOSITS_PAUSED;
        }

        public static AssetStatus fromCode(long code) {
            for (AssetStatus status : values()) if (status.code == code) return status;
            throw AssetCbor.malformed("unknown asset status");
        }
    }

    /**
     * {@code [1, domain_id, address_network_id, network_magic, chain_id, genesis_id, application_id,
     * profile_id, profile_version, settings_digest]}. The settings digest binds the whole genesis
     * configuration, so a restart with different settings fails instead of diverging.
     */
    public record DomainRecordV1(Hash32 domainId, int addressNetworkId, long networkMagic, String chainId,
                                 Hash32 genesisId, String applicationId, String profileId, long profileVersion,
                                 Hash32 settingsDigest) {
        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.bstr(domainId.bytes()));
            array.add(AssetCbor.uint(addressNetworkId));
            array.add(AssetCbor.uint(networkMagic));
            array.add(new UnicodeString(chainId));
            array.add(AssetCbor.bstr(genesisId.bytes()));
            array.add(new UnicodeString(applicationId));
            array.add(new UnicodeString(profileId));
            array.add(AssetCbor.uint(profileVersion));
            array.add(AssetCbor.bstr(settingsDigest.bytes()));
            return AssetCbor.encode(array);
        }
    }

    /**
     * {@code [1, vault_id, asset, script_hash(28), vault_address: tstr, status, active_from_slot,
     * added_at_height, ticker: tstr, decimals]}. Ticker and decimals are display data only.
     */
    public record RegistryEntryV1(Hash32 vaultId, AssetId asset, byte[] scriptHash, String vaultAddress,
                                  AssetStatus status, long activeFromSlot, long addedAtHeight, String ticker,
                                  int decimals) {
        public RegistryEntryV1 {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(status, "status");
            if (scriptHash == null || scriptHash.length != 28) throw new IllegalArgumentException("script hash is 28 bytes");
            if (vaultAddress == null || vaultAddress.isEmpty() || vaultAddress.length() > 128
                    || ticker == null || ticker.length() > 16 || decimals < 0 || decimals > 19
                    || activeFromSlot < 0 || addedAtHeight < 0) {
                throw new IllegalArgumentException("invalid registry entry");
            }
            scriptHash = scriptHash.clone();
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }

        /** Equal exactly when the canonical encodings are equal. */
        @Override
        public boolean equals(Object other) {
            return other instanceof RegistryEntryV1 that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.bstr(vaultId.bytes()));
            array.add(PaymentsBodies.assetItem(asset));
            array.add(AssetCbor.bstr(scriptHash));
            array.add(new UnicodeString(vaultAddress));
            array.add(AssetCbor.uint(status.code()));
            array.add(AssetCbor.uint(activeFromSlot));
            array.add(AssetCbor.uint(addedAtHeight));
            array.add(new UnicodeString(ticker));
            array.add(AssetCbor.uint(decimals));
            return AssetCbor.encode(array);
        }

        public static RegistryEntryV1 decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 10).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != 1) throw AssetCbor.malformed("registry entry version");
            try {
                return new RegistryEntryV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                        PaymentsBodies.asset(items.get(2)), AssetCbor.bytes(items.get(3), 28, 28),
                        AssetCbor.text(items.get(4), 128), AssetStatus.fromCode(AssetCbor.uintLong(items.get(5))),
                        AssetCbor.uintLong(items.get(6)), AssetCbor.uintLong(items.get(7)),
                        AssetCbor.text(items.get(8), 64), (int) Math.min(AssetCbor.uintLong(items.get(9)), 64));
            } catch (IllegalArgumentException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }
    }

    /**
     * {@code [1, credential_kind, key_hash(28), created_height, rotations, [* grant_id]]}. The
     * {@code AccountId} (the key) never changes; the owner credential does on rotation (D14a).
     */
    public record AccountRecordV1(int credentialKind, byte[] keyHash, long createdHeight, long rotations,
                                  List<Hash32> grants) {
        public AccountRecordV1 {
            if (credentialKind < 0 || credentialKind > 1 || keyHash == null || keyHash.length != 28
                    || createdHeight < 0 || rotations < 0) {
                throw new IllegalArgumentException("invalid account record");
            }
            grants = List.copyOf(grants);
            if (grants.size() > AssetPaymentsContract.MAX_GRANTS_PER_ACCOUNT) {
                throw new IllegalArgumentException("too many grants");
            }
            keyHash = keyHash.clone();
        }

        @Override
        public byte[] keyHash() {
            return keyHash.clone();
        }

        /** Equal exactly when the canonical encodings are equal. */
        @Override
        public boolean equals(Object other) {
            return other instanceof AccountRecordV1 that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }

        public AccountRecordV1 withGrants(List<Hash32> newGrants) {
            return new AccountRecordV1(credentialKind, keyHash, createdHeight, rotations, newGrants);
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.uint(credentialKind));
            array.add(AssetCbor.bstr(keyHash));
            array.add(AssetCbor.uint(createdHeight));
            array.add(AssetCbor.uint(rotations));
            Array list = new Array();
            grants.forEach(grant -> list.add(AssetCbor.bstr(grant.bytes())));
            array.add(list);
            return AssetCbor.encode(array);
        }

        public static AccountRecordV1 decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 6).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != 1) throw AssetCbor.malformed("account version");
            List<Hash32> grants = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(5), 0, AssetPaymentsContract.MAX_GRANTS_PER_ACCOUNT)
                    .getDataItems()) {
                grants.add(Hash32.of(AssetCbor.bytes(item, 32, 32)));
            }
            return new AccountRecordV1((int) AssetCbor.uintLong(items.get(1)), AssetCbor.bytes(items.get(2), 28, 28),
                    AssetCbor.uintLong(items.get(3)), AssetCbor.uintLong(items.get(4)), grants);
        }
    }

    public enum Outcome { ACCEPTED, REJECTED }

    /**
     * {@code ReceiptV1(IntentId, AccountId, action, AssetId, amount, outcome, height)} (D14), plus the
     * rejection code: {@code [1, intent_id, account_id, action, asset / [], amount, outcome, reason,
     * height]}. Written once per authenticated, not-yet-used intent, whether accepted or rejected.
     */
    public record ReceiptV1(Hash32 intentId, byte[] accountId, String action, Optional<AssetId> asset,
                            BigInteger amount, Outcome outcome, String reason, long height) {
        public ReceiptV1 {
            Objects.requireNonNull(intentId, "intent id");
            AssetPaymentsContract.requireAccount(accountId);
            Objects.requireNonNull(asset, "asset");
            Quantities.requireU64(amount, "amount");
            Objects.requireNonNull(outcome, "outcome");
            if (action == null || action.isEmpty() || action.length() > 64 || reason == null || reason.length() > 64
                    || height < 0 || (outcome == Outcome.ACCEPTED) != reason.isEmpty()) {
                throw new IllegalArgumentException("invalid receipt");
            }
            accountId = accountId.clone();
        }

        @Override
        public byte[] accountId() {
            return accountId.clone();
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.bstr(intentId.bytes()));
            array.add(AssetCbor.bstr(accountId));
            array.add(new UnicodeString(action));
            array.add(asset.map(PaymentsBodies::assetItem).orElseGet(Array::new));
            array.add(AssetCbor.uint(amount));
            array.add(AssetCbor.uint(outcome.ordinal()));
            array.add(new UnicodeString(reason));
            array.add(AssetCbor.uint(height));
            return AssetCbor.encode(array);
        }

        public static ReceiptV1 decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 9).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != 1) throw AssetCbor.malformed("receipt version");
            Array assetItem = AssetCbor.array(items.get(4), 0, 2);
            Optional<AssetId> asset = assetItem.getDataItems().isEmpty()
                    ? Optional.empty() : Optional.of(PaymentsBodies.asset(assetItem));
            long outcome = AssetCbor.uintLong(items.get(6));
            if (outcome > 1) throw AssetCbor.malformed("receipt outcome");
            try {
                return new ReceiptV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                        AssetCbor.bytes(items.get(2), 28, 28), AssetCbor.text(items.get(3), 64), asset,
                        AssetCbor.uint(items.get(5), Quantities.MAX_U64), Outcome.values()[(int) outcome],
                        AssetCbor.text(items.get(7), 64), AssetCbor.uintLong(items.get(8)));
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ReceiptV1 that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }
    }

    /** Small fixed-shape values: a u64, and arrays of u64s / an id plus a u64. */
    public static byte[] encodeU64s(long... values) {
        Array array = new Array();
        for (long value : values) array.add(AssetCbor.uint(value));
        return AssetCbor.encode(array);
    }

    public static long[] decodeU64s(byte[] bytes, int count) {
        List<DataItem> items = AssetCbor.decodeArray(bytes, count).getDataItems();
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = AssetCbor.uintLong(items.get(i));
        return out;
    }

    public static byte[] encodeAmount(BigInteger amount) {
        Array array = new Array();
        array.add(AssetCbor.uint(Quantities.requireU64(amount, "amount")));
        return AssetCbor.encode(array);
    }

    public static BigInteger decodeAmount(byte[] bytes) {
        return AssetCbor.uint(AssetCbor.decodeArray(bytes, 1).getDataItems().getFirst(), Quantities.MAX_U64);
    }

    /** A pruning-queue entry: {@code [intent_id, not_after_ms]}. */
    public record QueuedIntent(Hash32 intentId, long notAfterMs) {
        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.bstr(intentId.bytes()));
            array.add(AssetCbor.uint(notAfterMs));
            return AssetCbor.encode(array);
        }

        public static QueuedIntent decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 2).getDataItems();
            return new QueuedIntent(Hash32.of(AssetCbor.bytes(items.get(0), 32, 32)), AssetCbor.uintLong(items.get(1)));
        }
    }
}
