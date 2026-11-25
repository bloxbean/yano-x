package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B7: only canonical pointer addresses are payable recipients or refund addresses. */
class CardanoAddressesTest {
    private static final String KEY = "1c".repeat(28);
    private static final String ACCOUNT = "a1".repeat(28);

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    @Test
    void canonicalPointersAndKeyAddressesArePayable() {
        // CIP-19 test vector pointer (2498243, 27, 3) = 81 98 3f 1b 03.
        assertThat(CardanoAddresses.payableRecipient(hex("41" + KEY + "81983f1b03"), 1)).isTrue();
        assertThat(CardanoAddresses.payableRecipient(hex("40" + KEY + "000000"), 0)).isTrue();
        assertThat(CardanoAddresses.payableRecipient(hex("60" + KEY), 0)).isTrue();
        assertThat(CardanoAddresses.payableRecipient(hex("00" + KEY + KEY), 0)).isTrue();
        assertThat(CardanoAddresses.payableRecipient(hex("20" + KEY + KEY), 0)).isTrue();
    }

    @Test
    void nonCanonicalPointersScriptsRewardsAndOtherNetworksAreNot() {
        for (String raw : new String[] {
                "40" + KEY + "80000000",      // padded slot: 80 00 is 0 in two digits
                "40" + KEY + "00800100",      // padded transaction index
                "40" + KEY + "000000" + "00", // trailing byte
                "40" + KEY + "0000",          // only two naturals
                "40" + KEY + "0000ff",        // unterminated
                "50" + KEY + "000000",        // script payment
                "70" + KEY, "e0" + KEY, "10" + KEY + KEY, "61" + KEY}) {
            assertThat(CardanoAddresses.payableRecipient(hex(raw), 0)).as(raw).isFalse();
        }
        assertThat(CardanoAddresses.nonCanonicalPointer(hex("40" + KEY + "80000000"))).isTrue();
        assertThat(CardanoAddresses.nonCanonicalPointer(hex("40" + KEY + "000000"))).isFalse();
        assertThat(CardanoAddresses.nonCanonicalPointer(hex("60" + KEY))).isFalse();
    }

    /** C1: the Conway pointer limits, slot < 2^32 and transaction and certificate index < 2^16, on both sides. */
    @Test
    void pointerComponentsAreBoundedByTheConwayLimits() {
        String prefix = "40" + "01".repeat(28);
        // slot 2^32 - 1 = 8f ff ff ff 7f, 2^32 = 90 80 80 80 00; index 2^16 - 1 = 83 ff 7f, 2^16 = 84 80 00.
        assertThat(CardanoAddresses.payableRecipient(hex(prefix + "8fffffff7f" + "00" + "00"), 0)).isTrue();
        assertThat(CardanoAddresses.payableRecipient(hex(prefix + "9080808000" + "00" + "00"), 0)).isFalse();
        assertThat(CardanoAddresses.payableRecipient(hex(prefix + "00" + "83ff7f" + "83ff7f"), 0)).isTrue();
        // The review's example: slot 0, txIx 65536, certIx 0.
        assertThat(CardanoAddresses.payableRecipient(hex(prefix + "00" + "848000" + "00"), 0)).isFalse();
        assertThat(CardanoAddresses.payableRecipient(hex(prefix + "00" + "00" + "848000"), 0)).isFalse();
        assertThat(CardanoAddresses.nonCanonicalPointer(hex(prefix + "00848000" + "00"))).isTrue();
        // The same limit applies to a refund address, so such a deposit is malformed.
        assertThatThrownBy(() -> new DepositDatumV1(hex(ACCOUNT), hex(prefix + "0084800000"), hex(KEY)))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] datum = hex("d87983581c" + ACCOUNT + "5822" + prefix + "0084800000" + "581c" + KEY);
        assertThat(VaultOutputShape.classify(datum)).isEqualTo(VaultOutputShape.DEPOSIT);
        assertThatThrownBy(() -> DepositDatumV1.decode(datum)).isInstanceOf(IllegalArgumentException.class);
        new DepositDatumV1(hex(ACCOUNT), hex(prefix + "0083ff7f00"), hex(KEY));
    }

    @Test
    void aDepositDatumWithANonCanonicalPointerRefundAddressIsMalformed() {
        byte[] padded = hex("40" + KEY + "80000000");
        assertThatThrownBy(() -> new DepositDatumV1(hex(ACCOUNT), padded, hex(KEY)))
                .isInstanceOf(IllegalArgumentException.class);
        // d879 83 581c<account> 5821<pointer> 581c<key>
        byte[] datum = hex("d87983581c" + ACCOUNT + "5821" + "40" + KEY + "80000000" + "581c" + KEY);
        assertThat(VaultOutputShape.classify(datum)).isEqualTo(VaultOutputShape.DEPOSIT);
        assertThatThrownBy(() -> DepositDatumV1.decode(datum)).isInstanceOf(IllegalArgumentException.class);
        byte[] canonical = hex("d87983581c" + ACCOUNT + "5820" + "40" + KEY + "000000" + "581c" + KEY);
        assertThat(DepositDatumV1.decode(canonical).refundAddress()).isEqualTo(hex("40" + KEY + "000000"));
    }
}
