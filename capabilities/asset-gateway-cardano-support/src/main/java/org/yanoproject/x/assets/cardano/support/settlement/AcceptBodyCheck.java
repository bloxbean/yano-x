package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The member body check of ADR-059 D8h §1 for a co-signed {@code Accept} (step 4, the {@code Accept} variant), run by
 * every member before it signs and by the owner before it signs its own body. Like {@link SettleBodyCheck} it is a
 * pure function of the full unsigned transaction, the member's own L1 UTxO view and its own committed state, and it
 * fails closed.
 *
 * <ol start="0">
 *   <li>The request form and validity interval of {@link SettleBodyCheck}: exactly its own re-serialization, no vkey
 *       witnesses, a recomputed {@code script_data_hash}, a TTL and start bounded by the member's own L1 time.</li>
 *   <li>Every input and collateral input resolves in the member's own view; none is locked by the member's own
 *       key.</li>
 *   <li>At least one input is at the pinned vault address, and the only script inputs are this vault's own, at its
 *       exact address: no shard input, no other script.</li>
 *   <li>Mint, certificates, withdrawals, votes and proposals are empty; the reference inputs include the pinned live
 *       anchor and the exit-mode thread in {@code NORMAL}.</li>
 *   <li>Every vault input is deposit-shaped ({@link AcceptTxBuilder#depositShaped}) and its {@code DepositId},
 *       computed here from its outpoint, is recorded {@code CREDITED} for this vault in the member's committed state,
 *       admitted at or below the referenced anchor's height. The lead vault input carries {@code AcceptCosigned} and
 *       every other one the same redeemer or {@code Follow}; no other input has a redeemer, so there are no claims.
 *       Exactly one output has the vault's payment credential: at the exact vault address, with no reference script,
 *       the datum {@code VaultContinuationV1([])}, and exactly the inputs' summed lovelace and vault asset.</li>
 *   <li>The required signers name at least the referenced anchor's threshold of distinct members (V9).</li>
 * </ol>
 *
 * A signable outcome carries the body hash and the referenced anchor; it names no claims and no shard
 * ({@code shardIndex} is {@code -1}).
 */
public final class AcceptBodyCheck {
    private AcceptBodyCheck() {
    }

    /** The member's committed deposit records (the consumer's {@code asset-gateway/deposit} query). */
    @FunctionalInterface
    public interface Deposits {
        Optional<DepositRecordV1> deposit(Hash32 depositId);
    }

    /**
     * Checks one co-sign request for a co-signed {@code Accept}.
     *
     * @param ownKeyHash the 28-byte hash of this member's signing key
     * @param l1View     this member's own L1 UTxO view (unspent outputs only)
     */
    public static SettleBodyCheck.Outcome check(byte[] fullUnsignedTx, VaultPins pins, byte[] ownKeyHash,
                                                Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View,
                                                Deposits committed, CostMdls costModels, SettleBodyCheck.Tip tip) {
        return SettleBodyCheck.guarded(() -> checkOrThrow(fullUnsignedTx, pins, ownKeyHash, l1View, committed,
                costModels, tip));
    }

    /**
     * True when the request's redeemers name a co-signed {@code Accept}: a member's co-sign service routes such a
     * request to this check and every other one to {@link SettleBodyCheck}. Routing decides nothing: each check
     * verifies the whole body and refuses anything else.
     */
    public static boolean isAcceptRequest(byte[] fullUnsignedTx) {
        try {
            Transaction tx = Transaction.deserialize(fullUnsignedTx);
            var redeemers = tx.getWitnessSet() == null ? null : tx.getWitnessSet().getRedeemers();
            return redeemers != null && redeemers.stream().anyMatch(r -> GatewayPlutus.isAcceptCosigned(r.getData()));
        } catch (Exception malformed) {
            return false;
        }
    }

    private static SettleBodyCheck.Outcome checkOrThrow(byte[] fullUnsignedTx, VaultPins pins, byte[] ownKeyHash,
                                                        Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>>
                                                                l1View, Deposits committed, CostMdls costModels,
                                                        SettleBodyCheck.Tip tip) {
        // §0: the request form, its validity interval and its script data hash.
        Transaction tx = BodyChecks.requestForm(fullUnsignedTx, costModels, tip);
        TransactionBody body = tx.getBody();

        // §3: nothing but spends.
        BodyChecks.onlySpends(body);

        // §1, §2: every input resolves; the only script inputs are this vault's own.
        TreeMap<ResolvedOutput.Outpoint, ResolvedOutput> spent = new TreeMap<>();
        List<ResolvedOutput> vaultInputs = new ArrayList<>();
        for (TransactionInput input : body.getInputs()) {
            ResolvedOutput resolved = BodyChecks.resolve(input, l1View);
            if (spent.put(resolved.outpoint(), resolved) != null) throw BodyChecks.hazard("duplicate input");
            ResolvedOutput.PaymentCredential credential = BodyChecks.credential(resolved, ownKeyHash);
            if (!credential.script()) continue;
            if (!Arrays.equals(credential.hash(), pins.vaultScriptHash())) {
                throw BodyChecks.hazard("it spends a script input other than this vault's own");
            }
            if (!resolved.sameAddress(pins.vaultAddress())) {
                throw BodyChecks.hazard("a vault input is off the exact address");
            }
            if (!AcceptTxBuilder.depositShaped(resolved, pins)) {
                throw BodyChecks.hazard("a vault input is not a deposit-shaped output of this vault");
            }
            vaultInputs.add(resolved);
        }
        if (vaultInputs.isEmpty()) throw BodyChecks.hazard("no input is at the pinned vault address");
        BodyChecks.keyCollateral(body, l1View, ownKeyHash);

        // §3: the pinned live anchor and the exit-mode thread, both referenced.
        AnchorObservationV1.Anchored anchor = BodyChecks.pinnedAnchorInNormalMode(body, pins, l1View);

        // §4: the redeemers: AcceptCosigned on the lead, Follow or the same on the others, nothing else.
        List<ResolvedOutput.Outpoint> order = new ArrayList<>(spent.keySet());
        Map<ResolvedOutput.Outpoint, PlutusData> redeemers = BodyChecks.spendRedeemers(tx.getWitnessSet(), order);
        Set<ResolvedOutput.Outpoint> scripted = new HashSet<>();
        vaultInputs.forEach(in -> scripted.add(in.outpoint()));
        if (!redeemers.keySet().equals(scripted)) throw BodyChecks.hazard("redeemers do not match the vault inputs");
        ResolvedOutput.Outpoint lead = order.stream().filter(scripted::contains).findFirst().orElseThrow();
        PlutusData leadRedeemer = redeemers.get(lead);
        if (!GatewayPlutus.isAcceptCosigned(leadRedeemer)) {
            throw BodyChecks.hazard("the lead vault input does not carry AcceptCosigned");
        }
        for (ResolvedOutput vault : vaultInputs) {
            PlutusData data = redeemers.get(vault.outpoint());
            if (!vault.outpoint().equals(lead) && !GatewayPlutus.isFollow(data) && !data.equals(leadRedeemer)) {
                throw BodyChecks.hazard("a vault input carries another action");
            }
        }

        // §4: every deposit is CREDITED for this vault in committed state, at or below the referenced anchor.
        BigInteger lovelace = BigInteger.ZERO;
        BigInteger asset = BigInteger.ZERO;
        for (ResolvedOutput deposit : vaultInputs) {
            Hash32 depositId = AcceptTxBuilder.depositId(pins, deposit.outpoint());
            DepositRecordV1 record;
            try {
                record = committed.deposit(depositId).orElseThrow(() -> BodyChecks.hazard("unknown deposit "
                        + depositId));
            } catch (BodyChecks.RefusedException refused) {
                throw refused;
            } catch (RuntimeException unavailable) {
                throw BodyChecks.hazard("unknown deposit " + depositId);
            }
            if (record.status() != DepositRecordV1.Status.CREDITED) {
                throw BodyChecks.hazard("deposit " + depositId + " is not CREDITED");
            }
            if (!record.depositId().equals(depositId) || !record.vaultId().equals(pins.vaultId())
                    || !record.txId().equals(deposit.outpoint().txId())
                    || record.outputIndex() != deposit.outpoint().index()) {
                throw BodyChecks.hazard("deposit " + depositId + " is recorded for another output");
            }
            if (record.height() > anchor.height()) {
                throw BodyChecks.hazard("deposit " + depositId + " is credited above the referenced anchor");
            }
            lovelace = lovelace.add(deposit.lovelace());
            asset = asset.add(pins.adaVault() ? BigInteger.ZERO : deposit.quantityOf(pins.asset()));
        }

        // §4: exactly one vault output, the exact empty continuation of the inputs' summed value.
        TransactionOutput continuation = null;
        for (TransactionOutput out : body.getOutputs()) {
            Optional<ResolvedOutput.PaymentCredential> credential =
                    ResolvedOutput.paymentCredential(SettleBodyCheck.addressBytes(out));
            if (credential.isEmpty() || !credential.get().script()
                    || !Arrays.equals(credential.get().hash(), pins.vaultScriptHash())) {
                continue;
            }
            if (continuation != null) throw BodyChecks.hazard("another output pays the vault");
            continuation = out;
        }
        if (continuation == null) throw BodyChecks.hazard("no vault continuation");
        Map<AssetId, BigInteger> expectedAssets = asset.signum() == 0 ? Map.of() : Map.of(pins.asset(), asset);
        boolean exact = Arrays.equals(SettleBodyCheck.addressBytes(continuation), pins.vaultAddress())
                && continuation.getScriptRef() == null
                && continuation.getValue().getCoin().equals(lovelace)
                && SettleBodyCheck.assets(continuation).equals(expectedAssets)
                && continuation.getInlineDatum() != null
                && isEmptyContinuation(continuation.getInlineDatum());
        if (!exact) throw BodyChecks.hazard("the vault output is not the exact empty continuation of the deposits");
        BodyChecks.quorumOfRequiredSigners(body, anchor);
        byte[] bodyHash = HexFormat.of().parseHex(TransactionUtil.getTxHash(tx));
        return new SettleBodyCheck.Signable(new SettleBodyCheck.Verified(bodyHash, List.of(), -1, anchor));
    }

    private static boolean isEmptyContinuation(PlutusData datum) {
        try {
            return GatewayPlutus.decodeContinuation(datum).isEmpty();
        } catch (RuntimeException malformed) {
            return false;
        }
    }
}
