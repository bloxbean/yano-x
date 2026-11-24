package org.yanoproject.x.assets.onchain;

import org.julclang.core.types.AssetEntry;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * ADR-059 D8c: the one-shot policy of a vault's 16 nullifier shard thread tokens, one policy per vault (D4 step 4).
 * Parameterized by a bootstrap seed UTxO, so minting is possible only in the transaction that spends it. The mint
 * must be exactly the names {@code 0x00 .. 0x0F}, each at +1: no other name, no other quantity, and never a burn. The
 * shard validator re-emits its token on every spend. The policy cannot pin the shard address (the shard script hash
 * depends on the vault, which depends on this policy id); the {@code asset-shard-bootstrap-v1} observer verifies that
 * all 16 tokens sit at the shard address with an empty root before deposits are admitted (D8b, D8c).
 *
 * <p>The logic is the EUTxO bridge's {@code ShardThreadPolicy}, unchanged.
 */
@MintingValidator
public final class AssetShardThreadPolicy {

    /** Tx hash (32 bytes) of the bootstrap seed UTxO. */
    @Param
    static byte[] seedTxId;

    /** Output index of the bootstrap seed UTxO. */
    @Param
    static BigInteger seedIndex;

    @Entrypoint
    public static boolean validate(BigInteger redeemer, ScriptContext ctx) {
        byte[] ownPolicyHash = ContextsLib.ownHash(ctx);
        return consumesSeed(ctx.txInfo().inputs())
                && mintsExactlyShardTokens(ctx.txInfo().mint(), ownPolicyHash);
    }

    static boolean consumesSeed(
            JulcList<TxInInfo> inputs) {
        BigInteger matches = BigInteger.ZERO;
        for (TxInInfo input : inputs) {
            // TxId decodes to raw bytes at UPLC level — unwrap via cast
            // (the stdlib idiom; TxId.hash() would double-unwrap)
            byte[] txIdBytes = (byte[]) (Object) input.outRef().txId();
            boolean sameTx = txIdBytes.equals(seedTxId);
            boolean sameIndex = input.outRef().index().equals(seedIndex);
            if (sameTx && sameIndex) {
                matches = matches.add(BigInteger.ONE);
            }
        }
        return matches.compareTo(BigInteger.ZERO) > 0;
    }

    /**
     * The mint under our policy is exactly {0x00 … 0x0F}, each +1. A ledger
     * value map cannot hold duplicate names, so 16 entries that are each a
     * single byte below 16 at amount +1 ARE the full domain — no ordering or
     * duplicate check needed; any rider name/quantity/burn breaks one of the
     * three conditions.
     */
    static boolean mintsExactlyShardTokens(Value mint, byte[] ownPolicyHash) {
        BigInteger count = BigInteger.ZERO;
        boolean valid = true;
        for (AssetEntry entry : ValuesLib.flattenTyped(mint)) {
            if (Builtins.equalsByteString(entry.policyId(), ownPolicyHash)) {
                boolean shardName =
                        Builtins.lengthOfByteString(entry.tokenName()) == 1
                        && Builtins.indexByteString(entry.tokenName(), 0) < 16
                        && entry.amount().equals(BigInteger.ONE);
                if (shardName) {
                    count = count.add(BigInteger.ONE);
                } else {
                    valid = false;
                }
            }
        }
        return valid && count.equals(BigInteger.valueOf(16));
    }
}
