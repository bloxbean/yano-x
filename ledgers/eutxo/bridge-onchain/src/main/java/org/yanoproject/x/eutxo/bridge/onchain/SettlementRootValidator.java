package org.yanoproject.x.eutxo.bridge.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOut;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * ADR-UTXO-009 accepted-root thread (§7.1): the federation-threshold root of
 * the L2 state, consumed by the settlement vault as a REFERENCE input. On
 * top of the legacy federated root it records {@code updatedAtMillis} —
 * honestly bound to the transaction validity range — and the governed
 * {@code fallbackDelayMillis} that arms the permissionless Exit path once
 * the thread goes stale. Both are POSIX milliseconds, the unit of the
 * validity range a script sees; the bootstrap converts the governed delay
 * from L1 slots with the network's slot length.
 *
 * <p>Datum: Constr0[version=1, chainId, bridgeEpoch, height, stateRoot,
 * memberKeys(sorted), threshold, generation, updatedAtMillis,
 * fallbackDelayMillis]. Redeemer: 0 = advance (profile preserved),
 * 1 = migrate (epoch+1, generation+1; members/threshold/delay may change).
 */
@SpendingValidator
public final class SettlementRootValidator {
    @Param
    static byte[] rootThreadPolicyId;

    @Param
    static byte[] rootThreadAssetName;

    record RootDatum(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            byte[] stateRoot,
            JulcList<byte[]> memberKeys,
            BigInteger threshold,
            BigInteger generation,
            BigInteger updatedAtMillis,
            BigInteger fallbackDelayMillis
    ) {
    }

    private SettlementRootValidator() {
    }

    @Entrypoint
    public static boolean validate(
            RootDatum current,
            PlutusData redeemer,
            ScriptContext context
    ) {
        BigInteger action = Builtins.unIData(redeemer);
        if (!datumShapeValid(current)
                || !baseProfileValid(
                current.version(),
                current.chainId(),
                current.bridgeEpoch(),
                current.height(),
                current.stateRoot(),
                current.generation(),
                current.updatedAtMillis(),
                current.fallbackDelayMillis())
                || !currentValidAndSigned(
                context.txInfo(),
                current.memberKeys(),
                current.threshold())) {
            return false;
        }
        JulcList<TxOut> continuing =
                ContextsLib.getContinuingOutputs(context);
        return continuing.size() == 1
                && hasThread(continuing.head())
                && inlineNextStateValid(
                continuing.head().datum(), current, action,
                IntervalLib.finiteUpperBound(
                        context.txInfo().validRange()));
    }

    static boolean datumShapeValid(RootDatum datum) {
        PlutusData fields = Builtins.sndPair(
                Builtins.unConstrData(datum));
        PlutusData trailing = Builtins.dropList(10, fields);
        return Builtins.constrTag(datum) == 0
                && Builtins.nullList(trailing);
    }

    static boolean baseProfileValid(
            BigInteger version,
            byte[] chainId,
            BigInteger bridgeEpoch,
            BigInteger height,
            byte[] stateRoot,
            BigInteger generation,
            BigInteger updatedAtMillis,
            BigInteger fallbackDelayMillis
    ) {
        long chainLength = Builtins.lengthOfByteString(chainId);
        return version.equals(BigInteger.ONE)
                && chainLength >= 1 && chainLength <= 128
                && bridgeEpoch.signum() >= 0
                && height.signum() >= 0
                && Builtins.lengthOfByteString(stateRoot) == 32
                && generation.signum() >= 0
                && updatedAtMillis.signum() >= 0
                && fallbackDelayMillis.signum() > 0;
    }

    static boolean currentValidAndSigned(
            TxInfo txInfo,
            JulcList<byte[]> memberKeys,
            BigInteger threshold
    ) {
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

    private static boolean inlineNextStateValid(
            OutputDatum outputDatum,
            RootDatum current,
            BigInteger action,
            BigInteger validityUpperBound
    ) {
        if (outputDatum
                instanceof OutputDatum.OutputDatumInline inline) {
            return nextDataValid(
                    inline.datum(), current, action, validityUpperBound);
        }
        return false;
    }

    private static boolean nextDataValid(
            PlutusData data,
            RootDatum current,
            BigInteger action,
            BigInteger validityUpperBound
    ) {
        PlutusData fields = Builtins.sndPair(
                Builtins.unConstrData(data));
        BigInteger version =
                Builtins.unIData(Builtins.headList(fields));
        PlutusData f1 = Builtins.tailList(fields);
        byte[] chainId =
                Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        BigInteger epoch =
                Builtins.unIData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        BigInteger height =
                Builtins.unIData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        byte[] stateRoot =
                Builtins.unBData(Builtins.headList(f4));
        PlutusData f5 = Builtins.tailList(f4);
        PlutusData members =
                Builtins.unListData(Builtins.headList(f5));
        PlutusData f6 = Builtins.tailList(f5);
        BigInteger threshold =
                Builtins.unIData(Builtins.headList(f6));
        PlutusData f7 = Builtins.tailList(f6);
        BigInteger generation =
                Builtins.unIData(Builtins.headList(f7));
        PlutusData f8 = Builtins.tailList(f7);
        BigInteger updatedAtMillis =
                Builtins.unIData(Builtins.headList(f8));
        PlutusData f9 = Builtins.tailList(f8);
        BigInteger fallbackDelay =
                Builtins.unIData(Builtins.headList(f9));
        PlutusData trailing = Builtins.tailList(f9);
        if (Builtins.constrTag(data) != 0
                || !Builtins.nullList(trailing)
                || !baseProfileValid(
                version, chainId, epoch, height,
                stateRoot, generation, updatedAtMillis, fallbackDelay)
                || !memberProfileValid(members, threshold)
                || !Builtins.equalsByteString(
                chainId, current.chainId())
                // The recorded time is honestly bound to the validity range
                // and never regresses.
                || !updatedAtMillis.equals(validityUpperBound)
                || updatedAtMillis.compareTo(current.updatedAtMillis()) < 0) {
            return false;
        }
        if (action.signum() == 0) {
            return epoch.equals(current.bridgeEpoch())
                    && generation.equals(current.generation())
                    && threshold.equals(current.threshold())
                    && fallbackDelay.equals(current.fallbackDelayMillis())
                    && Builtins.equalsByteString(
                    membersDataDigest(members),
                    membersDigest(current.memberKeys()))
                    && height.compareTo(current.height()) > 0;
        }
        return action.equals(BigInteger.ONE)
                && epoch.equals(
                current.bridgeEpoch().add(BigInteger.ONE))
                && generation.equals(
                current.generation().add(BigInteger.ONE));
    }

    static boolean memberProfileValid(
            PlutusData members,
            BigInteger threshold
    ) {
        BigInteger count = BigInteger.ZERO;
        byte[] previous = Builtins.emptyByteString();
        boolean valid = true;
        PlutusData rest = members;
        while (!Builtins.nullList(rest)) {
            byte[] key = Builtins.unBData(Builtins.headList(rest));
            if (Builtins.lengthOfByteString(key) != 32
                    || (count.compareTo(BigInteger.ZERO) > 0
                    && !Builtins.lessThanByteString(previous, key))) {
                valid = false;
                break;
            }
            previous = key;
            count = count.add(BigInteger.ONE);
            rest = Builtins.tailList(rest);
        }
        return valid
                && count.compareTo(BigInteger.ONE) >= 0
                && count.compareTo(BigInteger.valueOf(32)) <= 0
                && threshold.compareTo(BigInteger.ONE) >= 0
                && threshold.compareTo(count) <= 0;
    }

    private static byte[] membersDigest(JulcList<byte[]> members) {
        byte[] joined = Builtins.emptyByteString();
        for (byte[] member : members) {
            joined = Builtins.appendByteString(joined, member);
        }
        return Builtins.blake2b_256(joined);
    }

    private static byte[] membersDataDigest(PlutusData members) {
        byte[] joined = Builtins.emptyByteString();
        PlutusData rest = members;
        while (!Builtins.nullList(rest)) {
            joined = Builtins.appendByteString(
                    joined, Builtins.unBData(Builtins.headList(rest)));
            rest = Builtins.tailList(rest);
        }
        return Builtins.blake2b_256(joined);
    }

    private static boolean hasThread(TxOut output) {
        return ValuesLib.assetOf(
                output.value(),
                rootThreadPolicyId,
                rootThreadAssetName)
                .equals(BigInteger.ONE);
    }
}
