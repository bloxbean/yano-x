package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The steps of the ADR-059 D8h §1 body check that every vault co-sign request shares, {@code Settle} and co-signed
 * {@code Accept} alike: the request form and validity interval (step 0), resolving inputs in the member's own view and
 * refusing the member's own key (step 1), nothing but spends (step 3), and the referenced pinned anchor and
 * {@code NORMAL} exit-mode thread (step 3). Each step throws {@link RefusedException}; the public checks turn it into a
 * {@link SettleBodyCheck.Refused} outcome.
 */
final class BodyChecks {
    private BodyChecks() {
    }

    /** A refusal raised by one step. */
    static final class RefusedException extends RuntimeException {
        private final SettleBodyCheck.Refusal refusal;

        RefusedException(SettleBodyCheck.Refusal refusal) {
            super(refusal.detail(), null, false, false);
            this.refusal = refusal;
        }

        SettleBodyCheck.Refusal refusal() {
            return refusal;
        }
    }

    static RefusedException hazard(String detail) {
        return new RefusedException(SettleBodyCheck.Refusal.hazard(detail));
    }

    /**
     * Step 0: the request is a transaction in exactly the form this library re-serializes it to, so the hash the
     * member signs is the id of the transaction it received; its validity interval is bounded by the member's own L1
     * time; it is the full unsigned transaction (redeemers, no vkey witnesses) with a script data hash recomputed here.
     */
    static Transaction requestForm(byte[] fullUnsignedTx, CostMdls costModels, SettleBodyCheck.Tip tip) {
        Transaction tx;
        byte[] reserialized;
        try {
            tx = Transaction.deserialize(fullUnsignedTx);
            reserialized = tx.serialize();
        } catch (Exception malformed) {
            throw hazard("the request is not a transaction");
        }
        if (!Arrays.equals(reserialized, fullUnsignedTx)) {
            throw hazard("the request does not re-serialize to its own bytes, so its id is not the hash I would sign");
        }
        TransactionBody body = tx.getBody();
        if (body.getTtl() <= 0) throw hazard("the body has no TTL");
        if (body.getTtl() > tip.slot() + tip.slotsWithin(SettleBodyCheck.MAX_TTL_AHEAD_MS)) {
            throw new RefusedException(SettleBodyCheck.Refusal.tipBound("the TTL lies more than "
                    + SettleBodyCheck.MAX_TTL_AHEAD_MS + " ms past my L1 tip (is my clock behind the owner's?)"));
        }
        if (body.getValidityStartInterval() > tip.slot() + tip.slotsWithin(SettleBodyCheck.MAX_START_SKEW_MS)) {
            throw new RefusedException(SettleBodyCheck.Refusal.tipBound("the validity start lies more than "
                    + SettleBodyCheck.MAX_START_SKEW_MS + " ms past my L1 tip (is my clock behind the owner's?)"));
        }
        TransactionWitnessSet witnesses = tx.getWitnessSet();
        if (witnesses == null || witnesses.getRedeemers() == null || witnesses.getRedeemers().isEmpty()) {
            throw hazard("the request carries no redeemers");
        }
        if (nonEmpty(witnesses.getVkeyWitnesses()) || nonEmpty(witnesses.getBootstrapWitnesses())
                || nonEmpty(witnesses.getNativeScripts())) {
            throw hazard("the request already carries witnesses or native scripts");
        }
        if (body.getScriptDataHash() == null) throw hazard("the body has no script_data_hash");
        byte[] expectedHash;
        try {
            expectedHash = ScriptDataHashGenerator.generate(witnesses.getRedeemers(),
                    witnesses.getPlutusDataList() == null ? List.of() : witnesses.getPlutusDataList(), costModels);
        } catch (Exception failure) {
            throw hazard("the script data hash cannot be recomputed");
        }
        if (!Arrays.equals(expectedHash, body.getScriptDataHash())) {
            throw hazard("script_data_hash does not commit to the witnessed redeemers and datums");
        }
        return tx;
    }

    /** Step 3: mint, certificates, withdrawals, votes and proposals are empty. */
    static void onlySpends(TransactionBody body) {
        if (nonEmpty(body.getMint())) throw hazard("it mints or burns");
        if (nonEmpty(body.getCerts())) throw hazard("it carries certificates");
        if (nonEmpty(body.getWithdrawals())) throw hazard("it withdraws rewards");
        if (body.getVotingProcedures() != null && body.getVotingProcedures().getVoting() != null
                && !body.getVotingProcedures().getVoting().isEmpty()) {
            throw hazard("it votes");
        }
        if (nonEmpty(body.getProposalProcedures())) throw hazard("it proposes");
    }

    /** Step 1: the input resolves in the member's own view, or the request is refused as unresolvable. */
    static ResolvedOutput resolve(TransactionInput input,
                                  Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View) {
        ResolvedOutput.Outpoint outpoint = new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()),
                input.getIndex());
        Optional<ResolvedOutput> resolved;
        try {
            resolved = l1View.apply(outpoint);
        } catch (RuntimeException unavailable) {
            resolved = Optional.empty();
        }
        return resolved.orElseThrow(() -> new RefusedException(
                SettleBodyCheck.Refusal.unresolved("input " + outpoint + " is not in my UTxO view")));
    }

    /** Step 1: the input's payment credential, refusing an input locked by the member's own key. */
    static ResolvedOutput.PaymentCredential credential(ResolvedOutput resolved, byte[] ownKeyHash) {
        ResolvedOutput.PaymentCredential credential = resolved.paymentCredential()
                .orElseThrow(() -> hazard("input " + resolved.outpoint() + " has no payment credential"));
        if (!credential.script() && Arrays.equals(credential.hash(), ownKeyHash)) {
            throw hazard("it spends an input locked by my own member key");
        }
        return credential;
    }

    /** Step 1: every collateral input resolves, is not the member's own, and is key-locked. */
    static void keyCollateral(TransactionBody body, Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View,
                              byte[] ownKeyHash) {
        if (body.getCollateral() == null) return;
        for (TransactionInput input : body.getCollateral()) {
            if (credential(resolve(input, l1View), ownKeyHash).script()) throw hazard("script collateral");
        }
    }

    /**
     * Step 3: the reference inputs include the pinned live anchor, its datum accepted against the pinned identity, and
     * the exit-mode thread in {@code NORMAL}. Returns the anchored datum.
     */
    static AnchorObservationV1.Anchored pinnedAnchorInNormalMode(
            TransactionBody body, VaultPins pins, Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View) {
        AnchorObservationV1.Anchored anchor = null;
        boolean normalMode = false;
        if (body.getReferenceInputs() != null) {
            for (TransactionInput input : body.getReferenceInputs()) {
                ResolvedOutput ref = resolve(input, l1View);
                Optional<ResolvedOutput.PaymentCredential> credential = ref.paymentCredential();
                if (credential.isEmpty() || !credential.get().script()) continue;
                if (Arrays.equals(credential.get().hash(), pins.anchor().scriptHash())
                        && ref.quantityOf(pins.anchorToken()).equals(BigInteger.ONE)) {
                    if (anchor != null) throw hazard("two anchors are referenced");
                    VaultPins.Anchor pinned = pins.anchor();
                    anchor = ref.inlineDatum().flatMap(datum -> GatewayDatums.acceptedAnchorV2(datum,
                            pinned.genesisId(), pinned.applicationId(), pinned.commitmentProfile(),
                            pinned.formatFingerprint())).orElseThrow(() -> hazard("the anchor datum is not accepted"));
                } else if (Arrays.equals(credential.get().hash(), pins.exitModeScriptHash())
                        && ref.quantityOf(pins.exitModeToken()).equals(BigInteger.ONE)) {
                    normalMode = ref.inlineDatum().map(BodyChecks::isNormal).orElse(false);
                }
            }
        }
        if (anchor == null) throw hazard("the pinned live anchor is not referenced");
        if (!normalMode) throw hazard("the exit-mode thread is not referenced in NORMAL");
        return anchor;
    }

    /**
     * The required signers name at least the referenced anchor's threshold of distinct members (V9), so a member never
     * signs a body that its quorum's signatures could not make pass on L1 (Codex P3-8).
     */
    static void quorumOfRequiredSigners(TransactionBody body, AnchorObservationV1.Anchored anchor) {
        Set<String> members = new HashSet<>();
        for (Hash32 member : anchor.memberKeys()) {
            members.add(HexFormat.of().formatHex(Blake2bUtil.blake2bHash224(member.bytes())));
        }
        Set<String> named = new HashSet<>();
        if (body.getRequiredSigners() != null) {
            for (byte[] signer : body.getRequiredSigners()) {
                String hash = HexFormat.of().formatHex(signer);
                if (members.contains(hash)) named.add(hash);
            }
        }
        if (named.size() < anchor.threshold()) {
            throw hazard("the required signers name " + named.size() + " anchor members, below its threshold of "
                    + anchor.threshold());
        }
    }

    /** The spend redeemers by the outpoint they point at in ledger order; anything else is refused. */
    static Map<ResolvedOutput.Outpoint, PlutusData> spendRedeemers(TransactionWitnessSet witnesses,
                                                                   List<ResolvedOutput.Outpoint> order) {
        Map<ResolvedOutput.Outpoint, PlutusData> redeemers = new TreeMap<>();
        for (Redeemer redeemer : witnesses.getRedeemers()) {
            if (redeemer.getTag() != RedeemerTag.Spend) throw hazard("a redeemer that is not a spend");
            int index = redeemer.getIndex().intValueExact();
            if (index < 0 || index >= order.size() || redeemers.put(order.get(index), redeemer.getData()) != null) {
                throw hazard("a redeemer points at no input");
            }
        }
        return redeemers;
    }

    private static boolean isNormal(byte[] datum) {
        try {
            return GatewayPlutus.isNormalMode(PlutusData.deserialize(datum));
        } catch (Exception malformed) {
            return false;
        }
    }

    static boolean nonEmpty(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }
}
