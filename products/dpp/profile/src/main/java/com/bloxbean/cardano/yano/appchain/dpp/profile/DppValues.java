package com.bloxbean.cardano.yano.appchain.dpp.profile;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Canonical CBOR layouts of the starter's values (ADR-051 §2.1). Every decoder re-encodes what
 * it decoded and rejects bytes that are not the canonical form, so an entry read from chain is
 * exactly what the profile's schema admitted.
 */
public final class DppValues {
    public static final String CLAIM_COMMITMENT_DOMAIN = "yano-dpp-claim-commitment-v1";

    private DppValues() {
    }

    /** {@code [1, manufacturerOrganizationId, status, currentVersion, successorProductId, passportProfileId]}. */
    public record ProductValue(String manufacturerOrganizationId, int status, long currentVersion,
                               String successorProductId, String passportProfileId) {
        public ProductValue {
            manufacturerOrganizationId = boundedText(manufacturerOrganizationId, 1,
                    DppStarterProfile.MAX_ORGANIZATION_BYTES, "manufacturerOrganizationId");
            DppStarterProfile.requireStatus(status);
            if (currentVersion < 0 || currentVersion > DppStarterProfile.MAX_VERSION) {
                throw new IllegalArgumentException("currentVersion must be 0-"
                        + DppStarterProfile.MAX_VERSION);
            }
            successorProductId = successorProductId == null ? "" : successorProductId;
            if (!successorProductId.isEmpty()) {
                DppStarterProfile.requireProductId(successorProductId);
            }
            passportProfileId = boundedText(passportProfileId, 1,
                    DppStarterProfile.MAX_PROFILE_ID_BYTES, "passportProfileId");
        }

        public String statusName() {
            return DppStarterProfile.statusName(status);
        }

        public ProductValue withStatus(int newStatus, String successor) {
            return new ProductValue(manufacturerOrganizationId, newStatus, currentVersion,
                    successor, passportProfileId);
        }

        public ProductValue withCurrentVersion(long version) {
            return new ProductValue(manufacturerOrganizationId, status, version,
                    successorProductId, passportProfileId);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(manufacturerOrganizationId));
            value.add(new UnsignedInteger(status));
            value.add(new UnsignedInteger(currentVersion));
            value.add(new UnicodeString(successorProductId));
            value.add(new UnicodeString(passportProfileId));
            return StdlibContractCbor.encode(value);
        }

        public static ProductValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 6).getDataItems();
            requireVersion(items.get(0));
            ProductValue decoded = new ProductValue(
                    StdlibContractCbor.text(items.get(1)),
                    StdlibContractCbor.uintInt(items.get(2)),
                    StdlibContractCbor.uint(items.get(3)),
                    StdlibContractCbor.text(items.get(4)),
                    StdlibContractCbor.text(items.get(5)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, documentSha256, mediaType, reference, byteLength]}: ADR-026 §7.5's content record. */
    public record VersionValue(byte[] documentSha256, String mediaType, String reference,
                               long byteLength) {
        public VersionValue {
            documentSha256 = exact(documentSha256, 32, "documentSha256");
            mediaType = boundedText(mediaType, 1, DppStarterProfile.MAX_MEDIA_TYPE_BYTES, "mediaType");
            reference = boundedText(reference == null ? "" : reference, 0,
                    DppStarterProfile.MAX_REFERENCE_BYTES, "reference");
            if (byteLength < 0 || byteLength > DppStarterProfile.MAX_BYTE_LENGTH) {
                throw new IllegalArgumentException("byteLength is outside bounds");
            }
        }

        @Override public byte[] documentSha256() { return documentSha256.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof VersionValue that
                    && Arrays.equals(documentSha256, that.documentSha256)
                    && mediaType.equals(that.mediaType) && reference.equals(that.reference)
                    && byteLength == that.byteLength;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(documentSha256), mediaType, reference, byteLength);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new ByteString(documentSha256));
            value.add(new UnicodeString(mediaType));
            value.add(new UnicodeString(reference));
            value.add(new UnsignedInteger(byteLength));
            return StdlibContractCbor.encode(value);
        }

        public static VersionValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 5).getDataItems();
            requireVersion(items.get(0));
            VersionValue decoded = new VersionValue(
                    StdlibContractCbor.bytes(items.get(1), 32),
                    StdlibContractCbor.text(items.get(2)),
                    StdlibContractCbor.text(items.get(3)),
                    StdlibContractCbor.uint(items.get(4)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /**
     * {@code [1, visibility, value, issuerOrganizationId, validFromHeight, validUntilHeight,
     * evidenceSha256]}. A public claim carries its UTF-8 text; a committed claim carries the
     * 32-byte commitment of {@link #claimCommitment(byte[], String)}.
     */
    public record ClaimValue(int visibility, byte[] value, String issuerOrganizationId,
                             long validFromHeight, long validUntilHeight, byte[] evidenceSha256) {
        public ClaimValue {
            if (visibility != DppStarterProfile.VISIBILITY_PUBLIC
                    && visibility != DppStarterProfile.VISIBILITY_COMMITTED) {
                throw new IllegalArgumentException("visibility must be 0 (public) or 1 (committed)");
            }
            value = Objects.requireNonNull(value, "value").clone();
            if (value.length < 1 || value.length > DppStarterProfile.MAX_CLAIM_TEXT_BYTES) {
                throw new IllegalArgumentException("claim value must be 1-"
                        + DppStarterProfile.MAX_CLAIM_TEXT_BYTES + " bytes");
            }
            if (visibility == DppStarterProfile.VISIBILITY_COMMITTED && value.length != 32) {
                throw new IllegalArgumentException("a committed claim carries a 32-byte commitment");
            }
            issuerOrganizationId = boundedText(issuerOrganizationId, 1,
                    DppStarterProfile.MAX_ORGANIZATION_BYTES, "issuerOrganizationId");
            requireHeights(validFromHeight, validUntilHeight);
            evidenceSha256 = optionalDigest(evidenceSha256, "evidenceSha256");
        }

        @Override public byte[] value() { return value.clone(); }
        @Override public byte[] evidenceSha256() { return evidenceSha256.clone(); }

        public boolean isPublic() {
            return visibility == DppStarterProfile.VISIBILITY_PUBLIC;
        }

        /** The claim text of a public claim; null for a committed one. */
        public String text() {
            return isPublic() ? new String(value, StandardCharsets.UTF_8) : null;
        }

        public boolean validAt(long height) {
            return DppValues.validAt(validFromHeight, validUntilHeight, height);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ClaimValue that && visibility == that.visibility
                    && Arrays.equals(value, that.value)
                    && issuerOrganizationId.equals(that.issuerOrganizationId)
                    && validFromHeight == that.validFromHeight
                    && validUntilHeight == that.validUntilHeight
                    && Arrays.equals(evidenceSha256, that.evidenceSha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(visibility, Arrays.hashCode(value), issuerOrganizationId,
                    validFromHeight, validUntilHeight, Arrays.hashCode(evidenceSha256));
        }

        public byte[] encode() {
            Array encoded = version();
            encoded.add(new UnsignedInteger(visibility));
            encoded.add(new ByteString(value));
            encoded.add(new UnicodeString(issuerOrganizationId));
            encoded.add(new UnsignedInteger(validFromHeight));
            encoded.add(new UnsignedInteger(validUntilHeight));
            encoded.add(new ByteString(evidenceSha256));
            return StdlibContractCbor.encode(encoded);
        }

        public static ClaimValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 7).getDataItems();
            requireVersion(items.get(0));
            ClaimValue decoded = new ClaimValue(
                    StdlibContractCbor.uintInt(items.get(1)),
                    StdlibContractCbor.bytes(items.get(2)),
                    StdlibContractCbor.text(items.get(3)),
                    StdlibContractCbor.uint(items.get(4)),
                    StdlibContractCbor.uint(items.get(5)),
                    StdlibContractCbor.bytes(items.get(6)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, eventType, actorOrganizationId, observedAt, location, evidenceSha256, note]}. */
    public record EventValue(String eventType, String actorOrganizationId, long observedAt,
                             String location, byte[] evidenceSha256, String note) {
        public EventValue {
            eventType = boundedText(eventType, 1, DppStarterProfile.MAX_EVENT_TYPE_BYTES, "eventType");
            actorOrganizationId = boundedText(actorOrganizationId, 1,
                    DppStarterProfile.MAX_ORGANIZATION_BYTES, "actorOrganizationId");
            if (observedAt < 0 || observedAt > DppStarterProfile.MAX_OBSERVED_AT) {
                throw new IllegalArgumentException("observedAt must be epoch seconds within bounds");
            }
            location = boundedText(location == null ? "" : location, 0,
                    DppStarterProfile.MAX_LOCATION_BYTES, "location");
            evidenceSha256 = optionalDigest(evidenceSha256, "evidenceSha256");
            note = boundedText(note == null ? "" : note, 0, DppStarterProfile.MAX_NOTE_BYTES, "note");
        }

        @Override public byte[] evidenceSha256() { return evidenceSha256.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof EventValue that && eventType.equals(that.eventType)
                    && actorOrganizationId.equals(that.actorOrganizationId)
                    && observedAt == that.observedAt && location.equals(that.location)
                    && Arrays.equals(evidenceSha256, that.evidenceSha256) && note.equals(that.note);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventType, actorOrganizationId, observedAt, location,
                    Arrays.hashCode(evidenceSha256), note);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(eventType));
            value.add(new UnicodeString(actorOrganizationId));
            value.add(new UnsignedInteger(observedAt));
            value.add(new UnicodeString(location));
            value.add(new ByteString(evidenceSha256));
            value.add(new UnicodeString(note));
            return StdlibContractCbor.encode(value);
        }

        public static EventValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 7).getDataItems();
            requireVersion(items.get(0));
            EventValue decoded = new EventValue(
                    StdlibContractCbor.text(items.get(1)),
                    StdlibContractCbor.text(items.get(2)),
                    StdlibContractCbor.uint(items.get(3)),
                    StdlibContractCbor.text(items.get(4)),
                    StdlibContractCbor.bytes(items.get(5)),
                    StdlibContractCbor.text(items.get(6)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, productId, certificateType, issuerOrganizationId, evidenceSha256, validFromHeight, validUntilHeight]}. */
    public record CertificateValue(String productId, String certificateType,
                                   String issuerOrganizationId, byte[] evidenceSha256,
                                   long validFromHeight, long validUntilHeight) {
        public CertificateValue {
            DppStarterProfile.requireProductId(productId);
            certificateType = boundedText(certificateType, 1,
                    DppStarterProfile.MAX_CERTIFICATE_TYPE_BYTES, "certificateType");
            issuerOrganizationId = boundedText(issuerOrganizationId, 1,
                    DppStarterProfile.MAX_ORGANIZATION_BYTES, "issuerOrganizationId");
            evidenceSha256 = exact(evidenceSha256, 32, "evidenceSha256");
            requireHeights(validFromHeight, validUntilHeight);
        }

        @Override public byte[] evidenceSha256() { return evidenceSha256.clone(); }

        public boolean validAt(long height) {
            return DppValues.validAt(validFromHeight, validUntilHeight, height);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CertificateValue that && productId.equals(that.productId)
                    && certificateType.equals(that.certificateType)
                    && issuerOrganizationId.equals(that.issuerOrganizationId)
                    && Arrays.equals(evidenceSha256, that.evidenceSha256)
                    && validFromHeight == that.validFromHeight
                    && validUntilHeight == that.validUntilHeight;
        }

        @Override
        public int hashCode() {
            return Objects.hash(productId, certificateType, issuerOrganizationId,
                    Arrays.hashCode(evidenceSha256), validFromHeight, validUntilHeight);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(productId));
            value.add(new UnicodeString(certificateType));
            value.add(new UnicodeString(issuerOrganizationId));
            value.add(new ByteString(evidenceSha256));
            value.add(new UnsignedInteger(validFromHeight));
            value.add(new UnsignedInteger(validUntilHeight));
            return StdlibContractCbor.encode(value);
        }

        public static CertificateValue decode(byte[] bytes) {
            List<DataItem> items = StdlibContractCbor.decodeArray(bytes, 7).getDataItems();
            requireVersion(items.get(0));
            CertificateValue decoded = new CertificateValue(
                    StdlibContractCbor.text(items.get(1)),
                    StdlibContractCbor.text(items.get(2)),
                    StdlibContractCbor.text(items.get(3)),
                    StdlibContractCbor.bytes(items.get(4), 32),
                    StdlibContractCbor.uint(items.get(5)),
                    StdlibContractCbor.uint(items.get(6)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    // ------------------------------------------------------------------ commitments and digests

    /** {@code SHA-256("yano-dpp-claim-commitment-v1" || salt32 || utf8(text))}. */
    public static byte[] claimCommitment(byte[] salt, String text) {
        byte[] saltBytes = exact(salt, 32, "salt");
        byte[] textBytes = Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8);
        if (textBytes.length < 1 || textBytes.length > DppStarterProfile.MAX_CLAIM_TEXT_BYTES) {
            throw new IllegalArgumentException("claim text must be 1-"
                    + DppStarterProfile.MAX_CLAIM_TEXT_BYTES + " UTF-8 bytes");
        }
        MessageDigest digest = sha256();
        digest.update(CLAIM_COMMITMENT_DOMAIN.getBytes(StandardCharsets.US_ASCII));
        digest.update(saltBytes);
        digest.update(textBytes);
        return digest.digest();
    }

    public static byte[] sha256(byte[] bytes) {
        return sha256().digest(Objects.requireNonNull(bytes, "bytes"));
    }

    static boolean validAt(long validFromHeight, long validUntilHeight, long height) {
        return height >= validFromHeight && (validUntilHeight == 0 || height <= validUntilHeight);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static void requireHeights(long validFromHeight, long validUntilHeight) {
        if (validFromHeight < 0 || validFromHeight > DppStarterProfile.MAX_HEIGHT
                || validUntilHeight < 0 || validUntilHeight > DppStarterProfile.MAX_HEIGHT
                || (validUntilHeight != 0 && validUntilHeight < validFromHeight)) {
            throw new IllegalArgumentException("validity heights are outside bounds");
        }
    }

    private static Array version() {
        Array value = new Array();
        value.add(new UnsignedInteger(DppStarterProfile.VALUE_VERSION));
        return value;
    }

    private static void requireVersion(DataItem item) {
        if (StdlibContractCbor.uintInt(item) != DppStarterProfile.VALUE_VERSION) {
            throw new IllegalArgumentException("unsupported DPP value version");
        }
    }

    private static void requireCanonical(byte[] supplied, byte[] normalized) {
        if (!Arrays.equals(supplied, normalized)) {
            throw new IllegalArgumentException("DPP value is not canonical CBOR");
        }
    }

    private static String boundedText(String value, int minimumBytes, int maximumBytes, String name) {
        Objects.requireNonNull(value, name);
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length < minimumBytes || length > maximumBytes) {
            throw new IllegalArgumentException(name + " must be " + minimumBytes + "-"
                    + maximumBytes + " UTF-8 bytes");
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

    private static byte[] optionalDigest(byte[] value, String name) {
        byte[] copy = value == null ? new byte[0] : value.clone();
        if (copy.length != 0 && copy.length != 32) {
            throw new IllegalArgumentException(name + " must be empty or 32 bytes");
        }
        return copy;
    }
}
