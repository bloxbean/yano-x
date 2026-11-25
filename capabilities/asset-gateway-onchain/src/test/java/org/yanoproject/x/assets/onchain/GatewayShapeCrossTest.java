package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.VaultOutputShape;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-9: the observer ({@code VaultOutputShape.classify}) and the vault script ({@code acceptedDatum}) judge the same
 * datum bytes the same way. Each case decodes the bytes as the ledger would and puts the decoded datum on an
 * ADA-only vault input of an EXIT-mode {@code Consolidate}, whose lead check accepts it exactly when classify says
 * CONTINUATION or FLOOR_TOP_UP.
 */
class GatewayShapeCrossTest extends GatewayTestBase {
    private static final String ID = "ab".repeat(32);

    @Test
    void classifyAndTheValidatorAgreeOnEverySerialization() {
        List<String> cases = List.of(
                // FloorTopUpV1: compact, indefinite fields, general form 102, non-minimal tag head.
                "d87b80", "d87b9fff", "d866820280", "d86682029fff", "d9007b80",
                // VaultContinuationV1: compact, indefinite, general form, chunked id, non-minimal byte head.
                "d87a8180", "d87a9f80ff", "d87a81815820" + ID, "d87a9f9f5820" + ID + "ffff",
                "d86682018181" + "5820" + ID, "d87a8181" + "5f5810" + ID.substring(0, 32) + "5810"
                        + ID.substring(32) + "ff", "d87a8181" + "590020" + ID,
                // MigrationInV1 is classified but never accepted custody before M4b (B11).
                "d87c825820" + ID + "5820" + ID,
                // Look-alikes and other data.
                "d87b8100", "d87a80", "d87a8281805820" + ID, "d87a81815819" + "00".repeat(25), "d87a8181d87980",
                "d87d80", "d8798100", "00", "4100", "80", "a0");
        for (String hex : cases) {
            byte[] bytes = HexFormat.of().parseHex(hex);
            PlutusData datum = PlutusDataCborDecoder.decode(bytes);
            VaultOutputShape shape = VaultOutputShape.classify(bytes);
            boolean accepted = shape == VaultOutputShape.CONTINUATION || shape == VaultOutputShape.FLOOR_TOP_UP;
            if (hex.startsWith("d87c")) {
                assertThat(shape).isEqualTo(VaultOutputShape.MIGRATION_IN);
            }
            Tx tx = adaOnlySecond(datum);
            boolean valid = run(vault(TOKEN), tx.spending(tx.inputs.get(0).input().outRef())).isSuccess();
            assertThat(valid).as("%s classifies %s", hex, shape).isEqualTo(accepted);
        }
        // On chain a top-up is accepted custody only with lovelace alone (admission records a token-carrying one
        // UNATTRIBUTED(MIXED_ASSETS)); the shape itself is the same.
        Tx dirty = VaultConsolidateConformanceTest.consolidation(TOKEN, 2, VaultConsolidateConformanceTest.EXIT, true);
        Spent second = dirty.inputs.get(1);
        dirty.inputs.set(1, new Spent(vaultInput(second.input().outRef(), second.input().resolved().value(),
                floorDatum()), follow()));
        assertThat(run(vault(TOKEN), dirty.spending(dirty.inputs.get(0).input().outRef())).isSuccess()).isFalse();
        // 64 ids are accepted, 65 are not, by both.
        for (int count : new int[] {64, 65}) {
            String hex = "d87a8198" + String.format("%02x", count) + ("5820" + ID).repeat(count);
            byte[] bytes = HexFormat.of().parseHex(hex);
            Tx tx = adaOnlySecond(PlutusDataCborDecoder.decode(bytes));
            boolean valid = run(vault(TOKEN), tx.spending(tx.inputs.get(0).input().outRef())).isSuccess();
            assertThat(valid).as("%d ids", count).isEqualTo(count == 64);
            assertThat(VaultOutputShape.classify(bytes) == VaultOutputShape.CONTINUATION).isEqualTo(count == 64);
        }
    }

    /** A consolidation whose second input holds 1.5 ADA alone under {@code datum}. */
    private static Tx adaOnlySecond(PlutusData datum) {
        Tx tx = VaultConsolidateConformanceTest.consolidation(TOKEN, 2, VaultConsolidateConformanceTest.EXIT, true);
        Spent first = tx.inputs.get(0);
        Spent second = tx.inputs.get(1);
        tx.inputs.set(1, new Spent(vaultInput(second.input().outRef(), lovelace(1_500_000), datum), follow()));
        tx.outputs.set(1, out(vaultAddress(), first.input().resolved().value().merge(lovelace(1_500_000)),
                continuationDatum(List.of())));
        return tx;
    }
}
