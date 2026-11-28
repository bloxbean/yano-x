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
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.lib.ValuesLib;
import org.yanoproject.x.proofs.onchain.MpfAnchorLib;
import org.yanoproject.x.proofs.onchain.MpfOnChainVerifier;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ADR-059 D8g §3: the domain's {@code ExitModeV1} thread (D4 step 3.3: parameterized by the thread policy id, the
 * anchor identity, the forced-inbox policy id, the timeouts and the pinned forced and status keys).
 *
 * <p>Datum {@code NORMAL = Constr 0 []}; {@code EXIT = Constr 1 [frozenRoot, frozenHeight, frozenAtMs]}. The only
 * spend is the permissionless {@code EnterExitMode}, from {@code NORMAL} only (exit mode is terminal, Q17): the one
 * continuation, at the exact enterprise address, holding only lovelace and the thread token, with no reference
 * script, carries {@code EXIT} taken from the referenced v2 anchor ({@code stateRoot}, {@code height},
 * {@code updatedAtMs}). One trigger must hold, with
 * {@code lb} the validity lower bound:
 * <ul>
 *   <li>{@code StaleAnchor} (a): {@code lb >= anchor.updatedAtMs + heartbeatTimeoutMs};</li>
 *   <li>{@code CensoredRequest(requestId, absence)} (b): a reference input at the exact inbox address carries the
 *       inbox token {@code requestId} with a well-formed datum; {@code lb >= postedAtMs + forcedDeadlineMs};
 *       {@code anchor.updatedAtMs >= postedAtMs + forcedDeadlineMs}; and {@code forcedKeyPrefix ‖ requestId} is
 *       absent under the anchor's root;</li>
 *   <li>{@code ProlongedPause(status)} (c): the anchored {@code DomainStatusV1} is {@code PAUSED} or {@code HALTED}
 *       and {@code lb >= sinceMs + maxPauseMs}.</li>
 * </ul>
 * The timeouts must lie within their D8g §8 bounds, or no trigger can fire.
 */
@SpendingValidator
public final class ExitModeThreadValidator {
    @Param
    static byte[] exitModeThreadPolicyId;
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
    static byte[] forcedInboxPolicyId;
    @Param
    static BigInteger heartbeatTimeoutMs;
    @Param
    static BigInteger forcedDeadlineMs;
    @Param
    static BigInteger maxPauseMs;
    @Param
    static byte[] forcedKeyPrefix;
    @Param
    static byte[] domainStatusKey;

    sealed interface Trigger permits StaleAnchor, CensoredRequest, ProlongedPause {
    }

    record StaleAnchor() implements Trigger {
    }

    record CensoredRequest(byte[] requestId, MpfOnChainVerifier.AbsenceProof absence) implements Trigger {
    }

    record ProlongedPause(MpfOnChainVerifier.Proof status) implements Trigger {
    }

    private ExitModeThreadValidator() {
    }

    @Entrypoint
    public static boolean validate(PlutusData datum, PlutusData redeemer, ScriptContext context) {
        TxInfo tx = context.txInfo();
        TxInInfo own = ContextsLib.findOwnInput(context).get();
        byte[] ownHash = GatewayCodec.paymentScriptHash(own.resolved().address().toPlutusData());
        Optional<MpfAnchorLib.AnchorState> anchorOptional = MpfAnchorLib.acceptedAnchorStateIn(tx.referenceInputs(),
                anchorThreadPolicyId, anchorThreadAssetName, anchorScriptHash, chainGenesisId, applicationId,
                commitmentProfileId, formatFingerprint);
        if (Builtins.constrTag(datum) != 0 || !Builtins.nullList(Builtins.constrFields(datum))
                || anchorOptional.isEmpty()
                || !anchorOptional.get().version().equals(BigInteger.TWO)
                || !within(heartbeatTimeoutMs, 86400000L, 2592000000L)
                || !within(forcedDeadlineMs, 86400000L, 2592000000L)
                || !within(maxPauseMs, 259200000L, 2592000000L)
                || GatewayTx.outputsWithCredential(tx.outputs(), ownHash) != 1) {
            return false;
        }
        MpfAnchorLib.AnchorState anchor = anchorOptional.get();
        BigInteger lowerBound = IntervalLib.finiteLowerBound(tx.validRange());
        TxOut next = continuation(tx.outputs(), ownHash);
        PlutusData frozen = Builtins.constrData(1, Builtins.mkCons(Builtins.bData(anchor.stateRoot()),
                Builtins.mkCons(Builtins.iData(anchor.height()), Builtins.mkCons(Builtins.iData(anchor.updatedAtMs()),
                        Builtins.mkNilData()))));
        return triggered((Trigger) (Object) redeemer, anchor, lowerBound, tx.referenceInputs())
                && GatewayCodec.isEnterpriseScript(next.address().toPlutusData(), ownHash)
                && threadOnly(next.value())
                && next.referenceScript().isEmpty()
                && Builtins.equalsData(GatewayTx.inlineDatum(next.datum()), frozen);
    }

    private static boolean triggered(Trigger trigger, MpfAnchorLib.AnchorState anchor, BigInteger lowerBound,
                                     JulcList<TxInInfo> references) {
        return switch (trigger) {
            case StaleAnchor stale -> lowerBound.compareTo(anchor.updatedAtMs().add(heartbeatTimeoutMs)) >= 0;
            case CensoredRequest censored -> censoredValid(censored, anchor, lowerBound, references);
            case ProlongedPause pause -> pausedValid(pause.status(), anchor.stateRoot(), lowerBound);
        };
    }

    private static boolean censoredValid(CensoredRequest censored, MpfAnchorLib.AnchorState anchor,
                                         BigInteger lowerBound, JulcList<TxInInfo> references) {
        byte[] requestId = censored.requestId();
        BigInteger deadline = requestPostedAt(references, requestId).add(forcedDeadlineMs);
        return Builtins.lengthOfByteString(requestId) == 32
                && lowerBound.compareTo(deadline) >= 0
                && anchor.updatedAtMs().compareTo(deadline) >= 0
                && Builtins.equalsByteString(censored.absence().key(),
                Builtins.appendByteString(forcedKeyPrefix, requestId))
                && MpfOnChainVerifier.verifyAbsence(censored.absence(), anchor.stateRoot());
    }

    /**
     * {@code postedAtMs} of the reference input at the exact inbox address that carries the inbox token
     * {@code requestId}, when its datum is well-formed; a value no deadline check passes otherwise (a malformed
     * datum fails the transaction).
     */
    private static BigInteger requestPostedAt(JulcList<TxInInfo> references, byte[] requestId) {
        if (references.isEmpty()) {
            return BigInteger.valueOf(4611686018427387904L);
        }
        TxOut out = references.head().resolved();
        if (ValuesLib.assetOf(out.value(), forcedInboxPolicyId, requestId).equals(BigInteger.ONE)
                && GatewayCodec.isEnterpriseScript(out.address().toPlutusData(), forcedInboxPolicyId)) {
            BigInteger posted = GatewayTx.forcedPostedAtMs(GatewayTx.inlineDatum(out.datum()));
            return posted.signum() >= 0 ? posted : BigInteger.valueOf(4611686018427387904L);
        }
        return requestPostedAt(references.tail(), requestId);
    }

    private static boolean pausedValid(MpfOnChainVerifier.Proof proof, byte[] root, BigInteger lowerBound) {
        GatewayCodec.DomainStatus status = GatewayCodec.domainStatus(proof.value());
        long code = status.status().longValue();
        return Builtins.equalsByteString(proof.key(), domainStatusKey)
                && (code == 1 || code == 2)
                && lowerBound.compareTo(status.sinceMs().add(maxPauseMs)) >= 0
                && MpfOnChainVerifier.verifyInclusion(proof, root);
    }

    private static boolean within(BigInteger value, long floor, long ceiling) {
        return value.compareTo(BigInteger.valueOf(floor)) >= 0 && value.compareTo(BigInteger.valueOf(ceiling)) <= 0;
    }

    /** Lovelace plus exactly one thread token (empty name), and nothing else. */
    private static boolean threadOnly(Value value) {
        PlutusData rest = Builtins.tailList(Builtins.unMapData(value));
        PlutusData policy = Builtins.headList(rest);
        PlutusData tokens = Builtins.unMapData(Builtins.sndPair(policy));
        PlutusData token = Builtins.headList(tokens);
        return Builtins.nullList(Builtins.tailList(rest))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(policy)), exitModeThreadPolicyId)
                && Builtins.nullList(Builtins.tailList(tokens))
                && Builtins.lengthOfByteString(Builtins.unBData(Builtins.fstPair(token))) == 0
                && Builtins.unIData(Builtins.sndPair(token)).equals(BigInteger.ONE);
    }

    private static TxOut continuation(JulcList<TxOut> outputs, byte[] ownHash) {
        TxOut out = outputs.head();
        if (Builtins.equalsByteString(GatewayCodec.paymentScriptHash(out.address().toPlutusData()), ownHash)) {
            return out;
        }
        return continuation(outputs.tail(), ownHash);
    }
}
