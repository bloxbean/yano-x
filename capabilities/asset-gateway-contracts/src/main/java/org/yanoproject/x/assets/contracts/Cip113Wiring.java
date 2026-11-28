package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-061 D6 versioned wiring of one CIP-113 deployment, as ledger state.
 *
 * <ul>
 *   <li>Every {@link Cip113WiringClaimV1} is recorded once as a <b>wiring version</b>, keyed by its params outpoint,
 *       with its fields or {@code UNDECODABLE}.</li>
 *   <li>The <b>head</b> is the version at the highest L1 position (slot, then transaction index, then output
 *       index).</li>
 *   <li>Approval is a set of field tuples, not outpoints: a version is approved exactly when its fields are in the
 *       set; an undecodable version never is. An authority-only rollover re-creates the params UTxO with identical
 *       fields, so it is approved at once.</li>
 *   <li>{@link #rewire} ({@code ASSET_REWIRE}) must name the head; it adds the head's fields, with the reviewed
 *       delegate hashes, to the set. Naming any older version is rejected, so a stale approval never clears a newer
 *       suspension.</li>
 * </ul>
 *
 * <p>Same-block determinism (I48) needs the consumer to record every wiring observation of a block before it admits
 * any deposit observation of that block; 059's canonical order of observations by observer id
 * ({@code asset-cip113-wiring-v1} before {@code asset-vault-deposit-v1}) gives exactly that.
 */
public final class Cip113Wiring {
    private static final byte[] VERSION_SEGMENT = "w/v/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAD_SEGMENT = "w/head".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] APPROVED_SEGMENT = "w/a/".getBytes(StandardCharsets.US_ASCII);

    /** A recorded wiring version. */
    public record Version(ProgrammableDepositDatumV1.Outpoint outpoint, long slot, long txIndex,
                          Optional<Cip113WiringClaimV1.Fields> fields) {
        public Version {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(fields, "fields");
        }

        boolean after(Version other) {
            if (slot != other.slot) return slot > other.slot;
            if (txIndex != other.txIndex) return txIndex > other.txIndex;
            return outpoint.index() > other.outpoint.index();
        }

        byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.bstr(outpoint.txId().bytes()));
            array.add(AssetCbor.uint(outpoint.index()));
            array.add(AssetCbor.uint(slot));
            array.add(AssetCbor.uint(txIndex));
            fields.ifPresent(f -> array.add(AssetCbor.bstr(f.key())));
            return AssetCbor.encode(array);
        }

        static Version decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 5, 6).getDataItems();
            Optional<Cip113WiringClaimV1.Fields> fields = Optional.empty();
            if (items.size() == 6) {
                byte[] key = AssetCbor.bytes(items.get(5), 112, 112);
                fields = Optional.of(new Cip113WiringClaimV1.Fields(Arrays.copyOfRange(key, 0, 28),
                        Arrays.copyOfRange(key, 28, 56), Arrays.copyOfRange(key, 56, 84),
                        Arrays.copyOfRange(key, 84, 112)));
            }
            return new Version(new ProgrammableDepositDatumV1.Outpoint(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                    AssetCbor.uintLong(items.get(2))), AssetCbor.uintLong(items.get(3)), AssetCbor.uintLong(items.get(4)),
                    fields);
        }
    }

    /**
     * An approved tuple: the four wiring fields, with the delegate hashes the reviewed deployment record pairs with
     * the dispatcher (the transfer, third-party and unfracking delegates it is compiled against).
     */
    public record ApprovedTuple(Cip113WiringClaimV1.Fields fields, byte[] transferDelegate,
                                byte[] thirdPartyDelegate, byte[] unfrackingDelegate) {
        public ApprovedTuple {
            Objects.requireNonNull(fields, "fields");
            transferDelegate = hash28(transferDelegate);
            thirdPartyDelegate = hash28(thirdPartyDelegate);
            unfrackingDelegate = hash28(unfrackingDelegate);
        }

        @Override
        public byte[] transferDelegate() {
            return transferDelegate.clone();
        }

        @Override
        public byte[] thirdPartyDelegate() {
            return thirdPartyDelegate.clone();
        }

        @Override
        public byte[] unfrackingDelegate() {
            return unfrackingDelegate.clone();
        }

        byte[] encodeDelegates() {
            Array array = new Array();
            array.add(AssetCbor.bstr(transferDelegate));
            array.add(AssetCbor.bstr(thirdPartyDelegate));
            array.add(AssetCbor.bstr(unfrackingDelegate));
            return AssetCbor.encode(array);
        }
    }

    /** Why a recording or a rewire changed nothing. */
    public enum Result {
        RECORDED, DUPLICATE, IGNORED, APPROVED, REJECTED_NOT_HEAD, REJECTED_UNDECODABLE, REJECTED_UNKNOWN_VERSION
    }

    private final byte[] prefix;
    private final String observerId;

    /** {@code prefix} names the deployment's state (for example the asset prefix and the params policy). */
    public Cip113Wiring(byte[] prefix, String observerId) {
        if (prefix == null || prefix.length == 0 || prefix.length > 96) {
            throw new IllegalArgumentException("prefix must be 1-96 bytes");
        }
        this.prefix = prefix.clone();
        this.observerId = Objects.requireNonNull(observerId, "observer id");
    }

    /** Genesis: version 0 (the params outpoint live at registration) and its approved tuple. */
    public void genesis(AssetAccounts.Store store, Version version0, ApprovedTuple tuple) {
        if (version0.fields().isEmpty() || !version0.fields().get().equals(tuple.fields())) {
            throw new IllegalArgumentException("version 0 must carry the approved tuple's fields");
        }
        store.put(versionKey(version0.outpoint()), version0.encode());
        store.put(key(HEAD_SEGMENT), version0.encode());
        store.put(approvedKey(tuple.fields()), tuple.encodeDelegates());
    }

    /**
     * Records one wiring observation. The envelope must name this observer, the claim's transaction and output, slot
     * and block hash; a version is written once, and the head moves only forward in L1 position. Never throws on L1
     * content.
     */
    public Result record(AssetAccounts.Store store, DepositAdmission.ObservedDeposit observed) {
        if (observed == null || observed.claim() == null || !observerId.equals(observed.observerId())) {
            return Result.IGNORED;
        }
        Cip113WiringClaimV1 claim;
        try {
            claim = Cip113WiringClaimV1.decode(observed.claim());
        } catch (IllegalArgumentException | ArithmeticException undecodable) {
            return Result.IGNORED;
        }
        if (observed.anchorTxId() == null || !Arrays.equals(observed.anchorTxId(), claim.txId().bytes())
                || observed.eventOrdinal() != claim.outputIndex() || observed.slot() != claim.slot()
                || observed.blockHash() == null || !Arrays.equals(observed.blockHash(), claim.blockHash().bytes())) {
            return Result.IGNORED;
        }
        Version version = new Version(claim.outpoint(), claim.slot(), claim.txIndex(), claim.fields());
        byte[] versionKey = versionKey(version.outpoint());
        if (store.get(versionKey).isPresent()) return Result.DUPLICATE;
        store.put(versionKey, version.encode());
        Optional<Version> head = head(store);
        if (head.isEmpty() || version.after(head.get())) {
            store.put(key(HEAD_SEGMENT), version.encode());
        }
        return Result.RECORDED;
    }

    public Optional<Version> version(AssetAccounts.Store store, ProgrammableDepositDatumV1.Outpoint outpoint) {
        return store.get(versionKey(outpoint)).map(Version::decode);
    }

    public Optional<Version> head(AssetAccounts.Store store) {
        return store.get(key(HEAD_SEGMENT)).map(Version::decode);
    }

    /** The approved tuple with exactly {@code fields}, if any. */
    public Optional<ApprovedTuple> approved(AssetAccounts.Store store, Cip113WiringClaimV1.Fields fields) {
        return store.get(approvedKey(fields)).map(bytes -> {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 3).getDataItems();
            return new ApprovedTuple(fields, AssetCbor.bytes(items.get(0), 28, 28), AssetCbor.bytes(items.get(1), 28, 28),
                    AssetCbor.bytes(items.get(2), 28, 28));
        });
    }

    /** The approved tuple of {@code version}: present exactly when its fields are in the approved set. */
    public Optional<ApprovedTuple> approved(AssetAccounts.Store store, Version version) {
        return version.fields().flatMap(fields -> approved(store, fields));
    }

    /**
     * The one recorded version among {@code referenceInputs}: the params UTxO the creating transaction used. Empty
     * when none, or more than one, is recorded.
     */
    public Optional<Version> referenced(AssetAccounts.Store store,
                                        List<ProgrammableDepositDatumV1.Outpoint> referenceInputs) {
        Version found = null;
        for (ProgrammableDepositDatumV1.Outpoint ref : referenceInputs) {
            Optional<Version> version = version(store, ref);
            if (version.isPresent()) {
                if (found != null) return Optional.empty();
                found = version.get();
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * {@code ASSET_REWIRE(deployment, versionOutpoint, delegateHashes)}: approves the head's fields with the reviewed
     * delegate hashes. Only the head can be approved this way.
     */
    public Result rewire(AssetAccounts.Store store, ProgrammableDepositDatumV1.Outpoint versionOutpoint,
                         byte[] transferDelegate, byte[] thirdPartyDelegate, byte[] unfrackingDelegate) {
        Optional<Version> named = version(store, versionOutpoint);
        if (named.isEmpty()) return Result.REJECTED_UNKNOWN_VERSION;
        Optional<Version> head = head(store);
        if (head.isEmpty() || !head.get().outpoint().equals(versionOutpoint)) return Result.REJECTED_NOT_HEAD;
        if (named.get().fields().isEmpty()) return Result.REJECTED_UNDECODABLE;
        ApprovedTuple tuple = new ApprovedTuple(named.get().fields().get(), transferDelegate, thirdPartyDelegate,
                unfrackingDelegate);
        store.put(approvedKey(tuple.fields()), tuple.encodeDelegates());
        return Result.APPROVED;
    }

    private byte[] versionKey(ProgrammableDepositDatumV1.Outpoint outpoint) {
        return key(VERSION_SEGMENT, GatewayIds.outpointCbor(outpoint.txId(), outpoint.index()));
    }

    private byte[] approvedKey(Cip113WiringClaimV1.Fields fields) {
        return key(APPROVED_SEGMENT, fields.key());
    }

    private byte[] key(byte[] segment, byte[]... rest) {
        int length = prefix.length + segment.length;
        for (byte[] part : rest) length += part.length;
        byte[] out = new byte[length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(segment, 0, out, prefix.length, segment.length);
        int at = prefix.length + segment.length;
        for (byte[] part : rest) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static byte[] hash28(byte[] value) {
        if (value == null || value.length != 28) throw new IllegalArgumentException("delegate hash must be 28 bytes");
        return value.clone();
    }
}
