package org.yanoproject.x.assets.contracts;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates the ADR-059 V1/V2 golden vectors. The committed properties file is checked two ways:
 * {@link AssetGatewayGoldenVectorTest} compares it with this generator, and
 * {@code scripts/verify-asset-gateway-vectors.py} recomputes every value with an independent
 * Python CBOR encoder and {@code hashlib.blake2b}.
 */
public final class AssetGatewayGoldenVectorGenerator {
    static final String POLICY = "f0ff48bbb7bbe9d59a40f1ce90e9e9d0ff5002ec48f232b49ca0fb9a";
    static final String GENESIS = "6c1f3b1f9e5d2a0c4b8e7d6a5f4e3d2c1b0a99887766554433221100ffeeddcc";
    static final String TX = "8d3f1c0e2b4a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0";
    static final String KEY_HASH = "1c2b3a495867768594a3b2c1d0e0f00112233445566778899aabbcc0";

    private AssetGatewayGoldenVectorGenerator() {
    }

    static Map<String, String> vectors() {
        HexFormat hex = HexFormat.of();
        Map<String, String> out = new LinkedHashMap<>();
        AssetId usdm = AssetId.ofHex(POLICY, "5553444d");
        AssetId empty = AssetId.ofHex(POLICY, "");
        AssetId ff = AssetId.ofHex(POLICY, "ff");
        AssetId zeroZero = AssetId.ofHex(POLICY, "0000");
        AssetId max = AssetId.ofHex(POLICY, "00".repeat(32));
        out.put("asset.ada", hex.formatHex(AssetId.ADA.encode()));
        out.put("asset.usdm", hex.formatHex(usdm.encode()));
        out.put("asset.empty-name", hex.formatHex(empty.encode()));
        out.put("asset.ff", hex.formatHex(ff.encode()));
        out.put("asset.0000", hex.formatHex(zeroZero.encode()));
        out.put("asset.name32", hex.formatHex(max.encode()));

        AssetValue value = AssetValue.of(Map.of(
                AssetId.ADA, BigInteger.valueOf(2_000_000),
                zeroZero, BigInteger.ONE,
                ff, Quantities.MAX_U64,
                usdm, BigInteger.valueOf(49_504_950)));
        out.put("value.mixed", hex.formatHex(value.encode()));
        out.put("value.empty", hex.formatHex(AssetValue.EMPTY.encode()));

        Hash32 domain = GatewayIds.domainId(0, 1, "payments-1", Hash32.ofHex(GENESIS),
                "asset-payments", "anchored-claim-vault-v1", 1);
        out.put("id.domain", domain.hex());
        Hash32 adaVault = GatewayIds.vaultId(domain, AssetId.ADA, 0);
        Hash32 usdmVault = GatewayIds.vaultId(domain, usdm, 0);
        out.put("id.vault.ada", adaVault.hex());
        out.put("id.vault.usdm", usdmVault.hex());
        out.put("id.deposit", GatewayIds.depositId(usdmVault, Hash32.ofHex(TX), 3).hex());
        byte[] recipient = hex.parseHex("61" + KEY_HASH);
        Hash32 claim = GatewayIds.claimId(domain, usdmVault, ClaimKind.USER_WITHDRAWAL,
                "w/1".getBytes(StandardCharsets.US_ASCII), recipient,
                AssetValue.single(usdm, BigInteger.valueOf(49_504_950)),
                BigInteger.valueOf(1_200_000), 7);
        out.put("id.claim", claim.hex());
        out.put("id.intent", GatewayIds.intentId(hex.parseHex("83010203")).hex());
        out.put("id.escape", GatewayIds.escapeNullifier(usdmVault, hex.parseHex(KEY_HASH)).hex());
        out.put("id.forced", GatewayIds.forcedRequestId(Hash32.ofHex(TX), 300).hex());

        ClaimLeafV1 leaf = new ClaimLeafV1(claim, usdmVault, ClaimKind.USER_WITHDRAWAL,
                AssetValue.single(usdm, BigInteger.valueOf(49_504_950)),
                BigInteger.valueOf(1_200_000), recipient, 1_234, 1_764_000_000_000L);
        out.put("leaf.claim", hex.formatHex(leaf.encode()));
        ExitLeafV1 exit = new ExitLeafV1(hex.parseHex(KEY_HASH), usdmVault,
                BigInteger.valueOf(100), BigInteger.valueOf(1_150_770));
        out.put("leaf.exit", hex.formatHex(exit.encode()));
        DomainStatusV1 status = new DomainStatusV1(DomainStatusV1.Status.PAUSED, 1_234,
                1_764_000_000_000L, 86_400_000L);
        out.put("leaf.domain-status", hex.formatHex(status.encode()));

        // SWEEP_UNATTRIBUTED commits to the outpoint and its full value, foreign tokens included (D8d).
        AssetId foreign = AssetId.ofHex("11".repeat(28), "01");
        SweepCommitment tokenSweep = SweepCommitment.of(usdm, Hash32.ofHex(TX), 2, BigInteger.valueOf(3_000_000),
                Map.of(usdm, BigInteger.valueOf(40), foreign, BigInteger.valueOf(5)));
        Hash32 tokenSweepId = tokenSweep.claimId(domain, usdmVault, recipient, 5);
        out.put("id.claim.sweep-token", tokenSweepId.hex());
        out.put("leaf.claim.sweep-token", hex.formatHex(tokenSweep.leaf(tokenSweepId, usdmVault, recipient, 95,
                1_764_000_000_000L).encode()));
        SweepCommitment adaSweep = SweepCommitment.of(AssetId.ADA, Hash32.ofHex(TX), 4,
                BigInteger.valueOf(2_500_000), Map.of(foreign, BigInteger.valueOf(5)));
        Hash32 adaSweepId = adaSweep.claimId(domain, adaVault, recipient, 6);
        out.put("id.claim.sweep-ada", adaSweepId.hex());
        out.put("leaf.claim.sweep-ada", hex.formatHex(adaSweep.leaf(adaSweepId, adaVault, recipient, 96,
                1_764_000_000_000L).encode()));
        out.put("datum.sweep-paid", hex.formatHex(new SweepPaidV1(tokenSweepId).encode()));
        return out;
    }

    public static void main(String[] args) {
        vectors().forEach((key, value) -> System.out.println(key + "=" + value));
    }
}
