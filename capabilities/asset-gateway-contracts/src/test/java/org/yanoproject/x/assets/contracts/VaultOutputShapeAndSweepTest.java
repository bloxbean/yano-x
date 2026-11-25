package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shapes are classified by decoded Plutus Data (as the vault script sees them); the sweep commitment is canonical. */
class VaultOutputShapeAndSweepTest {
    private static final String ID = "ab".repeat(32);

    private static VaultOutputShape shape(String hex) {
        return VaultOutputShape.classify(HexFormat.of().parseHex(hex));
    }

    @Test
    void everyPlutusSerializationOfAShapeClassifiesTheSame() {
        // FloorTopUpV1 = Constr 2 []: compact, indefinite, general form 102, non-minimal tag head.
        for (String floor : new String[] {"d87b80", "d87b9fff", "d866820280", "d86682029fff", "d9007b80",
                "d8668218029fff"}) {
            assertThat(shape(floor)).as(floor).isEqualTo(VaultOutputShape.FLOOR_TOP_UP);
        }
        // VaultContinuationV1([id]): compact, indefinite lists, general form, chunked and non-minimal byte strings.
        for (String continuation : new String[] {"d87a81815820" + ID, "d87a9f9f5820" + ID + "ffff",
                "d86682018181" + "5820" + ID, "d87a8181" + "5f5810" + ID.substring(0, 32) + "5810"
                + ID.substring(32) + "ff", "d87a8181" + "590020" + ID, "d87a8180", "d87a9f80ff"}) {
            assertThat(shape(continuation)).as(continuation).isEqualTo(VaultOutputShape.CONTINUATION);
        }
        // A general-form constructor 0 is deposit-shaped; its fields are judged by DepositDatumV1 (MALFORMED).
        assertThat(shape("d8668200" + "80")).isEqualTo(VaultOutputShape.DEPOSIT);
        // Constructor 7+ uses tag 1280+.
        assertThat(shape("d9050080")).isEqualTo(VaultOutputShape.OTHER);
    }

    @Test
    void lookAlikesAndNonDataAreOther() {
        for (String other : new String[] {"d87b8100", "d87a80", "d87a8281805820" + ID, "d87a81815819" + "00".repeat(25),
                "d87a8181d87980", "d87d80", "00", "4100", "d87b", "d87b80ff", "d87a83", "c24100", ""}) {
            assertThat(shape(other)).as(other).isEqualTo(VaultOutputShape.OTHER);
        }
        assertThat(VaultOutputShape.classify(null)).isEqualTo(VaultOutputShape.OTHER);
        // An undecodable compact constructor-0 datum is still a (malformed) deposit attempt.
        assertThat(shape("d87983")).isEqualTo(VaultOutputShape.DEPOSIT);
        assertThat(shape("d879" + "81".repeat(200) + "80")).isEqualTo(VaultOutputShape.DEPOSIT);
        StringBuilder sixtyFive = new StringBuilder("d87a8198" + "41");
        sixtyFive.append(("5820" + ID).repeat(65));
        assertThat(shape(sixtyFive.toString())).isEqualTo(VaultOutputShape.OTHER);
        StringBuilder sixtyFour = new StringBuilder("d87a8198" + "40");
        sixtyFour.append(("5820" + ID).repeat(64));
        assertThat(shape(sixtyFour.toString())).isEqualTo(VaultOutputShape.CONTINUATION);
    }

    @Test
    void sweepPaidRoundTripsInAnySerialization() {
        Hash32 id = Hash32.ofHex(ID);
        byte[] encoded = new SweepPaidV1(id).encode();
        assertThat(HexFormat.of().formatHex(encoded)).isEqualTo("d87d815820" + ID);
        assertThat(SweepPaidV1.decode(encoded)).contains(new SweepPaidV1(id));
        assertThat(SweepPaidV1.decode(HexFormat.of().parseHex("d86682049f5820" + ID + "ff")))
                .contains(new SweepPaidV1(id));
        assertThat(SweepPaidV1.decode(HexFormat.of().parseHex("d87d81581f" + ID.substring(2)))).isEmpty();
        assertThat(VaultOutputShape.classify(encoded)).isEqualTo(VaultOutputShape.OTHER);
    }

    @Test
    void sweepCommitmentHoldsTheFullValue() {
        AssetId usdm = AssetId.of(new byte[28], "USDM".getBytes());
        AssetId foreign = AssetId.of(filled(0x11), new byte[] {1});
        Hash32 tx = Hash32.ofHex("cd".repeat(32));
        SweepCommitment token = SweepCommitment.of(usdm, tx, 2, BigInteger.valueOf(3_000_000),
                Map.of(usdm, BigInteger.valueOf(40), foreign, BigInteger.valueOf(5)));
        assertThat(token.withdrawalKey()).isEqualTo(GatewayIds.outpointCbor(tx, 2));
        assertThat(token.vaultLovelace()).isEqualTo(BigInteger.valueOf(3_000_000));
        assertThat(token.value()).isEqualTo(AssetValue.of(Map.of(usdm, BigInteger.valueOf(40), foreign,
                BigInteger.valueOf(5))));

        SweepCommitment ada = SweepCommitment.of(AssetId.ADA, tx, 4, BigInteger.valueOf(2_500_000),
                Map.of(foreign, BigInteger.valueOf(5)));
        assertThat(ada.vaultLovelace()).isZero();
        assertThat(ada.value()).isEqualTo(AssetValue.of(Map.of(AssetId.ADA, BigInteger.valueOf(2_500_000), foreign,
                BigInteger.valueOf(5))));

        Map<AssetId, BigInteger> seventeen = new HashMap<>();
        for (int i = 0; i < 17; i++) {
            seventeen.put(AssetId.of(filled(0x20 + i), new byte[0]), BigInteger.ONE);
        }
        assertThatThrownBy(() -> SweepCommitment.of(usdm, tx, 0, BigInteger.ONE, seventeen))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SweepCommitment.of(usdm, tx, 0, BigInteger.ONE,
                Map.of(AssetId.ADA, BigInteger.ONE))).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] filled(int value) {
        byte[] out = new byte[28];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }
}
