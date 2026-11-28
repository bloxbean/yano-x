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
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.lib.ValuesLib;
import org.yanoproject.x.proofs.onchain.MpfAnchorLib;
import org.yanoproject.x.proofs.onchain.MpfOnChainVerifier;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ADR-061 custody script {@code h} of a CIP-113 asset's vault (D2, D5). The vault's custody is every UTxO at its
 * smart wallet {@code (Script(PLB), Inline(Script(h)))}: PLB is the spending script of each of them, and CIP-113
 * accepts a script owner's consent only as that script's withdrawal. So the vault rules run here, in the withdraw
 * handler, once per transaction, over every input at the vault smart wallet (V14).
 *
 * <p><b>Parameters</b>: the native vault's (059-D4 step 5), then the pinned deployment's PLB script hash and
 * {@code maxInlineDatumBytes}, then the exit delegate's hash {@code e} (derived first, D2).
 *
 * <p><b>Handlers</b>: {@code withdraw} runs the actions; {@code publish} accepts only {@code RegisterCredential};
 * {@code spend} always fails (nothing at this script's own payment address is custody); every other purpose fails.
 *
 * <p><b>Actions</b> (059's {@code vault_redeemer}, constructors 0 to 8): {@code Settle}, {@code Exit},
 * {@code SweepExit}, {@code AcceptCosigned} and {@code AcceptProved} run here. {@code RefundAbsent},
 * {@code RefundUncredited}, {@code BalanceExit} and {@code Consolidate} are EXIT-only and delegated: this script
 * requires the exit-mode thread in {@code EXIT} and {@code e}'s withdrawal with the redeemer
 * {@code DelegatedActionV1(h, action)}, and {@code e} runs their rules. {@code Follow} (9) and anything else fail.
 *
 * <p>Shared rules: certificates and mint are empty (V17); the only script inputs are the vault inputs and, for
 * {@code Settle}, {@code Exit} and {@code SweepExit}, one shard input (V8); no output pays {@code h} or {@code e},
 * and only the continuation sits at the vault smart wallet (V15); the continuation's id list is within the cap the
 * pinned datum bound allows (V16).
 */
@MultiValidator
public final class ProgrammableVaultValidator {
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
    @Param
    static byte[] exitDelegateHash;

    /** The fields of a {@code ClaimLeafV1} for this vault's asset; {@code recipient} is raw Shelley address bytes. */
    record Claim(byte[] claimId, BigInteger kind, BigInteger quantity, BigInteger vaultLovelace, byte[] recipient,
                 BigInteger reservedHeight, BigInteger reservedAtMs) {
    }

    record ExitClaim(Claim claim, MpfOnChainVerifier.Proof proof) {
    }

    /** The actions this script runs; constructors 0 to 4 of {@code vault_redeemer}. */
    sealed interface VaultAction permits Settle, Exit, SweepExit, AcceptCosigned, AcceptProved {
    }

    record Settle(JulcList<Claim> claims, BigInteger shardIndex) implements VaultAction {
    }

    record Exit(JulcList<ExitClaim> exits, MpfOnChainVerifier.Proof statusProof, BigInteger shardIndex)
            implements VaultAction {
    }

    /** One {@code [policy, name, quantity]} entry of a swept value, in canonical {@code AssetId} order. */
    record SweepAsset(byte[] policyId, byte[] assetName, BigInteger quantity) {
    }

    record SweepExit(byte[] claimId, byte[] recipient, BigInteger reservedHeight, BigInteger reservedAtMs,
                     JulcList<SweepAsset> assets, MpfOnChainVerifier.Proof proof,
                     MpfOnChainVerifier.Proof statusProof, BigInteger sequence, BigInteger shardIndex)
            implements VaultAction {
    }

    record AcceptCosigned() implements VaultAction {
    }

    record AcceptProved(JulcList<MpfOnChainVerifier.Proof> proofs) implements VaultAction {
    }

    private ProgrammableVaultValidator() {
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
        PlutusData vault = ProgrammableVaultLib.smartWallet(plbHash, ownHash);
        JulcList<TxInInfo> vaults = ProgrammableVaultLib.vaultInputs(tx.inputs(), vault);
        PlutusData mode = GatewayTx.exitModeDatum(tx.referenceInputs(), exitModeThreadPolicyId, exitModeScriptHash);
        long modeTag = Builtins.constrTag(mode);
        long tag = Builtins.constrTag(redeemer);
        if (Builtins.constrTag(credential) != 1 || vaults.isEmpty() || modeTag > 1
                || !ProgrammableVaultLib.noCertificatesOrMint(tx)) {
            return false;
        }
        if (tag >= 5) {
            // EXIT-only actions: e runs their rules, under a redeemer that mirrors this one (D2, I46).
            return tag <= 8 && modeTag == 1
                    && Builtins.equalsData(ProgrammableVaultLib.rewardRedeemer(
                    (PlutusData) (Object) tx.redeemers(), exitDelegateHash),
                    ProgrammableVaultLib.delegatedAction(ownHash, redeemer));
        }
        boolean normal = modeTag == 0;
        byte[] vaultId = GatewayCodec.vaultId(gatewayDomainId, assetPolicyId, assetName, vaultOrdinal);
        return switch ((VaultAction) (Object) redeemer) {
            case Settle settle -> normal && settleValid(settle, tx, ownHash, vault, vaults);
            case Exit exit -> exitValid(exit, mode, tx, ownHash, vault, vaults, vaultId);
            case SweepExit sweep -> normal && sweepValid(sweep, tx, ownHash, vault, vaults, vaultId);
            case AcceptCosigned accept -> normal && acceptShape(tx, ownHash, vault, vaults) && cosigned(tx);
            case AcceptProved accept -> acceptShape(tx, ownHash, vault, vaults)
                    && depositsProved(accept.proofs(), vaults, vaultId, stateRoot(mode, tx));
        };
    }

    // ------------------------------------------------------------------
    // Settle (V9) and Exit (V1-V3)

    private static boolean settleValid(Settle settle, TxInfo tx, byte[] ownHash, PlutusData vault,
                                       JulcList<TxInInfo> vaults) {
        JulcList<Claim> claims = settle.claims();
        long count = claims.size();
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        return count >= 1 && count <= maxSettleBatch.longValue() && count <= 64
                && anchor.isPresent()
                && MpfAnchorLib.signedByThreshold(tx, anchor.get())
                && userWithdrawalsOnly(claims)
                && payoutsValid(claims, tx, ownHash, vault, vaults, settle.shardIndex());
    }

    private static boolean userWithdrawalsOnly(JulcList<Claim> claims) {
        if (claims.isEmpty()) {
            return true;
        }
        return claims.head().kind().signum() == 0 && userWithdrawalsOnly(claims.tail());
    }

    private static boolean exitValid(Exit exit, PlutusData mode, TxInfo tx, byte[] ownHash, PlutusData vault,
                                     JulcList<TxInInfo> vaults, byte[] vaultId) {
        JulcList<ExitClaim> exits = exit.exits();
        long count = exits.size();
        JulcList<Claim> claims = claimsOf(exits);
        if (count < 1 || count > maxExitBatch.longValue() || count > 64
                || !payoutsValid(claims, tx, ownHash, vault, vaults, exit.shardIndex())) {
            return false;
        }
        if (Builtins.constrTag(mode) == 1) {
            return claimsProved(exits, vaultId, GatewayTx.frozenRoot(mode), BigInteger.valueOf(-1),
                    BigInteger.ZERO, BigInteger.ZERO);
        }
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        if (anchor.isEmpty()) {
            return false;
        }
        byte[] root = anchor.get().stateRoot();
        GatewayCodec.DomainStatus status = provedStatus(exit.statusProof(), root);
        return status.status().signum() >= 0
                && claimsProved(exits, vaultId, root, status.status(), status.exitDelayMs(),
                IntervalLib.finiteLowerBound(tx.validRange()));
    }

    /**
     * Each claim's script-computed leaf is proved at its script-computed key (V2). {@code status < 0} means exit
     * mode: user claims only, no delay and no status. Otherwise V3: maturity, and {@code ACTIVE} or (user claims
     * only) past the maximum claim age; the only governed kind here is {@code SWEEP_OPERATIONAL}.
     */
    private static boolean claimsProved(JulcList<ExitClaim> exits, byte[] vaultId, byte[] root, BigInteger status,
                                        BigInteger exitDelayMs, BigInteger lowerBound) {
        if (exits.isEmpty()) {
            return true;
        }
        ExitClaim exit = exits.head();
        Claim claim = exit.claim();
        long kind = claim.kind().longValue();
        BigInteger matured = claim.reservedAtMs().add(exitDelayMs);
        boolean timing = status.signum() < 0
                ? kind == 0
                : (kind == 0 || kind == 2)
                && matured.compareTo(lowerBound) <= 0
                && (status.signum() == 0
                || (kind == 0 && matured.add(maxPauseMs).compareTo(lowerBound) <= 0));
        return timing && leafProved(exit, vaultId, root)
                && claimsProved(exits.tail(), vaultId, root, status, exitDelayMs, lowerBound);
    }

    private static boolean leafProved(ExitClaim exit, byte[] vaultId, byte[] root) {
        Claim claim = exit.claim();
        MpfOnChainVerifier.Proof proof = exit.proof();
        return Builtins.equalsByteString(proof.key(), Builtins.appendByteString(claimKeyPrefix, claim.claimId()))
                && Builtins.equalsByteString(proof.value(), GatewayCodec.claimLeaf(claim.claimId(), vaultId,
                claim.kind(), GatewayCodec.assetValue(assetPolicyId, assetName, claim.quantity()),
                claim.vaultLovelace(), claim.recipient(), claim.reservedHeight(), claim.reservedAtMs()))
                && MpfOnChainVerifier.verifyInclusion(proof, root);
    }

    /** The proved {@code DomainStatusV1} within the pinned delay bounds, or {@code status = -1}. */
    private static GatewayCodec.DomainStatus provedStatus(MpfOnChainVerifier.Proof proof, byte[] root) {
        GatewayCodec.DomainStatus status = GatewayCodec.domainStatus(proof.value());
        boolean valid = Builtins.equalsByteString(proof.key(), domainStatusKey)
                && status.status().signum() >= 0
                && status.exitDelayMs().compareTo(exitDelayFloorMs) >= 0
                && status.exitDelayMs().compareTo(exitDelayCeilingMs) <= 0
                && exitDelayFloorMs.compareTo(BigInteger.valueOf(21600000L)) >= 0
                && exitDelayCeilingMs.compareTo(BigInteger.valueOf(2592000000L)) <= 0
                && maxPauseMs.compareTo(BigInteger.valueOf(259200000L)) >= 0
                && maxPauseMs.compareTo(BigInteger.valueOf(2592000000L)) <= 0
                && MpfOnChainVerifier.verifyInclusion(proof, root);
        return valid ? status : new GatewayCodec.DomainStatus(BigInteger.valueOf(-1), BigInteger.ZERO,
                BigInteger.ZERO);
    }

    private static JulcList<Claim> claimsOf(JulcList<ExitClaim> exits) {
        if (exits.isEmpty()) {
            return JulcList.empty();
        }
        return claimsOf(exits.tail()).prepend(exits.head().claim());
    }

    // ------------------------------------------------------------------
    // Payouts, continuation and inserts (V4-V8, V10, V15, V16)

    private static boolean payoutsValid(JulcList<Claim> claims, TxInfo tx, byte[] ownHash, PlutusData vault,
                                        JulcList<TxInInfo> vaults, BigInteger shardIndex) {
        JulcList<TxOut> outputs = tx.outputs();
        long count = claims.size();
        return count < outputs.size()
                && claimsPaid(claims, outputs, shardIndex)
                && ProgrammableVaultLib.allAccepted(vaults, assetPolicyId, assetName)
                && ProgrammableVaultLib.foreignInputs(tx.inputs(), vault, shardThreadPolicyId).equals(BigInteger.ONE)
                && Builtins.equalsByteString(ProgrammableVaultLib.shardInserts(tx, shardThreadPolicyId, shardIndex),
                joinedIds(claims))
                && ProgrammableVaultLib.continuationValid(outputs, count, vaults, vault, ownHash, exitDelegateHash,
                sumQuantity(claims), sumLovelace(claims), idsData(claims), count,
                ProgrammableVaultLib.maxIds(maxInlineDatumBytes), continuationFloor, assetPolicyId, assetName);
    }

    /** V5, positionally: output {@code i} pays {@code claim[i]}. */
    private static boolean claimsPaid(JulcList<Claim> claims, JulcList<TxOut> outputs, BigInteger shardIndex) {
        if (claims.isEmpty()) {
            return true;
        }
        Claim claim = claims.head();
        return claimShapeValid(claim, shardIndex)
                && ProgrammableVaultLib.paysTo(outputs.head(), claim.recipient(), claim.quantity(),
                claim.vaultLovelace(), plbHash, assetPolicyId, assetName, addressNetworkId.longValue())
                && claimsPaid(claims.tail(), outputs.tail(), shardIndex);
    }

    private static BigInteger sumQuantity(JulcList<Claim> claims) {
        if (claims.isEmpty()) {
            return BigInteger.ZERO;
        }
        return claims.head().quantity().add(sumQuantity(claims.tail()));
    }

    private static BigInteger sumLovelace(JulcList<Claim> claims) {
        if (claims.isEmpty()) {
            return BigInteger.ZERO;
        }
        return claims.head().vaultLovelace().add(sumLovelace(claims.tail()));
    }

    private static byte[] joinedIds(JulcList<Claim> claims) {
        if (claims.isEmpty()) {
            return Builtins.emptyByteString();
        }
        return Builtins.appendByteString(claims.head().claimId(), joinedIds(claims.tail()));
    }

    private static PlutusData idsData(JulcList<Claim> claims) {
        if (claims.isEmpty()) {
            return Builtins.mkNilData();
        }
        return Builtins.mkCons(Builtins.bData(claims.head().claimId()), idsData(claims.tail()));
    }

    private static boolean claimShapeValid(Claim claim, BigInteger shardIndex) {
        long recipientLength = Builtins.lengthOfByteString(claim.recipient());
        byte[] claimId = claim.claimId();
        BigInteger quantity = claim.quantity();
        BigInteger lovelace = claim.vaultLovelace();
        return Builtins.lengthOfByteString(claimId) == 32
                && Builtins.indexByteString(claimId, 31) % 16 == shardIndex.longValue()
                && claim.kind().signum() >= 0
                && quantity.signum() >= 0
                && lovelace.signum() >= 0
                && quantity.add(lovelace).signum() > 0
                && recipientLength >= 1 && recipientLength <= 128
                && claim.reservedHeight().signum() >= 0
                && claim.reservedAtMs().signum() >= 0;
    }

    // ------------------------------------------------------------------
    // SWEEP_UNATTRIBUTED (059-D8d, D5)

    /**
     * One non-accepted vault input. For a deposit-shaped input the withdrawal key is its origin-keyed
     * {@code DepositId}, its native assets must equal the committed ones and its lovelace must be at least the
     * committed lovelace, which the redeemer carries as an entry {@code [h'', h'', lovelace]} after the native
     * entries; for any other input the key is its outpoint and the commitment is its full value, as in 059. Output
     * 0 pays the native assets exactly and at least the input's lovelace, with {@code SweepPaidV1(claimId)}.
     */
    private static boolean sweepValid(SweepExit sweep, TxInfo tx, byte[] ownHash, PlutusData vault,
                                      JulcList<TxInInfo> vaults, byte[] vaultId) {
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        if (anchor.isEmpty() || !vaults.tail().isEmpty()) {
            return false;
        }
        byte[] root = anchor.get().stateRoot();
        TxInInfo swept = vaults.head();
        TxOut sweptOut = swept.resolved();
        Value value = sweptOut.value();
        PlutusData datum = GatewayTx.inlineDatum(sweptOut.datum());
        boolean depositShaped = ProgrammableVaultLib.depositShaped(datum, plbHash, addressNetworkId.longValue());
        byte[] claimId = sweep.claimId();
        byte[] recipient = sweep.recipient();
        JulcList<SweepAsset> assets = sweep.assets();
        long natives = assets.size() - (depositShaped ? 1 : 0);
        BigInteger inputLovelace = ValuesLib.lovelaceOf(value);
        BigInteger vaultLovelace = depositShaped ? committedLovelace(assets) : inputLovelace;
        byte[] withdrawalKey = depositShaped ? ProgrammableVaultLib.depositId(vaultId, datum)
                : GatewayCodec.outRefCbor((byte[]) (Object) swept.outRef().txId(), swept.outRef().index());
        byte[] assetValue = Builtins.appendByteString(GatewayCodec.head(4, BigInteger.valueOf(natives)),
                sweptAssets(assets, natives, value));
        GatewayCodec.DomainStatus status = provedStatus(sweep.statusProof(), root);
        MpfOnChainVerifier.Proof proof = sweep.proof();
        TxOut payout = tx.outputs().head();
        PlutusData payoutAddress = payout.address().toPlutusData();
        return Builtins.lengthOfByteString(claimId) == 32
                && Builtins.indexByteString(claimId, 31) % 16 == sweep.shardIndex().longValue()
                && status.status().signum() == 0
                && sweep.reservedAtMs().add(status.exitDelayMs())
                .compareTo(IntervalLib.finiteLowerBound(tx.validRange())) <= 0
                && !ProgrammableVaultLib.accepted(datum, value, assetPolicyId, assetName)
                && natives >= 0
                && natives + 1 == valueEntries(Builtins.unMapData(value))
                && vaultLovelace.signum() >= 0 && vaultLovelace.compareTo(inputLovelace) <= 0
                && Builtins.equalsByteString(claimId, GatewayCodec.claimId(gatewayDomainId, vaultId,
                BigInteger.ONE, withdrawalKey, recipient, assetValue, vaultLovelace, sweep.sequence()))
                && Builtins.equalsByteString(proof.key(), Builtins.appendByteString(claimKeyPrefix, claimId))
                && Builtins.equalsByteString(proof.value(), GatewayCodec.claimLeaf(claimId, vaultId, BigInteger.ONE,
                assetValue, vaultLovelace, recipient, sweep.reservedHeight(), sweep.reservedAtMs()))
                && MpfOnChainVerifier.verifyInclusion(proof, root)
                && ValuesLib.lovelaceOf(payout.value()).compareTo(inputLovelace) >= 0
                && Builtins.equalsData(Builtins.mapData(Builtins.tailList(Builtins.unMapData(payout.value()))),
                Builtins.mapData(Builtins.tailList(Builtins.unMapData(value))))
                && ProgrammableVaultLib.payable(payoutAddress, plbHash, natives > 0)
                && Builtins.equalsByteString(GatewayCodec.addressBytes(payoutAddress, addressNetworkId.longValue()),
                recipient)
                && Builtins.equalsData(GatewayTx.inlineDatum(payout.datum()),
                Builtins.constrData(4, Builtins.mkCons(Builtins.bData(claimId), Builtins.mkNilData())))
                && payout.referenceScript().isEmpty()
                && ProgrammableVaultLib.foreignInputs(tx.inputs(), vault, shardThreadPolicyId).equals(BigInteger.ONE)
                && ProgrammableVaultLib.vaultOutputs(tx.outputs(), vault, ownHash, exitDelegateHash, 0)
                && Builtins.equalsByteString(ProgrammableVaultLib.shardInserts(tx, shardThreadPolicyId,
                sweep.shardIndex()), claimId);
    }

    /** The quantity of the last entry, which must be {@code [h'', h'', lovelace]}; -1 otherwise. */
    private static BigInteger committedLovelace(JulcList<SweepAsset> assets) {
        if (assets.isEmpty()) {
            return BigInteger.valueOf(-1);
        }
        SweepAsset asset = assets.head();
        if (!assets.tail().isEmpty()) {
            return committedLovelace(assets.tail());
        }
        return Builtins.lengthOfByteString(asset.policyId()) == 0 && Builtins.lengthOfByteString(asset.assetName()) == 0
                ? asset.quantity() : BigInteger.valueOf(-1);
    }

    /**
     * The first {@code count} entries of the {@code asset_value} array, {@code [policy, name, quantity]} each,
     * concatenated, when each has a 28-byte policy and a positive quantity equal to the swept value's; a mismatched
     * entry is prefixed with a stray {@code ff}, which no canonical leaf contains.
     */
    private static byte[] sweptAssets(JulcList<SweepAsset> assets, long count, Value value) {
        if (count == 0) {
            return Builtins.emptyByteString();
        }
        SweepAsset asset = assets.head();
        byte[] entry = Builtins.appendByteString(Builtins.appendByteString(Builtins.consByteString(131,
                GatewayCodec.bytes(asset.policyId())), GatewayCodec.bytes(asset.assetName())),
                GatewayCodec.uint(asset.quantity()));
        boolean exact = asset.quantity().signum() > 0
                && Builtins.lengthOfByteString(asset.policyId()) == 28
                && ValuesLib.assetOf(value, asset.policyId(), asset.assetName()).equals(asset.quantity());
        return Builtins.appendByteString(exact ? entry : Builtins.consByteString(255, entry),
                sweptAssets(assets.tail(), count - 1, value));
    }

    /** The number of (policy, name) entries of a value map, the lovelace entry included. */
    private static long valueEntries(PlutusData policies) {
        if (Builtins.nullList(policies)) {
            return 0;
        }
        return lengthOf(Builtins.unMapData(Builtins.sndPair(Builtins.headList(policies))))
                + valueEntries(Builtins.tailList(policies));
    }

    private static long lengthOf(PlutusData list) {
        if (Builtins.nullList(list)) {
            return 0;
        }
        return 1 + lengthOf(Builtins.tailList(list));
    }

    // ------------------------------------------------------------------
    // Accept (059-D8f, D6)

    /**
     * Every vault input is deposit-shaped (D6's total predicate) and holds only the vault asset and lovelace, and
     * they merge into one exact {@code VaultContinuationV1([])}.
     */
    private static boolean acceptShape(TxInfo tx, byte[] ownHash, PlutusData vault, JulcList<TxInInfo> vaults) {
        return ProgrammableVaultLib.allDepositShaped(vaults, plbHash, addressNetworkId.longValue(), assetPolicyId,
                assetName)
                && ProgrammableVaultLib.mergeValid(tx, vaults, vault, ownHash, exitDelegateHash, shardThreadPolicyId,
                assetPolicyId, assetName);
    }

    private static boolean cosigned(TxInfo tx) {
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        return anchor.isPresent() && MpfAnchorLib.signedByThreshold(tx, anchor.get());
    }

    /** Proof {@code i} shows {@code deposit/<DepositId of vault input i's origin> = CREDITED} under {@code root}. */
    private static boolean depositsProved(JulcList<MpfOnChainVerifier.Proof> proofs, JulcList<TxInInfo> vaults,
                                          byte[] vaultId, byte[] root) {
        if (vaults.isEmpty()) {
            return proofs.isEmpty();
        }
        return !proofs.isEmpty()
                && depositRecordProved(proofs.head(), ProgrammableVaultLib.depositId(vaultId,
                GatewayTx.inlineDatum(vaults.head().resolved().datum())), vaultId, root, BigInteger.ZERO)
                && depositsProved(proofs.tail(), vaults.tail(), vaultId, root);
    }

    // ------------------------------------------------------------------
    // The anchor

    /** V1: the pinned anchor, read as a v2 datum. */
    private static Optional<MpfAnchorLib.AnchorState> anchorState(TxInfo tx) {
        Optional<MpfAnchorLib.AnchorState> anchor = MpfAnchorLib.acceptedAnchorStateIn(tx.referenceInputs(),
                anchorThreadPolicyId, anchorThreadAssetName, anchorScriptHash, chainGenesisId, applicationId,
                commitmentProfileId, formatFingerprint);
        return anchor.isPresent() && anchor.get().version().equals(BigInteger.TWO) ? anchor : Optional.empty();
    }

    /** The root proofs are checked against: {@code frozenRoot} in EXIT, else the anchor's (empty when none). */
    private static byte[] stateRoot(PlutusData mode, TxInfo tx) {
        if (Builtins.constrTag(mode) == 1) {
            return GatewayTx.frozenRoot(mode);
        }
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        return anchor.isPresent() ? anchor.get().stateRoot() : Builtins.emptyByteString();
    }

    /** The proof shows {@code deposit/<depositId>} with {@code status} under {@code root}. */
    private static boolean depositRecordProved(MpfOnChainVerifier.Proof proof, byte[] depositId, byte[] vaultId,
                                               byte[] root, BigInteger status) {
        return Builtins.equalsByteString(proof.key(), Builtins.appendByteString(depositKeyPrefix, depositId))
                && GatewayCodec.depositStatus(proof.value(), depositId, vaultId).equals(status)
                && MpfOnChainVerifier.verifyInclusion(proof, root);
    }
}
