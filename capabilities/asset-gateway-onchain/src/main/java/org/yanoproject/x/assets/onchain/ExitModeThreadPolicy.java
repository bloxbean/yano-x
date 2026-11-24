package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.lib.ContextsLib;

import java.math.BigInteger;

/**
 * ADR-059 D8g §3: the one-shot policy of a domain's single {@code ExitModeV1} thread token (D4 step 3.1). Minting is
 * valid only in the transaction that spends the seed outpoint, and only as exactly one token with the empty name at
 * +1 under this policy; a burn is never valid, so the thread lives forever. The policy cannot pin the thread address
 * (the exit-mode validator takes this policy id); the domain's bootstrap check verifies that the token sits at the
 * exit-mode validator's enterprise address with {@code NORMAL}.
 */
@MintingValidator
public final class ExitModeThreadPolicy {
    /** Tx hash (32 bytes) of the seed UTxO. */
    @Param
    static byte[] seedTxId;

    /** Output index of the seed UTxO. */
    @Param
    static BigInteger seedIndex;

    private ExitModeThreadPolicy() {
    }

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext context) {
        byte[] ownPolicy = ContextsLib.ownHash(context);
        PlutusData policies = Builtins.unMapData(context.txInfo().mint());
        return consumesSeed(context.txInfo().inputs()) && onlyThreadToken(policies, ownPolicy);
    }

    private static boolean consumesSeed(JulcList<TxInInfo> inputs) {
        if (inputs.isEmpty()) {
            return false;
        }
        TxInInfo input = inputs.head();
        return (Builtins.equalsByteString((byte[]) (Object) input.outRef().txId(), seedTxId)
                && input.outRef().index().equals(seedIndex))
                || consumesSeed(inputs.tail());
    }

    /** Under {@code ownPolicy} the mint holds exactly {@code {"" -> 1}}. */
    private static boolean onlyThreadToken(PlutusData policies, byte[] ownPolicy) {
        if (Builtins.nullList(policies)) {
            return false;
        }
        PlutusData entry = Builtins.headList(policies);
        if (!Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(entry)), ownPolicy)) {
            return onlyThreadToken(Builtins.tailList(policies), ownPolicy);
        }
        PlutusData tokens = Builtins.unMapData(Builtins.sndPair(entry));
        PlutusData token = Builtins.headList(tokens);
        return Builtins.nullList(Builtins.tailList(tokens))
                && Builtins.lengthOfByteString(Builtins.unBData(Builtins.fstPair(token))) == 0
                && Builtins.unIData(Builtins.sndPair(token)).equals(BigInteger.ONE);
    }
}
