package org.yanoproject.x.framework.cardano.api.anchor;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnchorDatumV2Test {

    @Test
    void pinsGoldenAbiAsV1FieldsWithVersionTwoAndAppendedUpdatedAt() {
        AnchorDatumV2 datum = datum(12, 1_700_000_000_000L);
        String encoded = HexFormat.of().formatHex(datum.encode());

        // The v1 golden bytes with version 2 and updated-at-ms appended before the break.
        assertThat(encoded).isEqualTo("d8799f024e65766964656e63652d636861696e5820"
                + "31".repeat(32)
                + "4b6f7264657265642d6c6f67516d70662d626c616b6532623235362d76315820"
                + "32".repeat(32) + "0c5820"
                + "1111111111111111111111111111111111111111111111111111111111111111"
                + "58202222222222222222222222222222222222222222222222222222222222222222"
                + "9f58200101010101010101010101010101010101010101010101010101010101010101"
                + "58200202020202020202020202020202020202020202020202020202020202020202"
                + "58200303030303030303030303030303030303030303030303030303030303030303"
                + "ff02"
                + "1b0000018bcfe56800"
                + "ff");
        AnchorDatumV2 decoded = AnchorDatumV2.decode(datum.encode());
        assertThat(decoded).isEqualTo(datum);
        assertThat(decoded.updatedAtMs()).isEqualTo(1_700_000_000_000L);
        assertThat(decoded.height()).isEqualTo(12);
        assertThat(decoded.threshold()).isEqualTo(2);
        assertThat(AnchorDatumV2.versionOf(datum.encode())).isEqualTo(2);
        assertThat(AnchorDatumV2.versionOf(datum.base().encode())).isEqualTo(1);
        assertThat(AnchorDatumV2.versionOf(new byte[] {1, 2})).isEqualTo(-1);
    }

    @Test
    void v1AndV2CodecsRejectEachOthersBytes() {
        AnchorDatumV2 datum = datum(3, 5);
        assertThatThrownBy(() -> AnchorDatumV1.decode(datum.encode()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnchorDatumV2.decode(datum.base().encode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2");
    }

    @Test
    void rejectsNegativeUpdatedAtWrongVersionExtraFieldsAndNonCanonicalBytes() {
        assertThatThrownBy(() -> datum(1, -1)).isInstanceOf(IllegalArgumentException.class);

        List<PlutusData> fields = fields(datum(1, 5));
        fields.set(0, BigIntPlutusData.of(3));
        assertThatThrownBy(() -> AnchorDatumV2.decode(encode(fields)))
                .isInstanceOf(IllegalArgumentException.class);

        List<PlutusData> negative = fields(datum(1, 5));
        negative.set(11, BigIntPlutusData.of(-1));
        assertThatThrownBy(() -> AnchorDatumV2.decode(encode(negative)))
                .isInstanceOf(IllegalArgumentException.class);

        List<PlutusData> extra = fields(datum(1, 5));
        extra.add(BigIntPlutusData.of(9));
        assertThatThrownBy(() -> AnchorDatumV2.decode(encode(extra)))
                .isInstanceOf(IllegalArgumentException.class);

        List<PlutusData> unsorted = fields(datum(1, 5));
        List<PlutusData> members = new ArrayList<>(((ListPlutusData) unsorted.get(9)).getPlutusDataList());
        java.util.Collections.reverse(members);
        ListPlutusData reversed = ListPlutusData.builder().build();
        members.forEach(reversed::add);
        unsorted.set(9, reversed);
        assertThatThrownBy(() -> AnchorDatumV2.decode(encode(unsorted)))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] trailing = Arrays.copyOf(datum(1, 5).encode(), datum(1, 5).encode().length + 1);
        assertThatThrownBy(() -> AnchorDatumV2.decode(trailing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void heartbeatSuccessorRequiresSameStateAndStrictlyNewerTime() {
        AnchorDatumV2 current = datum(10, 1_000);
        assertThat(current.isHeartbeatSuccessor(datum(10, 1_001))).isTrue();
        assertThat(current.isHeartbeatSuccessor(datum(10, 1_000))).isFalse();
        assertThat(current.isHeartbeatSuccessor(datum(10, 999))).isFalse();
        assertThat(current.isHeartbeatSuccessor(datum(11, 2_000))).isFalse();
        AnchorDatumV2 otherRoot = new AnchorDatumV2("evidence-chain", filled(0x31), "ordered-log",
                "mpf-blake2b256-v1", filled(0x32), 10, filled(0x11), filled(0x23),
                List.of(filled(1), filled(2), filled(3)), 2, 2_000);
        assertThat(current.isHeartbeatSuccessor(otherRoot)).isFalse();
    }

    private static AnchorDatumV2 datum(long height, long updatedAtMs) {
        return new AnchorDatumV2("evidence-chain", filled(0x31), "ordered-log",
                "mpf-blake2b256-v1", filled(0x32), height, filled(0x11), filled(0x22),
                List.of(filled(3), filled(1), filled(2)), 2, updatedAtMs);
    }

    private static List<PlutusData> fields(AnchorDatumV2 datum) {
        try {
            return new ArrayList<>(((ConstrPlutusData) PlutusData.deserialize(datum.encode()))
                    .getData().getPlutusDataList());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] encode(List<PlutusData> fields) {
        ListPlutusData list = ListPlutusData.builder().build();
        fields.forEach(list::add);
        return ConstrPlutusData.builder().alternative(0).data(list).build().serializeToBytes();
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
