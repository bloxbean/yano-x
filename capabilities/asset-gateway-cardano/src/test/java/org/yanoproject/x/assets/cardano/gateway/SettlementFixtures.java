package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared fixtures for the settlement observers: a USDM and an ADA vault with their shard threads, Plutus Data in the
 * on-chain ABI ({@code asset-gateway-onchain-v1.cddl}), and parsed-model transactions with mint and redeemers.
 */
final class SettlementFixtures {
    static final String POLICY = "f0".repeat(28);
    static final AssetId USDM = AssetId.ofHex(POLICY, "5553444d");
    static final byte[] ADA_HASH = filled(28, 0x01);
    static final byte[] USDM_HASH = filled(28, 0x02);
    static final String ADA_SHARD_POLICY = "a1".repeat(28);
    static final String USDM_SHARD_POLICY = "a2".repeat(28);
    static final byte[] ADA_SHARD_HASH = shardHash(ADA_SHARD_POLICY, ADA_HASH);
    static final byte[] USDM_SHARD_HASH = shardHash(USDM_SHARD_POLICY, USDM_HASH);
    static final String ADA_VAULT = enterprise(ADA_HASH);
    static final String USDM_VAULT = enterprise(USDM_HASH);
    static final String ADA_SHARD = enterprise(ADA_SHARD_HASH);
    static final String USDM_SHARD = enterprise(USDM_SHARD_HASH);
    static final String GENESIS = "6c".repeat(32);
    static final String CHAIN = "payments-1";
    static final byte[] BLOCK_HASH = filled(32, 0xb1);
    static final long SLOT = 9_000;
    static final byte[] ALICE_KEY = filled(28, 0xa1);
    static final String ALICE = AddressProvider.getEntAddress(Credential.fromKey(ALICE_KEY), Networks.testnet())
            .getAddress();
    static final String BOB = AddressProvider.getEntAddress(Credential.fromKey(filled(28, 0xb0)), Networks.testnet())
            .getAddress();
    static final Hash32 DOMAIN = GatewayIds.domainId(0, 42, CHAIN, Hash32.ofHex(GENESIS), "asset-payments",
            "anchored-claim-vault-v1", 1);
    static final Hash32 ADA_ID = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);
    static final Hash32 USDM_ID = GatewayIds.vaultId(DOMAIN, USDM, 0);

    private SettlementFixtures() {
    }

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    /** The julc shard of a vault (ADR-059 D4 step 7), as the settings require the shard pin to be. */
    static byte[] shardHash(String shardPolicyHex, byte[] vaultHash) {
        return ShardBackend.JULC.derive(HexFormat.of().parseHex(shardPolicyHex), vaultHash, 0).scriptHash();
    }

    static String enterprise(byte[] scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(scriptHash), Networks.testnet()).getAddress();
    }

    static byte[] raw(String bech32) {
        return new Address(bech32).getBytes();
    }

    /** Settings of the payout and shard-bootstrap observers (identical schema). */
    static Map<String, String> shardSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("domain.address-network-id", "0");
        settings.put("domain.network-magic", "42");
        settings.put("domain.chain-id", CHAIN);
        settings.put("domain.genesis-id", GENESIS);
        settings.put("vaults.0.asset", "lovelace");
        settings.put("vaults.0.script-hash", HexFormat.of().formatHex(ADA_HASH));
        settings.put("vaults.0.address", ADA_VAULT);
        settings.put("vaults.0.shard-policy-id", ADA_SHARD_POLICY);
        settings.put("vaults.0.shard-script-hash", HexFormat.of().formatHex(ADA_SHARD_HASH));
        settings.put("vaults.0.shard-address", ADA_SHARD);
        settings.put("vaults.1.asset", POLICY + ".5553444d");
        settings.put("vaults.1.script-hash", HexFormat.of().formatHex(USDM_HASH));
        settings.put("vaults.1.address", USDM_VAULT);
        settings.put("vaults.1.shard-policy-id", USDM_SHARD_POLICY);
        settings.put("vaults.1.shard-script-hash", HexFormat.of().formatHex(USDM_SHARD_HASH));
        settings.put("vaults.1.shard-address", USDM_SHARD);
        return settings;
    }

    // ------------------------------------------------------------------ Plutus Data (definite, compact tags)

    static byte[] constr(int index, byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (index < 7) {
            out.write(0xd8);
            out.write(121 + index);
        } else {
            int tag = 1280 + index - 7;
            out.write(0xd9);
            out.write(tag >>> 8);
            out.write(tag & 0xff);
        }
        out.writeBytes(list(fields));
        return out.toByteArray();
    }

    static byte[] list(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head(4, items.length));
        for (byte[] item : items) out.writeBytes(item);
        return out.toByteArray();
    }

    static byte[] bytes(byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head(2, value.length));
        out.writeBytes(value);
        return out.toByteArray();
    }

    static byte[] integer(long value) {
        return head(0, value);
    }

    static byte[] head(int major, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (value < 24) {
            out.write((major << 5) | (int) value);
        } else if (value < 256) {
            out.write((major << 5) | 24);
            out.write((int) value);
        } else if (value < 65_536) {
            out.write((major << 5) | 25);
            out.write((int) (value >>> 8));
            out.write((int) value);
        } else if (value < 4_294_967_296L) {
            out.write((major << 5) | 26);
            for (int shift = 24; shift >= 0; shift -= 8) out.write((int) (value >>> shift));
        } else {
            out.write((major << 5) | 27);
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (value >>> shift));
        }
        return out.toByteArray();
    }

    static byte[] continuation(Hash32... ids) {
        byte[][] items = new byte[ids.length][];
        for (int i = 0; i < ids.length; i++) items[i] = bytes(ids[i].bytes());
        return constr(1, list(items));
    }

    static byte[] shardDatum(int index, byte[] root) {
        return constr(0, integer(1), integer(index), bytes(root));
    }

    static byte[] emptyProof() {
        return constr(0, bytes(new byte[0]), bytes(new byte[0]), bytes(new byte[0]), list());
    }

    /** {@code InsertBatch([Insert(id, [])...])}, the shard redeemer. */
    static byte[] shardRedeemer(Hash32... ids) {
        byte[][] inserts = new byte[ids.length][];
        for (int i = 0; i < ids.length; i++) inserts[i] = constr(0, bytes(ids[i].bytes()), list());
        return constr(0, list(inserts));
    }

    /** A {@code Settle} redeemer naming the ids (the claim bodies are irrelevant to the observer). */
    static byte[] settleRedeemer(int shard, Hash32... ids) {
        byte[][] claims = new byte[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            claims[i] = constr(0, bytes(ids[i].bytes()), integer(0), integer(1), integer(0), bytes(raw(ALICE)),
                    integer(1), integer(1));
        }
        return constr(0, list(claims), integer(shard));
    }

    static byte[] balanceExitRedeemer(byte[] holderId, int shard) {
        return constr(7, bytes(holderId), bytes(ALICE_KEY), integer(40), integer(1_000_000), bytes(raw(ALICE)),
                emptyProof(), integer(shard));
    }

    static byte[] followRedeemer() {
        return constr(9);
    }

    // ------------------------------------------------------------------ parsed model

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount token(String policyHex, byte[] name, long quantity) {
        String nameHex = HexFormat.of().formatHex(name);
        return Amount.builder().unit(policyHex + (nameHex.isEmpty() ? "" : "." + nameHex)).policyId(policyHex)
                .assetName(nameHex).assetNameBytes(name).quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount usdm(long quantity) {
        return token(POLICY, USDM.assetName(), quantity);
    }

    static Amount shardToken(String policy, int shard) {
        return token(policy, new byte[] {(byte) shard}, 1);
    }

    static TransactionOutput output(String address, byte[] datum, Amount... amounts) {
        return TransactionOutput.builder().address(address).amounts(List.of(amounts))
                .inlineDatum(datum == null ? null : HexFormat.of().formatHex(datum)).build();
    }

    static TransactionBody tx(int hashByte, List<Amount> mint, TransactionOutput... outputs) {
        return TransactionBody.builder().txHash(HexFormat.of().formatHex(filled(32, hashByte)))
                .outputs(List.of(outputs)).mint(mint).build();
    }

    static Witnesses spends(byte[]... redeemers) {
        List<Redeemer> list = new ArrayList<>();
        for (int i = 0; i < redeemers.length; i++) {
            list.add(Redeemer.builder().tag(RedeemerTag.Spend).index(i)
                    .data(Datum.builder().cbor(HexFormat.of().formatHex(redeemers[i])).build()).build());
        }
        return Witnesses.builder().redeemers(list).build();
    }

    /** A block of transactions with their witness sets, in order; {@code invalid} lists phase-2-invalid indexes. */
    static Block block(List<Integer> invalid, List<TransactionBody> bodies, List<Witnesses> witnesses) {
        return Block.builder().transactionBodies(bodies).transactionWitness(witnesses).invalidTransactions(invalid)
                .build();
    }

    static Hash32 id(int value) {
        byte[] bytes = filled(32, value);
        return Hash32.of(bytes);
    }

    /** A claim id routed to {@code shard} by its last nibble. */
    static Hash32 claimOnShard(int seed, int shard) {
        byte[] bytes = filled(32, seed);
        bytes[31] = (byte) ((seed << 4) | shard);
        return Hash32.of(bytes);
    }
}
