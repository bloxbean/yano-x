package org.yanoproject.x.proofs.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Shared validation of the unique SCRIPT anchor reference input and its commitment identity.
 *
 * <p>{@link #acceptedAnchor} reads only the v1 datum and returns its state root, as it always has.
 * {@link #acceptedAnchorState} (ADR-059 §7 framework addition 1) also reads the v2 datum, which
 * appends {@code updated-at-ms} (POSIX milliseconds) as field 12, and returns the anchored height,
 * the member keys and the threshold, after checking that the keys are strictly ascending, unique and
 * 32 bytes each and that {@code 1 <= threshold <= count} (ADR-059 D8h §8).
 */
@OnchainLibrary
public final class MpfAnchorLib {
    public record AnchorRoot(byte[] stateRoot) { }

    /**
     * An accepted anchor datum. {@code memberKeys} is the datum's Plutus-Data list of 32-byte keys
     * (a list value: read it with {@code unListData}), already validated; {@code updatedAtMs} is
     * {@code -1} for a v1 datum, which has no such field.
     */
    public record AnchorState(BigInteger version, BigInteger height, byte[] stateRoot,
                              PlutusData memberKeys, BigInteger memberCount, BigInteger threshold,
                              BigInteger updatedAtMs) { }

    private MpfAnchorLib() { }

    public static Optional<AnchorRoot> acceptedAnchor(
            ScriptContext context, byte[] anchorThreadPolicyId,
            byte[] anchorThreadAssetName, byte[] anchorScriptHash,
            byte[] expectedChainGenesisId, byte[] expectedApplicationId,
            byte[] expectedCommitmentProfileId, byte[] expectedFormatFingerprint) {
        Optional<AnchorRoot> result = Optional.empty();
        BigInteger count = BigInteger.ZERO;
        for (TxInInfo input : context.txInfo().referenceInputs()) {
            if (ValuesLib.assetOf(input.resolved().value(), anchorThreadPolicyId,
                    anchorThreadAssetName).equals(BigInteger.ONE)
                    && atScriptAddress(input.resolved(), anchorScriptHash)) {
                count = count.add(BigInteger.ONE);
                result = anchor(input.resolved().datum(), expectedChainGenesisId,
                        expectedApplicationId, expectedCommitmentProfileId,
                        expectedFormatFingerprint);
            }
        }
        return count.equals(BigInteger.ONE) ? result : Optional.empty();
    }

    /**
     * Like {@link #acceptedAnchor}, for a v1 or v2 datum, returning the full {@link AnchorState}.
     * The caller decides which versions it accepts.
     */
    public static Optional<AnchorState> acceptedAnchorState(
            ScriptContext context, byte[] anchorThreadPolicyId,
            byte[] anchorThreadAssetName, byte[] anchorScriptHash,
            byte[] expectedChainGenesisId, byte[] expectedApplicationId,
            byte[] expectedCommitmentProfileId, byte[] expectedFormatFingerprint) {
        return acceptedAnchorStateIn(context.txInfo().referenceInputs(), anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash, expectedChainGenesisId, expectedApplicationId,
                expectedCommitmentProfileId, expectedFormatFingerprint);
    }

    /** {@link #acceptedAnchorState} over an explicit reference-input list. */
    public static Optional<AnchorState> acceptedAnchorStateIn(
            JulcList<TxInInfo> referenceInputs, byte[] anchorThreadPolicyId,
            byte[] anchorThreadAssetName, byte[] anchorScriptHash,
            byte[] expectedChainGenesisId, byte[] expectedApplicationId,
            byte[] expectedCommitmentProfileId, byte[] expectedFormatFingerprint) {
        JulcList<TxInInfo> anchors = anchorInputs(referenceInputs, anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash);
        if (anchors.isEmpty() || !anchors.tail().isEmpty()) {
            return Optional.empty();
        }
        return anchorStateOf(anchors.head().resolved().datum(), expectedChainGenesisId,
                expectedApplicationId, expectedCommitmentProfileId, expectedFormatFingerprint);
    }

    /** The reference inputs that carry the thread token at the anchor script address. */
    private static JulcList<TxInInfo> anchorInputs(
            JulcList<TxInInfo> inputs, byte[] anchorThreadPolicyId,
            byte[] anchorThreadAssetName, byte[] anchorScriptHash) {
        if (inputs.isEmpty()) {
            return JulcList.empty();
        }
        TxInInfo input = inputs.head();
        JulcList<TxInInfo> rest = anchorInputs(inputs.tail(), anchorThreadPolicyId,
                anchorThreadAssetName, anchorScriptHash);
        boolean anchor = ValuesLib.assetOf(input.resolved().value(), anchorThreadPolicyId,
                anchorThreadAssetName).equals(BigInteger.ONE)
                && atScriptAddress(input.resolved(), anchorScriptHash);
        return anchor ? rest.prepend(input) : rest;
    }

    /**
     * True when at least {@code threshold} of the anchor's member keys sign the transaction: for each
     * key, {@code blake2b-224(key)} is in {@code extraSignatories}, whose witnesses the ledger
     * verified. Each key counts once, because the keys were checked strictly ascending.
     */
    public static boolean signedByThreshold(TxInfo txInfo, AnchorState anchor) {
        return signedCount(txInfo, Builtins.unListData(anchor.memberKeys()))
                .compareTo(anchor.threshold()) >= 0;
    }

    private static BigInteger signedCount(TxInfo txInfo, PlutusData keys) {
        if (Builtins.nullList(keys)) {
            return BigInteger.ZERO;
        }
        BigInteger rest = signedCount(txInfo, Builtins.tailList(keys));
        return signedBy(txInfo.signatories(), Builtins.blake2b_224(Builtins.unBData(Builtins.headList(keys))))
                ? rest.add(BigInteger.ONE) : rest;
    }

    /**
     * True when {@code keyHash} is among {@code signers}. (The stdlib's {@code ContextsLib.signedBy},
     * called from a library with a computed hash, unwraps the hash a second time under julc 0.1.0-pre20
     * and fails at run time.)
     */
    public static boolean signedBy(JulcList<PubKeyHash> signers, byte[] keyHash) {
        if (signers.isEmpty()) {
            return false;
        }
        return Builtins.equalsByteString(signers.head().hash(), keyHash) || signedBy(signers.tail(), keyHash);
    }

    private static Optional<AnchorState> anchorStateOf(
            OutputDatum datum, byte[] expectedChainGenesisId,
            byte[] expectedApplicationId, byte[] expectedCommitmentProfileId,
            byte[] expectedFormatFingerprint) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return anchorStateData(inline.datum(), expectedChainGenesisId,
                    expectedApplicationId, expectedCommitmentProfileId,
                    expectedFormatFingerprint);
        }
        return Optional.empty();
    }

    /** The v1 field list, or the v2 list with {@code updated-at-ms} appended. */
    static Optional<AnchorState> anchorStateData(
            PlutusData value, byte[] expectedChainGenesisId,
            byte[] expectedApplicationId, byte[] expectedCommitmentProfileId,
            byte[] expectedFormatFingerprint) {
        if (Builtins.constrTag(value) != 0) return Optional.empty();
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] genesis = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] application = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] profile = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] fingerprint = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger height = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] blockHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData membersData = Builtins.headList(f9);
        PlutusData members = Builtins.unListData(membersData);
        PlutusData f10 = Builtins.tailList(f9);
        BigInteger threshold = Builtins.unIData(Builtins.headList(f10));
        PlutusData f11 = Builtins.tailList(f10);
        boolean v2 = version.equals(BigInteger.TWO);
        BigInteger updatedAtMs = v2 ? Builtins.unIData(Builtins.headList(f11)) : BigInteger.valueOf(-1);
        PlutusData trailing = v2 ? Builtins.tailList(f11) : f11;
        BigInteger memberCount = sortedMemberCount(members);
        if (!(version.equals(BigInteger.ONE) || v2)
                || !Builtins.nullList(trailing)
                || Builtins.lengthOfByteString(chainId) < 1
                || Builtins.lengthOfByteString(chainId) > 128
                || height.signum() < 0
                || Builtins.lengthOfByteString(blockHash) != 32
                || Builtins.lengthOfByteString(stateRoot) != 32
                || (v2 && updatedAtMs.signum() < 0)
                || memberCount.signum() <= 0
                || threshold.signum() <= 0
                || threshold.compareTo(memberCount) > 0
                || !Builtins.equalsByteString(genesis, expectedChainGenesisId)
                || !Builtins.equalsByteString(application, expectedApplicationId)
                || !Builtins.equalsByteString(profile, expectedCommitmentProfileId)
                || !Builtins.equalsByteString(fingerprint, expectedFormatFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new AnchorState(version, height, stateRoot, membersData, memberCount,
                threshold, updatedAtMs));
    }

    /**
     * The number of member keys when every key is 32 bytes and the list is strictly ascending
     * (which also makes it unique); {@code -1} otherwise.
     */
    static BigInteger sortedMemberCount(PlutusData members) {
        if (Builtins.nullList(members)) {
            return BigInteger.ZERO;
        }
        byte[] key = Builtins.unBData(Builtins.headList(members));
        PlutusData rest = Builtins.tailList(members);
        BigInteger tail = sortedMemberCount(rest);
        boolean valid = Builtins.lengthOfByteString(key) == 32 && tail.signum() >= 0
                && (Builtins.nullList(rest)
                || Builtins.lessThanByteString(key, Builtins.unBData(Builtins.headList(rest))));
        return valid ? tail.add(BigInteger.ONE) : BigInteger.valueOf(-1);
    }

    private static Optional<AnchorRoot> anchor(
            OutputDatum datum, byte[] expectedChainGenesisId,
            byte[] expectedApplicationId, byte[] expectedCommitmentProfileId,
            byte[] expectedFormatFingerprint) {
        if (datum instanceof OutputDatum.OutputDatumInline inline) {
            return anchorData(inline.datum(), expectedChainGenesisId,
                    expectedApplicationId, expectedCommitmentProfileId,
                    expectedFormatFingerprint);
        }
        return Optional.empty();
    }

    private static Optional<AnchorRoot> anchorData(
            PlutusData value, byte[] expectedChainGenesisId,
            byte[] expectedApplicationId, byte[] expectedCommitmentProfileId,
            byte[] expectedFormatFingerprint) {
        if (Builtins.constrTag(value) != 0) return Optional.empty();
        PlutusData fields = Builtins.constrFields(value);
        BigInteger version = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] genesis = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] application = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] profile = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] fingerprint = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger height = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] blockHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        byte[] stateRoot = Builtins.unBData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData f10 = Builtins.tailList(f9);
        PlutusData trailing = Builtins.tailList(f10);
        if (!version.equals(BigInteger.ONE)
                || !Builtins.nullList(trailing)
                || Builtins.lengthOfByteString(chainId) < 1
                || Builtins.lengthOfByteString(chainId) > 128
                || height.signum() < 0
                || Builtins.lengthOfByteString(blockHash) != 32
                || Builtins.lengthOfByteString(stateRoot) != 32
                || !Builtins.equalsByteString(genesis, expectedChainGenesisId)
                || !Builtins.equalsByteString(application, expectedApplicationId)
                || !Builtins.equalsByteString(profile, expectedCommitmentProfileId)
                || !Builtins.equalsByteString(fingerprint, expectedFormatFingerprint)) {
            return Optional.empty();
        }
        return Optional.of(new AnchorRoot(stateRoot));
    }

    private static boolean atScriptAddress(TxOut output, byte[] scriptHash) {
        PlutusData credential = output.address().credential().toPlutusData();
        PlutusData fields = Builtins.constrFields(credential);
        byte[] observed = Builtins.unBData(Builtins.headList(fields));
        return Builtins.constrTag(credential) == 1
                && Builtins.nullList(Builtins.tailList(fields))
                && Builtins.equalsByteString(observed, scriptHash);
    }
}
