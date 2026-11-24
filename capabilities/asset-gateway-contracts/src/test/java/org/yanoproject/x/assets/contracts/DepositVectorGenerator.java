package org.yanoproject.x.assets.contracts;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates the ADR-059 M3 deposit vectors: {@link DepositDatumV1}, {@link CardanoValueCbor},
 * {@link AssetDepositClaimV1} and {@link DepositRecordV1}. The committed
 * {@code deposit-vectors.properties} is checked by {@link DepositVectorTest} against this generator
 * and by {@code scripts/verify-asset-gateway-vectors.py} with an independent Python implementation.
 */
public final class DepositVectorGenerator {
    static final String ACCOUNT = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c";
    static final String BLOCK = "b10c0000000000000000000000000000000000000000000000000000000000b1";
    static final String POLICY_B = "11".repeat(28);
    /** The definite deposit datum re-serialized with an indefinite field list and a chunked first field. */
    static final String INDEFINITE_DATUM = "d8799f5f4e" + ACCOUNT.substring(0, 28) + "4e" + ACCOUNT.substring(28)
            + "ff581d60" + AssetGatewayGoldenVectorGenerator.KEY_HASH + "581c"
            + AssetGatewayGoldenVectorGenerator.KEY_HASH + "ff";

    private DepositVectorGenerator() {
    }

    static Map<String, String> vectors() {
        HexFormat hex = HexFormat.of();
        Map<String, String> out = new LinkedHashMap<>();
        byte[] account = hex.parseHex(ACCOUNT);
        byte[] keyHash = hex.parseHex(AssetGatewayGoldenVectorGenerator.KEY_HASH);
        byte[] refund = hex.parseHex("60" + AssetGatewayGoldenVectorGenerator.KEY_HASH);
        DepositDatumV1 datum = new DepositDatumV1(account, refund, keyHash);
        out.put("datum.deposit", hex.formatHex(datum.encode()));
        if (!DepositDatumV1.decode(hex.parseHex(INDEFINITE_DATUM)).equals(datum)) {
            throw new IllegalStateException("indefinite datum form must decode to the same value");
        }
        out.put("datum.deposit-indefinite", INDEFINITE_DATUM);
        out.put("datum.floor-top-up", hex.formatHex(VaultOutputShape.floorTopUpDatum()));

        AssetId usdm = AssetId.ofHex(AssetGatewayGoldenVectorGenerator.POLICY, "5553444d");
        AssetId ff = AssetId.ofHex(AssetGatewayGoldenVectorGenerator.POLICY, "ff");
        AssetId other = AssetId.ofHex(POLICY_B, "");
        out.put("value.ada-only", hex.formatHex(CardanoValueCbor.encode(BigInteger.valueOf(2_000_000), Map.of())));
        Map<AssetId, BigInteger> multi = Map.of(usdm, BigInteger.valueOf(49_504_950), ff, BigInteger.ONE,
                other, BigInteger.valueOf(7));
        out.put("value.multi", hex.formatHex(CardanoValueCbor.encode(BigInteger.valueOf(1_500_000), multi)));
        out.put("value.multi.hash", CardanoValueCbor.hash(BigInteger.valueOf(1_500_000), multi).hex());

        Hash32 domain = GatewayIds.domainId(0, 1, "payments-1",
                Hash32.ofHex(AssetGatewayGoldenVectorGenerator.GENESIS), "asset-payments",
                "anchored-claim-vault-v1", 1);
        Hash32 usdmVault = GatewayIds.vaultId(domain, usdm, 0);
        Hash32 tx = Hash32.ofHex(AssetGatewayGoldenVectorGenerator.TX);
        Hash32 block = Hash32.ofHex(BLOCK);
        Hash32 single = CardanoValueCbor.hash(BigInteger.valueOf(1_500_000),
                Map.of(usdm, BigInteger.valueOf(49_504_950)));
        Hash32 depositId = GatewayIds.depositId(usdmVault, tx, 3);
        out.put("claim.deposit", hex.formatHex(new AssetDepositClaimV1(depositId, usdmVault, tx, 3, 4_242_424,
                block, VaultOutputShape.DEPOSIT, new AssetDepositClaimV1.Account(account),
                BigInteger.valueOf(49_504_950), BigInteger.valueOf(1_500_000), 0, single).encode()));
        Hash32 malformedId = GatewayIds.depositId(usdmVault, tx, 4);
        out.put("claim.malformed", hex.formatHex(new AssetDepositClaimV1(malformedId, usdmVault, tx, 4, 4_242_424,
                block, VaultOutputShape.DEPOSIT,
                new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_UNDECODABLE),
                BigInteger.valueOf(49_504_950), BigInteger.valueOf(1_500_000), 0, single).encode()));
        Hash32 continuationId = GatewayIds.depositId(usdmVault, tx, 0);
        Hash32 multiHash = CardanoValueCbor.hash(BigInteger.valueOf(1_500_000), multi);
        out.put("claim.continuation", hex.formatHex(new AssetDepositClaimV1(continuationId, usdmVault, tx, 0,
                4_242_424, block, VaultOutputShape.CONTINUATION, new AssetDepositClaimV1.NotApplicable(),
                BigInteger.valueOf(49_504_950), BigInteger.valueOf(1_500_000), 2, multiHash).encode()));

        out.put("record.credited", hex.formatHex(new DepositRecordV1(depositId, usdmVault, tx, 3, 4_242_424, 77,
                VaultOutputShape.DEPOSIT, DepositRecordV1.Status.CREDITED, DepositAdmission.Reason.NONE, account,
                BigInteger.valueOf(49_504_950), BigInteger.valueOf(1_500_000), 0, single,
                BigInteger.valueOf(49_504_950), BigInteger.valueOf(1_500_000)).encode()));
        out.put("record.uncredited", hex.formatHex(new DepositRecordV1(depositId, usdmVault, tx, 3, 4_242_424, 77,
                VaultOutputShape.DEPOSIT, DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.MIXED_ASSETS,
                account, BigInteger.valueOf(49_504_950), BigInteger.valueOf(1_500_000), 2, multiHash,
                BigInteger.ZERO, BigInteger.ZERO).encode()));
        return out;
    }

    public static void main(String[] args) {
        vectors().forEach((key, value) -> System.out.println(key + "=" + value));
    }
}
