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
import org.yanoproject.x.proofs.onchain.MpfOnChainVerifier;

import java.math.BigInteger;

/**
 * ADR-061 exit delegate {@code e} of a CIP-113 asset's vault (D2): the EXIT-only actions {@code RefundAbsent},
 * {@code RefundUncredited}, {@code BalanceExit} and {@code Consolidate}. It is derived first, from the vault's
 * parameters without {@code h}; the custody script {@code h} takes this script's hash as a parameter and, for these
 * actions, requires this script's withdrawal with the redeemer {@code DelegatedActionV1(h, action)}, which mirrors
 * its own.
 *
 * <p>This script learns {@code h} from that redeemer, requires {@code Script(h)} among the withdrawals and runs the
 * full rules of the action over the inputs at {@code h}'s vault smart wallet. It checks V11 (the exit-mode thread is
 * {@code EXIT}) itself. It only adds constraints: only {@code h}'s withdrawal authorizes vault inputs, so running it
 * with a foreign {@code h'} authorizes nothing of this vault.
 *
 * <p>The absence verifier ({@code RefundAbsent}) lives only here, which keeps it out of {@code h}.
 */
@MultiValidator
public final class ProgrammableVaultExitDelegate {
    @Param
    static byte[] gatewayDomainId;
    @Param
    static byte[] assetPolicyId;
    @Param
    static byte[] assetName;
    @Param
    static BigInteger vaultOrdinal;
    @Param
    static BigInteger addressNetworkId;
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
    static byte[] shardThreadPolicyId;
    @Param
    static byte[] exitModeThreadPolicyId;
    @Param
    static byte[] exitModeScriptHash;
    @Param
    static byte[] claimKeyPrefix;
    @Param
    static byte[] depositKeyPrefix;
    @Param
    static byte[] exitKeyPrefix;
    @Param
    static byte[] domainStatusKey;
    @Param
    static BigInteger maxSettleBatch;
    @Param
    static BigInteger maxExitBatch;
    @Param
    static BigInteger continuationFloor;
    @Param
    static BigInteger exitDelayFloorMs;
    @Param
    static BigInteger exitDelayCeilingMs;
    @Param
    static BigInteger maxPauseMs;
    @Param
    static byte[] plbHash;
    @Param
    static BigInteger maxInlineDatumBytes;

    /** {@code DelegatedActionV1 = Constr 0 [h, action]}. */
    record DelegatedAction(byte[] custodyHash, PlutusData action) {
    }

    /** The delegated actions; constructors 0 to 4 are {@code h}'s own and never valid here. */
    sealed interface ExitAction permits A0, A1, A2, A3, A4, RefundAbsent, RefundUncredited, BalanceExit,
            Consolidate {
    }

    record A0() implements ExitAction {
    }

    record A1() implements ExitAction {
    }

    record A2() implements ExitAction {
    }

    record A3() implements ExitAction {
    }

    record A4() implements ExitAction {
    }

    record RefundAbsent(MpfOnChainVerifier.AbsenceProof proof) implements ExitAction {
    }

    record RefundUncredited(MpfOnChainVerifier.Proof proof) implements ExitAction {
    }

    record BalanceExit(byte[] holderId, byte[] holderKeyHash, BigInteger amount, BigInteger vaultLovelace,
                       byte[] recipient, MpfOnChainVerifier.Proof proof, BigInteger shardIndex)
            implements ExitAction {
    }

    record Consolidate() implements ExitAction {
    }

    private ProgrammableVaultExitDelegate() {
    }

    /**
     * One entrypoint, dispatched on the script purpose: {@code withdraw} (constructor 2) runs the actions,
     * {@code publish} (3) accepts only {@code RegisterCredential}; {@code spend} and every other purpose fail.
     */
    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext context) {
        PlutusData info = context.scriptInfo().toPlutusData();
        long purpose = Builtins.constrTag(info);
        if (purpose == 3) {
            // Registering this credential is the only certificate it witnesses (D2).
            return Builtins.constrTag(Builtins.headList(Builtins.tailList(Builtins.constrFields(info)))) == 0;
        }
        return purpose == 2 && withdraw(redeemer, context.txInfo(), Builtins.headList(Builtins.constrFields(info)));
    }

    private static boolean withdraw(PlutusData redeemer, TxInfo tx, PlutusData credential) {
        byte[] ownHash = Builtins.unBData(Builtins.headList(Builtins.constrFields(credential)));
        DelegatedAction delegated = (DelegatedAction) (Object) redeemer;
        byte[] custodyHash = delegated.custodyHash();
        PlutusData action = delegated.action();
        PlutusData vault = ProgrammableVaultLib.smartWallet(plbHash, custodyHash);
        JulcList<TxInInfo> vaults = ProgrammableVaultLib.vaultInputs(tx.inputs(), vault);
        PlutusData mode = GatewayTx.exitModeDatum(tx.referenceInputs(), exitModeThreadPolicyId, exitModeScriptHash);
        long tag = Builtins.constrTag(action);
        if (Builtins.constrTag(credential) != 1 || Builtins.constrTag(mode) != 1 || tag < 5 || tag > 8
                || vaults.isEmpty()
                || Builtins.lengthOfByteString(custodyHash) != 28
                || !ProgrammableVaultLib.withdrawn((PlutusData) (Object) tx.withdrawals(), custodyHash)
                || !ProgrammableVaultLib.noCertificatesOrMint(tx)) {
            return false;
        }
        byte[] vaultId = GatewayCodec.vaultId(gatewayDomainId, assetPolicyId, assetName, vaultOrdinal);
        byte[] frozenRoot = GatewayTx.frozenRoot(mode);
        return switch ((ExitAction) (Object) action) {
            case RefundAbsent refund -> refundShapeValid(tx, vault, custodyHash, ownHash, vaults)
                    && Builtins.equalsByteString(refund.proof().key(), Builtins.appendByteString(depositKeyPrefix,
                    depositIdOf(vaults.head(), vaultId)))
                    && MpfOnChainVerifier.verifyAbsence(refund.proof(), frozenRoot);
            case RefundUncredited refund -> refundShapeValid(tx, vault, custodyHash, ownHash, vaults)
                    && depositRecordProved(refund.proof(), depositIdOf(vaults.head(), vaultId),
                    vaultId, frozenRoot, BigInteger.ONE);
            case BalanceExit exit -> balanceExitValid(exit, frozenRoot, tx, vault, custodyHash, ownHash, vaults,
                    vaultId);
            case Consolidate consolidate -> !vaults.tail().isEmpty()
                    && ProgrammableVaultLib.allAccepted(vaults, assetPolicyId, assetName)
                    && ProgrammableVaultLib.mergeValid(tx, vaults, vault, custodyHash, ownHash, shardThreadPolicyId,
                    assetPolicyId, assetName);
            case A0 a -> false;
            case A1 a -> false;
            case A2 a -> false;
            case A3 a -> false;
            case A4 a -> false;
        };
    }

    private static byte[] depositIdOf(TxInInfo input, byte[] vaultId) {
        return ProgrammableVaultLib.depositId(vaultId, GatewayTx.inlineDatum(input.resolved().datum()));
    }

    /**
     * Refund (D5): the one vault input is deposit-shaped (D6's total predicate, so its refund address is a key-owned
     * smart wallet), its {@code refundKeyHash} signs, and output 0 pays its exact value, foreign tokens included, to
     * its {@code refundAddress}; no other script input and no output at the vault smart wallet or the custody
     * scripts' addresses.
     */
    private static boolean refundShapeValid(TxInfo tx, PlutusData vault, byte[] custodyHash, byte[] ownHash,
                                            JulcList<TxInInfo> vaults) {
        TxOut deposit = vaults.head().resolved();
        PlutusData datum = GatewayTx.inlineDatum(deposit.datum());
        TxOut refund = tx.outputs().head();
        return vaults.tail().isEmpty()
                && ProgrammableVaultLib.depositShaped(datum, plbHash, addressNetworkId.longValue())
                && ProgrammableVaultLib.foreignInputs(tx.inputs(), vault, shardThreadPolicyId).signum() == 0
                && GatewayTx.signedBy(tx, ProgrammableVaultLib.refundKeyHash(datum))
                && Builtins.equalsData(refund.value(), deposit.value())
                && refund.referenceScript().isEmpty()
                && Builtins.equalsByteString(GatewayCodec.addressBytes(refund.address().toPlutusData(),
                addressNetworkId.longValue()), ProgrammableVaultLib.refundAddress(datum))
                && ProgrammableVaultLib.vaultOutputs(tx.outputs(), vault, custodyHash, ownHash, 0);
    }

    /** {@code BalanceExit} (059-D8g §4): one exit leaf under {@code frozenRoot}, one singleton escape-id insert. */
    private static boolean balanceExitValid(BalanceExit exit, byte[] frozenRoot, TxInfo tx, PlutusData vault,
                                            byte[] custodyHash, byte[] ownHash, JulcList<TxInInfo> vaults,
                                            byte[] vaultId) {
        byte[] holderId = exit.holderId();
        long holderLength = Builtins.lengthOfByteString(holderId);
        byte[] escapeId = GatewayCodec.escapeId(vaultId, holderId);
        MpfOnChainVerifier.Proof proof = exit.proof();
        BigInteger amount = exit.amount();
        BigInteger lovelace = exit.vaultLovelace();
        JulcList<TxOut> outputs = tx.outputs();
        return holderLength >= 1 && holderLength <= 64
                && Builtins.lengthOfByteString(exit.holderKeyHash()) == 28
                && amount.signum() >= 0
                && lovelace.signum() >= 0
                && amount.add(lovelace).signum() > 0
                && GatewayTx.signedBy(tx, exit.holderKeyHash())
                && Builtins.equalsByteString(proof.key(),
                Builtins.appendByteString(Builtins.appendByteString(exitKeyPrefix, vaultId), holderId))
                && Builtins.equalsByteString(proof.value(),
                GatewayCodec.exitLeaf(exit.holderKeyHash(), vaultId, amount, lovelace))
                && MpfOnChainVerifier.verifyInclusion(proof, frozenRoot)
                && Builtins.indexByteString(escapeId, 31) % 16 == exit.shardIndex().longValue()
                && Builtins.equalsByteString(ProgrammableVaultLib.shardInserts(tx, shardThreadPolicyId,
                exit.shardIndex()), escapeId)
                && ProgrammableVaultLib.allAccepted(vaults, assetPolicyId, assetName)
                && ProgrammableVaultLib.foreignInputs(tx.inputs(), vault, shardThreadPolicyId).equals(BigInteger.ONE)
                && outputs.size() > 1
                && ProgrammableVaultLib.paysTo(outputs.head(), exit.recipient(), amount, lovelace, plbHash,
                assetPolicyId, assetName, addressNetworkId.longValue())
                && ProgrammableVaultLib.continuationValid(outputs, 1, vaults, vault, custodyHash, ownHash, amount,
                lovelace, Builtins.mkCons(Builtins.bData(escapeId), Builtins.mkNilData()), 1,
                ProgrammableVaultLib.maxIds(maxInlineDatumBytes), continuationFloor, assetPolicyId, assetName);
    }

    /** The proof shows {@code deposit/<depositId>} with {@code status} under {@code root}. */
    private static boolean depositRecordProved(MpfOnChainVerifier.Proof proof, byte[] depositId, byte[] vaultId,
                                               byte[] root, BigInteger status) {
        return Builtins.equalsByteString(proof.key(), Builtins.appendByteString(depositKeyPrefix, depositId))
                && GatewayCodec.depositStatus(proof.value(), depositId, vaultId).equals(status)
                && MpfOnChainVerifier.verifyInclusion(proof, root);
    }
}
