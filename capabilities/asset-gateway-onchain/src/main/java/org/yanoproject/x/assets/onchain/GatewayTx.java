package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ValuesLib;
import org.yanoproject.x.proofs.onchain.MpfAnchorLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Transaction-level helpers shared by the gateway validators.
 *
 * <p>The exit-mode thread datum ({@code ExitModeV1}) is Plutus Data: {@code NORMAL = Constr 0 []} and
 * {@code EXIT = Constr 1 [frozenRoot: bytes .size 32, frozenHeight: int, frozenAtMs: int]}.
 */
@OnchainLibrary
public final class GatewayTx {
    private GatewayTx() {
    }

    /**
     * V11: the datum of the single reference input that carries the exit-mode thread token (policy
     * {@code threadPolicyId}, empty name) at exactly the enterprise address of {@code threadScriptHash}, when that
     * datum is a well-formed {@code ExitModeV1}; otherwise {@code Constr 9 []}.
     */
    public static PlutusData exitModeDatum(JulcList<TxInInfo> references, byte[] threadPolicyId,
                                           byte[] threadScriptHash) {
        JulcList<TxInInfo> threads = threadInputs(references, threadPolicyId, threadScriptHash);
        if (threads.isEmpty() || !threads.tail().isEmpty()) {
            return invalidMode();
        }
        return exitModeShape(inlineDatum(threads.head().resolved().datum()));
    }

    private static JulcList<TxInInfo> threadInputs(JulcList<TxInInfo> inputs, byte[] threadPolicyId,
                                                   byte[] threadScriptHash) {
        if (inputs.isEmpty()) {
            return JulcList.empty();
        }
        TxInInfo input = inputs.head();
        TxOut out = input.resolved();
        JulcList<TxInInfo> rest = threadInputs(inputs.tail(), threadPolicyId, threadScriptHash);
        return ValuesLib.assetOf(out.value(), threadPolicyId, Builtins.emptyByteString()).equals(BigInteger.ONE)
                && GatewayCodec.isEnterpriseScript(out.address().toPlutusData(), threadScriptHash)
                ? rest.prepend(input) : rest;
    }

    /** The datum itself when it is {@code NORMAL} or a well-formed {@code EXIT}; {@code Constr 9 []} otherwise. */
    public static PlutusData exitModeShape(PlutusData datum) {
        long tag = Builtins.constrTag(datum);
        PlutusData fields = Builtins.constrFields(datum);
        if (tag == 0) {
            return Builtins.nullList(fields) ? datum : invalidMode();
        }
        if (tag != 1 || Builtins.nullList(fields)) {
            return invalidMode();
        }
        byte[] root = Builtins.unBData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        BigInteger height = Builtins.unIData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger atMs = Builtins.unIData(Builtins.headList(f2));
        return Builtins.lengthOfByteString(root) == 32 && height.signum() >= 0 && atMs.signum() >= 0
                && Builtins.nullList(Builtins.tailList(f2)) ? datum : invalidMode();
    }

    /**
     * {@code postedAtMs} of a well-formed {@code ForcedWithdrawalV1} inbox datum, or {@code -1}:
     * {@code Constr 0 [account_id: bytes .size 28, vault_id: bytes .size 32, amount, recipient: bytes .size (1..128),
     * requester_key_hash: bytes .size 28, posted_at_ms: int >= 0]} with {@code amount = Constr 0 [int > 0]}
     * (an exact amount) or {@code Constr 1 []} (ALL), and nothing else.
     */
    public static BigInteger forcedPostedAtMs(PlutusData datum) {
        if (Builtins.constrTag(datum) != 0) {
            return BigInteger.valueOf(-1);
        }
        PlutusData fields = Builtins.constrFields(datum);
        byte[] account = Builtins.unBData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] vaultId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        PlutusData amount = Builtins.headList(f2);
        PlutusData f3 = Builtins.tailList(f2);
        long recipientLength = Builtins.lengthOfByteString(Builtins.unBData(Builtins.headList(f3)));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] requester = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        BigInteger postedAtMs = Builtins.unIData(Builtins.headList(f5));
        long amountTag = Builtins.constrTag(amount);
        PlutusData amountFields = Builtins.constrFields(amount);
        boolean amountValid = amountTag == 0
                ? Builtins.unIData(Builtins.headList(amountFields)).signum() > 0
                && Builtins.nullList(Builtins.tailList(amountFields))
                : amountTag == 1 && Builtins.nullList(amountFields);
        return Builtins.lengthOfByteString(account) == 28
                && Builtins.lengthOfByteString(vaultId) == 32
                && amountValid
                && recipientLength >= 1 && recipientLength <= 128
                && Builtins.lengthOfByteString(requester) == 28
                && postedAtMs.signum() >= 0
                && Builtins.nullList(Builtins.tailList(f5)) ? postedAtMs : BigInteger.valueOf(-1);
    }

    /** {@code requester_key_hash} of a {@code ForcedWithdrawalV1} datum (field 4). */
    public static byte[] forcedRequester(PlutusData datum) {
        return Builtins.unBData(Builtins.headList(Builtins.tailList(Builtins.tailList(Builtins.tailList(
                Builtins.tailList(Builtins.constrFields(datum)))))));
    }

    /** {@code frozenRoot} of an {@code EXIT} datum. */
    public static byte[] frozenRoot(PlutusData exitDatum) {
        return Builtins.unBData(Builtins.headList(Builtins.constrFields(exitDatum)));
    }

    public static PlutusData invalidMode() {
        return Builtins.constrData(9, Builtins.mkNilData());
    }

    /** An inline datum, or {@code Constr 9 []} for no datum or a datum hash. */
    public static PlutusData inlineDatum(OutputDatum datum) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return inline.datum();
        }
        return invalidMode();
    }

    /** The spend redeemer of {@code ref}, or {@code I -1} when it has none. */
    public static PlutusData spendRedeemer(TxInfo tx, TxOutRef ref) {
        Optional<PlutusData> redeemer = tx.redeemers().lookup(new ScriptPurpose.Spending(ref));
        return redeemer.isPresent() ? redeemer.get() : Builtins.iData(-1);
    }

    /** The number of outputs whose payment credential is the script {@code scriptHash}, at any stake part. */
    public static long outputsWithCredential(JulcList<TxOut> outputs, byte[] scriptHash) {
        if (outputs.isEmpty()) {
            return 0;
        }
        long rest = outputsWithCredential(outputs.tail(), scriptHash);
        return Builtins.equalsByteString(
                GatewayCodec.paymentScriptHash(outputs.head().address().toPlutusData()), scriptHash)
                ? rest + 1 : rest;
    }

    /** True when {@code tx} has no certificates and no withdrawals. */
    public static boolean noCertificatesOrWithdrawals(TxInfo tx) {
        return tx.certificates().isEmpty() && tx.withdrawals().isEmpty();
    }

    /** True when {@code keyHash} is among the required signers. */
    public static boolean signedBy(TxInfo tx, byte[] keyHash) {
        return MpfAnchorLib.signedBy(tx.signatories(), keyHash);
    }
}
