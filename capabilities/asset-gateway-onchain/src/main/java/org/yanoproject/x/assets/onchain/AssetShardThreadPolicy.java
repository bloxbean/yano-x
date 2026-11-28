package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.AssetEntry;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
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
 * shard validator re-emits its token on every spend. Every output that receives a token holds exactly one token of
 * this policy and the inline datum {@code ShardDatumV1(1, i, emptyRoot)} for its name {@code [i]}, the empty root being
 * 32 zero bytes. The policy cannot pin the shard address (the shard script hash depends on the vault, which depends on
 * this policy id); the {@code asset-shard-bootstrap-v1} observer verifies that all 16 tokens sit at the shard address
 * before deposits are admitted (D8b, D8c).
 *
 * <p>The mint check is the EUTxO bridge's {@code ShardThreadPolicy}; the output check is new.
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
                && mintsExactlyShardTokens(ctx.txInfo().mint(), ownPolicyHash)
                && shardOutputs(ctx.txInfo().outputs(), ownPolicyHash);
    }

    /** Every output holding this policy holds one token {@code [i]} and {@code ShardDatumV1(1, i, emptyRoot)}. */
    static boolean shardOutputs(JulcList<TxOut> outputs, byte[] policy) {
        if (outputs.isEmpty()) {
            return true;
        }
        TxOut out = outputs.head();
        PlutusData tokens = policyTokens(Builtins.unMapData(out.value()), policy);
        if (Builtins.nullList(tokens)) {
            return shardOutputs(outputs.tail(), policy);
        }
        PlutusData token = Builtins.headList(tokens);
        byte[] name = Builtins.unBData(Builtins.fstPair(token));
        return Builtins.nullList(Builtins.tailList(tokens))
                && Builtins.unIData(Builtins.sndPair(token)).equals(BigInteger.ONE)
                && Builtins.lengthOfByteString(name) == 1
                && Builtins.equalsData(GatewayTx.inlineDatum(out.datum()), Builtins.constrData(0,
                Builtins.mkCons(Builtins.iData(1), Builtins.mkCons(Builtins.iData(Builtins.indexByteString(name, 0)),
                        Builtins.mkCons(Builtins.bData(new byte[]{0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0, 0}), Builtins.mkNilData())))))
                && shardOutputs(outputs.tail(), policy);
    }

    /** The token pairs of {@code policy} in a value map's pair list; empty when the policy is absent. */
    private static PlutusData policyTokens(PlutusData policies, byte[] policy) {
        if (Builtins.nullList(policies)) {
            return Builtins.mkNilPairData();
        }
        PlutusData entry = Builtins.headList(policies);
        if (Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(entry)), policy)) {
            return Builtins.unMapData(Builtins.sndPair(entry));
        }
        return policyTokens(Builtins.tailList(policies), policy);
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
