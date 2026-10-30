package org.yanoproject.x.eutxo.bridge.onchain.mpf;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.CryptoLib;
import org.julclang.testkit.JulcEval;
import org.julclang.testkit.JvmCryptoProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MerklePatriciaForestry — the on-chain MPF library.
 * <p>
 * Test vectors are derived from the Aiken merkle-patricia-forestry test suite.
 * DirectJavaTests call the executable stubs directly; UplcTests would compile to UPLC.
 * <p>
 * Ported with the library's proof shape checks from bloxbean/julc-examples PR #4.
 */
class MerklePatriciaForestryTest {

    static final HexFormat HEX = HexFormat.of();

    @BeforeAll
    static void setup() {
        Builtins.setCryptoProvider(new JvmCryptoProvider());
    }

    // =========================================================================
    // Helper to parse hex strings
    // =========================================================================

    static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    // =========================================================================
    // Direct Java Tests — call methods via executable stubs
    // =========================================================================

    @Nested
    class NibbleTests {

        @Test
        void nibble_ab_index0_is_10() {
            // nibble(#"ab", 0) == 10  (0xa = 10)
            assertEquals(10, MerklePatriciaForestry.nibble(hex("ab"), 0));
        }

        @Test
        void nibble_ab_index1_is_11() {
            // nibble(#"ab", 1) == 11  (0xb = 11)
            assertEquals(11, MerklePatriciaForestry.nibble(hex("ab"), 1));
        }

        @Test
        void nibble_reconstructs_byte() {
            // msb * 16 + lsb == byte value
            byte[] bytes = hex("3f");
            int msb = MerklePatriciaForestry.nibble(bytes, 0);
            int lsb = MerklePatriciaForestry.nibble(bytes, 1);
            assertEquals(0x3f, msb * 16 + lsb);
        }

        @Test
        void nibble_range_0_to_15() {
            byte[] bytes = hex("f0");
            int high = MerklePatriciaForestry.nibble(bytes, 0);
            int low = MerklePatriciaForestry.nibble(bytes, 1);
            assertTrue(high >= 0 && high <= 15);
            assertTrue(low >= 0 && low <= 15);
            assertEquals(15, high);
            assertEquals(0, low);
        }
    }

    @Nested
    class NibblesTests {

        @Test
        void nibbles_empty_range() {
            // nibbles(#"0123456789", 2, 2) == #[]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 2, 2);
            assertEquals(0, result.length);
        }

        @Test
        void nibbles_single_element() {
            // nibbles(#"0123456789", 2, 3) == #[2]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 2, 3);
            assertArrayEquals(new byte[]{2}, result);
        }

        @Test
        void nibbles_four_elements() {
            // nibbles(#"0123456789", 4, 8) == #[4, 5, 6, 7]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 4, 8);
            assertArrayEquals(new byte[]{4, 5, 6, 7}, result);
        }

        @Test
        void nibbles_three_elements() {
            // nibbles(#"0123456789", 3, 6) == #[3, 4, 5]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 3, 6);
            assertArrayEquals(new byte[]{3, 4, 5}, result);
        }

        @Test
        void nibbles_six_elements() {
            // nibbles(#"0123456789", 1, 7) == #[1, 2, 3, 4, 5, 6]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 1, 7);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, result);
        }
    }

    @Nested
    class SuffixTests {

        @Test
        void suffix_even_cursor_0() {
            // suffix(#"abcd456789", 0) == #"ffabcd456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 0);
            assertArrayEquals(hex("ffabcd456789"), result);
        }

        @Test
        void suffix_odd_cursor_1() {
            // suffix(#"abcd456789", 1) == #"000bcd456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 1);
            assertArrayEquals(hex("000bcd456789"), result);
        }

        @Test
        void suffix_even_cursor_2() {
            // suffix(#"abcd456789", 2) == #"ffcd456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 2);
            assertArrayEquals(hex("ffcd456789"), result);
        }

        @Test
        void suffix_even_cursor_4() {
            // suffix(#"abcd456789", 4) == #"ff456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 4);
            assertArrayEquals(hex("ff456789"), result);
        }

        @Test
        void suffix_odd_cursor_5() {
            // suffix(#"abcd456789", 5) == #"00056789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 5);
            assertArrayEquals(hex("00056789"), result);
        }

        @Test
        void suffix_even_cursor_10_end() {
            // suffix(#"abcd456789", 10) == #"ff"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 10);
            assertArrayEquals(hex("ff"), result);
        }

        @Test
        void suffix_prefix_byte_invariant() {
            // The first byte of suffix is either 0xff (even cursor) or 0x00 (odd cursor)
            byte[] path = hex("abcdef0123");
            for (int cursor = 0; cursor < 10; cursor++) {
                byte[] s = MerklePatriciaForestry.suffix(path, cursor);
                int head = Byte.toUnsignedInt(s[0]);
                if (cursor % 2 == 0) {
                    assertEquals(0xff, head, "Even cursor should produce 0xff prefix");
                } else {
                    assertEquals(0, head, "Odd cursor should produce 0x00 prefix");
                    int secondByte = Byte.toUnsignedInt(s[1]);
                    assertTrue(secondByte < 16, "After 0x00, nibble should be < 16");
                }
            }
        }
    }

    @Nested
    class CombineTests {

        @Test
        void combine_null_hashes() {
            // combine(nullHash, nullHash) == nullHash2
            byte[] nh = MerklePatriciaForestry.nullHash();
            byte[] nh2 = MerklePatriciaForestry.nullHash2();
            byte[] combined = MerklePatriciaForestry.combine(nh, nh);
            assertArrayEquals(nh2, combined);
        }

        @Test
        void nullHash2_to_nullHash4() {
            // combine(nullHash2, nullHash2) == nullHash4
            byte[] nh2 = MerklePatriciaForestry.nullHash2();
            byte[] nh4 = MerklePatriciaForestry.nullHash4();
            assertArrayEquals(nh4, MerklePatriciaForestry.combine(nh2, nh2));
        }

        @Test
        void nullHash4_to_nullHash8() {
            // combine(nullHash4, nullHash4) == nullHash8
            byte[] nh4 = MerklePatriciaForestry.nullHash4();
            byte[] nh8 = MerklePatriciaForestry.nullHash8();
            assertArrayEquals(nh8, MerklePatriciaForestry.combine(nh4, nh4));
        }

        @Test
        void combine_produces_32_bytes() {
            byte[] result = MerklePatriciaForestry.combine(new byte[32], new byte[32]);
            assertEquals(32, result.length);
        }
    }

    @Nested
    class Merkle4Tests {

        @Test
        void merkle4_places_node_correctly() {
            // From merkling.tests.ak:
            // root = combine(combine(a, b), combine(c, d))
            // merkle_4(0, a, combine(c,d), b) == root
            // merkle_4(1, b, combine(c,d), a) == root
            // merkle_4(2, c, combine(a,b), d) == root
            // merkle_4(3, d, combine(a,b), c) == root
            byte[] a = CryptoLib.blake2b_256(new byte[]{1});
            byte[] b = CryptoLib.blake2b_256(new byte[]{2});
            byte[] c = CryptoLib.blake2b_256(new byte[]{3});
            byte[] d = CryptoLib.blake2b_256(new byte[]{4});

            byte[] ab = MerklePatriciaForestry.combine(a, b);
            byte[] cd = MerklePatriciaForestry.combine(c, d);
            byte[] root = MerklePatriciaForestry.combine(ab, cd);

            assertArrayEquals(root, MerklePatriciaForestry.merkle4(0, a, cd, b));
            assertArrayEquals(root, MerklePatriciaForestry.merkle4(1, b, cd, a));
            assertArrayEquals(root, MerklePatriciaForestry.merkle4(2, c, ab, d));
            assertArrayEquals(root, MerklePatriciaForestry.merkle4(3, d, ab, c));
        }
    }

    @Nested
    class BitcoinBlockProofTests {

        // Test data from merkle-patricia-forestry.tests.ak
        static final byte[] ROOT = hex("225a4599b804ba53745538c83bfa699ecf8077201b61484c91171f5910a4a8f9");
        static final byte[] BLOCK_HASH = hex("00000000000000000002d79d6d49c114e174c22b8d8432432ce45a05fd6a4d7b");
        static final byte[] BLOCK_BODY = hex("f48fcceeac43babbf53a90023be2799a9d7617098b76ff229440ccbd1fd1b4d4");

        static final JulcList<ProofStep> PROOF_845999 = JulcList.of(
                new ProofStep.Branch(0, hex(
                        "bc13df27a19f8caf0bf922c900424025282a892ba8577095fd35256c9d553ca1" +
                        "3a589f00f97a417d07903d138b92f25f879f9462994bf0e69b51fa19a67faef9" +
                        "96c3f8196278c6ab196979911cc48b2d4a0d2a7aa5ef3f939eb056256d8efdfa" +
                        "0aa456963256af4fcb1ad43ef4e6323d1ca92c6d83ed4327904280228e1ba159")),
                new ProofStep.Branch(0, hex(
                        "eb63f921bd3ac576f979eba32490f8c0988f468d3308c2ed5480aaf6ff27cf9a" +
                        "0e610d8c38c17236104b995eb83aa062181525dccd72a755772004cc2bf4faaf" +
                        "3ac3518525f4b5dec498c8034c566a3539e524c6a2cd5fc8f19c6559a3226051" +
                        "3edca31960cd1f5cc6882b820ef57ca65d740734379781db22b479ae0e3bdef3")),
                new ProofStep.Branch(0, hex(
                        "e7bbc4fc5e5875f6f5469e8a016fa99a872075360e64d623f8b8688e6b63fee5" +
                        "091a7260d2a4fe1ca489c48020772e6d334c63115743e7c390450a139c6bc63b" +
                        "219aff62993846b5522bc1b1fffb5b485fc58d952a8f171bb6a000062fbdcb0e" +
                        "aa5637413d82489f0492c663ad0bac0a2a83b32e1b14e3940017cf830d47378e")),
                new ProofStep.Branch(0, hex(
                        "464f4d2211c7fe6e7e1b298be6cfa6fd35d562d3b37ce8b979df45fac9dbc5e0" +
                        "d4d93d0b14d7061351763cee1d878b8686c658cfca7ef69cfd58d50ffc3a4673" +
                        "40c3abc4067220f82f2dffe455038da3138859bffdb3d34fd7e84305de2ddfc6" +
                        "1630c97424469f6de887d42ca155069789fa1b843bdf26496d29222f33f8f6ae")),
                new ProofStep.Branch(0, hex(
                        "2170e155c04db534b1f0e27bb7604907d26b046e51dd7ca59f56693e8033b164" +
                        "03f9ff21fe66b6071042d35dcbad83950ffb1e3a2ad6673f96d043f67d58e820" +
                        "40e0c17f6230c44b857ed04dccd8ff1b84819abf26fa9e1e86d61fb08c80b74c" +
                        "0000000000000000000000000000000000000000000000000000000000000000"))
        );

        @Test
        void verify_bitcoin_block_845999_has() {
            boolean result = MerklePatriciaForestry.has(ROOT, BLOCK_HASH, BLOCK_BODY, PROOF_845999);
            assertTrue(result, "Bitcoin block 845999 proof should verify as present in the trie");
        }

        @Test
        void verify_wrong_value_fails() {
            byte[] wrongBody = hex("0000000000000000000000000000000000000000000000000000000000000000");
            boolean result = MerklePatriciaForestry.has(ROOT, BLOCK_HASH, wrongBody, PROOF_845999);
            assertFalse(result, "Wrong value should not verify");
        }

        @Test
        void verify_wrong_key_fails() {
            byte[] wrongKey = hex("0000000000000000000000000000000000000000000000000000000000000001");
            boolean result = MerklePatriciaForestry.has(ROOT, wrongKey, BLOCK_BODY, PROOF_845999);
            assertFalse(result, "Wrong key should not verify");
        }

        @Test
        void verify_wrong_root_fails() {
            byte[] wrongRoot = hex("0000000000000000000000000000000000000000000000000000000000000000");
            boolean result = MerklePatriciaForestry.has(wrongRoot, BLOCK_HASH, BLOCK_BODY, PROOF_845999);
            assertFalse(result, "Wrong root should not verify");
        }
    }

    @Nested
    class MissProofTests {

        // For miss proof, we use the insert test from Aiken.
        // In insert_bitcoin_block_845602, before insertion the key is NOT in the trie.
        // So excluding(block_hash_845602) should equal the original root.
        static final byte[] ROOT = hex("225a4599b804ba53745538c83bfa699ecf8077201b61484c91171f5910a4a8f9");
        static final byte[] BLOCK_HASH_845602 = hex("0000000000000000000261a131bf48cc5a19658ade8cfede99dc1c3933300d60");

        static final JulcList<ProofStep> PROOF_845602 = JulcList.of(
                new ProofStep.Branch(0, hex(
                        "bc13df27a19f8caf0bf922c900424025282a892ba8577095fd35256c9d553ca1" +
                        "20b8645121ebc9057f7b28fa4c0032b1f49e616dfb8dbd88e4bffd7c0844d29b" +
                        "011b1af0993ac88158342583053094590c66847acd7890c86f6de0fde0f7ae24" +
                        "79eafca17f9659f252fa13ee353c879373a65ca371093525cf359fae1704cf4a")),
                new ProofStep.Branch(0, hex(
                        "255753863960985679b4e752d4b133322ff567d210ffbb10ee56e51177db0574" +
                        "60b547fe42c6f44dfef8b3ecee35dfd4aa105d28b94778a3f1bb8211cf2679d7" +
                        "434b40848aebdd6565b59efdc781ffb5ca8a9f2b29f95a47d0bf01a09c38fa39" +
                        "359515ddb9d2d37a26bccb022968ef4c8e29a95c7c82edcbe561332ff79a51af")),
                new ProofStep.Branch(0, hex(
                        "9d95e34e6f74b59d4ea69943d2759c01fe9f986ff0c03c9e25ab561b23a413b7" +
                        "7792fa78d9fbcb98922a4eed2df0ed70a2852ae8dbac8cff54b9024f229e6662" +
                        "9136cfa60a569c464503a8b7779cb4a632ae052521750212848d1cc0ebed406e" +
                        "1ba4876c4fd168988c8fe9e226ed283f4d5f17134e811c3b5322bc9c494a598b")),
                new ProofStep.Branch(0, hex(
                        "b93c3b90e647f90beb9608aecf714e3fbafdb7f852cfebdbd8ff435df84a4116" +
                        "d10ccdbe4ea303efbf0f42f45d8dc4698c3890595be97e4b0f39001bde3f2ad9" +
                        "5b8f6f450b1e85d00dacbd732b0c5bc3e8c92fc13d43028777decb669060558" +
                        "821db21a9b01ba5ddf6932708cd96d45d41a1a4211412a46fe41870968389ec96")),
                new ProofStep.Branch(0, hex(
                        "f89f9d06b48ecc0e1ea2e6a43a9047e1ff02ecf9f79b357091ffc0a7104bbb26" +
                        "0908746f8e61ecc60dfe26b8d03bcc2f1318a2a95fa895e4d1aadbb917f9f293" +
                        "6b900c75ffe49081c265df9c7c329b9036a0efb46d5bac595a1dcb7c200e7d59" +
                        "0000000000000000000000000000000000000000000000000000000000000000"))
        );

        @Test
        void miss_block_845602_before_insert() {
            boolean result = MerklePatriciaForestry.miss(ROOT, BLOCK_HASH_845602, PROOF_845602);
            assertTrue(result, "Block 845602 should be absent from the trie (miss proof)");
        }

        @Test
        void miss_wrong_key_fails() {
            // A key that IS in the trie should not pass the miss proof
            byte[] blockHash845999 = hex("00000000000000000002d79d6d49c114e174c22b8d8432432ce45a05fd6a4d7b");
            boolean result = MerklePatriciaForestry.miss(ROOT, blockHash845999, PROOF_845602);
            assertFalse(result, "A different key should not pass with this miss proof");
        }
    }

    @Nested
    class IncludingExcludingTests {

        @Test
        void including_computes_root_for_single_element() {
            // For a trie with a single element, including should produce a deterministic root
            byte[] key = hex("deadbeef");
            byte[] value = hex("cafebabe");
            byte[] root = MerklePatriciaForestry.including(key, value, JulcList.of());
            assertNotNull(root);
            assertEquals(32, root.length);

            // Verify has() with empty proof for single-element trie
            assertTrue(MerklePatriciaForestry.has(root, key, value, JulcList.of()));
        }

        @Test
        void excluding_empty_proof_is_null_hash() {
            // Excluding with empty proof = nullHash (empty trie)
            byte[] key = hex("deadbeef");
            byte[] root = MerklePatriciaForestry.excluding(key, JulcList.of());
            assertArrayEquals(MerklePatriciaForestry.nullHash(), root);
        }

        @Test
        void miss_in_empty_trie() {
            // An empty trie (nullHash root) should report miss for any key
            byte[] emptyRoot = MerklePatriciaForestry.nullHash();
            byte[] key = hex("aabbccdd");
            assertTrue(MerklePatriciaForestry.miss(emptyRoot, key, JulcList.of()));
        }
    }

    // =========================================================================
    // Proof shape validation — forged, malformed and honest proofs
    // =========================================================================
    //
    // Tries are built with MpfTrie (cardano-client-lib) and node hashes with the library's
    // own functions. Every forged or malformed proof is checked twice: through the Java
    // executable stubs and through UPLC compiled from the library source (JulcEval), where
    // "returns false" and "fails" are distinguishable.

    static final byte[] VALUE = "v".getBytes();
    static final JulcEval UPLC = JulcEval.forClass(MerklePatriciaForestry.class);

    static byte[] h(String key) {
        return CryptoLib.blake2b_256(key.getBytes());
    }

    static byte[] hv() {
        return CryptoLib.blake2b_256(VALUE);
    }

    /** Hash of key's leaf (holding VALUE) when the leaf sits at {@code cursor}. */
    static byte[] leaf(String key, int cursor) {
        return MerklePatriciaForestry.combine(MerklePatriciaForestry.suffix(h(key), cursor), hv());
    }

    static MpfTrie trie(String... keys) {
        var t = new MpfTrie(new MapNodeStore());
        for (String k : keys) {
            t.put(k.getBytes(), VALUE);
        }
        return t;
    }

    static JulcList<ProofStep> proofOf(MpfTrie t, String key) {
        return toSteps(PlutusDataAdapter.fromClientLib(t.getProofPlutusData(key.getBytes()).orElseThrow()));
    }

    static JulcList<ProofStep> toSteps(PlutusData proofData) {
        var steps = new ArrayList<ProofStep>();
        for (var item : ((PlutusData.ListData) proofData).items()) {
            var c = (PlutusData.ConstrData) item;
            var f = c.fields();
            int skip = ((PlutusData.IntData) f.get(0)).value().intValueExact();
            switch (c.tag()) {
                case 0 -> steps.add(new ProofStep.Branch(skip, ((PlutusData.BytesData) f.get(1)).value()));
                case 1 -> {
                    var nf = ((PlutusData.ConstrData) f.get(1)).fields();
                    steps.add(new ProofStep.Fork(skip, new Neighbor(
                            ((PlutusData.IntData) nf.get(0)).value().intValueExact(),
                            ((PlutusData.BytesData) nf.get(1)).value(),
                            ((PlutusData.BytesData) nf.get(2)).value())));
                }
                case 2 -> steps.add(new ProofStep.Leaf(skip,
                        ((PlutusData.BytesData) f.get(1)).value(),
                        ((PlutusData.BytesData) f.get(2)).value()));
                default -> throw new IllegalArgumentException("Unknown proof step tag " + c.tag());
            }
        }
        return JulcList.of(steps.toArray(new ProofStep[0]));
    }

    static PlutusData toData(JulcList<ProofStep> proof) {
        var items = new ArrayList<PlutusData>();
        for (ProofStep step : proof) {
            if (step instanceof ProofStep.Branch b) {
                items.add(PlutusData.constr(0, PlutusData.integer(b.skip()), PlutusData.bytes(b.neighbors())));
            } else if (step instanceof ProofStep.Fork f) {
                Neighbor n = f.neighbor();
                items.add(PlutusData.constr(1, PlutusData.integer(f.skip()), PlutusData.constr(0,
                        PlutusData.integer(n.nibble()), PlutusData.bytes(n.prefix()), PlutusData.bytes(n.root()))));
            } else {
                ProofStep.Leaf l = (ProofStep.Leaf) step;
                items.add(PlutusData.constr(2, PlutusData.integer(l.skip()),
                        PlutusData.bytes(l.key()), PlutusData.bytes(l.value())));
            }
        }
        return PlutusData.list(items.toArray(new PlutusData[0]));
    }

    /** miss() through the Java stubs and through compiled UPLC; both must agree. */
    static boolean missBoth(byte[] root, String key, JulcList<ProofStep> proof) {
        boolean java = MerklePatriciaForestry.miss(root, key.getBytes(), proof);
        boolean uplc = UPLC.call("miss", PlutusData.bytes(root), PlutusData.bytes(key.getBytes()), toData(proof))
                .asBoolean();
        assertEquals(java, uplc, "Java and UPLC disagree on miss(" + key + ")");
        return java;
    }

    /** has() through the Java stubs and through compiled UPLC; both must agree. */
    static boolean hasBoth(byte[] root, String key, JulcList<ProofStep> proof) {
        boolean java = MerklePatriciaForestry.has(root, key.getBytes(), VALUE, proof);
        boolean uplc = UPLC.call("has", PlutusData.bytes(root), PlutusData.bytes(key.getBytes()),
                PlutusData.bytes(VALUE), toData(proof)).asBoolean();
        assertEquals(java, uplc, "Java and UPLC disagree on has(" + key + ")");
        return java;
    }

    /** First key "key-i" whose hashed path starts with the given byte. */
    static String keyWithFirstByte(int firstByte) {
        for (int i = 0; ; i++) {
            String k = "key-" + i;
            if (Byte.toUnsignedInt(h(k)[0]) == firstByte) {
                return k;
            }
        }
    }

    static byte[] withByte(byte[] bs, int index, int value) {
        byte[] copy = bs.clone();
        copy[index] = (byte) value;
        return copy;
    }

    @Nested
    class HashedKeyPrefixTests {

        @Test
        void hashed_keys_start_with_expected_bytes() {
            assertEquals(0xe1, Byte.toUnsignedInt(h("alice")[0]));
            assertEquals(0x87, Byte.toUnsignedInt(h("bob")[0]));
            assertEquals(0x0f, Byte.toUnsignedInt(h("carol")[0]));
            assertEquals(0x4d, Byte.toUnsignedInt(h("judy")[0]));
            assertEquals(0x4b, Byte.toUnsignedInt(h("mallory")[0]));
        }
    }

    @Nested
    class ForgedExclusionProofTests {

        @Test
        void forged_leaf_rebuilding_queried_key_is_rejected() {
            byte[] root = trie("alice").getRootHash();
            assertArrayEquals(leaf("alice", 0), root);

            var forged = JulcList.<ProofStep>of(new ProofStep.Leaf(0, h("alice"), hv()));
            assertFalse(missBoth(root, "alice", forged));
        }

        @Test
        void forged_terminal_leaf_after_honest_step_is_rejected() {
            byte[] root = trie("alice", "bob").getRootHash();
            var forged = JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, h("bob"), hv()),
                    new ProofStep.Leaf(0, h("alice"), hv()));
            assertFalse(missBoth(root, "alice", forged));
        }

        @Test
        void forged_lookalike_key_differing_before_cursor_is_rejected() {
            byte[] root = trie("alice", "bob").getRootHash();
            // 0xe1 -> 0x01 changes only nibble 0; suffix(key, 1) ignores it
            byte[] lookalike = withByte(h("alice"), 0, 0x01);
            assertArrayEquals(MerklePatriciaForestry.suffix(h("alice"), 1),
                    MerklePatriciaForestry.suffix(lookalike, 1));

            var forged = JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, h("bob"), hv()),
                    new ProofStep.Leaf(0, lookalike, hv()));
            assertFalse(missBoth(root, "alice", forged));
        }

        @Test
        void forged_fork_with_neighbor_on_queried_branch_is_rejected() {
            byte[] root = trie("judy", "mallory").getRootHash();
            byte[] children = MerklePatriciaForestry.sparseMerkle16(
                    0xd, leaf("judy", 2), 0xb, leaf("mallory", 2));
            assertArrayEquals(MerklePatriciaForestry.combine(new byte[]{4}, children), root);

            var forged = JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(4, new byte[0], children)));
            assertFalse(missBoth(root, "judy", forged));
            assertFalse(missBoth(root, "mallory", forged));
        }

        @Test
        void forged_fork_with_out_of_range_nibble_is_rejected() {
            byte[] root = trie("alice").getRootHash();
            // cons(0xff, H(alice)) is the leaf encoding suffix(H(alice), 0)
            var forged = JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(255, h("alice"), hv())));
            assertFalse(missBoth(root, "alice", forged));
        }
    }

    @Nested
    class MalformedProofTests {

        @Test
        void branch_with_wrong_neighbors_length_is_rejected() {
            MpfTrie t = trie("alice", "bob");
            byte[] root = t.getRootHash();
            var honest = proofOf(t, "carol");
            byte[] neighbors = ((ProofStep.Branch) honest.head()).neighbors();
            assertTrue(missBoth(root, "carol", honest));

            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(
                    new ProofStep.Branch(0, Arrays.copyOf(neighbors, 127)))));
            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(
                    new ProofStep.Branch(0, Arrays.copyOf(neighbors, 129)))));
            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(new ProofStep.Branch(0, new byte[0]))));
        }

        @Test
        void leaf_with_wrong_key_or_value_length_is_rejected() {
            MpfTrie t = trie("alice", "bob");
            byte[] root = t.getRootHash();
            assertTrue(hasBoth(root, "alice", JulcList.<ProofStep>of(new ProofStep.Leaf(0, h("bob"), hv()))));

            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, Arrays.copyOf(h("bob"), 31), hv()))));
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, Arrays.copyOf(h("bob"), 33), hv()))));
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, h("bob"), Arrays.copyOf(hv(), 31)))));
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, new byte[0], hv()))));
            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(
                    new ProofStep.Leaf(0, Arrays.copyOf(h("bob"), 31), hv()))));
        }

        @Test
        void fork_with_bad_neighbor_is_rejected() {
            byte[] root = trie("judy", "mallory", "alice").getRootHash();
            Neighbor n = judyMalloryNeighbor();
            assertTrue(hasBoth(root, "alice", JulcList.<ProofStep>of(new ProofStep.Fork(0, n))));

            // root not 32 bytes
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(n.nibble(), n.prefix(), Arrays.copyOf(n.root(), 31))))));
            // prefix byte 16 is not a nibble
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(n.nibble(), new byte[]{16}, n.root())))));
            // nibble out of range
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(16, n.prefix(), n.root())))));
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(-1, n.prefix(), n.root())))));
            // neighbor on the queried key's own branch (alice's nibble 0xe)
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(
                    new ProofStep.Fork(0, new Neighbor(0xe, n.prefix(), n.root())))));
        }

        @Test
        void including_fails_on_fork_neighbor_on_own_branch() {
            // including() skips the shape check; doFork itself must refuse this step
            Neighbor n = judyMalloryNeighbor();
            var bad = JulcList.<ProofStep>of(new ProofStep.Fork(0, new Neighbor(0xe, n.prefix(), n.root())));
            assertThrows(RuntimeException.class,
                    () -> MerklePatriciaForestry.including("alice".getBytes(), VALUE, bad));
            assertThrows(RuntimeException.class, () -> UPLC.call("including",
                    PlutusData.bytes("alice".getBytes()), PlutusData.bytes(VALUE), toData(bad)));
            // the honest neighbor still works through including()
            assertArrayEquals(trie("judy", "mallory", "alice").getRootHash(), MerklePatriciaForestry.including(
                    "alice".getBytes(), VALUE, JulcList.<ProofStep>of(new ProofStep.Fork(0, n))));
        }

        @Test
        void skip_out_of_range_is_rejected() {
            MpfTrie t = trie("alice", "bob");
            byte[] root = t.getRootHash();
            byte[] neighbors = ((ProofStep.Branch) proofOf(t, "carol").head()).neighbors();

            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(new ProofStep.Branch(64, neighbors))));
            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(new ProofStep.Branch(-1, neighbors))));
            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(
                    new ProofStep.Branch(Integer.MAX_VALUE, neighbors))));
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(new ProofStep.Leaf(64, h("bob"), hv()))));
            assertFalse(hasBoth(root, "alice", JulcList.<ProofStep>of(new ProofStep.Leaf(-1, h("bob"), hv()))));
            assertFalse(missBoth(root, "carol", JulcList.<ProofStep>of(
                    new ProofStep.Fork(70, new Neighbor(1, new byte[0], hv())))));
            // 63 single-nibble steps reach cursor 63; a step skipping 1 more lands on nibble 64
            var steps = new ArrayList<ProofStep>();
            for (int i = 0; i < 63; i++) {
                steps.add(new ProofStep.Branch(0, new byte[128]));
            }
            steps.add(new ProofStep.Branch(1, new byte[128]));
            assertFalse(missBoth(root, "carol", JulcList.of(steps.toArray(new ProofStep[0]))));
        }

        @Test
        void proof_deeper_than_path_is_rejected() {
            byte[] root = trie("alice", "bob").getRootHash();
            var steps = new ArrayList<ProofStep>();
            for (int i = 0; i < 65; i++) {
                steps.add(new ProofStep.Branch(0, new byte[128]));
            }
            var tooDeep = JulcList.of(steps.toArray(new ProofStep[0]));
            assertFalse(missBoth(root, "carol", tooDeep));
            assertFalse(hasBoth(root, "alice", tooDeep));
        }
    }

    /** isWellFormed() through the Java stubs and through compiled UPLC; both must agree. */
    static boolean wellFormedBoth(byte[] path, ProofStep... steps) {
        var proof = JulcList.of(steps);
        boolean java = MerklePatriciaForestry.isWellFormed(path, 0, proof);
        boolean uplc = UPLC.call("isWellFormed", PlutusData.bytes(path), PlutusData.integer(0), toData(proof))
                .asBoolean();
        assertEquals(java, uplc, "Java and UPLC disagree on isWellFormed");
        return java;
    }

    /** One rule at a time, on path H(alice) = e1..: each case differs from a valid step in one field. */
    @Nested
    class ShapeCheckTests {

        final byte[] path = h("alice");
        final byte[] h32 = hv();

        @Test
        void branch_rules() {
            assertTrue(wellFormedBoth(path, new ProofStep.Branch(0, new byte[128])));
            assertTrue(wellFormedBoth(path, new ProofStep.Branch(63, new byte[128])));
            assertFalse(wellFormedBoth(path, new ProofStep.Branch(0, new byte[127])));
            assertFalse(wellFormedBoth(path, new ProofStep.Branch(0, new byte[129])));
            assertFalse(wellFormedBoth(path, new ProofStep.Branch(-1, new byte[128])));
            assertFalse(wellFormedBoth(path, new ProofStep.Branch(64, new byte[128])));
        }

        @Test
        void fork_rules() {
            assertTrue(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(0, new byte[]{15, 0}, h32))));
            assertTrue(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(15, new byte[0], h32))));
            // the path's own nibble at the branch
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(0xe, new byte[0], h32))));
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(1, new Neighbor(0x1, new byte[0], h32))));
            // out-of-range nibble, with a prefix that passes the nibble-byte check
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(16, new byte[0], h32))));
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(255, new byte[0], h32))));
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(-1, new byte[0], h32))));
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(0, new Neighbor(0, new byte[]{15, 16}, h32))));
            assertFalse(wellFormedBoth(path,
                    new ProofStep.Fork(0, new Neighbor(0, new byte[0], Arrays.copyOf(h32, 31)))));
            assertFalse(wellFormedBoth(path,
                    new ProofStep.Fork(0, new Neighbor(0, new byte[0], Arrays.copyOf(h32, 33)))));
            assertFalse(wellFormedBoth(path, new ProofStep.Fork(64, new Neighbor(0, new byte[0], h32))));
        }

        @Test
        void leaf_rules() {
            byte[] differsAt0 = withByte(path, 0, 0x01);
            byte[] differsAt1 = withByte(path, 0, 0xe2);
            byte[] differsAt3 = withByte(path, 1, path[1] ^ 0x01);
            assertTrue(wellFormedBoth(path, new ProofStep.Leaf(0, differsAt0, h32)));
            assertTrue(wellFormedBoth(path, new ProofStep.Leaf(1, differsAt1, h32)));
            assertTrue(wellFormedBoth(path, new ProofStep.Leaf(3, differsAt3, h32)));
            // the path itself
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(0, path, h32)));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(5, path, h32)));
            // differs from the path, but before the branch nibble
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(1, differsAt0, h32)));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(3, differsAt1, h32)));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(4, differsAt3, h32)));
            // agrees with the path at the branch nibble
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(2, differsAt3, h32)));
            // differs at the branch nibble, but also before it
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(1, withByte(path, 0, 0x02), h32)));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(3, withByte(differsAt3, 0, 0x01), h32)));
            assertFalse(wellFormedBoth(path,
                    new ProofStep.Leaf(2, withByte(withByte(path, 0, 0xe2), 1, path[1] ^ 0x10), h32)));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(0, Arrays.copyOf(differsAt0, 31), h32)));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(0, differsAt0, Arrays.copyOf(h32, 31))));
            assertFalse(wellFormedBoth(path, new ProofStep.Leaf(64, differsAt0, h32)));
        }

        @Test
        void depth_rules() {
            ProofStep[] full = new ProofStep[64];
            Arrays.fill(full, new ProofStep.Branch(0, new byte[128]));
            assertTrue(wellFormedBoth(path, full));
            ProofStep[] tooDeep = Arrays.copyOf(full, 65);
            tooDeep[64] = new ProofStep.Branch(0, new byte[128]);
            assertFalse(wellFormedBoth(path, tooDeep));
            // rules apply to every step, not only the last
            assertFalse(wellFormedBoth(path,
                    new ProofStep.Leaf(0, path, h32),
                    new ProofStep.Branch(0, new byte[128])));
            assertFalse(wellFormedBoth(path,
                    new ProofStep.Branch(0, new byte[128]),
                    new ProofStep.Fork(0, new Neighbor(MerklePatriciaForestry.nibble(path, 1), new byte[0], h32)),
                    new ProofStep.Branch(0, new byte[128])));
        }
    }

    /** Branch node holding judy and mallory below the shared nibble 4, as a Fork neighbor. */
    static Neighbor judyMalloryNeighbor() {
        return new Neighbor(4, new byte[0],
                MerklePatriciaForestry.sparseMerkle16(0xd, leaf("judy", 2), 0xb, leaf("mallory", 2)));
    }

    /**
     * Honest proofs. Roots come from MpfTrie. Some Leaf and Fork proofs are written out from the
     * library's node functions to pin exact shapes; cardano-client-lib 0.8.0-pre7 (which includes
     * its PR 716) emits the same canonical Leaf and Fork steps, checked in
     * {@code ccl_fork_proofs_through_compiled_uplc}.
     */
    @Nested
    class HonestProofTests {

        @Test
        void honest_miss_with_branch_step() {
            MpfTrie t = trie("alice", "bob");
            var proof = proofOf(t, "carol");
            assertEquals(1, proof.size());
            assertInstanceOf(ProofStep.Branch.class, proof.head());
            assertTrue(missBoth(t.getRootHash(), "carol", proof));
            assertFalse(missBoth(t.getRootHash(), "alice", proof));
        }

        @Test
        void honest_leaf_proofs_for_two_keys() {
            byte[] root = trie("alice", "bob").getRootHash();
            assertTrue(hasBoth(root, "alice", JulcList.<ProofStep>of(new ProofStep.Leaf(0, h("bob"), hv()))));
            assertTrue(hasBoth(root, "bob", JulcList.<ProofStep>of(new ProofStep.Leaf(0, h("alice"), hv()))));
            // the same step is bob's exclusion proof for alice in {bob}
            assertTrue(missBoth(trie("bob").getRootHash(), "alice",
                    JulcList.<ProofStep>of(new ProofStep.Leaf(0, h("bob"), hv()))));
        }

        @Test
        void honest_terminal_leaf_with_skip() {
            // miss(mallory) in {judy}: judy's leaf one nibble below the shared prefix 4
            MpfTrie t = trie("judy");
            var proof = proofOf(t, "mallory");
            assertEquals(1, proof.size());
            var step = assertInstanceOf(ProofStep.Leaf.class, proof.head());
            assertEquals(1, step.skip());
            assertArrayEquals(h("judy"), step.key());
            assertTrue(missBoth(t.getRootHash(), "mallory", proof));
            // has(mallory) in {judy, mallory} uses the same step
            assertTrue(hasBoth(trie("judy", "mallory").getRootHash(), "mallory", proof));
        }

        @Test
        void honest_non_final_leaf_with_skip() {
            // judy (4df7..) and k4d (4d52..) share two nibbles; mallory (4b..) shares one.
            String k4d = keyWithFirstByte(0x4d);
            assertNotEquals(MerklePatriciaForestry.nibble(h("judy"), 2), MerklePatriciaForestry.nibble(h(k4d), 2));
            var proof = JulcList.<ProofStep>of(
                    new ProofStep.Leaf(1, h("mallory"), hv()),
                    new ProofStep.Leaf(0, h(k4d), hv()));

            assertTrue(hasBoth(trie("judy", "mallory", k4d).getRootHash(), "judy", proof));
            assertTrue(missBoth(trie("mallory", k4d).getRootHash(), "judy", proof));
            assertFalse(missBoth(trie("judy", "mallory", k4d).getRootHash(), "judy", proof));
        }

        @Test
        void honest_fork_with_skip() {
            // {judy, k4d} is one branch with prefix 4d; mallory forks off it after nibble 4
            String k4d = keyWithFirstByte(0x4d);
            byte[] children = MerklePatriciaForestry.sparseMerkle16(0xf, leaf("judy", 3), 0x5, leaf(k4d, 3));
            var proof = JulcList.<ProofStep>of(new ProofStep.Fork(1, new Neighbor(0xd, new byte[0], children)));

            assertTrue(missBoth(trie("judy", k4d).getRootHash(), "mallory", proof));
            assertTrue(hasBoth(trie("judy", k4d, "mallory").getRootHash(), "mallory", proof));
        }

        @Test
        void honest_fork_with_neighbor_prefix() {
            // alice forks off the {judy, k4d} branch, whose own prefix is the nibble d
            String k4d = keyWithFirstByte(0x4d);
            byte[] children = MerklePatriciaForestry.sparseMerkle16(0xf, leaf("judy", 3), 0x5, leaf(k4d, 3));
            var proof = JulcList.<ProofStep>of(new ProofStep.Fork(0, new Neighbor(4, new byte[]{0xd}, children)));

            assertTrue(hasBoth(trie("judy", k4d, "alice").getRootHash(), "alice", proof));
            assertTrue(missBoth(trie("judy", k4d).getRootHash(), "alice", proof));
        }

        @Test
        void honest_fork_for_judy_mallory_neighbor() {
            var proof = JulcList.<ProofStep>of(new ProofStep.Fork(0, judyMalloryNeighbor()));
            assertTrue(hasBoth(trie("judy", "mallory", "alice").getRootHash(), "alice", proof));
            assertTrue(missBoth(trie("judy", "mallory").getRootHash(), "alice", proof));
        }

        @Test
        void honest_proofs_for_larger_trie() {
            var keys = new ArrayList<String>();
            for (int i = 0; i < 64; i++) {
                keys.add("member-" + i);
            }
            MpfTrie t = trie(keys.toArray(new String[0]));
            byte[] root = t.getRootHash();
            for (String k : keys) {
                var proof = proofOf(t, k);
                assertTrue(MerklePatriciaForestry.has(root, k.getBytes(), VALUE, proof), "has " + k);
                assertFalse(MerklePatriciaForestry.miss(root, k.getBytes(), proof), "miss " + k);
            }
            for (int i = 0; i < 32; i++) {
                String k = "absent-" + i;
                var proof = proofOf(t, k);
                assertTrue(MerklePatriciaForestry.miss(root, k.getBytes(), proof), "miss " + k);
                assertFalse(MerklePatriciaForestry.has(root, k.getBytes(), VALUE, proof), "has " + k);
            }
            assertTrue(hasBoth(root, keys.get(7), proofOf(t, keys.get(7))));
            assertTrue(missBoth(root, "absent-3", proofOf(t, "absent-3")));
        }

        /**
         * Random keys rarely give Fork steps, and the settlement shards insert with whatever
         * MpfTrie emits. Two keys under the shared prefix 4d72 form one branch with a prefix;
         * keys leaving it after 0, 1 and 2 nibbles get a single terminal Fork whose neighbor
         * keeps the rest of that prefix. The same proof is the key's exclusion proof in the
         * pair and its inclusion and insert proof once added.
         */
        @Test
        void ccl_fork_proofs_through_compiled_uplc() {
            List<String> pair = keysWithNibbles("pair", 2, 4, 0xd, 7, 2);
            assertNotEquals(MerklePatriciaForestry.nibble(h(pair.get(0)), 4),
                    MerklePatriciaForestry.nibble(h(pair.get(1)), 4));
            byte[] pairRoot = trie(pair.get(0), pair.get(1)).getRootHash();
            String[] probes = {
                    keysWithNibbles("skip0", 1, 1).get(0),
                    keysWithNibbles("skip1", 1, 4, 0xe).get(0),
                    keysWithNibbles("skip2", 1, 4, 0xd, 8).get(0)};
            int[] neighborNibbles = {4, 0xd, 7};
            byte[][] neighborPrefixes = {{0xd, 7, 2}, {7, 2}, {2}};
            for (int skip = 0; skip < 3; skip++) {
                String key = probes[skip];
                var proof = proofOf(trie(pair.get(0), pair.get(1)), key);
                assertEquals(1, proof.size());
                var fork = assertInstanceOf(ProofStep.Fork.class, proof.head());
                assertEquals(skip, fork.skip());
                assertEquals(neighborNibbles[skip], fork.neighbor().nibble());
                assertArrayEquals(neighborPrefixes[skip], fork.neighbor().prefix());

                byte[] withKey = trie(pair.get(0), pair.get(1), key).getRootHash();
                assertTrue(missBoth(pairRoot, key, proof));
                assertTrue(hasBoth(withKey, key, proof));
                assertArrayEquals(withKey, UPLC.call("including", PlutusData.bytes(key.getBytes()),
                        PlutusData.bytes(VALUE), toData(proof)).asByteString());
            }
        }
    }

    /** The first count keys "tag-i" whose hashed path starts with the given nibbles. */
    static List<String> keysWithNibbles(String tag, int count, int... nibbles) {
        var keys = new ArrayList<String>();
        for (int i = 0; keys.size() < count; i++) {
            String k = tag + "-" + i;
            byte[] path = h(k);
            boolean match = true;
            for (int n = 0; n < nibbles.length && match; n++) {
                match = MerklePatriciaForestry.nibble(path, n) == nibbles[n];
            }
            if (match) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        @Override public byte[] get(byte[] hash) {
            return nodes.get(HEX.formatHex(hash));
        }
        @Override public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HEX.formatHex(hash), nodeBytes);
        }
        @Override public void delete(byte[] hash) {
            nodes.remove(HEX.formatHex(hash));
        }
    }
}
