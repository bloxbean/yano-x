package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.plutus.util.ScriptDataHashGenerator;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

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
 * The member body check of ADR-059 D8h §1 for a co-signed {@code Settle}, run by every member before it signs and by
 * the owner before it signs its own body (r7, I28). It is a pure function of the full unsigned transaction, the
 * member's own L1 UTxO view and its own committed state, and fails closed: anything it cannot positively verify is
 * refused.
 *
 * <ol start="0">
 *   <li>The request is the full unsigned transaction: no vkey witnesses; the member recomputes
 *       {@code script_data_hash} from the witnessed redeemers and datums and refuses a missing or different one.</li>
 *   <li>Every input and collateral input resolves in the member's own view, and none is locked by the member's own
 *       key, so its witness authorizes nothing else.</li>
 *   <li>At least one input is at the pinned vault address; the only script inputs are this vault's own (at its exact
 *       address) and exactly one shard input carrying this vault's shard thread token; collateral is key-locked.</li>
 *   <li>Mint, certificates, withdrawals, votes and proposals are empty; the reference inputs include the pinned, live
 *       anchor (its datum accepted against the pinned identity) and the exit-mode thread in {@code NORMAL}.</li>
 *   <li>(§1 step 5) The lead vault input carries {@code Settle}, every other vault input the same redeemer or
 *       {@code Follow}, and the shard inserts exactly the paid ids. Every claim is one of the member's own
 *       {@code RESERVED} {@code USER_WITHDRAWAL} claims of this vault, with exactly the committed fields, reserved at
 *       or below the referenced anchor's height (V12). The outputs satisfy V5-V7 against those committed leaves: output
 *       {@code i} pays {@code claim[i]}'s recipient its exact asset quantity and at least its {@code vaultLovelace},
 *       output {@code k} is the exact continuation with the exact value equation, and no other output pays the vault.
 *       Vault inputs are accepted custody (V10). The domain status leaf is {@code ACTIVE}.</li>
 * </ol>
 */
public final class SettleBodyCheck {
    private SettleBodyCheck() {
    }

    /** Why a body was refused; the {@code BodyRefusal} pattern of the script-anchor co-sign service. */
    public record Refusal(Kind kind, String detail) {
        public enum Kind {
            /** An input is not in this member's UTxO view: an availability problem, not a forged body. */
            UNRESOLVED_INPUT("unresolvable-input"),
            /** The body is unsafe to sign. */
            HAZARD("hazard");

            private final String label;

            Kind(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }
        }

        static Refusal unresolved(String detail) {
            return new Refusal(Kind.UNRESOLVED_INPUT, detail);
        }

        static Refusal hazard(String detail) {
            return new Refusal(Kind.HAZARD, detail);
        }
    }

    /** The member's committed state: its claims and its domain status leaf. */
    public interface Committed {
        Optional<SettlementQueries.ClaimView> claim(Hash32 claimId);

        Optional<DomainStatusV1> domainStatus();
    }

    /** A verified body: the hash to sign, the paid ids in order, and the referenced anchor. */
    public record Verified(byte[] bodyHash, List<Hash32> claimIds, int shardIndex,
                           AnchorObservationV1.Anchored anchor) {
        public Verified {
            bodyHash = bodyHash.clone();
            claimIds = List.copyOf(claimIds);
        }

        @Override
        public byte[] bodyHash() {
            return bodyHash.clone();
        }
    }

    /** Either a verified body or a refusal. */
    public sealed interface Outcome permits Signable, Refused {
    }

    public record Signable(Verified verified) implements Outcome {
    }

    public record Refused(Refusal refusal) implements Outcome {
    }

    /**
     * Checks one co-sign request.
     *
     * @param ownKeyHash the 28-byte hash of this member's signing key
     * @param l1View     this member's own L1 UTxO view (unspent outputs only)
     */
    public static Outcome check(byte[] fullUnsignedTx, VaultPins pins, byte[] ownKeyHash,
                                Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View,
                                Committed committed, CostMdls costModels) {
        try {
            return checkOrThrow(fullUnsignedTx, pins, ownKeyHash, l1View, committed, costModels);
        } catch (RefusedException refused) {
            return new Refused(refused.refusal);
        } catch (RuntimeException malformed) {
            return new Refused(Refusal.hazard("the request does not decode: " + malformed.getMessage()));
        }
    }

    private static final class RefusedException extends RuntimeException {
        private final Refusal refusal;

        RefusedException(Refusal refusal) {
            super(refusal.detail(), null, false, false);
            this.refusal = refusal;
        }
    }

    private static RefusedException hazard(String detail) {
        return new RefusedException(Refusal.hazard(detail));
    }

    private static Outcome checkOrThrow(byte[] fullUnsignedTx, VaultPins pins, byte[] ownKeyHash,
                                        Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View,
                                        Committed committed, CostMdls costModels) throws RefusedException {
        Transaction tx;
        try {
            tx = Transaction.deserialize(fullUnsignedTx);
        } catch (Exception malformed) {
            throw hazard("the request is not a transaction");
        }
        TransactionBody body = tx.getBody();

        // §0: the full unsigned transaction, with its script data hash recomputed here.
        var witnesses = tx.getWitnessSet();
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

        // §3: nothing but spends.
        if (nonEmpty(body.getMint())) throw hazard("it mints or burns");
        if (nonEmpty(body.getCerts())) throw hazard("it carries certificates");
        if (nonEmpty(body.getWithdrawals())) throw hazard("it withdraws rewards");
        if (body.getVotingProcedures() != null && body.getVotingProcedures().getVoting() != null
                && !body.getVotingProcedures().getVoting().isEmpty()) {
            throw hazard("it votes");
        }
        if (nonEmpty(body.getProposalProcedures())) throw hazard("it proposes");

        // §1, §2: every input resolves; only this vault's inputs and one shard input are script-locked.
        TreeMap<ResolvedOutput.Outpoint, ResolvedOutput> spent = new TreeMap<>();
        List<ResolvedOutput> vaultInputs = new ArrayList<>();
        ResolvedOutput shardInput = null;
        int shardIndex = -1;
        for (TransactionInput input : body.getInputs()) {
            ResolvedOutput resolved = resolve(input, l1View);
            if (spent.put(resolved.outpoint(), resolved) != null) throw hazard("duplicate input");
            ResolvedOutput.PaymentCredential credential = credential(resolved, ownKeyHash);
            if (!credential.script()) continue;
            if (Arrays.equals(credential.hash(), pins.vaultScriptHash())) {
                if (!resolved.sameAddress(pins.vaultAddress())) throw hazard("a vault input is off the exact address");
                if (!PayoutTxBuilder.accepted(resolved, pins)) throw hazard("a vault input is not accepted custody");
                vaultInputs.add(resolved);
            } else if (Arrays.equals(credential.hash(), pins.shardScriptHash()) && shardInput == null) {
                shardIndex = shardTokenIndex(resolved, pins);
                if (shardIndex < 0 || !resolved.sameAddress(pins.shardAddress())) {
                    throw hazard("the shard input does not carry exactly one of this vault's thread tokens");
                }
                shardInput = resolved;
            } else {
                throw hazard("it spends a script input other than this vault and its one shard");
            }
        }
        if (vaultInputs.isEmpty()) throw hazard("no input is at the pinned vault address");
        if (shardInput == null) throw hazard("no shard input");
        if (body.getCollateral() != null) {
            for (TransactionInput input : body.getCollateral()) {
                if (credential(resolve(input, l1View), ownKeyHash).script()) throw hazard("script collateral");
            }
        }

        // §3: the pinned live anchor and the exit-mode thread, both referenced.
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
                    normalMode = ref.inlineDatum().map(SettleBodyCheck::isNormal).orElse(false);
                }
            }
        }
        if (anchor == null) throw hazard("the pinned live anchor is not referenced");
        if (!normalMode) throw hazard("the exit-mode thread is not referenced in NORMAL");

        // §1 step 5: the redeemers.
        List<ResolvedOutput.Outpoint> order = new ArrayList<>(spent.keySet());
        Map<ResolvedOutput.Outpoint, PlutusData> redeemers = new TreeMap<>();
        for (Redeemer redeemer : witnesses.getRedeemers()) {
            if (redeemer.getTag() != RedeemerTag.Spend) throw hazard("a redeemer that is not a spend");
            int index = redeemer.getIndex().intValueExact();
            if (index < 0 || index >= order.size() || redeemers.put(order.get(index), redeemer.getData()) != null) {
                throw hazard("a redeemer points at no input");
            }
        }
        Set<ResolvedOutput.Outpoint> scripted = new HashSet<>();
        vaultInputs.forEach(in -> scripted.add(in.outpoint()));
        scripted.add(shardInput.outpoint());
        if (!redeemers.keySet().equals(scripted)) throw hazard("redeemers do not match the script inputs");
        ResolvedOutput.Outpoint lead = order.stream().filter(outpoint -> vaultInputs.stream()
                .anyMatch(in -> in.outpoint().equals(outpoint))).findFirst().orElseThrow();
        PlutusData leadRedeemer = redeemers.get(lead);
        GatewayPlutus.DecodedSettle settle;
        try {
            settle = GatewayPlutus.decodeSettle(leadRedeemer);
        } catch (RuntimeException notSettle) {
            throw hazard("the lead vault input does not carry Settle");
        }
        for (ResolvedOutput vault : vaultInputs) {
            PlutusData data = redeemers.get(vault.outpoint());
            if (!vault.outpoint().equals(lead) && !GatewayPlutus.isFollow(data) && !data.equals(leadRedeemer)) {
                throw hazard("a vault input carries another action");
            }
        }
        List<GatewayPlutus.RedeemerClaim> claims = settle.claims();
        if (claims.isEmpty() || claims.size() > pins.maxSettleBatch()) throw hazard("batch size outside the profile");
        if (settle.shardIndex() != shardIndex) throw hazard("Settle names another shard");
        List<Hash32> ids = claims.stream().map(GatewayPlutus.RedeemerClaim::claimId).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw hazard("a claim is paid twice");
        List<Hash32> inserts;
        try {
            inserts = GatewayPlutus.decodeShardInserts(redeemers.get(shardInput.outpoint()));
        } catch (RuntimeException malformed) {
            throw hazard("the shard redeemer does not decode");
        }
        if (!inserts.equals(ids)) throw hazard("the shard inserts differ from the paid claims");

        // Every claim is a committed, RESERVED user claim of this vault, with exactly these fields (V12 included).
        DomainStatusV1 status = committed.domainStatus().orElseThrow(() -> hazard("no committed domain status"));
        if (status.status() != DomainStatusV1.Status.ACTIVE) throw hazard("the domain is not ACTIVE");
        List<ClaimLeafV1> leaves = new ArrayList<>();
        for (GatewayPlutus.RedeemerClaim claim : claims) {
            if (SettlementBatchV1.shardOf(claim.claimId()) != shardIndex) throw hazard("claim of another shard");
            SettlementQueries.ClaimView view = committed.claim(claim.claimId())
                    .orElseThrow(() -> hazard("unknown claim " + claim.claimId()));
            ClaimLeafV1 leaf = view.leaf();
            if (view.status().status() != ClaimStatus.RESERVED) throw hazard("claim " + claim.claimId() + " is resolved");
            if (leaf.kind() != ClaimKind.USER_WITHDRAWAL) throw hazard("a governed claim in Settle");
            if (!leaf.vaultId().equals(pins.vaultId())) throw hazard("a claim of another vault");
            if (!leafMatches(leaf, claim, pins.asset())) throw hazard("claim fields differ from the committed leaf");
            if (leaf.reservedHeight() > anchor.height()) {
                throw hazard("claim " + claim.claimId() + " is reserved above the referenced anchor (V12)");
            }
            leaves.add(leaf);
        }

        // V5-V7 against the committed leaves.
        List<TransactionOutput> outputs = body.getOutputs();
        int k = leaves.size();
        if (outputs.size() <= k) throw hazard("too few outputs");
        BigInteger quantity = BigInteger.ZERO;
        BigInteger vaultLovelace = BigInteger.ZERO;
        for (int i = 0; i < k; i++) {
            ClaimLeafV1 leaf = leaves.get(i);
            TransactionOutput out = outputs.get(i);
            byte[] raw = addressBytes(out);
            Optional<ResolvedOutput.PaymentCredential> credential = ResolvedOutput.paymentCredential(raw);
            if (!Arrays.equals(raw, leaf.recipient()) || credential.isEmpty() || credential.get().script()) {
                throw hazard("output " + i + " does not pay claim " + leaf.claimId() + "'s recipient");
            }
            BigInteger claimQuantity = leaf.value().quantityOf(pins.asset());
            Map<AssetId, BigInteger> assets = assets(out);
            boolean amounts = pins.adaVault()
                    ? assets.isEmpty() && out.getValue().getCoin().compareTo(claimQuantity) >= 0
                    : assets.equals(claimQuantity.signum() == 0 ? Map.of() : Map.of(pins.asset(), claimQuantity))
                    && out.getValue().getCoin().compareTo(leaf.vaultLovelace()) >= 0;
            if (!amounts) throw hazard("output " + i + " does not pay claim " + leaf.claimId() + "'s amount");
            quantity = quantity.add(claimQuantity);
            vaultLovelace = vaultLovelace.add(leaf.vaultLovelace());
        }
        BigInteger inLovelace = vaultInputs.stream().map(ResolvedOutput::lovelace).reduce(BigInteger.ZERO,
                BigInteger::add);
        BigInteger inAsset = vaultInputs.stream().map(in -> pins.adaVault() ? BigInteger.ZERO : in.quantityOf(pins.asset()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        TransactionOutput continuation = outputs.get(k);
        BigInteger expectedLovelace = inLovelace.subtract(pins.adaVault() ? quantity : vaultLovelace);
        BigInteger expectedAsset = pins.adaVault() ? BigInteger.ZERO : inAsset.subtract(quantity);
        Map<AssetId, BigInteger> continuationAssets = assets(continuation);
        boolean continuationValid = Arrays.equals(addressBytes(continuation), pins.vaultAddress())
                && continuation.getScriptRef() == null
                && continuation.getValue().getCoin().equals(expectedLovelace)
                && expectedLovelace.compareTo(pins.continuationFloor()) >= 0
                && continuationAssets.equals(expectedAsset.signum() == 0 ? Map.of() : Map.of(pins.asset(), expectedAsset))
                && continuation.getInlineDatum() != null
                && continuationIds(continuation.getInlineDatum()).equals(ids);
        if (!continuationValid) throw hazard("output " + k + " is not the exact vault continuation");
        for (int i = 0; i < outputs.size(); i++) {
            if (i == k) continue;
            Optional<ResolvedOutput.PaymentCredential> credential =
                    ResolvedOutput.paymentCredential(addressBytes(outputs.get(i)));
            if (credential.isPresent() && Arrays.equals(credential.get().hash(), pins.vaultScriptHash())) {
                throw hazard("another output pays the vault");
            }
        }
        byte[] bodyHash = HexFormat.of().parseHex(TransactionUtil.getTxHash(tx));
        return new Signable(new Verified(bodyHash, ids, shardIndex, anchor));
    }

    private static boolean leafMatches(ClaimLeafV1 leaf, GatewayPlutus.RedeemerClaim claim, AssetId asset) {
        BigInteger quantity = leaf.value().quantityOf(asset);
        boolean onlyVaultAsset = leaf.value().isEmpty() || leaf.value().isOnly(asset);
        return onlyVaultAsset
                && leaf.claimId().equals(claim.claimId())
                && claim.kind() == leaf.kind().code()
                && quantity.equals(claim.quantity())
                && leaf.vaultLovelace().equals(claim.vaultLovelace())
                && Arrays.equals(leaf.recipient(), claim.recipient())
                && leaf.reservedHeight() == claim.reservedHeight()
                && leaf.reservedAtMs() == claim.reservedAtMs();
    }

    private static ResolvedOutput resolve(TransactionInput input,
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
                Refusal.unresolved("input " + outpoint + " is not in my UTxO view")));
    }

    private static ResolvedOutput.PaymentCredential credential(ResolvedOutput resolved, byte[] ownKeyHash) {
        ResolvedOutput.PaymentCredential credential = resolved.paymentCredential()
                .orElseThrow(() -> hazard("input " + resolved.outpoint() + " has no payment credential"));
        if (!credential.script() && Arrays.equals(credential.hash(), ownKeyHash)) {
            throw hazard("it spends an input locked by my own member key");
        }
        return credential;
    }

    private static int shardTokenIndex(ResolvedOutput shard, VaultPins pins) {
        int found = -1;
        for (Map.Entry<AssetId, BigInteger> entry : shard.assets().entrySet()) {
            AssetId asset = entry.getKey();
            if (!Arrays.equals(asset.policyId(), pins.shardPolicyId())) continue;
            if (found >= 0 || asset.assetName().length != 1 || !entry.getValue().equals(BigInteger.ONE)) return -1;
            found = asset.assetName()[0] & 0xff;
        }
        return found < SettlementBatchV1.SHARDS ? found : -1;
    }

    private static boolean isNormal(byte[] datum) {
        try {
            return GatewayPlutus.isNormalMode(PlutusData.deserialize(datum));
        } catch (Exception malformed) {
            return false;
        }
    }

    private static List<Hash32> continuationIds(PlutusData datum) {
        try {
            return GatewayPlutus.decodeContinuation(datum);
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }

    static byte[] addressBytes(TransactionOutput out) {
        return new Address(out.getAddress()).getBytes();
    }

    static Map<AssetId, BigInteger> assets(TransactionOutput out) {
        Map<AssetId, BigInteger> assets = new TreeMap<>();
        if (out.getValue().getMultiAssets() == null) return assets;
        for (MultiAsset multi : out.getValue().getMultiAssets()) {
            for (var token : multi.getAssets()) {
                if (token.getValue().signum() == 0) continue;
                assets.merge(AssetId.of(HexFormat.of().parseHex(multi.getPolicyId()), token.getNameAsBytes()),
                        token.getValue(), BigInteger::add);
            }
        }
        return assets;
    }

    private static boolean nonEmpty(java.util.Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }
}
