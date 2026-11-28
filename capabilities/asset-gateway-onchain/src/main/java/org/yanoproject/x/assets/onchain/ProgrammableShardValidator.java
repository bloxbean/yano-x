package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.ValuesLib;
import org.yanoproject.x.assets.onchain.mpf.MerklePatriciaForestry;
import org.yanoproject.x.assets.onchain.mpf.ProofStep;

import java.math.BigInteger;

/**
 * ADR-061 shard binding variant (D5): the ADR-059 D8c nullifier shard for a CIP-113 vault. It is
 * {@code AssetShardValidator} with one predicate replaced. A CIP-113 vault transaction has no input whose payment
 * credential is the vault script (PLB spends every vault input), so instead of requiring such an input, this shard
 * requires {@code Script(vaultScriptHash)} among the withdrawals, which means the custody script {@code h} ran. The
 * datum, redeemer, fold, proof-shape rules, continuation rules and thread policy are unchanged; the native shard's
 * bytes are unchanged too.
 *
 * <p>Parameterized by {@code (shardThreadPolicyId, h)} (D2 derivation step 7).
 */
@SpendingValidator
public final class ProgrammableShardValidator {
    @Param
    static byte[] shardThreadPolicyId;
    @Param
    static byte[] vaultScriptHash;

    record Insert(byte[] id, JulcList<ProofStep> proof) {
    }

    private ProgrammableShardValidator() {
    }

    @Entrypoint
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext context) {
        TxInfo tx = context.txInfo();
        TxInInfo own = ContextsLib.findOwnInput(context).get();
        byte[] ownHash = GatewayCodec.paymentScriptHash(own.resolved().address().toPlutusData());
        PlutusData fields = Builtins.constrFields(datum);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        BigInteger shardIndex = Builtins.unIData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] root = Builtins.unBData(Builtins.headList(f2));
        JulcList<Insert> inserts = (JulcList<Insert>) (Object) Builtins.unListData(
                Builtins.headList(Builtins.constrFields(redeemer)));
        byte[] name = Builtins.consByteString(shardIndex.longValue(), Builtins.emptyByteString());
        if (Builtins.constrTag(datum) != 0 || !version.equals(BigInteger.ONE)
                || !Builtins.nullList(Builtins.tailList(f2))
                || shardIndex.signum() < 0 || shardIndex.compareTo(BigInteger.valueOf(16)) >= 0
                || Builtins.lengthOfByteString(root) != 32
                || inserts.isEmpty()
                || !ValuesLib.assetOf(own.resolved().value(), shardThreadPolicyId, name).equals(BigInteger.ONE)
                || !withdrawn((PlutusData) (Object) tx.withdrawals())
                || ValuesLib.containsPolicy(tx.mint(), shardThreadPolicyId)
                || GatewayTx.outputsWithCredential(tx.outputs(), ownHash) != 1) {
            return false;
        }
        byte[] finalRoot = fold(inserts, root, shardIndex.longValue());
        TxOut next = continuation(tx.outputs(), ownHash);
        PlutusData expected = Builtins.constrData(0, Builtins.mkCons(Builtins.iData(1),
                Builtins.mkCons(Builtins.iData(shardIndex), Builtins.mkCons(Builtins.bData(finalRoot),
                        Builtins.mkNilData()))));
        return Builtins.lengthOfByteString(finalRoot) == 32
                && GatewayCodec.isEnterpriseScript(next.address().toPlutusData(), ownHash)
                && threadOnly(next.value(), name)
                && ValuesLib.lovelaceOf(next.value()).compareTo(ValuesLib.lovelaceOf(own.resolved().value())) >= 0
                && next.referenceScript().isEmpty()
                && Builtins.equalsData(GatewayTx.inlineDatum(next.datum()), expected);
    }

    /**
     * Each id must be absent under the running root (exclusion proof); the root then advances to its post-insert
     * value. A wrong-shard id or a failed exclusion collapses to a 1-byte sentinel that is never a root.
     */
    private static byte[] fold(JulcList<Insert> inserts, byte[] root, long shardIndex) {
        if (inserts.isEmpty()) {
            return root;
        }
        Insert insert = inserts.head();
        byte[] id = insert.id();
        if (Builtins.lengthOfByteString(id) != 32
                || Builtins.indexByteString(id, 31) % 16 != shardIndex
                || !MerklePatriciaForestry.miss(root, id, insert.proof())) {
            return Builtins.consByteString(0, Builtins.emptyByteString());
        }
        // including() does not check the proof shape; miss() above just checked this proof for this key.
        return fold(inserts.tail(), MerklePatriciaForestry.including(id, id, insert.proof()), shardIndex);
    }

    private static boolean withdrawn(PlutusData pairs) {
        if (Builtins.nullList(pairs)) {
            return false;
        }
        PlutusData key = Builtins.fstPair(Builtins.headList(pairs));
        return (Builtins.constrTag(key) == 1
                && Builtins.equalsByteString(Builtins.unBData(Builtins.headList(Builtins.constrFields(key))),
                vaultScriptHash))
                || withdrawn(Builtins.tailList(pairs));
    }

    private static TxOut continuation(JulcList<TxOut> outputs, byte[] ownHash) {
        TxOut out = outputs.head();
        if (Builtins.equalsByteString(GatewayCodec.paymentScriptHash(out.address().toPlutusData()), ownHash)) {
            return out;
        }
        return continuation(outputs.tail(), ownHash);
    }

    /** Lovelace plus exactly the one thread token named {@code name}, and nothing else. */
    private static boolean threadOnly(Value value, byte[] name) {
        PlutusData rest = Builtins.tailList(Builtins.unMapData(value));
        PlutusData policy = Builtins.headList(rest);
        PlutusData tokens = Builtins.unMapData(Builtins.sndPair(policy));
        PlutusData token = Builtins.headList(tokens);
        return Builtins.nullList(Builtins.tailList(rest))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(policy)), shardThreadPolicyId)
                && Builtins.nullList(Builtins.tailList(tokens))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(token)), name)
                && Builtins.unIData(Builtins.sndPair(token)).equals(BigInteger.ONE);
    }
}
