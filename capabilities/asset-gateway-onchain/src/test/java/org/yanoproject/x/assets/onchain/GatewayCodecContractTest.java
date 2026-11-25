package org.yanoproject.x.assets.onchain;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.Pointer;
import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.core.PlutusData;
import org.julclang.testkit.ContractTest;
import org.julclang.testkit.JulcEval;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SweepCommitment;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The on-chain encodings equal the Java contract bytes (ADR-059 V2 for the vault): every identifier, leaf and
 * address the scripts compute is evaluated as compiled UPLC and compared with {@code asset-gateway-contracts}.
 */
class GatewayCodecContractTest extends ContractTest {
    private static JulcEval codec;

    @BeforeAll
    static void compile() {
        initCrypto();
        codec = JulcEval.forClass(GatewayCodec.class, GatewayFixtures.SOURCES);
    }

    @Test
    void cborHeadsAreShortestForm() {
        for (long n : new long[] {0, 23, 24, 255, 256, 65_535, 65_536, 4_294_967_295L, 4_294_967_296L,
                Long.MAX_VALUE}) {
            byte[] onChain = codec.call("uint", BigInteger.valueOf(n)).asByteString();
            assertThat(onChain).as("uint %d", n).isEqualTo(GatewayFixtures.cborUInt(BigInteger.valueOf(n)));
            assertThat(codec.call("readUInt", onChain, 0L).asData())
                    .isEqualTo(PlutusData.constr(0, PlutusData.integer(n), PlutusData.integer(onChain.length)));
        }
        BigInteger max = new BigInteger("18446744073709551615");
        assertThat(codec.call("uint", max).asByteString()).isEqualTo(GatewayFixtures.cborUInt(max));
        // Non-shortest heads read as invalid.
        assertThat(((PlutusData.IntData) ((PlutusData.ConstrData) codec.call("readUInt",
                new byte[] {0x18, 0x10}, 0L).asData()).fields().get(0)).value()).isEqualTo(BigInteger.valueOf(-1));
    }

    @Test
    void identifiersMatchGatewayIds() {
        Hash32 domain = GatewayFixtures.DOMAIN;
        for (AssetId asset : List.of(AssetId.ADA, GatewayFixtures.TOKEN)) {
            byte[] onChain = codec.call("vaultId", domain.bytes(), asset.policyId(), asset.assetName(),
                    BigInteger.ZERO).asByteString();
            assertThat(onChain).isEqualTo(GatewayIds.vaultId(domain, asset, 0).bytes());
        }
        Hash32 vault = GatewayIds.vaultId(domain, GatewayFixtures.TOKEN, 0);
        byte[] txId = GatewayFixtures.filled(0x61, 32);
        for (long index : new long[] {0, 23, 24, 300}) {
            assertThat(codec.call("depositId", vault.bytes(), txId, BigInteger.valueOf(index)).asByteString())
                    .isEqualTo(GatewayIds.depositId(vault, Hash32.of(txId), index).bytes());
        }
        byte[] holder = GatewayFixtures.filled(0x42, 28);
        assertThat(codec.call("escapeId", vault.bytes(), holder).asByteString())
                .isEqualTo(GatewayIds.escapeNullifier(vault, holder).bytes());
        assertThat(codec.call("forcedRequestId", txId, BigInteger.valueOf(3)).asByteString())
                .isEqualTo(GatewayIds.forcedRequestId(Hash32.of(txId), 3).bytes());

        byte[] recipient = GatewayFixtures.enterpriseKeyAddress(GatewayFixtures.filled(0x80, 28));
        byte[] outpoint = codec.call("outRefCbor", txId, BigInteger.valueOf(2)).asByteString();
        for (BigInteger quantity : List.of(BigInteger.ZERO, BigInteger.valueOf(7_000_000))) {
            AssetValue value = quantity.signum() == 0 ? AssetValue.EMPTY
                    : AssetValue.single(GatewayFixtures.TOKEN, quantity);
            byte[] assetValue = codec.call("assetValue", GatewayFixtures.TOKEN.policyId(),
                    GatewayFixtures.TOKEN.assetName(), quantity).asByteString();
            assertThat(assetValue).isEqualTo(value.encode());
            byte[] onChain = codec.call("claimId", domain.bytes(), vault.bytes(),
                    BigInteger.valueOf(ClaimKind.USER_WITHDRAWAL.code()), outpoint, recipient, assetValue,
                    BigInteger.valueOf(1_500_000), BigInteger.valueOf(9)).asByteString();
            assertThat(onChain).isEqualTo(GatewayIds.claimId(domain, vault, ClaimKind.USER_WITHDRAWAL, outpoint,
                    recipient, value, BigInteger.valueOf(1_500_000), 9).bytes());
        }
        // A full multi-asset value (a sweep's commitment) hashes the same on and off chain.
        SweepCommitment sweep = SweepCommitment.of(GatewayFixtures.TOKEN, Hash32.of(txId), 2,
                BigInteger.valueOf(3_000_000), java.util.Map.of(GatewayFixtures.TOKEN, BigInteger.valueOf(40),
                        AssetId.of(GatewayFixtures.filled(0x11, 28), new byte[] {1}), BigInteger.valueOf(5)));
        assertThat(sweep.withdrawalKey()).isEqualTo(outpoint);
        assertThat(codec.call("claimId", domain.bytes(), vault.bytes(),
                BigInteger.valueOf(ClaimKind.SWEEP_UNATTRIBUTED.code()), outpoint, recipient, sweep.value().encode(),
                sweep.vaultLovelace(), BigInteger.valueOf(5)).asByteString())
                .isEqualTo(sweep.claimId(domain, vault, recipient, 5).bytes());
    }

    @Test
    void leavesMatchTheContractCodecs() {
        Hash32 vault = GatewayIds.vaultId(GatewayFixtures.DOMAIN, GatewayFixtures.TOKEN, 0);
        Hash32 adaVault = GatewayIds.vaultId(GatewayFixtures.DOMAIN, AssetId.ADA, 0);
        byte[] recipient = GatewayFixtures.baseKeyAddress(GatewayFixtures.filled(0x80, 28),
                GatewayFixtures.filled(0x81, 28));
        Hash32 claimId = Hash32.of(GatewayFixtures.filled(0x30, 32));
        record Case(Hash32 vaultId, AssetId asset, long quantity, long vaultLovelace, ClaimKind kind) {
        }
        for (Case c : List.of(new Case(vault, GatewayFixtures.TOKEN, 5_000, 1_400_000, ClaimKind.USER_WITHDRAWAL),
                new Case(vault, GatewayFixtures.TOKEN, 0, 1_400_000, ClaimKind.SWEEP_OPERATIONAL),
                new Case(adaVault, AssetId.ADA, 12_000_000, 0, ClaimKind.USER_WITHDRAWAL))) {
            AssetValue value = c.quantity == 0 ? AssetValue.EMPTY
                    : AssetValue.single(c.asset, BigInteger.valueOf(c.quantity));
            byte[] expected = new ClaimLeafV1(claimId, c.vaultId, c.kind, value, BigInteger.valueOf(c.vaultLovelace),
                    recipient, 1_000_000L, 1_732_000_000_000L).encode();
            byte[] onChain = codec.call("claimLeaf", claimId.bytes(), c.vaultId.bytes(),
                    BigInteger.valueOf(c.kind.code()), value.encode(), BigInteger.valueOf(c.vaultLovelace), recipient,
                    BigInteger.valueOf(1_000_000L), BigInteger.valueOf(1_732_000_000_000L)).asByteString();
            assertThat(onChain).isEqualTo(expected);
        }
        byte[] holder = GatewayFixtures.filled(0x42, 28);
        assertThat(codec.call("exitLeaf", holder, vault.bytes(), BigInteger.valueOf(75),
                BigInteger.valueOf(2_000_000)).asByteString())
                .isEqualTo(new ExitLeafV1(holder, vault, BigInteger.valueOf(75), BigInteger.valueOf(2_000_000))
                        .encode());
    }

    @Test
    void depositRecordAndDomainStatusAreReadAtTheirContractOffsets() {
        Hash32 vault = GatewayIds.vaultId(GatewayFixtures.DOMAIN, GatewayFixtures.TOKEN, 0);
        Hash32 tx = Hash32.of(GatewayFixtures.filled(0x61, 32));
        for (long index : new long[] {0, 30, 70_000}) {
            Hash32 depositId = GatewayIds.depositId(vault, tx, index);
            DepositRecordV1 credited = GatewayFixtures.creditedRecord(vault, tx, index, 5_000_000_000L);
            DepositRecordV1 uncredited = GatewayFixtures.uncreditedRecord(vault, tx, index,
                    DepositAdmission.Reason.DATUM_MALFORMED);
            assertThat(depositStatus(credited.encode(), depositId, vault)).isEqualTo(0);
            assertThat(depositStatus(uncredited.encode(), depositId, vault)).isEqualTo(1);
            Hash32 otherVault = GatewayIds.vaultId(GatewayFixtures.DOMAIN, AssetId.ADA, 0);
            assertThat(depositStatus(credited.encode(), depositId, otherVault)).isEqualTo(-1);
            assertThat(depositStatus(credited.encode(), GatewayIds.depositId(vault, tx, index + 1), vault))
                    .isEqualTo(-1);
        }

        for (DomainStatusV1.Status status : DomainStatusV1.Status.values()) {
            DomainStatusV1 leaf = new DomainStatusV1(status, 77, 1_732_000_000_000L, 86_400_000L);
            PlutusData parsed = codec.call("domainStatus", leaf.encode()).asData();
            assertThat(parsed).isEqualTo(PlutusData.constr(0, PlutusData.integer(status.code()),
                    PlutusData.integer(1_732_000_000_000L), PlutusData.integer(86_400_000L)));
        }
        byte[] trailing = GatewayFixtures.concat(
                new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 1, 2, 3).encode(), new byte[] {0});
        assertThat(((PlutusData.ConstrData) codec.call("domainStatus", trailing).asData()).fields().get(0))
                .isEqualTo(PlutusData.integer(-1));
    }

    @Test
    void addressBytesMatchCardanoClientLib() {
        byte[] key = GatewayFixtures.filled(0x80, 28);
        byte[] stake = GatewayFixtures.filled(0x81, 28);
        byte[] script = GatewayFixtures.filled(0x82, 28);
        List<Address> addresses = List.of(
                AddressProvider.getEntAddress(Credential.fromKey(key), Networks.testnet()),
                AddressProvider.getEntAddress(Credential.fromScript(script), Networks.mainnet()),
                AddressProvider.getBaseAddress(Credential.fromKey(key), Credential.fromKey(stake), Networks.testnet()),
                AddressProvider.getBaseAddress(Credential.fromKey(key), Credential.fromScript(script),
                        Networks.mainnet()),
                AddressProvider.getBaseAddress(Credential.fromScript(script), Credential.fromKey(stake),
                        Networks.testnet()));
        for (Address address : addresses) {
            PlutusData data = GatewayFixtures.plutusAddress(address.getBytes());
            long network = address.getBytes()[0] & 0x0f;
            assertThat(codec.call("addressBytes", data, network).asByteString())
                    .as(address.toBech32()).isEqualTo(address.getBytes());
        }
    }

    /** Pointer addresses: slot, transaction index and certificate index as CIP-19 variable-length naturals. */
    @Test
    void pointerAddressBytesMatchCardanoClientLib() {
        byte[] key = GatewayFixtures.filled(0x80, 28);
        byte[] script = GatewayFixtures.filled(0x82, 28);
        List<Pointer> pointers = List.of(new Pointer(0, 0, 0), new Pointer(127, 128, 16_383),
                new Pointer(2_498_243, 27, 3), new Pointer(4_294_967_295L, 65_535, 1),
                new Pointer(116_000_000L, 2_147_483_647, 0));
        for (Pointer pointer : pointers) {
            for (Address address : List.of(
                    AddressProvider.getPointerAddress(Credential.fromKey(key), pointer, Networks.mainnet()),
                    AddressProvider.getPointerAddress(Credential.fromScript(script), pointer, Networks.testnet()))) {
                PlutusData data = new org.julclang.ledger.Address(
                        (address.getBytes()[0] & 0x10) == 0
                                ? new org.julclang.ledger.Credential.PubKeyCredential(
                                new org.julclang.ledger.PubKeyHash(key))
                                : new org.julclang.ledger.Credential.ScriptCredential(
                                new org.julclang.ledger.ScriptHash(script)),
                        java.util.Optional.of(new org.julclang.ledger.StakingCredential.StakingPtr(
                                BigInteger.valueOf(pointer.getSlot()), BigInteger.valueOf(pointer.getTxIndex()),
                                BigInteger.valueOf(pointer.getCertIndex())))).toPlutusData();
                long network = address.getBytes()[0] & 0x0f;
                assertThat(codec.call("addressBytes", data, network).asByteString())
                        .as("%s %d/%d/%d", address.toBech32(), pointer.getSlot(), pointer.getTxIndex(),
                                pointer.getCertIndex())
                        .isEqualTo(address.getBytes());
                assertThat(GatewayFixtures.ledgerAddress(address.getBytes()).toPlutusData()).isEqualTo(data);
            }
        }
    }

    private static long depositStatus(byte[] record, Hash32 depositId, Hash32 vault) {
        return codec.call("depositStatus", record, depositId.bytes(), vault.bytes()).asLong();
    }
}
