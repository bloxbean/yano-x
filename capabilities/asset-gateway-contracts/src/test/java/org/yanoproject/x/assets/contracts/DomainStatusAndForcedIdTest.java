package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainStatusAndForcedIdTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void domainStatusRoundTripsAndRejectsEverythingElse() {
        for (DomainStatusV1.Status status : DomainStatusV1.Status.values()) {
            DomainStatusV1 leaf = new DomainStatusV1(status, 7, 1_764_000_000_000L, 86_400_000L);
            assertThat(DomainStatusV1.decode(leaf.encode())).isEqualTo(leaf);
        }
        // [1, 0, 1, 2, 3] is valid; a version 2, an unknown status, a non-shortest integer and trailing bytes are not.
        assertThat(DomainStatusV1.decode(HEX.parseHex("850100010203")).status())
                .isEqualTo(DomainStatusV1.Status.ACTIVE);
        for (String invalid : new String[] {"850200010203", "850103010203", "8501001801010203", "85010001020300",
                "8401000102"}) {
            assertThatThrownBy(() -> DomainStatusV1.decode(HEX.parseHex(invalid))).as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new DomainStatusV1(DomainStatusV1.Status.PAUSED, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forcedRequestIdHashesTheTransactionInputCbor() {
        Hash32 tx = Hash32.ofHex("8d3f1c0e2b4a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0");
        assertThat(HEX.formatHex(GatewayIds.outpointCbor(tx, 300)))
                .isEqualTo("825820" + tx.hex() + "19012c");
        assertThat(HEX.formatHex(GatewayIds.outpointCbor(tx, 0))).isEqualTo("825820" + tx.hex() + "00");
        assertThat(GatewayIds.forcedRequestId(tx, 300)).isNotEqualTo(GatewayIds.forcedRequestId(tx, 301));
        assertThatThrownBy(() -> GatewayIds.forcedRequestId(tx, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
