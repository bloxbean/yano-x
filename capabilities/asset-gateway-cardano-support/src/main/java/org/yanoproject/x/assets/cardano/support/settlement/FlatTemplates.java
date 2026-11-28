package org.yanoproject.x.assets.cardano.support.settlement;

import java.io.ByteArrayOutputStream;

/**
 * Applies byte-string parameters to a compiled Plutus V3 template, without a UPLC toolchain: the result is the flat
 * program {@code [(...((template p1) p2)...) pn]} with every parameter a {@code data} constant ({@code B p}), exactly as
 * {@code Program.applyParams(PlutusData.bytes(p)...)} encodes it. The template's term is copied bit for bit; the walk
 * over it only finds where it ends (ADR-059 r23: the runtime derives the shard script of either backend).
 *
 * <p>Flat encoding (Plutus Core spec, appendix C): version {@code 1.1.0} as three naturals, then the term, then a
 * filler {@code 0* 1} to the byte boundary. Term tags are 4 bits; a constant carries its type as a list of 4-bit type
 * tags and its value; a byte string is a filler and then chunks of at most 255 bytes, each after its length byte, and
 * a 0 byte.
 */
final class FlatTemplates {
    private FlatTemplates() {
    }

    /** The parameterized flat program. */
    static byte[] applyBytes(byte[] template, byte[]... params) {
        if (template.length < 4 || template[0] != 1 || template[1] != 1 || template[2] != 0) {
            throw new IllegalArgumentException("not a Plutus 1.1.0 flat program");
        }
        Reader reader = new Reader(template, 24);
        reader.term();
        long termEnd = reader.position;
        reader.filler();
        if (reader.position != 8L * template.length) {
            throw new IllegalArgumentException("trailing bytes after the program");
        }
        Writer writer = new Writer();
        for (int i = 0; i < 24; i++) writer.bit(bit(template, i));
        for (int i = 0; i < params.length; i++) writer.bits(0b0011, 4); // Apply
        for (long i = 24; i < termEnd; i++) writer.bit(bit(template, i));
        for (byte[] param : params) {
            writer.bits(0b0100, 4); // Constant
            writer.bits(0b1, 1);    // type list: one tag
            writer.bits(0b1000, 4); // data
            writer.bits(0b0, 1);    // end of the type list
            writer.byteString(cborBytes(param));
        }
        writer.filler();
        return writer.toByteArray();
    }

    /** The definite-length CBOR byte string of {@code value} (the CBOR of {@code B value}, and a script's bytes). */
    static byte[] cborBytes(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 5);
        int n = value.length;
        if (n < 24) {
            out.write(0x40 | n);
        } else if (n < 0x100) {
            out.write(0x58);
            out.write(n);
        } else if (n < 0x10000) {
            out.write(0x59);
            out.write(n >>> 8);
            out.write(n);
        } else {
            out.write(0x5a);
            out.write(n >>> 24);
            out.write(n >>> 16);
            out.write(n >>> 8);
            out.write(n);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static int bit(byte[] bytes, long index) {
        return (bytes[(int) (index >>> 3)] >>> (7 - (int) (index & 7))) & 1;
    }

    /** Walks one flat term and reports where it ends. */
    private static final class Reader {
        private final byte[] bytes;
        long position;

        Reader(byte[] bytes, long position) {
            this.bytes = bytes;
            this.position = position;
        }

        int bit() {
            if (position >= 8L * bytes.length) throw new IllegalArgumentException("truncated flat program");
            return FlatTemplates.bit(bytes, position++);
        }

        int bits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) value = (value << 1) | bit();
            return value;
        }

        /** A natural: 7-bit groups, each after a continuation bit. */
        void natural() {
            while (bits(8) >= 0x80) {
                // continuation
            }
        }

        void filler() {
            while (bit() == 0) {
                // padding
            }
        }

        void byteString() {
            filler();
            int length;
            while ((length = bits(8)) != 0) position += 8L * length;
            if (position > 8L * bytes.length) throw new IllegalArgumentException("truncated byte string");
        }

        void term() {
            switch (bits(4)) {
                case 0 -> natural();              // Var
                case 1, 2, 5 -> term();           // Delay, LamAbs, Force
                case 3 -> {                       // Apply
                    term();
                    term();
                }
                case 4 -> {                       // Constant
                    int[] type = typeTags();
                    int[] cursor = {0};
                    value(type, cursor);
                }
                case 6 -> {                       // Error
                }
                case 7 -> bits(7);                // Builtin
                case 8 -> {                       // Constr
                    natural();
                    while (bit() == 1) term();
                }
                case 9 -> {                       // Case
                    term();
                    while (bit() == 1) term();
                }
                default -> throw new IllegalArgumentException("unknown flat term tag");
            }
        }

        int[] typeTags() {
            int[] tags = new int[16];
            int count = 0;
            while (bit() == 1) {
                if (count == tags.length) tags = java.util.Arrays.copyOf(tags, count * 2);
                tags[count++] = bits(4);
            }
            return java.util.Arrays.copyOf(tags, count);
        }

        /** One constant value of the type starting at {@code type[cursor[0]]}; advances the cursor past it. */
        void value(int[] type, int[] cursor) {
            int at = cursor[0];
            int tag = type[cursor[0]++];
            switch (tag) {
                case 0 -> natural();              // integer (zigzag natural)
                case 1, 2, 8 -> byteString();     // bytestring, string, data (its CBOR)
                case 3 -> {                       // unit
                }
                case 4 -> bit();                  // bool
                case 7 -> {                       // type application: list or pair
                    int applied = type[cursor[0]];
                    if (applied == 5) {
                        cursor[0]++;
                        int element = cursor[0];
                        int after = skipType(type, element);
                        while (bit() == 1) {
                            cursor[0] = element;
                            value(type, cursor);
                        }
                        cursor[0] = after;
                    } else if (applied == 7 && type[cursor[0] + 1] == 6) {
                        cursor[0] += 2;
                        value(type, cursor);
                        value(type, cursor);
                    } else {
                        throw new IllegalArgumentException("unsupported constant type at " + at);
                    }
                }
                default -> throw new IllegalArgumentException("unsupported constant type " + tag);
            }
        }

        /** The index just past the type starting at {@code index}. */
        static int skipType(int[] type, int index) {
            if (type[index] != 7) return index + 1;
            if (type[index + 1] == 5) return skipType(type, index + 2);
            return skipType(type, skipType(type, index + 3));
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;

        void bit(int value) {
            current = (current << 1) | value;
            if (++used == 8) {
                out.write(current);
                current = 0;
                used = 0;
            }
        }

        void bits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bit((value >>> i) & 1);
        }

        void filler() {
            while (used != 7) bit(0);
            bit(1);
        }

        void byteString(byte[] value) {
            filler();
            for (int offset = 0; offset < value.length; offset += 255) {
                int length = Math.min(255, value.length - offset);
                out.write(length);
                out.write(value, offset, length);
            }
            out.write(0);
        }

        byte[] toByteArray() {
            if (used != 0) throw new IllegalStateException("unaligned");
            return out.toByteArray();
        }
    }
}
