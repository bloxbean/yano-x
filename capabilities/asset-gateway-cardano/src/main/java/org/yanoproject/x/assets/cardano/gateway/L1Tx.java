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

    /**
     * ADR-061 D6, D4: the script credentials among the withdrawals, in ledger order (script before key credentials,
     * bytewise), each with the outer constructor index of its reward redeemer when that redeemer is a constructor;
     * each reward redeemer is indexed by its withdrawal's ledger position, so it is attributed exactly. Empty
     * ({@code OVERFLOW}) above {@code cap} script withdrawals or when the withdrawals or witness set cannot be read.
     */
    static java.util.Optional<List<org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1.Withdrawal>>
    scriptWithdrawals(com.bloxbean.cardano.yaci.core.model.TransactionBody body, Block block, int blockIndex,
                      int cap) {
        List<byte[]> accounts = rewardAccounts(body);
        if (accounts == null) return java.util.Optional.empty();
        java.util.Map<Integer, byte[]> rewardRedeemers = rewardRedeemers(block, blockIndex);
        if (rewardRedeemers == null) return java.util.Optional.empty();
        List<org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1.Withdrawal> out = new ArrayList<>();
        for (int position = 0; position < accounts.size(); position++) {
            byte[] account = accounts.get(position);
            if (((account[0] & 0xff) >>> 4) != 0xf) continue; // a key credential
            if (out.size() == cap) return java.util.Optional.empty();
            byte[] hash = java.util.Arrays.copyOfRange(account, 1, 29);
            byte[] redeemer = rewardRedeemers.get(position);
            long ctor = redeemer == null ? -1
                    : org.yanoproject.x.assets.contracts.internal.PlutusDataReader.outerConstructor(redeemer);
            out.add(new org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1.Withdrawal(hash,
                    ctor < 0 ? java.util.Optional.empty() : java.util.Optional.of(ctor)));
        }
        return java.util.Optional.of(out);
    }

    /** The reward accounts of the withdrawals in ledger order, or null when one cannot be read. */
    static List<byte[]> rewardAccounts(com.bloxbean.cardano.yaci.core.model.TransactionBody body) {
        List<byte[]> accounts = new ArrayList<>();
        if (body.getWithdrawals() == null) return accounts;
        for (String key : body.getWithdrawals().keySet()) {
            byte[] account;
            try {
                account = key.startsWith("stake") ? new Address(key).getBytes() : HexFormat.of().parseHex(key);
            } catch (RuntimeException unreadable) {
                return null;
            }
            if (account == null || account.length != 29) return null;
            accounts.add(account);
        }
        accounts.sort((a, b) -> {
            int kind = Integer.compare(((a[0] & 0xff) >>> 4) == 0xf ? 0 : 1, ((b[0] & 0xff) >>> 4) == 0xf ? 0 : 1);
            return kind != 0 ? kind : java.util.Arrays.compareUnsigned(a, 1, 29, b, 1, 29);
        });
        return accounts;
    }

    /** The decoded data of the reward redeemers by index, or null when the witness set cannot be read. */
    static java.util.Map<Integer, byte[]> rewardRedeemers(Block block, int blockIndex) {
        java.util.Map<Integer, byte[]> out = new java.util.HashMap<>();
        if (block.getTransactionWitness() == null || blockIndex >= block.getTransactionWitness().size()) return out;
        Witnesses witnesses = block.getTransactionWitness().get(blockIndex);
        if (witnesses == null || witnesses.getRedeemers() == null) return out;
        for (Redeemer redeemer : witnesses.getRedeemers()) {
            if (redeemer == null) return null;
            if (redeemer.getTag() != RedeemerTag.Reward) continue;
            byte[] data = redeemer.getData() == null ? null : inlineDatum(redeemer.getData().getCbor());
            if (data == null) return null;
            out.put(redeemer.getIndex(), data);
        }
        return out;
    }
}
