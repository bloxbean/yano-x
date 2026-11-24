package org.yanoproject.x.framework.cardano.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * App-chain script anchor v2 — spending validator (ADR-059 framework addition 5,
 * D8g §1; ABI: framework/cardano-api/src/main/cddl/appchain/anchor-v2.cddl).
 * Julc/Java source of the same ABI as the shipped Aiken build in
 * {@code META-INF/plutus/AnchorValidatorV2.plutus.json}; compiled and compared only
 * by the optional {@code -Djulc.source-check=true} drift check.
 *
 * <p>The datum is the v1 datum with version 2 and an appended
 * {@code updatedAtMs}: {@code Constr(0, [version, chain-id, chain-genesis-id,
 * application-id, commitment-profile-id, format-fingerprint, height, block-hash,
 * state-root, member-keys, threshold, updated-at-ms])}.
 *
 * <p>Every v1 rule applies, plus:
 * <ol start="6">
 *   <li>a pure heartbeat ({@code next.height == current.height}) keeps the block
 *       hash, state root, member set and threshold, and strictly increases
 *       {@code updatedAtMs}; any other advance strictly increases the height</li>
 *   <li>both validity bounds are finite, {@code next.updatedAtMs} equals the
 *       upper bound and {@code upper − lower ≤ maxWindowMs}</li>
 * </ol>
 */
@SpendingValidator
public class AnchorValidatorV2 {

    /** Policy id (28 bytes) of the one-shot state-thread NFT. */
    @Param
    static byte[] threadPolicyId;

    /** Maximum validity window in ms (the ADR default is 600000). */
    @Param
    static BigInteger maxWindowMs;

    record AnchorDatum(BigInteger version,
                       byte[] chainId,
                       byte[] chainGenesisId,
                       byte[] applicationId,
                       byte[] commitmentProfileId,
                       byte[] formatFingerprint,
                       BigInteger height,
                       byte[] blockHash,
                       byte[] stateRoot,
                       JulcList<byte[]> memberKeys,
                       BigInteger threshold,
                       BigInteger updatedAtMs) {
    }

    @Entrypoint
    public static boolean validate(AnchorDatum datum, BigInteger redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        boolean currentShapeValid = datumShapeValid(datum);
        boolean currentBaseProfileValid = baseProfileValid(
                datum.version(), datum.chainId(), datum.chainGenesisId(), datum.applicationId(),
                datum.commitmentProfileId(), datum.formatFingerprint(), datum.height(),
                datum.blockHash(), datum.stateRoot(), datum.updatedAtMs());
        boolean currentValidAndSigned = currentValidAndSigned(
                txInfo, datum.memberKeys(), datum.threshold());
        if (!currentShapeValid || !currentBaseProfileValid
                || !currentValidAndSigned) {
            return false;
        }

        JulcList<TxOut> continuing = ContextsLib.getContinuingOutputs(ctx);
        boolean oneContinuing = continuing.size() == 1;
        TxOut next = continuing.head();
        boolean threadContinues = ValuesLib.countTokensWithQty(
                next.value(), threadPolicyId, BigInteger.ONE).equals(BigInteger.ONE);
        boolean nextStateValid = nextStateValid(next.datum(), datum, txInfo.validRange());

        boolean valuePreserved = ValuesLib.lovelaceOf(next.value())
                .compareTo(ownInputLovelace(ctx)) >= 0;

        return oneContinuing && threadContinues
                && nextStateValid && valuePreserved;
    }

    static boolean nextStateValid(OutputDatum outputDatum, AnchorDatum current, Interval range) {
        if (outputDatum instanceof OutputDatum.OutputDatumInline inlineDatum) {
            return checkNextDatum(inlineDatum.datum(), current, range);
        }
        return false;
    }

    static boolean currentValidAndSigned(TxInfo txInfo, JulcList<byte[]> memberKeys,
                                         BigInteger threshold) {
        if (memberKeys.size() < 1 || memberKeys.size() > 32) {
            return false;
        }
        BigInteger count = BigInteger.ZERO;
        BigInteger signerCount = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        boolean valid = true;
        for (byte[] memberKey : memberKeys) {
            if (Builtins.lengthOfByteString(memberKey) != 32
                    || (count.compareTo(BigInteger.ZERO) > 0
                    && !Builtins.lessThanByteString(previous, memberKey))) {
                valid = false;
                break;
            }
            previous = memberKey;
            count = count.add(BigInteger.ONE);
            byte[] keyHash = Builtins.blake2b_224(memberKey);
            if (ContextsLib.signedBy(txInfo, keyHash)) {
                signerCount = signerCount.add(BigInteger.ONE);
            }
        }
        return valid && count.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0
                && signerCount.compareTo(threshold) >= 0;
    }

    static boolean baseProfileValid(BigInteger version, byte[] chainId,
                                    byte[] chainGenesisId, byte[] applicationId,
                                    byte[] commitmentProfileId, byte[] formatFingerprint,
                                    BigInteger height, byte[] blockHash,
                                    byte[] stateRoot, BigInteger updatedAtMs) {
        long chainIdLength = Builtins.lengthOfByteString(chainId);
        return version.equals(BigInteger.TWO)
                && chainIdLength >= 1 && chainIdLength <= 128
                && Builtins.lengthOfByteString(chainGenesisId) == 32
                && Builtins.lengthOfByteString(applicationId) >= 1
                && Builtins.lengthOfByteString(applicationId) <= 128
                && Builtins.lengthOfByteString(commitmentProfileId) >= 1
                && Builtins.lengthOfByteString(commitmentProfileId) <= 128
                && Builtins.lengthOfByteString(formatFingerprint) == 32
                && height.compareTo(BigInteger.ZERO) >= 0
                && updatedAtMs.compareTo(BigInteger.ZERO) >= 0
                && Builtins.lengthOfByteString(blockHash) == 32
                && Builtins.lengthOfByteString(stateRoot) == 32;
    }

    /** Require the v2 datum's exact Constr(0, twelve fields) wire shape. */
    static boolean datumShapeValid(AnchorDatum datum) {
        long alternative = Builtins.constrTag(datum);
        PlutusData pair = Builtins.unConstrData(datum);
        PlutusData fields = Builtins.sndPair(pair);
        PlutusData f1 = Builtins.tailList(fields);
        PlutusData f2 = Builtins.tailList(f1);
        PlutusData f3 = Builtins.tailList(f2);
        PlutusData f4 = Builtins.tailList(f3);
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData f6 = Builtins.tailList(f5);
        PlutusData f7 = Builtins.tailList(f6);
        PlutusData f8 = Builtins.tailList(f7);
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData f10 = Builtins.tailList(f9);
        PlutusData f11 = Builtins.tailList(f10);
        PlutusData trailingFields = Builtins.tailList(f11);
        return alternative == 0
                && Builtins.nullList(trailingFields);
    }

    static BigInteger ownInputLovelace(ScriptContext ctx) {
        Optional<TxInInfo> ownInputOpt = ContextsLib.findOwnInput(ctx);
        TxInInfo ownInput = ownInputOpt.get();
        return ValuesLib.lovelaceOf(ownInput.resolved().value());
    }

    /** Finite bounds, {@code updatedAtMs == upper} and {@code upper − lower ≤ maxWindowMs}. */
    static boolean validityWindowValid(Interval range, BigInteger updatedAtMs) {
        boolean lowerFinite = switch (range.from().boundType()) {
            case IntervalBoundType.Finite f -> true;
            case IntervalBoundType.NegInf ignored -> false;
            case IntervalBoundType.PosInf ignored -> false;
        };
        boolean upperFinite = switch (range.to().boundType()) {
            case IntervalBoundType.Finite f -> true;
            case IntervalBoundType.NegInf ignored -> false;
            case IntervalBoundType.PosInf ignored -> false;
        };
        if (!lowerFinite || !upperFinite) {
            return false;
        }
        BigInteger lower = switch (range.from().boundType()) {
            case IntervalBoundType.Finite f -> f.time();
            case IntervalBoundType.NegInf ignored -> BigInteger.ZERO;
            case IntervalBoundType.PosInf ignored -> BigInteger.ZERO;
        };
        BigInteger upper = switch (range.to().boundType()) {
            case IntervalBoundType.Finite f -> f.time();
            case IntervalBoundType.NegInf ignored -> BigInteger.ZERO;
            case IntervalBoundType.PosInf ignored -> BigInteger.ZERO;
        };
        return updatedAtMs.equals(upper)
                && upper.subtract(lower).compareTo(maxWindowMs) <= 0;
    }

    /**
     * Decode the continuing inline datum and enforce rules 2, 4, 6 and 7
     * against the current datum and the transaction's validity range.
     */
    static boolean checkNextDatum(PlutusData data, AnchorDatum current, Interval range) {
        PlutusData pair = Builtins.unConstrData(data);
        long alternative = Builtins.constrTag(data);
        PlutusData fields = Builtins.sndPair(pair);
        BigInteger nextVersion = Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] nextChainId = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] nextChainGenesisId = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] nextApplicationId = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] nextCommitmentProfileId = Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        byte[] nextFormatFingerprint = Builtins.unBData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger nextHeight = Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        byte[] nextBlockHash = Builtins.unBData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        byte[] nextStateRoot = Builtins.unBData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        PlutusData nextMembersData = Builtins.headList(f9);
        PlutusData nextMemberList = Builtins.unListData(nextMembersData);
        PlutusData f10 = Builtins.tailList(f9);
        BigInteger nextThreshold = Builtins.unIData(Builtins.headList(f10));
        PlutusData f11 = Builtins.tailList(f10);
        BigInteger nextUpdatedAtMs = Builtins.unIData(Builtins.headList(f11));
        PlutusData trailingFields = Builtins.tailList(f11);

        boolean exactShape = alternative == 0
                && Builtins.nullList(trailingFields);
        boolean baseProfileValid = baseProfileValid(nextVersion, nextChainId,
                nextChainGenesisId, nextApplicationId, nextCommitmentProfileId,
                nextFormatFingerprint, nextHeight, nextBlockHash, nextStateRoot,
                nextUpdatedAtMs);
        boolean sameChain = nextChainId.equals(current.chainId());
        boolean sameGenesis = nextChainGenesisId.equals(current.chainGenesisId());
        boolean sameApplication = nextApplicationId.equals(current.applicationId());
        boolean sameCommitment = nextCommitmentProfileId.equals(current.commitmentProfileId())
                && nextFormatFingerprint.equals(current.formatFingerprint());
        boolean sameVersion = nextVersion.equals(current.version());
        boolean memberProfileValid = memberProfileValid(nextMemberList, nextThreshold);

        boolean progresses = progresses(current, nextHeight, nextBlockHash, nextStateRoot,
                nextMembersData, nextThreshold, nextUpdatedAtMs);
        boolean timeBound = validityWindowValid(range, nextUpdatedAtMs);

        return exactShape && baseProfileValid && progresses && timeBound
                && sameChain && sameGenesis && sameApplication && sameCommitment
                && sameVersion && memberProfileValid;
    }

    /** Rule 6: a strictly higher height, or a pure heartbeat at the same height. */
    static boolean progresses(AnchorDatum current, BigInteger nextHeight, byte[] nextBlockHash,
                              byte[] nextStateRoot, PlutusData nextMembersData,
                              BigInteger nextThreshold, BigInteger nextUpdatedAtMs) {
        if (nextHeight.equals(current.height())) {
            PlutusData currentMembersData = Builtins.listData(current.memberKeys());
            return nextBlockHash.equals(current.blockHash())
                    && nextStateRoot.equals(current.stateRoot())
                    && Builtins.equalsData(nextMembersData, currentMembersData)
                    && nextThreshold.equals(current.threshold())
                    && nextUpdatedAtMs.compareTo(current.updatedAtMs()) > 0;
        }
        return nextHeight.compareTo(current.height()) > 0;
    }

    static boolean memberProfileValid(PlutusData list, BigInteger threshold) {
        BigInteger count = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        PlutusData rest = list;
        boolean valid = true;
        while (!Builtins.nullList(rest)) {
            byte[] memberKey = Builtins.unBData(Builtins.headList(rest));
            if (count.compareTo(BigInteger.valueOf(32)) >= 0
                    || Builtins.lengthOfByteString(memberKey) != 32
                    || (count.compareTo(BigInteger.ZERO) > 0
                    && !Builtins.lessThanByteString(previous, memberKey))) {
                valid = false;
                break;
            }
            previous = memberKey;
            count = count.add(BigInteger.ONE);
            rest = Builtins.tailList(rest);
        }
        return valid && count.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0;
    }
}
