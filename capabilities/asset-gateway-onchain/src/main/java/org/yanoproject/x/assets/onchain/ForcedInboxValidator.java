package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.Purpose;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.lib.ValuesLib;
import org.yanoproject.x.proofs.onchain.MpfAnchorLib;
import org.yanoproject.x.proofs.onchain.MpfOnChainVerifier;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ADR-059 D8g R1/R2: the forced-withdrawal inbox, ONE multi-purpose Plutus V3 script. Its policy id equals its script
 * hash, which is both the request-token policy and the inbox's exact enterprise address (D4 step 3.2: parameterized by
 * the gateway domain, the anchor identity, the exit-mode thread policy id, the forced key prefix and the mint window).
 *
 * <p><b>Mint</b>, redeemer {@code Constr 0 []} (request): exactly one token, named
 * {@code requestId = blake2b-256("forced\0" ‖ [txId, index])} of the transaction's first (smallest) spent input, at
 * +1, in the one output that carries this policy, at the exact inbox address, holding no other token of this policy,
 * with a well-formed {@code ForcedWithdrawalV1} inline datum whose {@code postedAtMs} equals the validity upper bound,
 * the validity window being at most {@code maxMintWindowMs}, and whose {@code requesterKeyHash} signs. Redeemer
 * {@code Constr 1 []} (burn): every entry of this policy is -1 and names a spent inbox token; nothing is minted.
 *
 * <p><b>Spend</b>: the token never moves; it can only be burned, together with this spend, and the input's lovelace
 * goes back to an output whose payment key is {@code requesterKeyHash}. Redeemer {@code Constr 0 [proof]}: the
 * referenced v2 anchor's root includes {@code forcedKeyPrefix ‖ requestId} (the request was processed). Redeemer
 * {@code Constr 1 []}: the exit-mode thread, authenticated by its policy and the empty token name, is {@code EXIT}.
 * The inbox cannot pin the exit-mode address (that validator takes this policy id); the token is one-shot and the
 * domain's bootstrap check pins its address.
 */
@MultiValidator
public final class ForcedInboxValidator {
    @Param
    static byte[] gatewayDomainId;
    @Param
    static byte[] anchorThreadPolicyId;
    @Param
    static byte[] anchorThreadAssetName;
    @Param
    static byte[] anchorScriptHash;
    @Param
    static byte[] chainGenesisId;
    @Param
    static byte[] applicationId;
    @Param
    static byte[] commitmentProfileId;
    @Param
    static byte[] formatFingerprint;
    @Param
    static byte[] exitModeThreadPolicyId;
    @Param
    static byte[] forcedKeyPrefix;
    @Param
    static BigInteger maxMintWindowMs;

    private ForcedInboxValidator() {
    }

    @Entrypoint(purpose = Purpose.MINT)
    static boolean mint(PlutusData redeemer, ScriptContext context) {
        TxInfo tx = context.txInfo();
        byte[] policy = ContextsLib.ownHash(context);
        PlutusData minted = policyTokens(Builtins.unMapData(tx.mint()), policy);
        if (Builtins.constrTag(redeemer) == 1) {
            return !Builtins.nullList(minted) && burnsSpentTokens(minted, tx.inputs(), policy);
        }
        TxInInfo first = tx.inputs().head();
        byte[] requestId = GatewayCodec.forcedRequestId((byte[]) (Object) first.outRef().txId(),
                first.outRef().index());
        PlutusData token = Builtins.headList(minted);
        JulcList<TxOut> carriers = carriers(tx.outputs(), policy);
        if (Builtins.nullList(minted) || !Builtins.nullList(Builtins.tailList(minted))
                || !Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(token)), requestId)
                || !Builtins.unIData(Builtins.sndPair(token)).equals(BigInteger.ONE)
                || carriers.isEmpty() || !carriers.tail().isEmpty()) {
            return false;
        }
        TxOut inbox = carriers.head();
        PlutusData datum = GatewayTx.inlineDatum(inbox.datum());
        BigInteger postedAtMs = GatewayTx.forcedPostedAtMs(datum);
        BigInteger upper = IntervalLib.finiteUpperBound(tx.validRange());
        BigInteger lower = IntervalLib.finiteLowerBound(tx.validRange());
        return Builtins.lengthOfByteString(gatewayDomainId) == 32
                && GatewayCodec.isEnterpriseScript(inbox.address().toPlutusData(), policy)
                && Builtins.nullList(Builtins.tailList(policyTokens(Builtins.unMapData(inbox.value()), policy)))
                && postedAtMs.signum() >= 0
                && postedAtMs.equals(upper)
                && lower.signum() >= 0
                && upper.subtract(lower).compareTo(maxMintWindowMs) <= 0
                && GatewayTx.signedBy(tx, GatewayTx.forcedRequester(datum));
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean spend(PlutusData redeemer, ScriptContext context) {
        TxInfo tx = context.txInfo();
        TxInInfo own = ContextsLib.findOwnInput(context).get();
        TxOut out = own.resolved();
        byte[] policy = GatewayCodec.paymentScriptHash(out.address().toPlutusData());
        PlutusData held = policyTokens(Builtins.unMapData(out.value()), policy);
        PlutusData token = Builtins.headList(held);
        byte[] requestId = Builtins.unBData(Builtins.fstPair(token));
        PlutusData datum = GatewayTx.inlineDatum(out.datum());
        if (!Builtins.nullList(Builtins.tailList(held))
                || !Builtins.unIData(Builtins.sndPair(token)).equals(BigInteger.ONE)
                || !ValuesLib.assetOf(tx.mint(), policy, requestId).equals(BigInteger.valueOf(-1))
                || !refunded(tx.outputs(), GatewayTx.forcedRequester(datum), ValuesLib.lovelaceOf(out.value()))) {
            return false;
        }
        if (Builtins.constrTag(redeemer) == 1) {
            return exitMode(tx.referenceInputs());
        }
        MpfOnChainVerifier.Proof proof = (MpfOnChainVerifier.Proof) (Object) Builtins.headList(
                Builtins.constrFields(redeemer));
        Optional<MpfAnchorLib.AnchorState> anchor = MpfAnchorLib.acceptedAnchorStateIn(tx.referenceInputs(),
                anchorThreadPolicyId, anchorThreadAssetName, anchorScriptHash, chainGenesisId, applicationId,
                commitmentProfileId, formatFingerprint);
        return anchor.isPresent()
                && anchor.get().version().equals(BigInteger.TWO)
                && Builtins.equalsByteString(proof.key(), Builtins.appendByteString(forcedKeyPrefix, requestId))
                && MpfOnChainVerifier.verifyInclusion(proof, anchor.get().stateRoot());
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

    /** Every minted pair is {@code (name, -1)} for a spent input holding that token. */
    private static boolean burnsSpentTokens(PlutusData minted, JulcList<TxInInfo> inputs, byte[] policy) {
        if (Builtins.nullList(minted)) {
            return true;
        }
        PlutusData pair = Builtins.headList(minted);
        return Builtins.unIData(Builtins.sndPair(pair)).equals(BigInteger.valueOf(-1))
                && spends(inputs, policy, Builtins.unBData(Builtins.fstPair(pair)))
                && burnsSpentTokens(Builtins.tailList(minted), inputs, policy);
    }

    private static boolean spends(JulcList<TxInInfo> inputs, byte[] policy, byte[] name) {
        if (inputs.isEmpty()) {
            return false;
        }
        return ValuesLib.assetOf(inputs.head().resolved().value(), policy, name).equals(BigInteger.ONE)
                || spends(inputs.tail(), policy, name);
    }

    private static JulcList<TxOut> carriers(JulcList<TxOut> outputs, byte[] policy) {
        if (outputs.isEmpty()) {
            return JulcList.empty();
        }
        TxOut out = outputs.head();
        JulcList<TxOut> rest = carriers(outputs.tail(), policy);
        return ValuesLib.containsPolicy(out.value(), policy) ? rest.prepend(out) : rest;
    }

    /** Some output pays the requester's payment key at least {@code lovelace}. */
    private static boolean refunded(JulcList<TxOut> outputs, byte[] requester, BigInteger lovelace) {
        if (outputs.isEmpty()) {
            return false;
        }
        TxOut out = outputs.head();
        return (Builtins.equalsByteString(GatewayCodec.paymentKeyHash(out.address().toPlutusData()), requester)
                && ValuesLib.lovelaceOf(out.value()).compareTo(lovelace) >= 0)
                || refunded(outputs.tail(), requester, lovelace);
    }

    /** A reference input holds the exit-mode thread token (empty name) and an {@code EXIT} datum. */
    private static boolean exitMode(JulcList<TxInInfo> references) {
        if (references.isEmpty()) {
            return false;
        }
        TxOut out = references.head().resolved();
        return (ValuesLib.assetOf(out.value(), exitModeThreadPolicyId, Builtins.emptyByteString())
                .equals(BigInteger.ONE)
                && Builtins.constrTag(GatewayTx.exitModeShape(GatewayTx.inlineDatum(out.datum()))) == 1)
                || exitMode(references.tail());
    }
}
