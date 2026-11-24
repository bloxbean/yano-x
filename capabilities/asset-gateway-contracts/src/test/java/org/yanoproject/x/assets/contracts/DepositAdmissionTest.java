package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositAdmissionTest {
    static final Hash32 DOMAIN = GatewayIds.domainId(0, 42, "payments-1", Hash32.of(new byte[32]),
            "asset-payments", "anchored-claim-vault-v1", 1);
    static final AssetId USDM = AssetId.ofHex("f0".repeat(28), "5553444d");
    static final Hash32 ADA_VAULT = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);
    static final Hash32 USDM_VAULT = GatewayIds.vaultId(DOMAIN, USDM, 0);
    static final byte[] ALICE = filled(28, 0xa1);
    static final byte[] BLOCK = filled(32, 0xb1);
    static final byte[] PREFIX = "ap/".getBytes(StandardCharsets.US_ASCII);

    /** A store whose reads see its own writes, like the app-ledger staged writer. */
    static final class MapStore implements AssetAccounts.Store {
        final TreeMap<String, byte[]> values = new TreeMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }
    }

    final MapStore store = new MapStore();
    final Map<Hash32, DepositAdmission.VaultBinding> registry = new TreeMap<>(Map.of(
            ADA_VAULT, new DepositAdmission.VaultBinding(ADA_VAULT, AssetId.ADA, true),
            USDM_VAULT, new DepositAdmission.VaultBinding(USDM_VAULT, USDM, true)));
    final DepositAdmission admission = new DepositAdmission(PREFIX, "deposits",
            id -> Optional.ofNullable(registry.get(id)));
    final DepositAdmission.CreditCheck accept = candidate -> Optional.empty();

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static AssetDepositClaimV1 claim(Hash32 vault, int txByte, int index, VaultOutputShape shape,
                                     AssetDepositClaimV1.Beneficiary who, long registered, long lovelace,
                                     long others) {
        Hash32 tx = Hash32.of(filled(32, txByte));
        return new AssetDepositClaimV1(GatewayIds.depositId(vault, tx, index), vault, tx, index, 500,
                Hash32.of(BLOCK), shape, who, BigInteger.valueOf(registered), BigInteger.valueOf(lovelace),
                others, Hash32.of(filled(32, 0xee)));
    }

    static DepositAdmission.ObservedDeposit observed(AssetDepositClaimV1 claim) {
        return new DepositAdmission.ObservedDeposit("deposits", claim.txId().bytes(), claim.outputIndex(),
                claim.slot(), claim.blockHash().bytes(), claim.encode());
    }

    static AssetDepositClaimV1 deposit(Hash32 vault, int txByte, int index, long registered, long lovelace) {
        return claim(vault, txByte, index, VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(ALICE),
                registered, lovelace, 0);
    }

    @Test
    void aCreditableTokenDepositIsRecordedOnceAndCreditsTokenAndMinAda() {
        AssetDepositClaimV1 claim = deposit(USDM_VAULT, 1, 0, 100, 1_500_000);
        DepositAdmission.Outcome first = admission.admit(store, observed(claim), 7, accept);
        assertThat(first.disposition()).isEqualTo(DepositAdmission.Disposition.RECORDED);
        assertThat(first.credit()).hasValueSatisfying(credit -> {
            assertThat(credit.amount()).isEqualTo(BigInteger.valueOf(100));
            assertThat(credit.minAdaCredit()).isEqualTo(BigInteger.valueOf(1_500_000));
            assertThat(credit.beneficiary()).isEqualTo(ALICE);
            assertThat(credit.asset()).isEqualTo(USDM);
        });
        assertThat(admission.record(store, claim.depositId())).hasValueSatisfying(record -> {
            assertThat(record.status()).isEqualTo(DepositRecordV1.Status.CREDITED);
            assertThat(record.height()).isEqualTo(7);
        });
        DepositAdmission.Outcome replay = admission.admit(store, observed(claim), 8, accept);
        assertThat(replay.disposition()).isEqualTo(DepositAdmission.Disposition.DUPLICATE);
        assertThat(replay.credit()).isEmpty();
    }

    @Test
    void anAdaDepositCreditsAllLovelaceAndNoMinAda() {
        AssetDepositClaimV1 claim = deposit(ADA_VAULT, 2, 1, 5_000_000, 5_000_000);
        assertThat(admission.admit(store, observed(claim), 1, accept).credit()).hasValueSatisfying(credit -> {
            assertThat(credit.amount()).isEqualTo(BigInteger.valueOf(5_000_000));
            assertThat(credit.minAdaCredit()).isZero();
        });
    }

    @Test
    void twoOutputsOfOneTransactionAreBothCredited() {
        AssetDepositClaimV1 first = deposit(USDM_VAULT, 3, 0, 10, 1_000_000);
        AssetDepositClaimV1 second = deposit(USDM_VAULT, 3, 1, 20, 1_000_000);
        assertThat(admission.admit(store, observed(first), 1, accept).credit()).isPresent();
        assertThat(admission.admit(store, observed(second), 1, accept).credit()).isPresent();
        assertThat(admission.admit(store, observed(first), 1, accept).disposition())
                .isEqualTo(DepositAdmission.Disposition.DUPLICATE);
    }

    @Test
    void uncreditableDepositsAreRecordedWithTheirReasonAndNeverCredit() {
        assertReason(claim(USDM_VAULT, 4, 0, VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(ALICE),
                100, 1_000_000, 1), DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.MIXED_ASSETS);
        assertReason(claim(USDM_VAULT, 4, 1, VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(ALICE),
                0, 1_000_000, 1), DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.UNREGISTERED_ASSET);
        assertReason(claim(USDM_VAULT, 4, 2, VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(ALICE),
                0, 1_000_000, 0), DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.ZERO_AMOUNT);
        assertReason(claim(ADA_VAULT, 4, 3, VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(ALICE),
                2_000_000, 2_000_000, 1), DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.MIXED_ASSETS);
        assertReason(claim(USDM_VAULT, 4, 4, VaultOutputShape.DEPOSIT,
                new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_UNDECODABLE),
                100, 1_000_000, 0), DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.DATUM_MALFORMED);
        Hash32 foreign = GatewayIds.vaultId(GatewayIds.domainId(0, 42, "payments-2", Hash32.of(new byte[32]),
                "asset-payments", "anchored-claim-vault-v1", 1), USDM, 0);
        assertReason(deposit(foreign, 4, 5, 100, 1_000_000),
                DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.UNKNOWN_VAULT);
        assertReason(claim(USDM_VAULT, 4, 6, VaultOutputShape.CONTINUATION, new AssetDepositClaimV1.NotApplicable(),
                100, 1_000_000, 0), DepositRecordV1.Status.UNATTRIBUTED, DepositAdmission.Reason.NOT_A_DEPOSIT);
        assertReason(claim(USDM_VAULT, 4, 7, VaultOutputShape.OTHER, new AssetDepositClaimV1.NotApplicable(),
                0, 3_000_000, 0), DepositRecordV1.Status.UNATTRIBUTED, DepositAdmission.Reason.NOT_A_DEPOSIT);
        assertReason(claim(USDM_VAULT, 4, 8, VaultOutputShape.FLOOR_TOP_UP, new AssetDepositClaimV1.NotApplicable(),
                0, 3_000_000, 0), DepositRecordV1.Status.OPERATIONAL, DepositAdmission.Reason.FLOOR_TOP_UP);
        registry.put(USDM_VAULT, new DepositAdmission.VaultBinding(USDM_VAULT, USDM, false));
        assertReason(deposit(USDM_VAULT, 4, 9, 100, 1_000_000),
                DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.DEPOSITS_PAUSED);
    }

    @Test
    void theConsumersRefusalIsRecordedInsteadOfACredit() {
        AssetDepositClaimV1 claim = deposit(USDM_VAULT, 5, 0, 100, 1_000_000);
        DepositAdmission.Outcome outcome = admission.admit(store, observed(claim), 1,
                candidate -> Optional.of(DepositAdmission.Reason.UNKNOWN_BENEFICIARY));
        assertThat(outcome.credit()).isEmpty();
        assertThat(outcome.record()).hasValueSatisfying(record -> {
            assertThat(record.status()).isEqualTo(DepositRecordV1.Status.UNCREDITED);
            assertThat(record.reason()).isEqualTo(DepositAdmission.Reason.UNKNOWN_BENEFICIARY);
            assertThat(record.creditedAmount()).isZero();
        });
    }

    @Test
    void envelopeMismatchesAreIgnoredWithoutAnyWrite() {
        AssetDepositClaimV1 claim = deposit(USDM_VAULT, 6, 2, 100, 1_000_000);
        byte[] tx = claim.txId().bytes();
        byte[] body = claim.encode();
        assertIgnored(new DepositAdmission.ObservedDeposit("other", tx, 2, 500, BLOCK, body));
        assertIgnored(new DepositAdmission.ObservedDeposit("deposits", filled(32, 9), 2, 500, BLOCK, body));
        assertIgnored(new DepositAdmission.ObservedDeposit("deposits", null, 2, 500, BLOCK, body));
        assertIgnored(new DepositAdmission.ObservedDeposit("deposits", tx, 1, 500, BLOCK, body));
        assertIgnored(new DepositAdmission.ObservedDeposit("deposits", tx, 2, 501, BLOCK, body));
        assertIgnored(new DepositAdmission.ObservedDeposit("deposits", tx, 2, 500, filled(32, 1), body));
        assertIgnored(new DepositAdmission.ObservedDeposit("deposits", tx, 2, 500, BLOCK, new byte[] {1, 2, 3}));
        AssetDepositClaimV1 forged = new AssetDepositClaimV1(Hash32.of(filled(32, 7)), USDM_VAULT, claim.txId(), 2,
                500, Hash32.of(BLOCK), VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(ALICE),
                BigInteger.TEN, BigInteger.ONE, 0, Hash32.of(filled(32, 0xee)));
        assertIgnored(observed(forged));
        assertThat(store.values).isEmpty();
    }

    @Test
    void codecsRejectInconsistentClaimsAndRecords() {
        assertThatThrownBy(() -> claim(USDM_VAULT, 1, 0, VaultOutputShape.CONTINUATION,
                new AssetDepositClaimV1.Account(ALICE), 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claim(USDM_VAULT, 1, 0, VaultOutputShape.DEPOSIT,
                new AssetDepositClaimV1.NotApplicable(), 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        AssetDepositClaimV1 claim = deposit(USDM_VAULT, 1, 0, 1, 1);
        byte[] bytes = claim.encode();
        assertThat(AssetDepositClaimV1.decode(bytes)).isEqualTo(claim);
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThatThrownBy(() -> AssetDepositClaimV1.decode(trailing)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DepositRecordV1(claim.depositId(), USDM_VAULT, claim.txId(), 0, 1, 1,
                VaultOutputShape.DEPOSIT, DepositRecordV1.Status.CREDITED, DepositAdmission.Reason.ZERO_AMOUNT,
                ALICE, BigInteger.ONE, BigInteger.ONE, 0, claim.valueHash(), BigInteger.ONE, BigInteger.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void datumShapesAreClassifiedByConstructorTag() {
        byte[] deposit = new DepositDatumV1(ALICE, new byte[] {0x60, 1}, filled(28, 2)).encode();
        assertThat(VaultOutputShape.classify(deposit)).isEqualTo(VaultOutputShape.DEPOSIT);
        assertThat(VaultOutputShape.classify(null)).isEqualTo(VaultOutputShape.OTHER);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("d87a80"))).isEqualTo(VaultOutputShape.CONTINUATION);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("d87b80"))).isEqualTo(VaultOutputShape.FLOOR_TOP_UP);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("d87b9fff"))).isEqualTo(VaultOutputShape.FLOOR_TOP_UP);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("d87b8100"))).isEqualTo(VaultOutputShape.OTHER);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("d87c80"))).isEqualTo(VaultOutputShape.MIGRATION_IN);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("d87d80"))).isEqualTo(VaultOutputShape.OTHER);
        assertThat(VaultOutputShape.classify(HexFormat.of().parseHex("00"))).isEqualTo(VaultOutputShape.OTHER);
    }

    private void assertReason(AssetDepositClaimV1 claim, DepositRecordV1.Status status, DepositAdmission.Reason reason) {
        DepositAdmission.Outcome outcome = admission.admit(store, observed(claim), 3, accept);
        assertThat(outcome.disposition()).as(reason.name()).isEqualTo(DepositAdmission.Disposition.RECORDED);
        assertThat(outcome.credit()).as(reason.name()).isEmpty();
        DepositRecordV1 record = admission.record(store, claim.depositId()).orElseThrow();
        assertThat(record.status()).as(reason.name()).isEqualTo(status);
        assertThat(record.reason()).as(reason.name()).isEqualTo(reason);
        assertThat(DepositRecordV1.decode(record.encode())).isEqualTo(record);
    }

    private void assertIgnored(DepositAdmission.ObservedDeposit observed) {
        assertThat(admission.admit(store, observed, 1, accept).disposition())
                .isEqualTo(DepositAdmission.Disposition.IGNORED);
    }
}
