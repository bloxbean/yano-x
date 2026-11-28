package org.yanoproject.x.proofs.onchain;

import org.julclang.core.PlutusData;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 framework addition 1 and D8h §8, evaluated as compiled UPLC: {@code acceptedAnchorState} reads v1 and v2
 * anchor datums, returns height, members, threshold and {@code updatedAtMs}, and rejects a member list that is not
 * strictly ascending 32-byte keys or a threshold outside {@code 1..count}. {@code acceptedAnchor} keeps reading v1
 * only.
 */
class MpfAnchorStateTest extends ContractTest {
    private static final byte[] GENESIS = filled(0x33, 32);
    private static final byte[] APPLICATION = "asset-payments".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROFILE = "mpf-blake2b256-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FINGERPRINT = filled(0x44, 32);
    private static final byte[] ROOT = filled(0x55, 32);
    private static final List<byte[]> MEMBERS = List.of(filled(0x11, 32), filled(0x22, 32), filled(0x33, 32));
    private static JulcEval eval;

    @BeforeAll
    static void setUp() {
        initCrypto();
        eval = JulcEval.forClass(MpfAnchorLib.class);
    }

    @Test
    void readsAV2DatumWithItsHeightMembersThresholdAndUpdatedAt() {
        Optional<PlutusData> state = state(datum(2, MEMBERS, 2, 1_700_000_000_000L, false));
        assertThat(state).isPresent();
        List<PlutusData> fields = fields(state.get());
        assertThat(fields).hasSize(7);
        assertThat(integer(fields.get(0))).isEqualTo(2);
        assertThat(integer(fields.get(1))).isEqualTo(42);
        assertThat(((PlutusData.BytesData) fields.get(2)).value()).isEqualTo(ROOT);
        assertThat(((PlutusData.ListData) fields.get(3)).items()).hasSize(3);
        assertThat(integer(fields.get(4))).isEqualTo(3);
        assertThat(integer(fields.get(5))).isEqualTo(2);
        assertThat(integer(fields.get(6))).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void readsAV1DatumWithoutUpdatedAt() {
        Optional<PlutusData> state = state(datum(1, MEMBERS, 3, -1, false));
        assertThat(state).isPresent();
        List<PlutusData> fields = fields(state.get());
        assertThat(integer(fields.get(0))).isEqualTo(1);
        assertThat(integer(fields.get(6))).isEqualTo(-1);
    }

    @Test
    void rejectsMalformedMembershipAndShapes() {
        byte[] first = MEMBERS.get(0);
        byte[] second = MEMBERS.get(1);
        assertThat(state(datum(2, List.of(first, first), 1, 5L, false))).as("duplicate key").isEmpty();
        assertThat(state(datum(2, List.of(second, first), 1, 5L, false))).as("descending keys").isEmpty();
        assertThat(state(datum(2, List.of(first, filled(0x66, 31)), 1, 5L, false))).as("31-byte key").isEmpty();
        assertThat(state(datum(2, List.of(), 1, 5L, false))).as("no members").isEmpty();
        assertThat(state(datum(2, MEMBERS, 0, 5L, false))).as("threshold 0").isEmpty();
        assertThat(state(datum(2, MEMBERS, 4, 5L, false))).as("threshold above count").isEmpty();
        assertThat(state(datum(2, MEMBERS, 2, -5L, false))).as("negative updatedAt").isEmpty();
        assertThat(state(datum(2, MEMBERS, 2, 5L, true))).as("trailing field").isEmpty();
        assertThat(state(datum(1, MEMBERS, 2, -1, true))).as("v1 with a 12th field").isEmpty();
        assertThat(state(datum(3, MEMBERS, 2, 5L, false))).as("unknown version").isEmpty();
    }

    @Test
    void rejectsAWrongCommitmentIdentity() {
        PlutusData datum = datum(2, MEMBERS, 2, 5L, false);
        assertThat(eval.call("anchorStateData", datum, filled(0x34, 32), APPLICATION, PROFILE, FINGERPRINT)
                .asOptional()).isEmpty();
        assertThat(eval.call("anchorStateData", datum, GENESIS, APPLICATION, PROFILE, filled(0x45, 32))
                .asOptional()).isEmpty();
    }

    @Test
    void theV1OnlyEntryPointStillRejectsAV2Datum() {
        assertThat(eval.call("anchorData", datum(1, MEMBERS, 2, -1, false), GENESIS, APPLICATION, PROFILE,
                FINGERPRINT).asOptional()).isPresent();
        assertThat(eval.call("anchorData", datum(2, MEMBERS, 2, 5L, false), GENESIS, APPLICATION, PROFILE,
                FINGERPRINT).asOptional()).isEmpty();
    }

    private static Optional<PlutusData> state(PlutusData datum) {
        return eval.call("anchorStateData", datum, GENESIS, APPLICATION, PROFILE, FINGERPRINT).asOptional();
    }

    /** {@code Constr(0, [version, chain, genesis, app, profile, fingerprint, 42, hash, root, keys, t, (updatedAt)])}. */
    static PlutusData datum(int version, List<byte[]> members, int threshold, long updatedAtMs, boolean trailing) {
        List<PlutusData> keys = new ArrayList<>();
        for (byte[] key : members) {
            keys.add(PlutusData.bytes(key));
        }
        List<PlutusData> fields = new ArrayList<>(List.of(
                PlutusData.integer(version),
                PlutusData.bytes("payments".getBytes(StandardCharsets.US_ASCII)),
                PlutusData.bytes(GENESIS),
                PlutusData.bytes(APPLICATION),
                PlutusData.bytes(PROFILE),
                PlutusData.bytes(FINGERPRINT),
                PlutusData.integer(42),
                PlutusData.bytes(filled(0x66, 32)),
                PlutusData.bytes(ROOT),
                PlutusData.list(keys.toArray(new PlutusData[0])),
                PlutusData.integer(threshold)));
        if (version != 1) {
            fields.add(PlutusData.integer(updatedAtMs));
        }
        if (trailing) {
            fields.add(PlutusData.integer(0));
        }
        return PlutusData.constr(0, fields.toArray(new PlutusData[0]));
    }

    private static List<PlutusData> fields(PlutusData data) {
        return ((PlutusData.ConstrData) data).fields();
    }

    private static long integer(PlutusData data) {
        return ((PlutusData.IntData) data).value().longValueExact();
    }

    private static byte[] filled(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }
}
