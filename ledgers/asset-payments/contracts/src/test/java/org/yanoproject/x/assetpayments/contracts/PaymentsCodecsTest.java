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
