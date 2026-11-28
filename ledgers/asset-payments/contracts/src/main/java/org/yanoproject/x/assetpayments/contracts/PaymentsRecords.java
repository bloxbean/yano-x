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
        ACTIVE(0), DEPOSITS_PAUSED(1), TRANSFERS_PAUSED(2), WIND_DOWN(3), RETIRED(4),
        /**
         * ADR-061 D14: a governed {@code ASSET_HOLD} after an attributed issuer event on a CIP-113 asset. Deposits are
         * recorded {@code UNCREDITED(ASSET_HELD)}; transfers and voluntary withdrawals of the asset are refused, and
         * members refuse co-signed {@code Settle}. {@code Accept}, forced withdrawals and proof {@code Exit} continue.
         */
        ISSUER_HOLD(5);

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

        /** ADR-061 D14: the asset is held after an issuer event. */
        public boolean held() {
            return this == ISSUER_HOLD;
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

    /** ADR-061 D14: the trust labels an asset carries, next to the domain's. */
    public enum TrustLabel {
        FEDERATED_COSIGNED_WITH_ESCAPE_HATCH,
        /**
         * The issuer (and its admin set) can freeze the vault, seize or burn its tokens, and change the token's
         * transfer, third-party and unfracking logic for tokens already deposited; the deployment's CIP-113 upgrade
         * authority can re-point the dispatcher and delegates; a token can never be de-registered; payouts go only
         * to smart wallets and the issuer may refuse a recipient, which cannot be re-targeted; the escape hatch does
         * not override the issuer; after a seizure only the co-signed path pauses, so the loss falls on the last to
         * exit; every asset under one policy shares one freeze.
         */
        ISSUER_CONTROLLED
    }

    /**
     * ADR-061 D12: the genesis-fixed pins of a CIP-113 vault's custody, {@code [deployment, token, custody,
     * substandard, wiring0, baseline]}:
     * <ul>
     *   <li>the deployment identity: bootstrap transaction hash, params policy, PLB hash, registry node policy and
     *       {@code maxInlineDatumBytes};</li>
     *   <li>the custody backend ({@code julc} or {@code aiken}) and the exit delegate's hash ({@code e}; the
     *       registry entry's {@code script_hash} is {@code h} and its address the smart wallet);</li>
     *   <li>the substandard's id and version;</li>
     *   <li>wiring version 0: the params outpoint live at registration, its fields (dispatcher, issuance logic,
     *       transfer and third-party credentials) and that dispatcher's delegate hashes (transfer, third party,
     *       unfracking);</li>
     *   <li>the registry-node baseline (the transfer logic and {@code global_state_cs} at registration), for the
     *       drift flag only.</li>
     * </ul>
     */
    public record Cip113Custody(byte[] bootstrapTxHash, byte[] paramsPolicy, byte[] plbHash, byte[] registryNodePolicy,
                                int maxInlineDatumBytes, String backend, byte[] exitDelegateHash, String substandard,
                                int substandardVersion, Hash32 params0TxId, int params0Index, byte[] dispatcher,
                                byte[] issuanceLogic, byte[] transferCredential, byte[] thirdPartyCredential,
                                byte[] transferDelegate, byte[] thirdPartyDelegate, byte[] unfrackingDelegate,
                                byte[] baselineTransferLogic, byte[] baselineGlobalStateCs) {
        public Cip113Custody {
            bootstrapTxHash = fixed(bootstrapTxHash, 32, "bootstrap tx hash");
            paramsPolicy = fixed(paramsPolicy, 28, "params policy");
            plbHash = fixed(plbHash, 28, "PLB hash");
            registryNodePolicy = fixed(registryNodePolicy, 28, "registry node policy");
            exitDelegateHash = fixed(exitDelegateHash, 28, "exit delegate hash");
            dispatcher = fixed(dispatcher, 28, "dispatcher");
            issuanceLogic = fixed(issuanceLogic, 28, "issuance logic");
            transferCredential = fixed(transferCredential, 28, "transfer credential");
            thirdPartyCredential = fixed(thirdPartyCredential, 28, "third-party credential");
            transferDelegate = fixed(transferDelegate, 28, "transfer delegate");
            thirdPartyDelegate = fixed(thirdPartyDelegate, 28, "third-party delegate");
            unfrackingDelegate = fixed(unfrackingDelegate, 28, "unfracking delegate");
            baselineTransferLogic = fixed(baselineTransferLogic, 28, "baseline transfer logic");
            baselineGlobalStateCs = Objects.requireNonNull(baselineGlobalStateCs, "baseline global state").clone();
            Objects.requireNonNull(params0TxId, "params outpoint");
            if (maxInlineDatumBytes < 40 || maxInlineDatumBytes > 65_536 || params0Index < 0
                    || !("julc".equals(backend) || "aiken".equals(backend))
                    || substandard == null || substandard.isEmpty() || substandard.length() > 32
                    || substandardVersion < 0 || baselineGlobalStateCs.length > 28) {
                throw new IllegalArgumentException("invalid CIP-113 custody pins");
            }
        }

        private static byte[] fixed(byte[] value, int length, String name) {
            if (value == null || value.length != length) throw new IllegalArgumentException(name + " size");
            return value.clone();
        }

        Array encode() {
            Array array = new Array();
            for (byte[] value : List.of(bootstrapTxHash, paramsPolicy, plbHash, registryNodePolicy)) {
                array.add(AssetCbor.bstr(value));
            }
            array.add(AssetCbor.uint(maxInlineDatumBytes));
            array.add(new UnicodeString(backend));
            array.add(AssetCbor.bstr(exitDelegateHash));
            array.add(new UnicodeString(substandard));
            array.add(AssetCbor.uint(substandardVersion));
            array.add(AssetCbor.bstr(params0TxId.bytes()));
            array.add(AssetCbor.uint(params0Index));
            for (byte[] value : List.of(dispatcher, issuanceLogic, transferCredential, thirdPartyCredential,
                    transferDelegate, thirdPartyDelegate, unfrackingDelegate, baselineTransferLogic,
                    baselineGlobalStateCs)) {
                array.add(AssetCbor.bstr(value));
            }
            return array;
        }

        static Cip113Custody decode(DataItem item) {
            List<DataItem> f = AssetCbor.array(item, 20, 20).getDataItems();
            return new Cip113Custody(AssetCbor.bytes(f.get(0), 32, 32), AssetCbor.bytes(f.get(1), 28, 28),
                    AssetCbor.bytes(f.get(2), 28, 28), AssetCbor.bytes(f.get(3), 28, 28),
                    (int) AssetCbor.uintLong(f.get(4)), AssetCbor.text(f.get(5), 8), AssetCbor.bytes(f.get(6), 28, 28),
                    AssetCbor.text(f.get(7), 32), (int) AssetCbor.uintLong(f.get(8)),
                    Hash32.of(AssetCbor.bytes(f.get(9), 32, 32)), (int) AssetCbor.uintLong(f.get(10)),
                    AssetCbor.bytes(f.get(11), 28, 28), AssetCbor.bytes(f.get(12), 28, 28),
                    AssetCbor.bytes(f.get(13), 28, 28), AssetCbor.bytes(f.get(14), 28, 28),
                    AssetCbor.bytes(f.get(15), 28, 28), AssetCbor.bytes(f.get(16), 28, 28),
                    AssetCbor.bytes(f.get(17), 28, 28), AssetCbor.bytes(f.get(18), 28, 28),
                    AssetCbor.bytes(f.get(19), 0, 28));
        }
    }

    /**
     * {@code [1, vault_id, asset, script_hash(28), vault_address: tstr, status, active_from_slot,
     * added_at_height, ticker: tstr, decimals]}, and for a CIP-113 vault (ADR-061 D1) an eleventh element, its
     * {@link Cip113Custody} pins. A native vault's entry is byte for byte the ten-element form. Ticker and decimals
     * are display data only.
     */
    public record RegistryEntryV1(Hash32 vaultId, AssetId asset, byte[] scriptHash, String vaultAddress,
                                  AssetStatus status, long activeFromSlot, long addedAtHeight, String ticker,
                                  int decimals, Optional<Cip113Custody> custody) {
        /** A native vault's entry. */
        public RegistryEntryV1(Hash32 vaultId, AssetId asset, byte[] scriptHash, String vaultAddress,
                               AssetStatus status, long activeFromSlot, long addedAtHeight, String ticker,
                               int decimals) {
            this(vaultId, asset, scriptHash, vaultAddress, status, activeFromSlot, addedAtHeight, ticker, decimals,
                    Optional.empty());
        }

        /** The asset's trust labels: the domain's, and {@code ISSUER_CONTROLLED} for a CIP-113 asset (D14). */
        public List<TrustLabel> trustLabels() {
            return custody.isPresent()
                    ? List.of(TrustLabel.FEDERATED_COSIGNED_WITH_ESCAPE_HATCH, TrustLabel.ISSUER_CONTROLLED)
                    : List.of(TrustLabel.FEDERATED_COSIGNED_WITH_ESCAPE_HATCH);
        }

        /** The same entry with {@code next} as its status ({@code ASSET_HOLD}, {@code ASSET_RESUME}). */
        public RegistryEntryV1 withStatus(AssetStatus next) {
            return new RegistryEntryV1(vaultId, asset, scriptHash, vaultAddress, next, activeFromSlot, addedAtHeight,
                    ticker, decimals, custody);
        }

        public RegistryEntryV1 {
            Objects.requireNonNull(custody, "custody");
            if (status == AssetStatus.ISSUER_HOLD && custody.isEmpty()) {
                throw new IllegalArgumentException("ISSUER_HOLD applies to CIP-113 assets only");
            }
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
            custody.ifPresent(pins -> array.add(pins.encode()));
            return AssetCbor.encode(array);
        }

        public static RegistryEntryV1 decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 10, 11).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != 1) throw AssetCbor.malformed("registry entry version");
            try {
                return new RegistryEntryV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                        PaymentsBodies.asset(items.get(2)), AssetCbor.bytes(items.get(3), 28, 28),
                        AssetCbor.text(items.get(4), 128), AssetStatus.fromCode(AssetCbor.uintLong(items.get(5))),
                        AssetCbor.uintLong(items.get(6)), AssetCbor.uintLong(items.get(7)),
                        AssetCbor.text(items.get(8), 64), (int) Math.min(AssetCbor.uintLong(items.get(9)), 64),
                        items.size() == 11 ? Optional.of(Cip113Custody.decode(items.get(10))) : Optional.empty());
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
