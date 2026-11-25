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
 * ADR-059 D8c nullifier shard: one of a vault's 16 threads, each an MPF root over paid claim ids and escape ids.
 * Parameterized by {@code (shardThreadPolicyId, vaultScriptHash)} (D4 step 7), so it pairs only with its own vault
 * and the vault never needs the shard's hash.
 *
 * <p>Datum {@code ShardDatumV1 = Constr 0 [1, shardIndex, nullifierRoot]}. Redeemer {@code InsertBatch = Constr 0
 * [[Insert = Constr 0 [id, proof]]]}; the vault reads the ordered ids from it. A spend requires: the own input holds
 * the thread token {@code [shardIndex]}; this vault is spent in the same transaction; every id is 32 bytes, routes to
 * this shard by its last nibble and is absent under the running root, which then advances to the post-insert root
 * (I6, I17); exactly one output has this script's payment credential, at the exact enterprise address, with no
 * reference script, holding only lovelace (at least the spent shard's, so a payout cannot drain the shard's min-ADA
 * into its fee) and the one thread token, with {@code ShardDatumV1(1, shardIndex, finalRoot)}; and the mint never
 * touches the thread policy. The MPF value of an id is the id itself.
 */
@SpendingValidator
public final class AssetShardValidator {
    @Param
    static byte[] shardThreadPolicyId;
    @Param
    static byte[] vaultScriptHash;

    record Insert(byte[] id, JulcList<ProofStep> proof) {
    }

    private AssetShardValidator() {
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
                || !vaultSpent(tx.inputs())
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

    private static boolean vaultSpent(JulcList<TxInInfo> inputs) {
        if (inputs.isEmpty()) {
            return false;
        }
        return Builtins.equalsByteString(
                GatewayCodec.paymentScriptHash(inputs.head().resolved().address().toPlutusData()), vaultScriptHash)
                || vaultSpent(inputs.tail());
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
