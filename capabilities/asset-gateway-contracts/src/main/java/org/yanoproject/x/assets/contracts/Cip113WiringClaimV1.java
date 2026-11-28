package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;
import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-061 D6: the claim of the {@code asset-cip113-wiring-v1} observer, one per output that carries the pinned
 * deployment's one-shot {@code ProtocolParams} token: its outpoint, its L1 position (slot and transaction index in
 * the block) and the wiring fields of its datum, or {@code UNDECODABLE}. It never throws on a malformed datum.
 *
 * <p>Wire form: {@code [1, tx_id, output_index, slot, tx_index, block_hash, fields]} with {@code fields} =
 * {@code [0, dispatcher, issuance_logic, transfer, third_party, keys]} (28-byte credential hashes, and the bitmap of
 * the fields that are verification-key credentials) or {@code [1]}.
 */
public record Cip113WiringClaimV1(Hash32 txId, long outputIndex, long slot, long txIndex, Hash32 blockHash,
                                  Optional<Fields> fields) {
    public static final int VERSION = 1;
    public static final String CLAIM_SCHEMA = "asset-cip113-wiring-claim-v1";

    /**
     * The protocol-params fields 0 to 3, each a credential: its 28-byte hash and its kind. {@code keys} has bit
     * {@code i} set when field {@code i} is a verification-key credential ({@code Constr 0 [hash]}) and clear when it
     * is a script credential ({@code Constr 1 [hash]}); the pinned params validator accepts a key in field 1 (the
     * issuance logic). The kinds are part of the tuple's identity, so a key and a script with the same hash never
     * match. Field 4 (the upgrade authority) and field 5 (its nominee) are not reported: an authority-only rollover
     * re-creates the params UTxO with identical fields.
     */
    public record Fields(byte[] dispatcher, byte[] issuanceLogic, byte[] transfer, byte[] thirdParty, int keys) {
        /** The number of reported fields. */
        public static final int COUNT = 4;

        public Fields {
            dispatcher = hash28(dispatcher, "dispatcher");
            issuanceLogic = hash28(issuanceLogic, "issuance logic");
            transfer = hash28(transfer, "transfer");
            thirdParty = hash28(thirdParty, "third party");
            if (keys < 0 || keys > 0b1111) throw new IllegalArgumentException("credential kinds are 4 bits");
        }

        /** Four script credentials. */
        public Fields(byte[] dispatcher, byte[] issuanceLogic, byte[] transfer, byte[] thirdParty) {
            this(dispatcher, issuanceLogic, transfer, thirdParty, 0);
        }

        @Override
        public byte[] dispatcher() {
            return dispatcher.clone();
        }

        @Override
        public byte[] issuanceLogic() {
            return issuanceLogic.clone();
        }

        @Override
        public byte[] transfer() {
            return transfer.clone();
        }

        @Override
        public byte[] thirdParty() {
            return thirdParty.clone();
        }

        /** True when field {@code index} (0 to 3) is a script credential. */
        public boolean script(int index) {
            if (index < 0 || index >= COUNT) throw new IllegalArgumentException("field index is 0..3");
            return (keys >> index & 1) == 0;
        }

        /** Field {@code index}'s hash when it is a script credential; empty for a key credential. */
        public Optional<byte[]> scriptHash(int index) {
            if (!script(index)) return Optional.empty();
            return Optional.of(switch (index) {
                case 0 -> dispatcher();
                case 1 -> issuanceLogic();
                case 2 -> transfer();
                default -> thirdParty();
            });
        }

        /**
         * The 113 bytes {@code dispatcher ‖ issuance logic ‖ transfer ‖ third party ‖ keys}, the tuple's identity,
         * credential kinds included.
         */
        public byte[] key() {
            byte[] out = new byte[KEY_BYTES];
            System.arraycopy(dispatcher, 0, out, 0, 28);
            System.arraycopy(issuanceLogic, 0, out, 28, 28);
            System.arraycopy(transfer, 0, out, 56, 28);
            System.arraycopy(thirdParty, 0, out, 84, 28);
            out[112] = (byte) keys;
            return out;
        }

        /** The length of {@link #key()}. */
        public static final int KEY_BYTES = 113;

        /** The fields whose identity is {@code key} ({@link #key()}). */
        public static Fields ofKey(byte[] key) {
            if (key == null || key.length != KEY_BYTES) throw new IllegalArgumentException("a tuple key is 113 bytes");
            return new Fields(Arrays.copyOfRange(key, 0, 28), Arrays.copyOfRange(key, 28, 56),
                    Arrays.copyOfRange(key, 56, 84), Arrays.copyOfRange(key, 84, 112), key[112] & 0xff);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Fields that && Arrays.equals(key(), that.key());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(key());
        }

        /**
         * The fields of a {@code ProtocolParams} datum: a constructor-0 value whose fields 0 to 3 are credentials,
         * {@code Constr 0 [hash28]} (a verification key) or {@code Constr 1 [hash28]} (a script), kinds kept. Fields
         * 4 and 5 are not read. Empty for anything else, never throws.
         */
        public static Optional<Fields> ofDatum(byte[] inlineDatum) {
            Optional<List<Object>> fields = paramsFields(inlineDatum);
            if (fields.isEmpty()) return Optional.empty();
            byte[][] hashes = new byte[COUNT][];
            int keys = 0;
            for (int i = 0; i < COUNT; i++) {
                Optional<Credential> credential = credential(fields.get().get(i));
                if (credential.isEmpty()) return Optional.empty();
                hashes[i] = credential.get().hash();
                if (!credential.get().script()) keys |= 1 << i;
            }
            return Optional.of(new Fields(hashes[0], hashes[1], hashes[2], hashes[3], keys));
        }
    }

    /**
     * The dispatcher of a {@code ProtocolParams} datum alone: field 0 when it is a script credential, whatever fields
     * 1 to 3 hold. A vault transaction uses only the dispatcher and its transfer delegate (D10 rule 6), so a
     * transfer-only build never depends on how the other fields decode. Empty otherwise, never throws.
     */
    public static Optional<byte[]> dispatcherOf(byte[] inlineDatum) {
        return paramsFields(inlineDatum).flatMap(fields -> credential(fields.getFirst()))
                .filter(Credential::script).map(Credential::hash);
    }

    /** A decoded credential field. */
    private record Credential(boolean script, byte[] hash) {
    }

    private static Optional<List<Object>> paramsFields(byte[] inlineDatum) {
        if (inlineDatum == null || !(PlutusDataReader.read(inlineDatum) instanceof PlutusDataReader.Constr constr)
                || constr.index() != 0 || constr.fields().size() < Fields.COUNT) {
            return Optional.empty();
        }
        return Optional.of(constr.fields());
    }

    private static Optional<Credential> credential(Object field) {
        if (!(field instanceof PlutusDataReader.Constr credential) || credential.index() > 1
                || credential.fields().size() != 1 || !(credential.fields().getFirst() instanceof byte[] hash)
                || hash.length != 28) {
            return Optional.empty();
        }
        return Optional.of(new Credential(credential.index() == 1, hash));
    }

    public Cip113WiringClaimV1 {
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(blockHash, "block hash");
        Objects.requireNonNull(fields, "fields");
        if (outputIndex < 0 || slot < 0 || txIndex < 0) throw new IllegalArgumentException("unsigned positions");
    }

    public ProgrammableDepositDatumV1.Outpoint outpoint() {
        return new ProgrammableDepositDatumV1.Outpoint(txId, outputIndex);
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(txId.bytes()));
        array.add(AssetCbor.uint(outputIndex));
        array.add(AssetCbor.uint(slot));
        array.add(AssetCbor.uint(txIndex));
        array.add(AssetCbor.bstr(blockHash.bytes()));
        Array f = new Array();
        if (fields.isPresent()) {
            f.add(AssetCbor.uint(0));
            f.add(AssetCbor.bstr(fields.get().dispatcher()));
            f.add(AssetCbor.bstr(fields.get().issuanceLogic()));
            f.add(AssetCbor.bstr(fields.get().transfer()));
            f.add(AssetCbor.bstr(fields.get().thirdParty()));
            f.add(AssetCbor.uint(fields.get().keys()));
        } else {
            f.add(AssetCbor.uint(1));
        }
        array.add(f);
        return AssetCbor.encode(array);
    }

    public static Cip113WiringClaimV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 7).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("wiring claim version");
        List<DataItem> f = AssetCbor.array(items.get(6), 1, 6).getDataItems();
        long kind = AssetCbor.uintLong(f.getFirst());
        Optional<Fields> fields;
        if (kind == 0 && f.size() == 6) {
            long keys = AssetCbor.uintLong(f.get(5));
            if (keys > 0b1111) throw AssetCbor.malformed("wiring credential kinds");
            fields = Optional.of(new Fields(AssetCbor.bytes(f.get(1), 28, 28), AssetCbor.bytes(f.get(2), 28, 28),
                    AssetCbor.bytes(f.get(3), 28, 28), AssetCbor.bytes(f.get(4), 28, 28), (int) keys));
        } else if (kind == 1 && f.size() == 1) {
            fields = Optional.empty();
        } else {
            throw AssetCbor.malformed("wiring fields form");
        }
        try {
            return new Cip113WiringClaimV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                    AssetCbor.uintLong(items.get(2)), AssetCbor.uintLong(items.get(3)), AssetCbor.uintLong(items.get(4)),
                    Hash32.of(AssetCbor.bytes(items.get(5), 32, 32)), fields);
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    private static byte[] hash28(byte[] value, String name) {
        if (value == null || value.length != 28) throw new IllegalArgumentException(name + " must be 28 bytes");
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cip113WiringClaimV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
