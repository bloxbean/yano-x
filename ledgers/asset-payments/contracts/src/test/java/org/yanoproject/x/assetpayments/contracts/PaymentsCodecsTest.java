package org.yanoproject.x.assetpayments.contracts;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.SessionGrantV1;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentsCodecsTest {
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final byte[] BOB = filled(28, 0xb0);

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    @Test
    void messagesRoundTripAndRejectEverythingElse() {
        PaymentsMessageV1 owner = new PaymentsMessageV1.Owner(new byte[] {1, 2}, new byte[] {3});
        PaymentsMessageV1 session = new PaymentsMessageV1.Session(new byte[] {4}, filled(64, 5));
        PaymentsMessageV1 rotation = new PaymentsMessageV1.Rotation(new byte[] {6}, new byte[] {7}, new byte[] {8},
                new byte[] {9});
        for (PaymentsMessageV1 message : List.of(owner, session, rotation)) {
            byte[] bytes = message.encode();
            assertThat(PaymentsMessageV1.decode(bytes).encode()).isEqualTo(bytes);
            byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
            assertThatThrownBy(() -> PaymentsMessageV1.decode(trailing)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(HexFormat.of().formatHex(owner.encode())).isEqualTo("8401004201024103");
        assertThatThrownBy(() -> PaymentsMessageV1.decode(HexFormat.of().parseHex("8402004201024103")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentsMessageV1.decode(HexFormat.of().parseHex("8401014104420506")))
                .isInstanceOf(IllegalArgumentException.class); // a 2-byte session signature
        assertThatThrownBy(() -> PaymentsMessageV1.decode(HexFormat.of().parseHex("8401004001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentsMessageV1.Owner(new byte[16_385], new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bodiesRoundTripAndAreStrict() {
        PaymentsBodies.Transfer transfer = new PaymentsBodies.Transfer(USDM, BOB, BigInteger.valueOf(30));
        assertThat(PaymentsBodies.Transfer.decode(transfer.encode())).isEqualTo(transfer);
        assertThatThrownBy(() -> new PaymentsBodies.Transfer(USDM, BOB, BigInteger.ZERO))
                .isInstanceOf(ArithmeticException.class);
        byte[] zeroAmount = HexFormat.of().parseHex(HexFormat.of().formatHex(transfer.encode())
                .replaceAll("181e$", "00"));
        assertThatThrownBy(() -> PaymentsBodies.Transfer.decode(zeroAmount)).isInstanceOf(IllegalArgumentException.class);
        PaymentsBodies.AuthorizeSession grant = new PaymentsBodies.AuthorizeSession(filled(32, 1), List.of("TRANSFER"),
                List.of(new SessionGrantV1.Limit(Hash32.of(filled(32, 2)), BigInteger.TEN, BigInteger.valueOf(15))),
                List.of(BOB), 99);
        PaymentsBodies.AuthorizeSession decoded = PaymentsBodies.AuthorizeSession.decode(grant.encode());
        assertThat(decoded.encode()).isEqualTo(grant.encode());
        PaymentsBodies.RevokeSession revoke = new PaymentsBodies.RevokeSession(Hash32.of(filled(32, 3)));
        assertThat(PaymentsBodies.RevokeSession.decode(revoke.encode())).isEqualTo(revoke);
        assertThat(PaymentsBodies.isEmpty(PaymentsBodies.REGISTER_ACCOUNT)).isTrue();
    }

    @Test
    void recordsRoundTrip() {
        PaymentsRecords.ReceiptV1 receipt = new PaymentsRecords.ReceiptV1(Hash32.of(filled(32, 1)), BOB, "TRANSFER",
                Optional.of(USDM), BigInteger.TEN, PaymentsRecords.Outcome.REJECTED, "INSUFFICIENT_BALANCE", 7);
        assertThat(PaymentsRecords.ReceiptV1.decode(receipt.encode())).isEqualTo(receipt);
        PaymentsRecords.ReceiptV1 plain = new PaymentsRecords.ReceiptV1(Hash32.of(filled(32, 1)), BOB,
                "REGISTER_ACCOUNT", Optional.empty(), BigInteger.ZERO, PaymentsRecords.Outcome.ACCEPTED, "", 7);
        assertThat(PaymentsRecords.ReceiptV1.decode(plain.encode())).isEqualTo(plain);
        assertThatThrownBy(() -> new PaymentsRecords.ReceiptV1(Hash32.of(filled(32, 1)), BOB, "X", Optional.empty(),
                BigInteger.ZERO, PaymentsRecords.Outcome.ACCEPTED, "WHY", 7)).isInstanceOf(IllegalArgumentException.class);
        PaymentsRecords.AccountRecordV1 account = new PaymentsRecords.AccountRecordV1(0, filled(28, 4), 3, 1,
                List.of(Hash32.of(filled(32, 5))));
        assertThat(PaymentsRecords.AccountRecordV1.decode(account.encode()).encode()).isEqualTo(account.encode());
        PaymentsRecords.RegistryEntryV1 entry = new PaymentsRecords.RegistryEntryV1(Hash32.of(filled(32, 6)), USDM,
                filled(28, 7), "addr_test1xyz", PaymentsRecords.AssetStatus.DEPOSITS_PAUSED, 0, 0, "USDM", 6);
        assertThat(PaymentsRecords.RegistryEntryV1.decode(entry.encode()).encode()).isEqualTo(entry.encode());
        assertThat(PaymentsRecords.AssetStatus.DEPOSITS_PAUSED.transfersEnabled()).isTrue();
        assertThat(PaymentsRecords.AssetStatus.DEPOSITS_PAUSED.depositsEnabled()).isFalse();
        assertThat(PaymentsRecords.AssetStatus.WIND_DOWN.transfersEnabled()).isFalse();
    }

    @Test
    void recordsWithByteFieldsCompareByContentWithoutChangingTheirBytes() {
        // Messages: equal content, distinct arrays.
        assertEqualDistinctCopies(new PaymentsMessageV1.Owner(new byte[] {1, 2}, new byte[] {3}),
                new PaymentsMessageV1.Owner(new byte[] {1, 2}, new byte[] {3}),
                new PaymentsMessageV1.Owner(new byte[] {1, 2}, new byte[] {4}));
        assertEqualDistinctCopies(new PaymentsMessageV1.Session(new byte[] {4}, filled(64, 5)),
                new PaymentsMessageV1.Session(new byte[] {4}, filled(64, 5)),
                new PaymentsMessageV1.Session(new byte[] {4}, filled(64, 6)));
        assertEqualDistinctCopies(
                new PaymentsMessageV1.Rotation(new byte[] {6}, new byte[] {7}, new byte[] {8}, new byte[] {9}),
                new PaymentsMessageV1.Rotation(new byte[] {6}, new byte[] {7}, new byte[] {8}, new byte[] {9}),
                new PaymentsMessageV1.Rotation(new byte[] {6}, new byte[] {7}, new byte[] {9}, new byte[] {8}));
        // Different kinds never compare equal, even with the same parts.
        assertThat((Object) new PaymentsMessageV1.Owner(new byte[] {1}, new byte[] {2}))
                .isNotEqualTo(new PaymentsMessageV1.Session(new byte[] {1}, filled(64, 2)));
        for (PaymentsMessageV1 message : List.of(new PaymentsMessageV1.Owner(new byte[] {1, 2}, new byte[] {3}),
                new PaymentsMessageV1.Session(new byte[] {4}, filled(64, 5)))) {
            assertThat(PaymentsMessageV1.decode(message.encode())).isEqualTo(message);
        }
        assertThat(HexFormat.of().formatHex(new PaymentsMessageV1.Owner(new byte[] {1, 2}, new byte[] {3}).encode()))
                .isEqualTo("8401004201024103"); // equality does not touch the wire bytes

        PaymentsBodies.AuthorizeSession grant = authorizeSession(filled(28, 0xb0), 99);
        assertEqualDistinctCopies(grant, authorizeSession(filled(28, 0xb0), 99), authorizeSession(filled(28, 0xb1), 99));
        assertThat(PaymentsBodies.AuthorizeSession.decode(grant.encode())).isEqualTo(grant);

        PaymentsRecords.AccountRecordV1 account = new PaymentsRecords.AccountRecordV1(0, filled(28, 4), 3, 1,
                List.of(Hash32.of(filled(32, 5))));
        assertEqualDistinctCopies(account, new PaymentsRecords.AccountRecordV1(0, filled(28, 4), 3, 1,
                List.of(Hash32.of(filled(32, 5)))), account.withGrants(List.of()));
        assertThat(PaymentsRecords.AccountRecordV1.decode(account.encode())).isEqualTo(account);

        PaymentsRecords.RegistryEntryV1 entry = registryEntry(filled(28, 7));
        assertEqualDistinctCopies(entry, registryEntry(filled(28, 7)), registryEntry(filled(28, 8)));
        assertThat(PaymentsRecords.RegistryEntryV1.decode(entry.encode())).isEqualTo(entry);
    }

    @Test
    void authorizeSessionRejectsDuplicateVaultLimitsAndActionsAtDecode() {
        Hash32 vault = Hash32.of(filled(32, 2));
        Hash32 other = Hash32.of(filled(32, 3));
        SessionGrantV1.Limit limit = new SessionGrantV1.Limit(vault, BigInteger.TEN, BigInteger.TEN);
        assertThatThrownBy(() -> new PaymentsBodies.AuthorizeSession(filled(32, 1), List.of("TRANSFER"),
                List.of(limit, limit), List.of(), 99)).isInstanceOf(IllegalArgumentException.class);
        // The same vault twice on the wire: decode fails, so the ledger answers BAD_BODY.
        byte[] two = new PaymentsBodies.AuthorizeSession(filled(32, 1), List.of("TRANSFER"),
                List.of(limit, new SessionGrantV1.Limit(other, BigInteger.TEN, BigInteger.TEN)), List.of(), 99).encode();
        byte[] duplicated = HexFormat.of().parseHex(HexFormat.of().formatHex(two)
                .replace(other.hex(), vault.hex()));
        assertThatThrownBy(() -> PaymentsBodies.AuthorizeSession.decode(duplicated))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] twoActions = HexFormat.of().parseHex(HexFormat.of().formatHex(
                new PaymentsBodies.AuthorizeSession(filled(32, 1), List.of("TRANSFER", "TRANSFEX"), List.of(limit),
                        List.of(), 99).encode()).replace("685452414e53464558", "685452414e53464552"));
        assertThatThrownBy(() -> PaymentsBodies.AuthorizeSession.decode(twoActions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static PaymentsBodies.AuthorizeSession authorizeSession(byte[] recipient, long notAfter) {
        return new PaymentsBodies.AuthorizeSession(filled(32, 1), List.of("TRANSFER"),
                List.of(new SessionGrantV1.Limit(Hash32.of(filled(32, 2)), BigInteger.TEN, BigInteger.valueOf(15))),
                List.of(recipient), notAfter);
    }

    static PaymentsRecords.RegistryEntryV1 registryEntry(byte[] scriptHash) {
        return new PaymentsRecords.RegistryEntryV1(Hash32.of(filled(32, 6)), USDM, scriptHash, "addr_test1xyz",
                PaymentsRecords.AssetStatus.ACTIVE, 0, 0, "USDM", 6);
    }

    private static void assertEqualDistinctCopies(Object value, Object copy, Object different) {
        assertThat(value).isNotSameAs(copy).isEqualTo(copy).hasSameHashCodeAs(copy);
        assertThat(value).isNotEqualTo(different);
    }

    @Test
    void stateKeysDoNotOverlap() {
        Hash32 id = Hash32.of(filled(32, 9));
        List<byte[]> keys = List.of(AssetPaymentsContract.usedKey(id), AssetPaymentsContract.receiptKey(id),
                AssetPaymentsContract.grantKey(id), AssetPaymentsContract.registryKey(id),
                AssetPaymentsContract.usedQueueKey(0), AssetPaymentsContract.usedQueueMetaKey(),
                AssetPaymentsContract.domainKey(), AssetPaymentsContract.accountKey(BOB),
                AssetPaymentsContract.usageKey(id, id));
        for (byte[] a : keys) {
            for (byte[] b : keys) {
                if (a != b) {
                    assertThat(startsWith(a, b) || startsWith(b, a)).as(HexFormat.of().formatHex(a)).isFalse();
                }
            }
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }
}
