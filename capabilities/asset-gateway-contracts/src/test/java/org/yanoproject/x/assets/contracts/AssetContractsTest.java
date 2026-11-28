package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetContractsTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final String POLICY = AssetGatewayGoldenVectorGenerator.POLICY;

    @Test
    void adaIsTheUniqueEmptyPairAndSortsFirst() {
        assertThat(AssetId.of(new byte[0], new byte[0])).isSameAs(AssetId.ADA);
        assertThat(AssetId.ADA.compareTo(AssetId.ofHex(POLICY, ""))).isNegative();
    }

    @Test
    void anEmptyPolicyWithANameIsRejected() {
        assertThatThrownBy(() -> AssetId.of(new byte[0], new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyAndNameLengthsAreEnforced() {
        assertThatThrownBy(() -> AssetId.of(new byte[27], new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetId.of(new byte[28], new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AssetId.of(new byte[28], new byte[32]).assetName()).hasSize(32);
    }

    @Test
    void orderingIsLengthFirstLikeTheCanonicalEncoding() {
        AssetId ff = AssetId.ofHex(POLICY, "ff");
        AssetId zeroZero = AssetId.ofHex(POLICY, "0000");
        assertThat(ff.compareTo(zeroZero)).isNegative();
        TreeSet<AssetId> sorted = new TreeSet<>(List.of(zeroZero, ff, AssetId.ADA));
        assertThat(sorted).containsExactly(AssetId.ADA, ff, zeroZero);
    }

    @Test
    void assetIdRoundTripsAndRejectsNonCanonicalBytes() {
        AssetId usdm = AssetId.ofHex(POLICY, "5553444d");
        assertThat(AssetId.decode(usdm.encode())).isEqualTo(usdm);
        // 0x82 0x58 0x1c ... is canonical; a byte string length encoded in a wider head is not.
        byte[] canonical = usdm.encode();
        byte[] widened = new byte[canonical.length + 1];
        widened[0] = canonical[0];
        widened[1] = 0x59; // two-byte length head
        widened[2] = 0x00;
        System.arraycopy(canonical, 2, widened, 3, canonical.length - 2);
        assertThatThrownBy(() -> AssetId.decode(widened)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assetValueDropsZerosSortsAndBoundsEntries() {
        AssetId a = AssetId.ofHex(POLICY, "01");
        AssetId b = AssetId.ofHex(POLICY, "0000");
        AssetValue value = AssetValue.of(Map.of(b, BigInteger.TWO, a, BigInteger.ONE,
                AssetId.ADA, BigInteger.ZERO));
        assertThat(value.entries().keySet()).containsExactly(a, b);
        assertThat(AssetValue.decode(value.encode())).isEqualTo(value);

        java.util.HashMap<AssetId, BigInteger> tooMany = new java.util.HashMap<>();
        for (int i = 0; i < 17; i++) {
            tooMany.put(AssetId.of(new byte[28], new byte[] {(byte) i}), BigInteger.ONE);
        }
        assertThatThrownBy(() -> AssetValue.of(tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assetValueRejectsOutOfOrderZeroAndOversizedQuantities() {
        // [[policy, h'01', 1], [policy, h'00', 1]] is out of order (h'00' < h'01').
        String p = "581c" + POLICY;
        byte[] outOfOrder = HEX.parseHex("82" + "83" + p + "4101" + "01" + "83" + p + "4100" + "01");
        assertThatThrownBy(() -> AssetValue.decode(outOfOrder)).isInstanceOf(IllegalArgumentException.class);
        byte[] zero = HEX.parseHex("81" + "83" + p + "4101" + "00");
        assertThatThrownBy(() -> AssetValue.decode(zero)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetValue.lovelace(Quantities.MAX_U64.add(BigInteger.ONE)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void claimAndExitLeavesRoundTrip() {
        Hash32 vault = Hash32.of(new byte[32]);
        ClaimLeafV1 leaf = new ClaimLeafV1(Hash32.of(new byte[32]), vault, ClaimKind.USER_WITHDRAWAL,
                AssetValue.EMPTY, BigInteger.valueOf(1_500_000), new byte[] {0x61, 1}, 10, 20);
        assertThat(ClaimLeafV1.decode(leaf.encode())).isEqualTo(leaf);
        ExitLeafV1 exit = new ExitLeafV1(new byte[28], vault, BigInteger.TEN, BigInteger.ZERO);
        assertThat(ExitLeafV1.decode(exit.encode())).isEqualTo(exit);
        assertThatThrownBy(() -> new ClaimLeafV1(Hash32.of(new byte[32]), vault,
                ClaimKind.USER_WITHDRAWAL, AssetValue.EMPTY, BigInteger.ZERO, new byte[] {1}, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiersAreDomainSeparated() {
        Hash32 domain = GatewayIds.domainId(0, 1, "c", Hash32.of(new byte[32]), "a", "p", 1);
        Hash32 otherNetwork = GatewayIds.domainId(1, 1, "c", Hash32.of(new byte[32]), "a", "p", 1);
        assertThat(domain).isNotEqualTo(otherNetwork);
        Hash32 vaultA = GatewayIds.vaultId(domain, AssetId.ADA, 0);
        Hash32 vaultB = GatewayIds.vaultId(domain, AssetId.ofHex(POLICY, ""), 0);
        Hash32 tx = Hash32.of(new byte[32]);
        assertThat(GatewayIds.depositId(vaultA, tx, 0)).isNotEqualTo(GatewayIds.depositId(vaultB, tx, 0));
        assertThat(GatewayIds.depositId(vaultA, tx, 0)).isNotEqualTo(GatewayIds.depositId(vaultA, tx, 1));
    }

    @Test
    void checkedArithmeticNeverWraps() {
        assertThatThrownBy(() -> Quantities.addU64(Quantities.MAX_U64, BigInteger.ONE, "x"))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Quantities.subU64(BigInteger.ONE, BigInteger.TWO, "x"))
                .isInstanceOf(ArithmeticException.class);
        assertThat(Quantities.addU128(Quantities.MAX_U64, Quantities.MAX_U64, "x"))
                .isEqualTo(Quantities.MAX_U64.shiftLeft(1));
    }
}
