package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Total, deterministic reads of the parsed L1 block model for the gateway observers. Every reader returns
 * {@code null} (or an empty list) for content it cannot read, never throws, so an observer can skip such content the
 * same way on every member (I2).
 */
final class L1Tx {
    private static final String LOVELACE = "lovelace";

    private L1Tx() {
    }

    /** A token entry of a value or a mint: policy (lowercase hex), name and signed quantity. */
    record Token(String policyHex, byte[] name, BigInteger quantity) {
    }

    static Hash32 txId(String hex) {
        try {
            return hex == null ? null : Hash32.of(HexFormat.of().parseHex(hex));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** The inline datum bytes, or null when there is none or it is not hex. */
    static byte[] inlineDatum(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException notHex) {
            return null;
        }
    }

    /** The raw Shelley address bytes of a bech32 output address, or null (Byron, malformed). */
    static byte[] addressBytes(String bech32) {
        if (bech32 == null || !bech32.startsWith("addr")) return null;
        try {
            byte[] bytes = new Address(bech32).getBytes();
            return bytes == null || bytes.length == 0 ? null : bytes;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** The payment credential of a script-payment Shelley address (header types 1, 3, 5, 7), or null. */
    static byte[] scriptPaymentCredential(String bech32) {
        byte[] bytes = addressBytes(bech32);
        if (bytes == null || bytes.length < 29) return null;
        int type = (bytes[0] & 0xff) >>> 4;
        if (type > 7 || (type & 1) == 0) return null;
        byte[] credential = new byte[28];
        System.arraycopy(bytes, 1, credential, 0, 28);
        return credential;
    }

    /** The native-token entries of a value or mint (lovelace excluded); null when one cannot be read. */
    static List<Token> tokens(List<Amount> amounts) {
        List<Token> out = new ArrayList<>();
        if (amounts == null) return out;
        for (Amount amount : amounts) {
            if (amount == null || LOVELACE.equals(amount.getUnit())) continue;
            try {
                String unit = amount.getUnit();
                String policy = amount.getPolicyId();
                if (policy == null && unit != null && unit.length() >= 56) policy = unit.substring(0, 56);
                byte[] name = amount.getAssetNameBytes();
                if (name == null && unit != null && unit.length() > 56) {
                    String rest = unit.substring(56);
                    name = HexFormat.of().parseHex(rest.startsWith(".") ? rest.substring(1) : rest);
                }
                if (policy == null || policy.length() != 56 || amount.getQuantity() == null) return null;
                HexFormat.of().parseHex(policy);
                out.add(new Token(policy.toLowerCase(Locale.ROOT), name == null ? new byte[0] : name,
                        amount.getQuantity()));
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
        return out;
    }

    /** The entries of one policy; null when the value or mint cannot be read. */
    static List<Token> tokensOf(List<Amount> amounts, String policyHex) {
        List<Token> all = tokens(amounts);
        if (all == null) return null;
        return all.stream().filter(token -> token.policyHex().equals(policyHex)).toList();
    }

    /**
     * The decoded data of the transaction's spend redeemers, in witness order, or null when its witness set is
     * missing or a spend redeemer's data cannot be read: an observer that needs the vault redeemer must then skip
     * the transaction rather than guess.
     */
    static List<byte[]> spendRedeemers(Block block, int blockIndex) {
        if (block.getTransactionWitness() == null || blockIndex >= block.getTransactionWitness().size()) return null;
        Witnesses witnesses = block.getTransactionWitness().get(blockIndex);
        if (witnesses == null) return null;
        List<byte[]> out = new ArrayList<>();
        if (witnesses.getRedeemers() == null) return out;
        for (Redeemer redeemer : witnesses.getRedeemers()) {
            if (redeemer == null) return null;
            if (redeemer.getTag() != RedeemerTag.Spend) continue;
            byte[] data = redeemer.getData() == null ? null : inlineDatum(redeemer.getData().getCbor());
            if (data == null) return null;
            out.add(data);
        }
        return out;
    }
}
