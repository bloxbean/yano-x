package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D10: a {@code BalanceExit} holder is read from the redeemer's prefix, as the vault validator reads its fields by
 * position, so fields after the seventh, of any shape or depth, never hide it.
 */
class GatewayDatumsTest {
    private static final String HOLDER = "46".repeat(28);
    /** Fields 1..6 after the holder: key hash, amount 40, vault lovelace 1000000, recipient, empty proof, shard 3. */
    private static final String REST = "581c" + "a1".repeat(28) + "1828" + "1a000f4240" + "581d60" + "a1".repeat(28)
            + "d87984404040" + "80" + "03";
    /** {@code Constr 7} as compact tag 1280. */
    private static final String CONSTR_7 = "d90500";

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static String holderField() {
        return "581c" + HOLDER;
    }

    @Test
    void aBalanceExitHolderIsReadFromThePrefixTheValidatorReads() {
        String seven = CONSTR_7 + "87" + holderField() + REST;
        String eight = CONSTR_7 + "88" + holderField() + REST + "00";
        String deep = CONSTR_7 + "88" + holderField() + REST + "81".repeat(70) + "00";
        String indefinite = CONSTR_7 + "9f" + holderField() + REST + "00" + "ff";
        String general = "d866" + "82" + "07" + "88" + holderField() + REST + "00";
        String chunked = CONSTR_7 + "88" + "5f" + "4e" + "46".repeat(14) + "4e" + "46".repeat(14) + "ff" + REST + "00";
        for (String redeemer : new String[] {seven, eight, deep, indefinite, general, chunked}) {
            assertThat(GatewayDatums.balanceExitHolderId(hex(redeemer))).as(redeemer)
                    .hasValueSatisfying(holder -> assertThat(holder).isEqualTo(hex(HOLDER)));
        }
        // The full bounded decode rejects the deep one; the prefix read does not depend on it.
        assertThat(PlutusDataReader.read(hex(deep))).isNull();
    }

    @Test
    void otherShapesNameNoHolder() {
        String[] others = {
                "d87988" + holderField() + REST + "00",            // constructor 0 (Settle-like)
                "d90501" + "88" + holderField() + REST + "00",     // constructor 8
                CONSTR_7 + "80",                                   // no fields
                CONSTR_7 + "9fff",                                 // no fields, indefinite
                CONSTR_7 + "88" + "00" + REST + "00",              // first field an integer
                CONSTR_7 + "88" + "40" + REST + "00",              // empty holder
                CONSTR_7 + "81" + "5841" + "46".repeat(65),        // 65-byte holder
                CONSTR_7 + "81" + "581c" + "46".repeat(27),        // truncated holder
                "581c" + HOLDER,                                   // not a constructor
                ""};
        for (String redeemer : others) {
            assertThat(GatewayDatums.balanceExitHolderId(hex(redeemer))).as(redeemer).isEmpty();
        }
        assertThat(GatewayDatums.balanceExitHolderId(null)).isEmpty();
    }
}
