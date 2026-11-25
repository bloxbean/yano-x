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
 * ADR-059 {@code anchored-claim-vault-v1}: one vault script per registered asset (D8).
 *
 * <p><b>Parameters</b> (D4 step 5, applied in declaration order): the {@code VaultId} fields (the vault id is
 * computed on chain from them), the address network id, the pinned anchor identity, the shard thread policy id (never
 * the shard's script hash), the exit-mode thread policy id and script hash, the pinned key prefixes and the profile
 * parameters.
 *
 * <p><b>Redeemers</b> (one action per transaction, V13):
 * <ul>
 *   <li>{@code Settle} (NORMAL): threshold of the referenced anchor's members as required signers (V9);
 *       {@code USER_WITHDRAWAL} claims only; no proof.</li>
 *   <li>{@code Exit}: per claim, the script-computed leaf at the script-computed key under the anchor root (V1, V2),
 *       {@code reservedAtMs + exitDelayMs <= lowerBound} and the status leaf {@code ACTIVE}, or a user claim past its
 *       maximum age (V3); {@code SWEEP_OPERATIONAL} governed claims only with {@code ACTIVE}. In EXIT mode: user
 *       claims only, proved against {@code frozenRoot}, with no delay or status check.</li>
 *   <li>{@code SweepExit} (NORMAL): one {@code SWEEP_UNATTRIBUTED} claim whose {@code ClaimId} and leaf the script
 *       rebuilds from the one outpoint it spends and that outpoint's full value ({@code SweepCommitment}); the
 *       outpoint must not be accepted custody; output 0 pays exactly that value to the recipient with the inline
 *       datum {@code SweepPaidV1(claimId)}, and no vault output remains.</li>
 *   <li>{@code AcceptCosigned} (NORMAL) and {@code AcceptProved} (NORMAL against the anchor root, EXIT against
 *       {@code frozenRoot}): deposit-shaped inputs merge into one {@code VaultContinuationV1([])}.</li>
 *   <li>{@code RefundAbsent} / {@code RefundUncredited} (EXIT): one deposit back to its refund address.</li>
 *   <li>{@code BalanceExit} (EXIT): one exit leaf, one singleton escape-id insert.</li>
 *   <li>{@code Consolidate} (NORMAL and EXIT, permissionless): two or more accepted inputs merge into one exact
 *       {@code VaultContinuationV1([])}; no shard, no payout.</li>
 * </ul>
 *
 * <p><b>Shared rules.</b> Only the first vault input in the (ledger-sorted) input list, the lead, runs the full check,
 * and its redeemer must be an action. Every other vault input carries either the identical redeemer or the
 * {@code Follow} marker ({@code Constr 9 []}) and only checks that the lead's redeemer is an action, so exactly one
 * action applies to the transaction and the lead's check, which covers every vault input, runs once (V13; the marker
 * keeps large proofs out of every further redeemer). The exit-mode thread must be referenced (V11). Certificates and
 * withdrawals are empty and the mint never touches the shard thread policy (V8). Every vault input sits at the exact
 * enterprise vault address. Payout redeemers spend only accepted inputs (V10: {@code VaultContinuationV1} or
 * {@code FloorTopUpV1}, judged on the decoded datum, holding only the vault asset and lovelace), pay output {@code i}
 * to {@code claim[i]} (V5), keep the value equation exact (V6: the fee and collateral therefore come from elsewhere),
 * and leave exactly one output with the vault's payment credential, at output {@code k}, at the exact enterprise
 * address, with no reference script and {@code VaultContinuationV1(ids)} (V7). Exactly one input carries the shard
 * thread token, it is the shard {@code shardIndex}, and its ordered inserts are exactly the paid ids (V4, I17); every
 * other script input is rejected (V8). A batch holds at most 64 claims, the bound of a continuation's id list.
 *
 * <p>The spend entrypoint takes no datum argument: a vault output may have no datum or only a datum hash (both
 * unattributed custody, sweepable), and the script reads every datum it needs from {@code txInfo.inputs}.
 */
@SpendingValidator
public final class AssetVaultValidator {
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

    /** The fields of a {@code ClaimLeafV1} for this vault's asset; {@code recipient} is raw Shelley address bytes. */
    record Claim(byte[] claimId, BigInteger kind, BigInteger quantity, BigInteger vaultLovelace, byte[] recipient,
                 BigInteger reservedHeight, BigInteger reservedAtMs) {
    }

    record ExitClaim(Claim claim, MpfOnChainVerifier.Proof proof) {
    }

    sealed interface VaultAction permits Settle, Exit, SweepExit, AcceptCosigned, AcceptProved, RefundAbsent,
            RefundUncredited, BalanceExit, Consolidate, Follow {
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

    record RefundAbsent(MpfOnChainVerifier.AbsenceProof proof) implements VaultAction {
    }

    record RefundUncredited(MpfOnChainVerifier.Proof proof) implements VaultAction {
    }

    record BalanceExit(byte[] holderId, byte[] holderKeyHash, BigInteger amount, BigInteger vaultLovelace,
                       byte[] recipient, MpfOnChainVerifier.Proof proof, BigInteger shardIndex)
            implements VaultAction {
    }

    record Consolidate() implements VaultAction {
    }

    /** The marker a non-lead vault input may carry instead of repeating the lead's redeemer. Never an action. */
    record Follow() implements VaultAction {
    }

    private AssetVaultValidator() {
    }

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext context) {
        TxInfo tx = context.txInfo();
        TxInInfo own = ContextsLib.findOwnInput(context).get();
        byte[] ownHash = GatewayCodec.paymentScriptHash(own.resolved().address().toPlutusData());
        TxInInfo lead = firstVaultInput(tx.inputs(), ownHash);
        if (!Builtins.equalsData(own.outRef().toPlutusData(), lead.outRef().toPlutusData())) {
            PlutusData leadRedeemer = GatewayTx.spendRedeemer(tx, lead.outRef());
            return Builtins.constrTag(leadRedeemer) < 9
                    && (Builtins.equalsData(redeemer, Builtins.constrData(9, Builtins.mkNilData()))
                    || Builtins.equalsData(redeemer, leadRedeemer));
        }
        JulcList<TxInInfo> vaults = vaultInputs(tx.inputs(), ownHash);
        PlutusData mode = GatewayTx.exitModeDatum(tx.referenceInputs(), exitModeThreadPolicyId, exitModeScriptHash);
        long modeTag = Builtins.constrTag(mode);
        return modeTag <= 1
                && exactAddresses(vaults, ownHash)
                && GatewayTx.noCertificatesOrWithdrawals(tx)
                && !ValuesLib.containsPolicy(tx.mint(), shardThreadPolicyId)
                && actionValid((VaultAction) (Object) redeemer, mode, modeTag == 0, tx, ownHash, vaults,
                GatewayCodec.vaultId(gatewayDomainId, assetPolicyId, assetName, vaultOrdinal));
    }

    private static boolean actionValid(VaultAction action, PlutusData mode, boolean normal, TxInfo tx,
                                       byte[] ownHash, JulcList<TxInInfo> vaults, byte[] vaultId) {
        return switch (action) {
            case Settle settle -> normal && settleValid(settle, tx, ownHash, vaults);
            case Exit exit -> exitValid(exit, mode, tx, ownHash, vaults, vaultId);
            case SweepExit sweep -> normal && sweepValid(sweep, tx, ownHash, vaults, vaultId);
            case AcceptCosigned accept -> normal && vaultsDepositShaped(vaults) && mergeValid(tx, ownHash, vaults)
                    && cosigned(tx);
            case AcceptProved accept -> vaultsDepositShaped(vaults) && mergeValid(tx, ownHash, vaults)
                    && depositsProved(accept.proofs(), vaults, vaultId, stateRoot(mode, tx));
            case RefundAbsent refund -> !normal && refundShapeValid(tx, ownHash, vaults)
                    && Builtins.equalsByteString(refund.proof().key(), depositKey(vaults, vaultId))
                    && MpfOnChainVerifier.verifyAbsence(refund.proof(), GatewayTx.frozenRoot(mode));
            case RefundUncredited refund -> !normal && refundShapeValid(tx, ownHash, vaults)
                    && depositRecordProved(refund.proof(), vaults.head(), vaultId, GatewayTx.frozenRoot(mode),
                    BigInteger.ONE);
            case BalanceExit exit -> !normal && balanceExitValid(exit, mode, tx, ownHash, vaults, vaultId);
            case Consolidate consolidate -> !vaults.tail().isEmpty() && vaultsAccepted(vaults)
                    && mergeValid(tx, ownHash, vaults);
            case Follow follow -> false;
        };
    }

    // ------------------------------------------------------------------
    // Settle (V9) and Exit (V1-V3)

    private static boolean settleValid(Settle settle, TxInfo tx, byte[] ownHash, JulcList<TxInInfo> vaults) {
        JulcList<Claim> claims = settle.claims();
        long count = claims.size();
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        return count >= 1 && count <= maxSettleBatch.longValue() && count <= 64
                && anchor.isPresent()
                && MpfAnchorLib.signedByThreshold(tx, anchor.get())
                && userWithdrawalsOnly(claims)
                && payoutsValid(claims, tx, ownHash, vaults, settle.shardIndex());
    }

    private static boolean userWithdrawalsOnly(JulcList<Claim> claims) {
        if (claims.isEmpty()) {
            return true;
        }
        return claims.head().kind().signum() == 0 && userWithdrawalsOnly(claims.tail());
    }

    private static boolean exitValid(Exit exit, PlutusData mode, TxInfo tx, byte[] ownHash,
                                     JulcList<TxInInfo> vaults, byte[] vaultId) {
        JulcList<ExitClaim> exits = exit.exits();
        long count = exits.size();
        JulcList<Claim> claims = claimsOf(exits);
        if (count < 1 || count > maxExitBatch.longValue() || count > 64
                || !payoutsValid(claims, tx, ownHash, vaults, exit.shardIndex())) {
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

    /**
     * The proved {@code DomainStatusV1}, with {@code status = -1} unless it is proved at the pinned key and its
     * delay lies within [{@code exitDelayFloorMs}, {@code exitDelayCeilingMs}], which must lie within [6 h, 30 d]
     * (and {@code maxPauseMs} within [72 h, 30 d]).
     */
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
    // Payouts, continuation and inserts (V4-V8, V10)

    private static boolean payoutsValid(JulcList<Claim> claims, TxInfo tx, byte[] ownHash,
                                        JulcList<TxInInfo> vaults, BigInteger shardIndex) {
        JulcList<TxOut> outputs = tx.outputs();
        long count = claims.size();
        return count < outputs.size()
                && claimsPaid(claims, outputs, shardIndex)
                && vaultsAccepted(vaults)
                && foreignInputs(tx.inputs(), ownHash).equals(BigInteger.ONE)
                && Builtins.equalsByteString(shardInserts(tx, shardIndex), joinedIds(claims))
                && continuationValid(outputs, count, ownHash, vaults, sumQuantity(claims), sumLovelace(claims),
                idsData(claims), continuationFloor);
    }

    /** V5, positionally: output {@code i} pays {@code claim[i]}. */
    private static boolean claimsPaid(JulcList<Claim> claims, JulcList<TxOut> outputs, BigInteger shardIndex) {
        if (claims.isEmpty()) {
            return true;
        }
        Claim claim = claims.head();
        return claimShapeValid(claim, shardIndex)
                && paysClaim(outputs.head(), claim.recipient(), claim.quantity(), claim.vaultLovelace())
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
                && (!adaVault() || lovelace.signum() == 0)
                && recipientLength >= 1 && recipientLength <= 128
                && claim.reservedHeight().signum() >= 0
                && claim.reservedAtMs().signum() >= 0;
    }

    /**
     * V5: the output pays a key-payment {@code recipient} (raw address bytes) exactly {@code quantity} of the vault
     * asset plus at least {@code vaultLovelace}; for the ADA vault, at least {@code quantity} lovelace.
     */
    private static boolean paysClaim(TxOut output, byte[] recipient, BigInteger quantity, BigInteger vaultLovelace) {
        PlutusData address = output.address().toPlutusData();
        BigInteger paidLovelace = ValuesLib.lovelaceOf(output.value());
        boolean amounts = adaVault()
                ? paidLovelace.compareTo(quantity) >= 0
                : ValuesLib.assetOf(output.value(), assetPolicyId, assetName).equals(quantity)
                && paidLovelace.compareTo(vaultLovelace) >= 0;
        return amounts
                && Builtins.lengthOfByteString(GatewayCodec.paymentKeyHash(address)) == 28
                && Builtins.equalsByteString(GatewayCodec.addressBytes(address, addressNetworkId.longValue()),
                recipient);
    }

    /**
     * V6/V7: output {@code index} is the only output with the vault's payment credential, sits at the exact
     * enterprise address, holds only the vault asset and lovelace, carries exactly {@code in - debit} of each, at
     * least {@code floor} lovelace, no reference script, and the inline datum {@code VaultContinuationV1(ids)}.
     */
    private static boolean continuationValid(JulcList<TxOut> outputs, long index, byte[] ownHash,
                                             JulcList<TxInInfo> vaults, BigInteger quantity, BigInteger lovelace,
                                             PlutusData ids, BigInteger floor) {
        TxOut out = outputs.get(index);
        Value value = out.value();
        BigInteger outLovelace = ValuesLib.lovelaceOf(value);
        BigInteger inLovelace = inputLovelace(vaults);
        boolean amounts = adaVault()
                ? outLovelace.equals(inLovelace.subtract(quantity))
                : outLovelace.equals(inLovelace.subtract(lovelace))
                && ValuesLib.assetOf(value, assetPolicyId, assetName).equals(inputAsset(vaults).subtract(quantity));
        return amounts
                && outLovelace.compareTo(floor) >= 0
                && cleanValue(value)
                && out.referenceScript().isEmpty()
                && GatewayCodec.isEnterpriseScript(out.address().toPlutusData(), ownHash)
                && GatewayTx.outputsWithCredential(outputs, ownHash) == 1
                && Builtins.equalsData(GatewayTx.inlineDatum(out.datum()),
                Builtins.constrData(1, Builtins.mkCons(Builtins.listData(ids), Builtins.mkNilData())));
    }

    /**
     * The ids that inputs carrying the thread token named {@code [shardIndex]} insert, concatenated in redeemer
     * order; with exactly one shard input ({@link #foreignInputs}) that is the one shard's insert list. The shard
     * validator proves each id absent and inserts it.
     */
    private static byte[] shardInserts(TxInfo tx, BigInteger shardIndex) {
        byte[] name = Builtins.consByteString(shardIndex.longValue(), Builtins.emptyByteString());
        return shardInsertsIn(tx, tx.inputs(), name);
    }

    private static byte[] shardInsertsIn(TxInfo tx, JulcList<TxInInfo> inputs, byte[] name) {
        if (inputs.isEmpty()) {
            return Builtins.emptyByteString();
        }
        TxInInfo input = inputs.head();
        byte[] rest = shardInsertsIn(tx, inputs.tail(), name);
        return ValuesLib.assetOf(input.resolved().value(), shardThreadPolicyId, name).equals(BigInteger.ONE)
                ? Builtins.appendByteString(insertIds(Builtins.unListData(Builtins.headList(Builtins.constrFields(
                GatewayTx.spendRedeemer(tx, input.outRef()))))), rest)
                : rest;
    }

    private static byte[] insertIds(PlutusData inserts) {
        if (Builtins.nullList(inserts)) {
            return Builtins.emptyByteString();
        }
        return Builtins.appendByteString(
                Builtins.unBData(Builtins.headList(Builtins.constrFields(Builtins.headList(inserts)))),
                insertIds(Builtins.tailList(inserts)));
    }

    // ------------------------------------------------------------------
    // SWEEP_UNATTRIBUTED (D8d)

    private static boolean sweepValid(SweepExit sweep, TxInfo tx, byte[] ownHash, JulcList<TxInInfo> vaults,
                                      byte[] vaultId) {
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        if (anchor.isEmpty() || !vaults.tail().isEmpty()) {
            return false;
        }
        byte[] root = anchor.get().stateRoot();
        TxInInfo swept = vaults.head();
        TxOut sweptOut = swept.resolved();
        Value value = sweptOut.value();
        byte[] claimId = sweep.claimId();
        byte[] recipient = sweep.recipient();
        JulcList<SweepAsset> assets = sweep.assets();
        GatewayCodec.DomainStatus status = provedStatus(sweep.statusProof(), root);
        // SweepCommitment: every entry of the value (the lovelace too, for the ADA vault), and for a token vault the
        // lovelace as vault_lovelace.
        BigInteger vaultLovelace = adaVault() ? BigInteger.ZERO : ValuesLib.lovelaceOf(value);
        byte[] assetValue = Builtins.appendByteString(
                GatewayCodec.head(4, BigInteger.valueOf(assets.size())), sweptAssets(assets, value));
        byte[] withdrawalKey = GatewayCodec.outRefCbor((byte[]) (Object) swept.outRef().txId(),
                swept.outRef().index());
        MpfOnChainVerifier.Proof proof = sweep.proof();
        TxOut payout = tx.outputs().head();
        // The recipient, height and time are bounded by the proved leaf, which the reserving ledger wrote.
        return Builtins.lengthOfByteString(claimId) == 32
                && Builtins.indexByteString(claimId, 31) % 16 == sweep.shardIndex().longValue()
                && status.status().signum() == 0
                && sweep.reservedAtMs().add(status.exitDelayMs())
                .compareTo(IntervalLib.finiteLowerBound(tx.validRange())) <= 0
                && !(acceptedDatum(GatewayTx.inlineDatum(sweptOut.datum())) && cleanValue(value))
                && assets.size() + (adaVault() ? 0 : 1) == valueEntries(Builtins.unMapData(value))
                && Builtins.equalsByteString(claimId, GatewayCodec.claimId(gatewayDomainId, vaultId,
                BigInteger.ONE, withdrawalKey, recipient, assetValue, vaultLovelace, sweep.sequence()))
                && Builtins.equalsByteString(proof.key(), Builtins.appendByteString(claimKeyPrefix, claimId))
                && Builtins.equalsByteString(proof.value(), GatewayCodec.claimLeaf(claimId, vaultId, BigInteger.ONE,
                assetValue, vaultLovelace, recipient, sweep.reservedHeight(), sweep.reservedAtMs()))
                && MpfOnChainVerifier.verifyInclusion(proof, root)
                && Builtins.equalsData(payout.value(), value)
                && Builtins.lengthOfByteString(GatewayCodec.paymentKeyHash(payout.address().toPlutusData())) == 28
                && Builtins.equalsByteString(GatewayCodec.addressBytes(payout.address().toPlutusData(),
                addressNetworkId.longValue()), recipient)
                && Builtins.equalsData(GatewayTx.inlineDatum(payout.datum()),
                Builtins.constrData(4, Builtins.mkCons(Builtins.bData(claimId), Builtins.mkNilData())))
                && foreignInputs(tx.inputs(), ownHash).equals(BigInteger.ONE)
                && GatewayTx.outputsWithCredential(tx.outputs(), ownHash) == 0
                && Builtins.equalsByteString(shardInserts(tx, sweep.shardIndex()), claimId);
    }

    /**
     * The entries of the {@code asset_value} array, {@code [policy, name, quantity]} each, concatenated, when every
     * entry has a positive quantity equal to the swept value's, under a 28-byte policy (or, for the ADA vault, the
     * lovelace entry); a mismatched entry is prefixed with a stray {@code ff}, which no canonical leaf contains. With the entry
     * count equal to the value's, and the entries distinct (the proved leaf is canonical), the list is exactly the
     * value.
     */
    private static byte[] sweptAssets(JulcList<SweepAsset> assets, Value value) {
        if (assets.isEmpty()) {
            return Builtins.emptyByteString();
        }
        SweepAsset asset = assets.head();
        long policyLength = Builtins.lengthOfByteString(asset.policyId());
        byte[] entry = Builtins.appendByteString(Builtins.appendByteString(Builtins.consByteString(131,
                GatewayCodec.bytes(asset.policyId())), GatewayCodec.bytes(asset.assetName())),
                GatewayCodec.uint(asset.quantity()));
        boolean exact = asset.quantity().signum() > 0
                && (policyLength == 28 || (policyLength == 0 && adaVault()))
                && ValuesLib.assetOf(value, asset.policyId(), asset.assetName()).equals(asset.quantity());
        return Builtins.appendByteString(exact ? entry : Builtins.consByteString(255, entry),
                sweptAssets(assets.tail(), value));
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
    // Accept and Refund (D8f)

    /**
     * The vault inputs merge into one exact {@code VaultContinuationV1([])} whose value is their sum, wherever it sits
     * among the outputs; no shard, no other script input, no other vault output. The continuation floor does not
     * apply. ({@code Accept} spends deposit-shaped inputs, {@code Consolidate} accepted ones.)
     */
    private static boolean mergeValid(TxInfo tx, byte[] ownHash, JulcList<TxInInfo> vaults) {
        JulcList<TxOut> outputs = tx.outputs();
        long index = vaultOutputIndex(outputs, ownHash, 0);
        return foreignInputs(tx.inputs(), ownHash).signum() == 0
                && index >= 0
                && continuationValid(outputs, index, ownHash, vaults, BigInteger.ZERO, BigInteger.ZERO,
                Builtins.mkNilData(), BigInteger.ZERO);
    }

    private static long vaultOutputIndex(JulcList<TxOut> outputs, byte[] ownHash, long index) {
        if (outputs.isEmpty()) {
            return -1;
        }
        if (Builtins.equalsByteString(
                GatewayCodec.paymentScriptHash(outputs.head().address().toPlutusData()), ownHash)) {
            return index;
        }
        return vaultOutputIndex(outputs.tail(), ownHash, index + 1);
    }

    private static boolean cosigned(TxInfo tx) {
        Optional<MpfAnchorLib.AnchorState> anchor = anchorState(tx);
        return anchor.isPresent() && MpfAnchorLib.signedByThreshold(tx, anchor.get());
    }

    /** Proof {@code i} shows {@code deposit/<DepositId of vault input i> = CREDITED} under {@code root}. */
    private static boolean depositsProved(JulcList<MpfOnChainVerifier.Proof> proofs, JulcList<TxInInfo> vaults,
                                          byte[] vaultId, byte[] root) {
        if (vaults.isEmpty()) {
            return proofs.isEmpty();
        }
        return !proofs.isEmpty()
                && depositRecordProved(proofs.head(), vaults.head(), vaultId, root, BigInteger.ZERO)
                && depositsProved(proofs.tail(), vaults.tail(), vaultId, root);
    }

    /** The proof shows the deposit record of {@code input} with {@code status} under {@code root}. */
    private static boolean depositRecordProved(MpfOnChainVerifier.Proof proof, TxInInfo input, byte[] vaultId,
                                               byte[] root, BigInteger status) {
        byte[] depositId = GatewayCodec.depositId(vaultId, (byte[]) (Object) input.outRef().txId(),
                input.outRef().index());
        return Builtins.equalsByteString(proof.key(), Builtins.appendByteString(depositKeyPrefix, depositId))
                && GatewayCodec.depositStatus(proof.value(), depositId, vaultId).equals(status)
                && MpfOnChainVerifier.verifyInclusion(proof, root);
    }

    private static byte[] depositKey(JulcList<TxInInfo> vaults, byte[] vaultId) {
        TxInInfo deposit = vaults.head();
        return Builtins.appendByteString(depositKeyPrefix, GatewayCodec.depositId(vaultId,
                (byte[]) (Object) deposit.outRef().txId(), deposit.outRef().index()));
    }

    /**
     * Refund: the one vault input is deposit-shaped, its {@code refundKeyHash} signs, and output 0 pays its exact
     * value, foreign tokens included (an uncredited {@code MIXED_ASSETS} deposit goes back whole), to its
     * {@code refundAddress}; no other script input and no output with the vault's credential.
     */
    private static boolean refundShapeValid(TxInfo tx, byte[] ownHash, JulcList<TxInInfo> vaults) {
        TxInInfo deposit = vaults.head();
        PlutusData datum = GatewayTx.inlineDatum(deposit.resolved().datum());
        PlutusData fields = Builtins.constrFields(datum);
        PlutusData f1 = Builtins.tailList(fields);
        byte[] refundAddress = Builtins.unBData(Builtins.headList(f1));
        PlutusData f2 = Builtins.tailList(f1);
        byte[] refundKeyHash = Builtins.unBData(Builtins.headList(f2));
        TxOut refund = tx.outputs().head();
        return vaults.tail().isEmpty()
                && Builtins.constrTag(datum) == 0
                && foreignInputs(tx.inputs(), ownHash).signum() == 0
                && Builtins.nullList(Builtins.tailList(f2))
                && Builtins.lengthOfByteString(refundKeyHash) == 28
                && GatewayTx.signedBy(tx, refundKeyHash)
                && Builtins.equalsData(refund.value(), deposit.resolved().value())
                && Builtins.equalsByteString(GatewayCodec.addressBytes(refund.address().toPlutusData(),
                addressNetworkId.longValue()), refundAddress)
                && GatewayTx.outputsWithCredential(tx.outputs(), ownHash) == 0;
    }

    // ------------------------------------------------------------------
    // BalanceExit (D8g §4)

    private static boolean balanceExitValid(BalanceExit exit, PlutusData mode, TxInfo tx, byte[] ownHash,
                                            JulcList<TxInInfo> vaults, byte[] vaultId) {
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
                && (!adaVault() || lovelace.signum() == 0)
                && GatewayTx.signedBy(tx, exit.holderKeyHash())
                && Builtins.equalsByteString(proof.key(),
                Builtins.appendByteString(Builtins.appendByteString(exitKeyPrefix, vaultId), holderId))
                && Builtins.equalsByteString(proof.value(),
                GatewayCodec.exitLeaf(exit.holderKeyHash(), vaultId, amount, lovelace))
                && MpfOnChainVerifier.verifyInclusion(proof, GatewayTx.frozenRoot(mode))
                && Builtins.indexByteString(escapeId, 31) % 16 == exit.shardIndex().longValue()
                && Builtins.equalsByteString(shardInserts(tx, exit.shardIndex()), escapeId)
                && vaultsAccepted(vaults)
                && foreignInputs(tx.inputs(), ownHash).equals(BigInteger.ONE)
                && outputs.size() > 1
                && paysClaim(outputs.head(), exit.recipient(), amount, lovelace)
                && continuationValid(outputs, 1, ownHash, vaults, amount, lovelace,
                Builtins.mkCons(Builtins.bData(escapeId), Builtins.mkNilData()), continuationFloor);
    }

    // ------------------------------------------------------------------
    // Inputs, values and the anchor

    /** The first input whose payment credential is this vault: the lead. */
    private static TxInInfo firstVaultInput(JulcList<TxInInfo> inputs, byte[] ownHash) {
        TxInInfo input = inputs.head();
        if (Builtins.equalsByteString(
                GatewayCodec.paymentScriptHash(input.resolved().address().toPlutusData()), ownHash)) {
            return input;
        }
        return firstVaultInput(inputs.tail(), ownHash);
    }

    /** The inputs whose payment credential is this vault, in ledger order. */
    private static JulcList<TxInInfo> vaultInputs(JulcList<TxInInfo> inputs, byte[] ownHash) {
        if (inputs.isEmpty()) {
            return JulcList.empty();
        }
        TxInInfo input = inputs.head();
        JulcList<TxInInfo> rest = vaultInputs(inputs.tail(), ownHash);
        return Builtins.equalsByteString(
                GatewayCodec.paymentScriptHash(input.resolved().address().toPlutusData()), ownHash)
                ? rest.prepend(input) : rest;
    }

    /** Every vault input sits at the exact enterprise vault address. */
    private static boolean exactAddresses(JulcList<TxInInfo> vaults, byte[] ownHash) {
        if (vaults.isEmpty()) {
            return true;
        }
        return GatewayCodec.isEnterpriseScript(vaults.head().resolved().address().toPlutusData(), ownHash)
                && exactAddresses(vaults.tail(), ownHash);
    }

    /**
     * The other inputs that matter to V4/V8, as {@code shards + 256 * otherScripts}: inputs carrying a shard thread
     * token, and script inputs that are neither this vault's nor a shard.
     */
    private static BigInteger foreignInputs(JulcList<TxInInfo> inputs, byte[] ownHash) {
        if (inputs.isEmpty()) {
            return BigInteger.ZERO;
        }
        TxOut out = inputs.head().resolved();
        byte[] scriptHash = GatewayCodec.paymentScriptHash(out.address().toPlutusData());
        BigInteger rest = foreignInputs(inputs.tail(), ownHash);
        if (Builtins.equalsByteString(scriptHash, ownHash)) {
            return rest;
        }
        if (ValuesLib.containsPolicy(out.value(), shardThreadPolicyId)) {
            return rest.add(BigInteger.ONE);
        }
        return Builtins.lengthOfByteString(scriptHash) > 0 ? rest.add(BigInteger.valueOf(256)) : rest;
    }

    /** V10: every vault input is accepted custody. */
    private static boolean vaultsAccepted(JulcList<TxInInfo> vaults) {
        if (vaults.isEmpty()) {
            return true;
        }
        TxOut out = vaults.head().resolved();
        return acceptedDatum(GatewayTx.inlineDatum(out.datum())) && cleanValue(out.value())
                && vaultsAccepted(vaults.tail());
    }

    /** Every vault input is deposit-shaped (Constr 0) and holds only the vault asset and lovelace. */
    private static boolean vaultsDepositShaped(JulcList<TxInInfo> vaults) {
        if (vaults.isEmpty()) {
            return true;
        }
        TxOut out = vaults.head().resolved();
        return Builtins.constrTag(GatewayTx.inlineDatum(out.datum())) == 0 && cleanValue(out.value())
                && vaultsDepositShaped(vaults.tail());
    }

    private static BigInteger inputLovelace(JulcList<TxInInfo> vaults) {
        if (vaults.isEmpty()) {
            return BigInteger.ZERO;
        }
        return ValuesLib.lovelaceOf(vaults.head().resolved().value()).add(inputLovelace(vaults.tail()));
    }

    private static BigInteger inputAsset(JulcList<TxInInfo> vaults) {
        if (vaults.isEmpty()) {
            return BigInteger.ZERO;
        }
        return ValuesLib.assetOf(vaults.head().resolved().value(), assetPolicyId, assetName)
                .add(inputAsset(vaults.tail()));
    }

    /**
     * V10, on the decoded datum: {@code VaultContinuationV1} (Constr 1, one field: a list of at most 64 32-byte ids)
     * or {@code FloorTopUpV1} (Constr 2, no fields). Read from the datum's canonical Plutus serialization, so it is
     * total: any other datum, of any kind, is simply not accepted. That serialization is {@code d87b80} for a top-up,
     * {@code d87a9f80ff} for an empty continuation and {@code d87a9f9f (5820 <id>)+ ffff} otherwise (non-empty lists
     * are indefinite, empty ones definite, byte strings up to 64 bytes definite).
     */
    private static boolean acceptedDatum(PlutusData datum) {
        if (Builtins.equalsData(datum, Builtins.constrData(2, Builtins.mkNilData()))) {
            return true;
        }
        byte[] encoded = Builtins.serialiseData(datum);
        long length = Builtins.lengthOfByteString(encoded);
        // Big-endian: d87a9f80ff, d87a9f9f and ffff.
        return (length == 5 && Builtins.byteStringToInteger(true, encoded).equals(BigInteger.valueOf(929770209535L)))
                || (Builtins.byteStringToInteger(true, Builtins.sliceByteString(0, 4, encoded))
                .equals(BigInteger.valueOf(3631914911L))
                && Builtins.byteStringToInteger(true, Builtins.sliceByteString(length - 2, 2, encoded))
                .equals(BigInteger.valueOf(65535L))
                && (length - 6) % 34 == 0 && length - 6 <= 64 * 34
                && idChunks(encoded, 4, length - 2));
    }

    /** {@code encoded[at, end)} is a run of 32-byte byte strings, {@code 5820 <id>} each. */
    private static boolean idChunks(byte[] encoded, long at, long end) {
        if (at == end) {
            return true;
        }
        return Builtins.indexByteString(encoded, at) == 88 && Builtins.indexByteString(encoded, at + 1) == 32
                && idChunks(encoded, at + 34, end);
    }

    /** The value holds lovelace and, for a token vault, at most this vault's one asset, and nothing else. */
    private static boolean cleanValue(Value value) {
        PlutusData rest = Builtins.tailList(Builtins.unMapData(value));
        if (Builtins.nullList(rest)) {
            return true;
        }
        PlutusData policy = Builtins.headList(rest);
        PlutusData tokens = Builtins.unMapData(Builtins.sndPair(policy));
        return !adaVault()
                && Builtins.nullList(Builtins.tailList(rest))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(policy)), assetPolicyId)
                && Builtins.nullList(Builtins.tailList(tokens))
                && Builtins.equalsByteString(Builtins.unBData(Builtins.fstPair(Builtins.headList(tokens))),
                assetName);
    }

    private static boolean adaVault() {
        return Builtins.lengthOfByteString(assetPolicyId) == 0;
    }

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
}
