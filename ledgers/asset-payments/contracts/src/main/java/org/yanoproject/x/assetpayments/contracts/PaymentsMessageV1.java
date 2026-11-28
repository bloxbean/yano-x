package org.yanoproject.x.assetpayments.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.Arrays;
import java.util.List;

/**
 * The body of a message on {@link AssetPaymentsContract#INTENT_TOPIC}: a user intent exactly as the
 * wallet or session key signed it (ADR-059 D13 "ingress of user intents"). The application gateway
 * that submits it can censor or delay it but cannot alter it; every executed field comes from the
 * signed {@code IntentEnvelopeV1}.
 *
 * <pre>
 * payments_message_v1 = [1, 0, cose_sign1: bytes, cose_key: bytes]            ; owner, CIP-30 signData
 *                     / [1, 1, envelope: bytes, signature: bytes .size 64]    ; session key, Ed25519
 *                     / [1, 2, current_sign1, current_key, new_sign1, new_key] ; ROTATE_OWNER (D14a)
 * </pre>
 * Strict contract CBOR subset; each COSE part is at most 16 KiB (D13 §1).
 */
public sealed interface PaymentsMessageV1 {
    int VERSION = 1;
    int MAX_PART_BYTES = 16_384;

    record Owner(byte[] coseSign1, byte[] coseKey) implements PaymentsMessageV1 {
        public Owner {
            coseSign1 = part(coseSign1);
            coseKey = part(coseKey);
        }

        @Override public byte[] coseSign1() { return coseSign1.clone(); }
        @Override public byte[] coseKey() { return coseKey.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Owner that && Arrays.equals(coseSign1, that.coseSign1)
                    && Arrays.equals(coseKey, that.coseKey);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(coseSign1) + Arrays.hashCode(coseKey);
        }
    }

    record Session(byte[] envelope, byte[] signature) implements PaymentsMessageV1 {
        public Session {
            envelope = part(envelope);
            if (signature == null || signature.length != 64) {
                throw new IllegalArgumentException("session signature must be 64 bytes");
            }
            signature = signature.clone();
        }

        @Override public byte[] envelope() { return envelope.clone(); }
        @Override public byte[] signature() { return signature.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Session that && Arrays.equals(envelope, that.envelope)
                    && Arrays.equals(signature, that.signature);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(envelope) + Arrays.hashCode(signature);
        }
    }

    record Rotation(byte[] currentSign1, byte[] currentKey, byte[] newSign1, byte[] newKey)
            implements PaymentsMessageV1 {
        public Rotation {
            currentSign1 = part(currentSign1);
            currentKey = part(currentKey);
            newSign1 = part(newSign1);
            newKey = part(newKey);
        }

        @Override public byte[] currentSign1() { return currentSign1.clone(); }
        @Override public byte[] currentKey() { return currentKey.clone(); }
        @Override public byte[] newSign1() { return newSign1.clone(); }
        @Override public byte[] newKey() { return newKey.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof Rotation that && Arrays.equals(currentSign1, that.currentSign1)
                    && Arrays.equals(currentKey, that.currentKey) && Arrays.equals(newSign1, that.newSign1)
                    && Arrays.equals(newKey, that.newKey);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(new Object[] {currentSign1, currentKey, newSign1, newKey});
        }
    }

    default byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        switch (this) {
            case Owner owner -> {
                array.add(AssetCbor.uint(0));
                array.add(AssetCbor.bstr(owner.coseSign1()));
                array.add(AssetCbor.bstr(owner.coseKey()));
            }
            case Session session -> {
                array.add(AssetCbor.uint(1));
                array.add(AssetCbor.bstr(session.envelope()));
                array.add(AssetCbor.bstr(session.signature()));
            }
            case Rotation rotation -> {
                array.add(AssetCbor.uint(2));
                array.add(AssetCbor.bstr(rotation.currentSign1()));
                array.add(AssetCbor.bstr(rotation.currentKey()));
                array.add(AssetCbor.bstr(rotation.newSign1()));
                array.add(AssetCbor.bstr(rotation.newKey()));
            }
        }
        return AssetCbor.encode(array);
    }

    /** Decodes the exact canonical form; throws {@link IllegalArgumentException} otherwise. */
    static PaymentsMessageV1 decode(byte[] body) {
        List<DataItem> items = AssetCbor.decodeArray(body, 4, 6).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("payments message version");
        long kind = AssetCbor.uintLong(items.get(1));
        if (kind == 0 && items.size() == 4) {
            return new Owner(bytes(items.get(2)), bytes(items.get(3)));
        }
        if (kind == 1 && items.size() == 4) {
            return new Session(bytes(items.get(2)), AssetCbor.bytes(items.get(3), 64, 64));
        }
        if (kind == 2 && items.size() == 6) {
            return new Rotation(bytes(items.get(2)), bytes(items.get(3)), bytes(items.get(4)), bytes(items.get(5)));
        }
        throw AssetCbor.malformed("unknown payments message kind");
    }

    private static byte[] bytes(DataItem item) {
        return AssetCbor.bytes(item, 1, MAX_PART_BYTES);
    }

    private static byte[] part(byte[] value) {
        if (value == null || value.length == 0 || value.length > MAX_PART_BYTES) {
            throw new IllegalArgumentException("message parts are 1-16384 bytes");
        }
        return Arrays.copyOf(value, value.length);
    }
}
