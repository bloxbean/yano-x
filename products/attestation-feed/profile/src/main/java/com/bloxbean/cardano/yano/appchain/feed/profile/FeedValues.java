package com.bloxbean.cardano.yano.appchain.feed.profile;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.internal.StdlibContractCbor;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical CBOR layouts of the starter's values (ADR-052 §2.1). Every decoder re-encodes what
 * it decoded and rejects bytes that are not the canonical form, so an entry read from chain is
 * exactly what the profile's schema admitted. Numbers are signed 64-bit integers within
 * ±(2^63 − 1) and are encoded as CBOR major type 0 or 1.
 */
public final class FeedValues {
    private FeedValues() {
    }

    /**
     * {@code [1, description, unit, scale, epochStart, roundSeconds, sources, minimumSources,
     * maximumDeviationPpm, maximumDeviationAbsolute, minimumValue, maximumValue, status]}.
     */
    public record FeedValue(String description, String unit, int scale, long epochStart,
                            long roundSeconds, List<String> sources, int minimumSources,
                            long maximumDeviationPpm, long maximumDeviationAbsolute,
                            long minimumValue, long maximumValue, int status) {
        public FeedValue {
            description = boundedText(description == null ? "" : description, 0,
                    FeedStarterProfile.MAX_DESCRIPTION_BYTES, "description");
            unit = boundedText(unit, 1, FeedStarterProfile.MAX_UNIT_BYTES, "unit");
            if (scale < 0 || scale > FeedStarterProfile.MAX_SCALE) {
                throw new IllegalArgumentException("scale must be 0-" + FeedStarterProfile.MAX_SCALE);
            }
            if (epochStart < 0 || epochStart > FeedStarterProfile.MAX_EPOCH_SECONDS) {
                throw new IllegalArgumentException("epochStart must be epoch seconds within bounds");
            }
            if (roundSeconds < 1 || roundSeconds > FeedStarterProfile.MAX_ROUND_SECONDS) {
                throw new IllegalArgumentException("roundSeconds must be 1-"
                        + FeedStarterProfile.MAX_ROUND_SECONDS);
            }
            sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
            if (sources.isEmpty() || sources.size() > FeedStarterProfile.MAX_SOURCES) {
                throw new IllegalArgumentException("sources must list 1-"
                        + FeedStarterProfile.MAX_SOURCES + " source actors");
            }
            Set<String> distinct = new HashSet<>();
            for (String source : sources) {
                FeedStarterProfile.requireSourceId(source);
                if (!distinct.add(source)) {
                    throw new IllegalArgumentException("duplicate source " + source);
                }
            }
            if (minimumSources < 1 || minimumSources > sources.size()) {
                throw new IllegalArgumentException("minimumSources must be 1-" + sources.size());
            }
            if (maximumDeviationPpm < 0 || maximumDeviationPpm > FeedStarterProfile.MAX_PPM) {
                throw new IllegalArgumentException("maximumDeviationPpm must be 0-"
                        + FeedStarterProfile.MAX_PPM);
            }
            if (maximumDeviationAbsolute < 0) {
                throw new IllegalArgumentException("maximumDeviationAbsolute must be non-negative");
            }
            requireValue(minimumValue, "minimumValue");
            requireValue(maximumValue, "maximumValue");
            if (minimumValue > maximumValue) {
                throw new IllegalArgumentException("minimumValue exceeds maximumValue");
            }
            FeedStarterProfile.requireFeedStatus(status);
        }

        public boolean active() {
            return status == FeedStarterProfile.FEED_ACTIVE;
        }

        public String statusName() {
            return FeedStarterProfile.feedStatusName(status);
        }

        public long roundOf(long observedAt) {
            return FeedStarterProfile.roundOf(epochStart, roundSeconds, observedAt);
        }

        public long roundStart(long round) {
            return FeedStarterProfile.roundStart(epochStart, roundSeconds, round);
        }

        public long roundEnd(long round) {
            return FeedStarterProfile.roundEnd(epochStart, roundSeconds, round);
        }

        public boolean inRange(long value) {
            return value >= minimumValue && value <= maximumValue;
        }

        public FeedValue withStatus(int newStatus) {
            return new FeedValue(description, unit, scale, epochStart, roundSeconds, sources,
                    minimumSources, maximumDeviationPpm, maximumDeviationAbsolute, minimumValue,
                    maximumValue, newStatus);
        }

        public FeedValue withSources(List<String> newSources, int newMinimumSources) {
            return new FeedValue(description, unit, scale, epochStart, roundSeconds, newSources,
                    newMinimumSources, maximumDeviationPpm, maximumDeviationAbsolute, minimumValue,
                    maximumValue, status);
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnicodeString(description));
            value.add(new UnicodeString(unit));
            value.add(new UnsignedInteger(scale));
            value.add(new UnsignedInteger(epochStart));
            value.add(new UnsignedInteger(roundSeconds));
            value.add(textArray(sources));
            value.add(new UnsignedInteger(minimumSources));
            value.add(new UnsignedInteger(maximumDeviationPpm));
            value.add(new UnsignedInteger(maximumDeviationAbsolute));
            value.add(integer(minimumValue));
            value.add(integer(maximumValue));
            value.add(new UnsignedInteger(status));
            return StdlibContractCbor.encode(value);
        }

        public static FeedValue decode(byte[] bytes) {
            List<DataItem> items = decodeArray(bytes, 13).getDataItems();
            requireVersion(items.get(0));
            FeedValue decoded = new FeedValue(
                    StdlibContractCbor.text(items.get(1)),
                    StdlibContractCbor.text(items.get(2)),
                    StdlibContractCbor.uintInt(items.get(3)),
                    StdlibContractCbor.uint(items.get(4)),
                    StdlibContractCbor.uint(items.get(5)),
                    textList(items.get(6)),
                    StdlibContractCbor.uintInt(items.get(7)),
                    StdlibContractCbor.uint(items.get(8)),
                    StdlibContractCbor.uint(items.get(9)),
                    signed(items.get(10)),
                    signed(items.get(11)),
                    StdlibContractCbor.uintInt(items.get(12)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, value, observedAt, evidenceSha256, note]}. */
    public record ObservationValue(long value, long observedAt, byte[] evidenceSha256, String note) {
        public ObservationValue {
            requireValue(value, "value");
            if (observedAt < 0 || observedAt > FeedStarterProfile.MAX_EPOCH_SECONDS) {
                throw new IllegalArgumentException("observedAt must be epoch seconds within bounds");
            }
            evidenceSha256 = optionalDigest(evidenceSha256, "evidenceSha256");
            note = boundedText(note == null ? "" : note, 0, FeedStarterProfile.MAX_NOTE_BYTES, "note");
        }

        @Override public byte[] evidenceSha256() { return evidenceSha256.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof ObservationValue that && value == that.value
                    && observedAt == that.observedAt
                    && Arrays.equals(evidenceSha256, that.evidenceSha256) && note.equals(that.note);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, observedAt, Arrays.hashCode(evidenceSha256), note);
        }

        public byte[] encode() {
            Array encoded = version();
            encoded.add(integer(value));
            encoded.add(new UnsignedInteger(observedAt));
            encoded.add(new ByteString(evidenceSha256));
            encoded.add(new UnicodeString(note));
            return StdlibContractCbor.encode(encoded);
        }

        public static ObservationValue decode(byte[] bytes) {
            List<DataItem> items = decodeArray(bytes, 5).getDataItems();
            requireVersion(items.get(0));
            ObservationValue decoded = new ObservationValue(
                    signed(items.get(1)),
                    StdlibContractCbor.uint(items.get(2)),
                    StdlibContractCbor.bytes(items.get(3)),
                    StdlibContractCbor.text(items.get(4)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    /** {@code [1, status, closedAtHeight, aggregate, scale, acceptedSources, policySha256, datumSha256]}. */
    public record RoundValue(int status, long closedAtHeight, long aggregate, int scale,
                             List<String> acceptedSources, byte[] policySha256, byte[] datumSha256) {
        public RoundValue {
            FeedStarterProfile.requireRoundStatus(status);
            if (closedAtHeight < 1 || closedAtHeight > FeedStarterProfile.MAX_HEIGHT) {
                throw new IllegalArgumentException("closedAtHeight must be a positive height");
            }
            requireValue(aggregate, "aggregate");
            if (scale < 0 || scale > FeedStarterProfile.MAX_SCALE) {
                throw new IllegalArgumentException("scale must be 0-" + FeedStarterProfile.MAX_SCALE);
            }
            acceptedSources = List.copyOf(Objects.requireNonNull(acceptedSources, "acceptedSources"));
            if (acceptedSources.size() > FeedStarterProfile.MAX_SOURCES) {
                throw new IllegalArgumentException("acceptedSources exceeds " + FeedStarterProfile.MAX_SOURCES);
            }
            Set<String> distinct = new HashSet<>();
            for (String source : acceptedSources) {
                FeedStarterProfile.requireSourceId(source);
                if (!distinct.add(source)) {
                    throw new IllegalArgumentException("duplicate accepted source " + source);
                }
            }
            policySha256 = exact(policySha256, 32, "policySha256");
            datumSha256 = optionalDigest(datumSha256, "datumSha256");
            if (status == FeedStarterProfile.ROUND_NO_QUORUM && datumSha256.length != 0) {
                throw new IllegalArgumentException("a NO_QUORUM round carries no datum");
            }
            if (status == FeedStarterProfile.ROUND_CLOSED && datumSha256.length != 32) {
                throw new IllegalArgumentException("a CLOSED round carries its datum hash");
            }
        }

        @Override public byte[] policySha256() { return policySha256.clone(); }
        @Override public byte[] datumSha256() { return datumSha256.clone(); }

        public boolean closed() {
            return status == FeedStarterProfile.ROUND_CLOSED;
        }

        public String statusName() {
            return FeedStarterProfile.roundStatusName(status);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RoundValue that && status == that.status
                    && closedAtHeight == that.closedAtHeight && aggregate == that.aggregate
                    && scale == that.scale && acceptedSources.equals(that.acceptedSources)
                    && Arrays.equals(policySha256, that.policySha256)
                    && Arrays.equals(datumSha256, that.datumSha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, closedAtHeight, aggregate, scale, acceptedSources,
                    Arrays.hashCode(policySha256), Arrays.hashCode(datumSha256));
        }

        public byte[] encode() {
            Array value = version();
            value.add(new UnsignedInteger(status));
            value.add(new UnsignedInteger(closedAtHeight));
            value.add(integer(aggregate));
            value.add(new UnsignedInteger(scale));
            value.add(textArray(acceptedSources));
            value.add(new ByteString(policySha256));
            value.add(new ByteString(datumSha256));
            return StdlibContractCbor.encode(value);
        }

        public static RoundValue decode(byte[] bytes) {
            List<DataItem> items = decodeArray(bytes, 8).getDataItems();
            requireVersion(items.get(0));
            RoundValue decoded = new RoundValue(
                    StdlibContractCbor.uintInt(items.get(1)),
                    StdlibContractCbor.uint(items.get(2)),
                    signed(items.get(3)),
                    StdlibContractCbor.uintInt(items.get(4)),
                    textList(items.get(5)),
                    StdlibContractCbor.bytes(items.get(6), 32),
                    StdlibContractCbor.bytes(items.get(7)));
            requireCanonical(bytes, decoded.encode());
            return decoded;
        }
    }

    // ------------------------------------------------------------------ helpers

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes, "bytes"));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** {@code value × 10^-scale} rendered as a plain decimal string, for display only. */
    public static String decimal(long value, int scale) {
        BigInteger magnitude = BigInteger.valueOf(value).abs();
        String digits = magnitude.toString();
        if (scale > 0) {
            if (digits.length() <= scale) {
                digits = "0".repeat(scale - digits.length() + 1) + digits;
            }
            digits = digits.substring(0, digits.length() - scale) + "." + digits.substring(digits.length() - scale);
        }
        return (value < 0 ? "-" : "") + digits;
    }

    private static final int MAX_ITEMS = 64;
    private static final int MAX_DEPTH = 3;

    /**
     * A bounded decoder for the starter's arrays: one root array of the given arity, no chunked
     * strings, no tags, at most three levels, only integers (both signs), bytes, text, and
     * arrays; the bytes must re-encode to themselves. The stdlib helper admits unsigned
     * integers only, and observations may be negative.
     */
    static Array decodeArray(byte[] input, int arity) {
        if (input == null || input.length == 0 || input.length > StdlibContractCbor.MAX_WIRE_BYTES) {
            throw StdlibContractCbor.malformed();
        }
        List<DataItem> roots;
        try {
            CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(input));
            decoder.setMaxPreallocationSize(StdlibContractCbor.MAX_WIRE_BYTES);
            roots = decoder.decode();
        } catch (Exception | StackOverflowError failure) {
            throw StdlibContractCbor.malformed();
        }
        if (roots.size() != 1 || !(roots.getFirst() instanceof Array array) || array.isChunked()
                || array.getDataItems().size() != arity) {
            throw StdlibContractCbor.malformed();
        }
        record Pending(DataItem item, int depth) { }
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(array, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            Pending current = pending.removeLast();
            DataItem item = current.item();
            if (++count > MAX_ITEMS || current.depth() > MAX_DEPTH || item.hasTag()) {
                throw StdlibContractCbor.malformed();
            }
            switch (item) {
                case Array nested -> {
                    if (nested.isChunked()) throw StdlibContractCbor.malformed();
                    for (DataItem child : nested.getDataItems()) {
                        pending.add(new Pending(child, current.depth() + 1));
                    }
                }
                case ByteString bytes -> {
                    if (bytes.isChunked()) throw StdlibContractCbor.malformed();
                }
                case UnicodeString text -> {
                    if (text.isChunked()) throw StdlibContractCbor.malformed();
                }
                case UnsignedInteger ignored -> { }
                case NegativeInteger ignored -> { }
                default -> throw StdlibContractCbor.malformed();
            }
        }
        if (!Arrays.equals(input, StdlibContractCbor.encode(array))) {
            throw StdlibContractCbor.malformed();
        }
        return array;
    }

    static DataItem integer(long value) {
        return value < 0 ? new NegativeInteger(value) : new UnsignedInteger(value);
    }

    static long signed(DataItem item) {
        if (!(item instanceof co.nstant.in.cbor.model.Number number)) {
            throw StdlibContractCbor.malformed();
        }
        BigInteger value = number.getValue();
        if (value.bitLength() > 63) {
            throw StdlibContractCbor.malformed();
        }
        long parsed = value.longValueExact();
        if (parsed < FeedStarterProfile.MIN_VALUE) {
            throw StdlibContractCbor.malformed();
        }
        return parsed;
    }

    private static Array textArray(List<String> texts) {
        Array array = new Array();
        texts.forEach(text -> array.add(new UnicodeString(text)));
        return array;
    }

    private static List<String> textList(DataItem item) {
        Array array = StdlibContractCbor.array(item, FeedStarterProfile.MAX_SOURCES);
        List<String> texts = new ArrayList<>(array.getDataItems().size());
        for (DataItem element : array.getDataItems()) {
            texts.add(StdlibContractCbor.text(element));
        }
        return texts;
    }

    private static void requireValue(long value, String name) {
        if (value < FeedStarterProfile.MIN_VALUE) {
            throw new IllegalArgumentException(name + " must be within ±(2^63 - 1)");
        }
    }

    private static Array version() {
        Array value = new Array();
        value.add(new UnsignedInteger(FeedStarterProfile.VALUE_VERSION));
        return value;
    }

    private static void requireVersion(DataItem item) {
        if (StdlibContractCbor.uintInt(item) != FeedStarterProfile.VALUE_VERSION) {
            throw new IllegalArgumentException("unsupported feed value version");
        }
    }

    private static void requireCanonical(byte[] supplied, byte[] normalized) {
        if (!Arrays.equals(supplied, normalized)) {
            throw new IllegalArgumentException("feed value is not canonical CBOR");
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
