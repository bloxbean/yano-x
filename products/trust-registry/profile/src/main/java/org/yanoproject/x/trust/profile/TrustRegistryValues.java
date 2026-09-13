package org.yanoproject.x.trust.profile;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.yanoproject.x.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Canonical CBOR layouts of the registry values (ADR-049 §2.1). Every decoder re-encodes
 * what it decoded and rejects bytes that are not the canonical form, so an entry read
 * from chain is exactly what the profile's schema admitted.
 */
public final class TrustRegistryValues {
    private TrustRegistryValues() {
    }

    /** {@code [1, controllerOrganizationId, kind, metadataHash32]}. */
    public record SubjectValue(String controllerOrganizationId, String kind, byte[] metadataHash) {
        public SubjectValue {
            controllerOrganizationId = boundedText(controllerOrganizationId, 63,
                    "controllerOrganizationId");
            kind = boundedText(kind, 64, "kind");
            metadataHash = exact(metadataHash, 32, "metadataHash");
        }

        @Override public byte[] metadataHash() { return metadataHash.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof SubjectValue that
                    && controllerOrganizationId.equals(that.controllerOrganizationId)
                    && kind.equals(that.kind) && Arrays.equals(metadataHash, that.metadataHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(controllerOrganizationId, kind, Arrays.hashCode(metadataHash));
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(controllerOrganizationId));
            value.add(new UnicodeString(kind));
            value.add(new ByteString(metadataHash));
            return StdlibContractCbor.encode(value);
        }

        public static SubjectValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 4).getDataItems();
            requireVersion(items.get(0));
            SubjectValue decoded = new SubjectValue(
                    StdlibContractCbor.text(items.get(1)),
                    StdlibContractCbor.text(items.get(2)),
                    StdlibContractCbor.bytes(items.get(3), 32));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, bit, reasonCode]}; {@code bit} is the value of the list position. */
    public record StatusValue(int bit, int reasonCode) {
        public StatusValue {
            if (bit != 0 && bit != 1) {
                throw new IllegalArgumentException("status bit must be 0 or 1");
            }
            if (reasonCode < 0 || reasonCode > TrustRegistryProfile.MAX_REASON_CODE) {
                throw new IllegalArgumentException("status reason code is outside its bound");
            }
        }

        public boolean set() {
            return bit == 1;
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnsignedInteger(bit));
            value.add(new UnsignedInteger(reasonCode));
            return StdlibContractCbor.encode(value);
        }

        public static StatusValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 3).getDataItems();
            requireVersion(items.get(0));
            StatusValue decoded = new StatusValue(
                    StdlibContractCbor.uintInt(items.get(1)),
                    StdlibContractCbor.uintInt(items.get(2)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, purpose, bitLength, listSha256, publishedHeight]}. */
    public record StatusListValue(String purpose, long bitLength, byte[] listSha256,
                                  long publishedHeight) {
        public StatusListValue {
            purpose = boundedText(purpose, 32, "purpose");
            if (bitLength < TrustRegistryProfile.MIN_BIT_LENGTH
                    || bitLength > TrustRegistryProfile.MAX_BIT_LENGTH) {
                throw new IllegalArgumentException("status list bit length is outside its bound");
            }
            listSha256 = exact(listSha256, 32, "listSha256");
            if (publishedHeight < 0 || publishedHeight > TrustRegistryProfile.MAX_HEIGHT) {
                throw new IllegalArgumentException("published height is outside its bound");
            }
        }

        @Override public byte[] listSha256() { return listSha256.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof StatusListValue that
                    && purpose.equals(that.purpose) && bitLength == that.bitLength
                    && Arrays.equals(listSha256, that.listSha256)
                    && publishedHeight == that.publishedHeight;
        }

        @Override
        public int hashCode() {
            return Objects.hash(purpose, bitLength, Arrays.hashCode(listSha256), publishedHeight);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(purpose));
            value.add(new UnsignedInteger(bitLength));
            value.add(new ByteString(listSha256));
            value.add(new UnsignedInteger(publishedHeight));
            return StdlibContractCbor.encode(value);
        }

        public static StatusListValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 5).getDataItems();
            requireVersion(items.get(0));
            StatusListValue decoded = new StatusListValue(
                    StdlibContractCbor.text(items.get(1)),
                    StdlibContractCbor.uint(items.get(2)),
                    StdlibContractCbor.bytes(items.get(3), 32),
                    StdlibContractCbor.uint(items.get(4)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, framework, [authorization...], validFromHeight, validUntilHeight]}; 0 = no end. */
    public record IssuerValue(String framework, List<String> authorizations, long validFromHeight,
                              long validUntilHeight) {
        public IssuerValue {
            framework = boundedText(framework, TrustRegistryProfile.MAX_TEXT_BYTES, "framework");
            authorizations = List.copyOf(Objects.requireNonNull(authorizations, "authorizations"));
            if (authorizations.size() > TrustRegistryProfile.MAX_AUTHORIZATIONS) {
                throw new IllegalArgumentException("too many issuer authorizations");
            }
            for (String authorization : authorizations) {
                boundedText(authorization, TrustRegistryProfile.MAX_TEXT_BYTES, "authorization");
            }
            if (validFromHeight < 0 || validFromHeight > TrustRegistryProfile.MAX_HEIGHT
                    || validUntilHeight < 0 || validUntilHeight > TrustRegistryProfile.MAX_HEIGHT
                    || validUntilHeight != 0 && validUntilHeight < validFromHeight) {
                throw new IllegalArgumentException("issuer validity heights are invalid");
            }
        }

        public boolean validAt(long height) {
            return height >= validFromHeight && (validUntilHeight == 0 || height <= validUntilHeight);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(framework));
            Array granted = new Array();
            authorizations.forEach(item -> granted.add(new UnicodeString(item)));
            value.add(granted);
            value.add(new UnsignedInteger(validFromHeight));
            value.add(new UnsignedInteger(validUntilHeight));
            return StdlibContractCbor.encode(value);
        }

        public static IssuerValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 5).getDataItems();
            requireVersion(items.get(0));
            Array granted = StdlibContractCbor.array(items.get(2),
                    TrustRegistryProfile.MAX_AUTHORIZATIONS);
            IssuerValue decoded = new IssuerValue(
                    StdlibContractCbor.text(items.get(1)),
                    granted.getDataItems().stream().map(StdlibContractCbor::text).toList(),
                    StdlibContractCbor.uint(items.get(3)),
                    StdlibContractCbor.uint(items.get(4)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    private static Array version() {
        Array value = new Array();
        value.add(new UnsignedInteger(TrustRegistryProfile.VALUE_VERSION));
        return value;
    }

    private static void requireVersion(DataItem item) {
        if (StdlibContractCbor.uintInt(item) != TrustRegistryProfile.VALUE_VERSION) {
            throw new IllegalArgumentException("unsupported registry value version");
        }
    }

    private static void requireCanonical(byte[] supplied, byte[] normalized) {
        if (!Arrays.equals(supplied, normalized)) {
            throw new IllegalArgumentException("registry value is not canonical CBOR");
        }
    }

    private static String boundedText(String value, int maximumBytes, String name) {
        Objects.requireNonNull(value, name);
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length < 1 || length > maximumBytes) {
            throw new IllegalArgumentException(name + " must be 1-" + maximumBytes + " UTF-8 bytes");
        }
        return value;
    }

    private static byte[] exact(byte[] value, int length, String name) {
        byte[] copy = Objects.requireNonNull(value, name).clone();
        if (copy.length != length) {
            throw new IllegalArgumentException(name + " must be " + length + " bytes");
        }
        return copy;
    }
}
