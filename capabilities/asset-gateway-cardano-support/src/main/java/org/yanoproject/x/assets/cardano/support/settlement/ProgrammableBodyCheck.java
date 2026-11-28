package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.CostMdls;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.CardanoAddresses;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The members' body check of a co-signed CIP-113 vault transaction ({@code Settle} or {@code AcceptCosigned};
 * ADR-061 D13, I41). Every rule is derived from the member's own L1 view, pins and committed state, never from the
 * request, and it fails closed.
 *
 * <p>{@link #check} is the CIP-113 structural delta:
 * <ol>
 *   <li>every input and reference input resolves in the member's view;</li>
 *   <li>the vault inputs are exactly the inputs at the vault smart wallet; no other PLB input; the only other script
 *       input is the shard ({@code Settle});</li>
 *   <li>no mint, certificate, vote, proposal or treasury donation; the withdrawals are exactly the live dispatcher
 *       (an approved tuple's: drift refuses, step 8), its transfer delegate, the merged hooks of the registered
 *       policies present, and {@code h}; no key-credential withdrawal; each amount equals the live reward balance
 *       where the member can read it;</li>
 *   <li>(3a) the redeemers are exactly one {@code BaseSpendRedeemer} per vault input with the sorted indexes, the
 *       shard's, {@code TransferAct}, the {@code TransferRedeemer} the member's own {@link Cip113Requirements#of}
 *       derives, the hook redeemers byte-equal to the member's own adapters', and {@code h}'s action, which must be
 *       {@code Settle} or {@code AcceptCosigned}; an index outside its list is refused, never thrown;</li>
 *   <li>(3b) the reference inputs are exactly the anchor, the exit-mode thread, the live params UTxO, the registry
 *       nodes and adapter state of the requirements, and reference-script UTxOs, each holding a script the
 *       transaction runs (PLB, the dispatcher, the transfer delegate, a hook, {@code h}, the shard);</li>
 *   <li>(7) the PLB outputs are exactly the action's positional payouts and the continuation: output {@code i < k}
 *       (the {@code Settle}'s {@code k} claims) may be a key-owned smart wallet of the deployment, output {@code k}
 *       is at the vault smart wallet, and no other output has the PLB payment credential; the change goes to the fee
 *       wallet's non-PLB address; the only required signers are members.</li>
 * </ol>
 *
 * <p>{@link #checkSettle} and {@link #checkAccept} are the whole check a member runs: 059-D8h's request form, inputs,
 * collateral, pinned anchor and {@code NORMAL} mode, this structural delta, the action itself (059-D8h §1 step 5 for
 * {@code Settle}; step 4 with origin identity for {@code Accept}, D13), the asset's hold (a held asset's
 * {@code Settle} is refused, D14) and the signer quorum.
 *
 * Registry-node drift (a hook that differs from the registration baseline) does not refuse: the member signs and
 * raises the flag, because a hook only adds constraints.
 */
public final class ProgrammableBodyCheck {
    private ProgrammableBodyCheck() {
    }

    /** What the member knows, all from its own view and pins. */
    public record View(ProgrammableVaultTxAssembler.Deployment deployment,
                       ProgrammableVaultTxAssembler.LiveWiring wiring, ProgrammableVaultTxAssembler.L1 l1,
                       Cip113Requirements.Adapter adapter, byte[] custodyHash, byte[] vaultWallet,
                       Optional<byte[]> shardAddress, List<ResolvedOutput> anchorAndMode, List<byte[]> memberKeys,
                       byte[] feeWalletAddress, Optional<ProgrammableVaultTxAssembler.RewardBalances> balances) {
    }

    /** The member's committed state for a CIP-113 vault: whether its asset is in {@code ISSUER_HOLD} (D14). */
    @FunctionalInterface
    public interface Holds {
        /** Present when known; empty when the member cannot read it, which refuses a {@code Settle}. */
        Optional<Boolean> held(Hash32 vaultId);
    }

    /** The refusals; empty when the CIP-113 parts of the body are exactly what the member derives. */
    public static List<String> check(Transaction tx, View view) {
        List<String> refusals = new ArrayList<>();
        TransactionBody body = tx.getBody();
        HexFormat hex = HexFormat.of();

        // Step 1: resolve every input and reference input.
        List<ResolvedOutput.Outpoint> inputs = new ArrayList<>();
        Map<ResolvedOutput.Outpoint, ResolvedOutput> resolved = new TreeMap<>();
        for (TransactionInput input : body.getInputs()) {
            ResolvedOutput.Outpoint at = new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()),
                    input.getIndex());
            inputs.add(at);
            Optional<ResolvedOutput> out = view.l1().resolve(new ProgrammableDepositDatumV1.Outpoint(at.txId(),
                    at.index()));
            if (out.isEmpty()) refusals.add("input " + at + " is unknown to this member");
            else resolved.put(at, out.get());
        }
        List<ResolvedOutput.Outpoint> references = new ArrayList<>();
        Map<ResolvedOutput.Outpoint, ResolvedOutput> referenced = new TreeMap<>();
        for (TransactionInput input : body.getReferenceInputs() == null ? List.<TransactionInput>of()
                : body.getReferenceInputs()) {
            ResolvedOutput.Outpoint at = new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()),
                    input.getIndex());
            references.add(at);
            Optional<ResolvedOutput> out = view.l1().resolve(new ProgrammableDepositDatumV1.Outpoint(at.txId(),
                    at.index()));
            if (out.isEmpty()) refusals.add("reference input " + at + " is unknown to this member");
            else referenced.put(at, out.get());
        }
        if (!refusals.isEmpty()) return refusals;
        inputs.sort(null);
        references.sort(null);

        // Step 2: the vault inputs, no other PLB input, script inputs only the vault's and the shard.
        List<ResolvedOutput> vaultInputs = new ArrayList<>();
        boolean shardSpent = false;
        for (ResolvedOutput out : resolved.values()) {
            boolean script = out.paymentCredential().map(ResolvedOutput.PaymentCredential::script).orElse(false);
            if (Arrays.equals(out.address(), view.vaultWallet())) {
                vaultInputs.add(out);
            } else if (view.deployment().programmable(out.address())) {
                refusals.add("PLB input " + out.outpoint() + " is not the vault's");
            } else if (script && view.shardAddress().isPresent()
                    && Arrays.equals(out.address(), view.shardAddress().get())) {
                shardSpent = true;
            } else if (script) {
                refusals.add("script input " + out.outpoint() + " is neither the vault's nor the shard's");
            }
        }
        if (vaultInputs.isEmpty()) refusals.add("no vault input");

        // Step 3: no mint, certificates, votes, proposals or donation.
        if (body.getMint() != null && !body.getMint().isEmpty()) refusals.add("the transaction mints");
        if (body.getCerts() != null && !body.getCerts().isEmpty()) refusals.add("the transaction has certificates");
        if (body.getVotingProcedures() != null) refusals.add("the transaction votes");
        if (body.getProposalProcedures() != null && !body.getProposalProcedures().isEmpty()) {
            refusals.add("the transaction proposes");
        }
        if (body.getDonation() != null && body.getDonation().signum() != 0) refusals.add("the transaction donates");

        // The member's own requirements, from the resolved vault inputs.
        List<Map<byte[], Map<byte[], BigInteger>>> values = new ArrayList<>();
        for (ResolvedOutput in : vaultInputs) values.add(ProgrammableVaultTxAssembler.policyMap(in.assets()));
        Cip113Requirements requirements;
        try {
            requirements = Cip113Requirements.of(values, view.wiring().dispatcher(), view.wiring().transferDelegate(),
                    view.l1(), view.adapter());
        } catch (Cip113Requirements.Unassemblable refused) {
            refusals.add(refused.getMessage());
            return refusals;
        }

        // The withdrawals: exactly the derived set, script credentials only, at the live balances.
        String custodyKey = ProgrammableVaultTxAssembler.scriptKey(view.custodyHash());
        TreeMap<String, PlutusData> expected = new TreeMap<>(ProgrammableVaultTxAssembler.LEDGER_ORDER);
        List<PlutusData> proofs = new ArrayList<>();
        for (Cip113Requirements.Proof proof : requirements.proofs()) {
            int index = references.indexOf(ProgrammableVaultTxAssembler.outpoint(proof.node().outpoint()));
            proofs.add(ProgrammableVaultTxAssembler.constr(proof.registered() ? 0 : 1, BigIntPlutusData.of(index)));
        }
        expected.put(ProgrammableVaultTxAssembler.scriptKey(view.wiring().dispatcher()),
                ProgrammableVaultTxAssembler.constr(0));
        expected.put(ProgrammableVaultTxAssembler.scriptKey(view.wiring().transferDelegate()),
                ProgrammableVaultTxAssembler.constr(0, GatewayPlutus.list(proofs)));
        Set<String> runs = new HashSet<>();
        runs.add(hex.formatHex(view.deployment().plbHash()));
        runs.add(hex.formatHex(view.wiring().dispatcher()));
        runs.add(hex.formatHex(view.wiring().transferDelegate()));
        runs.add(hex.formatHex(view.custodyHash()));
        if (shardSpent) runs.add(hex.formatHex(Arrays.copyOfRange(view.shardAddress().get(), 1, 29)));
        for (Cip113Requirements.Hook hook : requirements.hooks()) {
            if (!hook.credential().script()) {
                refusals.add("a hook is a key credential");
                continue;
            }
            runs.add(hex.formatHex(hook.credential().hash()));
            try {
                expected.put(ProgrammableVaultTxAssembler.scriptKey(hook.credential().hash()),
                        PlutusData.deserialize(hook.redeemer()));
            } catch (Exception undecodable) {
                refusals.add("a hook redeemer does not decode");
            }
        }
        expected.put(custodyKey, null);
        TreeMap<String, BigInteger> withdrawals = new TreeMap<>(ProgrammableVaultTxAssembler.LEDGER_ORDER);
        for (Withdrawal w : body.getWithdrawals() == null ? List.<Withdrawal>of() : body.getWithdrawals()) {
            byte[] account = new Address(w.getRewardAddress()).getBytes();
            boolean script = ((account[0] & 0xff) >> 4) == 0xf;
            String key = (script ? "0" : "1") + hex.formatHex(Arrays.copyOfRange(account, 1, 29));
            if (!script) refusals.add("a key-credential withdrawal");
            withdrawals.put(key, w.getCoin());
        }
        if (!withdrawals.keySet().equals(expected.keySet())) {
            refusals.add("the withdrawals " + withdrawals.keySet() + " are not exactly " + expected.keySet());
            return refusals;
        }
        view.balances().ifPresent(balances -> withdrawals.forEach((key, amount) -> {
            BigInteger live = balances.balance(hex.parseHex(key.substring(1)));
            if (live != null && !live.equals(amount)) {
                refusals.add("withdrawal " + key + " takes " + amount + ", not the live balance " + live);
            }
        }));
        List<String> order = new ArrayList<>(expected.keySet());
        int dispatcherIndex = order.indexOf(ProgrammableVaultTxAssembler.scriptKey(view.wiring().dispatcher()));
        int paramsIndex = references.indexOf(view.wiring().params().outpoint());

        // Step 3a: the redeemers, every index within its list.
        Map<String, PlutusData> spend = new LinkedHashMap<>();
        Map<Integer, PlutusData> reward = new LinkedHashMap<>();
        for (Redeemer redeemer : tx.getWitnessSet() == null || tx.getWitnessSet().getRedeemers() == null
                ? List.<Redeemer>of() : tx.getWitnessSet().getRedeemers()) {
            BigInteger raw = redeemer.getIndex();
            int index = raw == null || raw.signum() < 0 || raw.bitLength() > 31 ? -1 : raw.intValue();
            if (redeemer.getTag() == RedeemerTag.Spend) {
                if (index < 0 || index >= inputs.size()) {
                    refusals.add("a spend redeemer points at no input");
                } else if (spend.put(inputs.get(index).toString(), redeemer.getData()) != null) {
                    refusals.add("two spend redeemers point at one input");
                }
            } else if (redeemer.getTag() == RedeemerTag.Reward) {
                if (index < 0 || index >= order.size()) {
                    refusals.add("a reward redeemer points at no withdrawal");
                } else if (reward.put(index, redeemer.getData()) != null) {
                    refusals.add("two reward redeemers point at one withdrawal");
                }
            } else {
                refusals.add("an unexpected redeemer " + redeemer.getTag());
            }
        }
        PlutusData base = ProgrammableVaultTxAssembler.constr(0, BigIntPlutusData.of(paramsIndex),
                BigIntPlutusData.of(dispatcherIndex));
        for (ResolvedOutput vault : vaultInputs) {
            if (!same(base, spend.get(vault.outpoint().toString()))) {
                refusals.add("vault input " + vault.outpoint() + " does not carry BaseSpendRedeemer" + "{" + paramsIndex
                        + ", " + dispatcherIndex + "}");
            }
        }
        if (spend.size() != vaultInputs.size() + (shardSpent ? 1 : 0)) {
            refusals.add("spend redeemers are not exactly the vault inputs' and the shard's");
        }
        if (reward.size() != order.size()) refusals.add("reward redeemers are not one per withdrawal");
        for (int i = 0; i < order.size(); i++) {
            PlutusData want = expected.get(order.get(i));
            if (want != null && !same(want, reward.get(i))) {
                refusals.add("the redeemer of withdrawal " + order.get(i) + " is not the derived one");
            }
        }
        // h's action: Settle (with its claim count) or AcceptCosigned, the two co-signed actions.
        int payouts = -1;
        PlutusData action = reward.get(order.indexOf(custodyKey));
        if (GatewayPlutus.isAcceptCosigned(action)) {
            payouts = 0;
        } else if (action != null) {
            try {
                payouts = GatewayPlutus.decodeSettle(action).claims().size();
            } catch (RuntimeException notSettle) {
                refusals.add("h's redeemer is neither Settle nor AcceptCosigned");
            }
        } else {
            refusals.add("h has no redeemer");
        }

        // Step 3b: the reference inputs.
        Set<ResolvedOutput.Outpoint> allowed = new HashSet<>();
        view.anchorAndMode().forEach(out -> allowed.add(out.outpoint()));
        allowed.add(view.wiring().params().outpoint());
        requirements.references().forEach(ref -> allowed.add(ProgrammableVaultTxAssembler.outpoint(ref)));
        Set<ResolvedOutput.Outpoint> required = new TreeSet<>(allowed);
        for (ResolvedOutput.Outpoint ref : references) {
            ResolvedOutput out = referenced.get(ref);
            boolean runsItsScript = out.referenceScript()
                    .map(script -> runs.contains(hex.formatHex(script.scriptHash()))).orElse(false);
            if (!allowed.contains(ref) && !runsItsScript) {
                refusals.add("reference input " + ref + " is not one the transaction needs");
            }
            required.remove(ref);
        }
        if (!required.isEmpty()) refusals.add("missing reference inputs " + required);

        // Step 7: the PLB outputs are the action's positional payouts and the continuation; change and signers.
        List<TransactionOutput> outputs = body.getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            byte[] address = new Address(outputs.get(i).getAddress()).getBytes();
            if (!view.deployment().programmable(address)) continue;
            boolean continuation = i == payouts && Arrays.equals(address, view.vaultWallet());
            boolean payout = i < payouts && payout(address, view);
            if (!continuation && !payout) {
                refusals.add("PLB output " + i + " is neither a payout nor the continuation");
            }
        }
        if (payouts >= 0 && (payouts >= outputs.size()
                || !Arrays.equals(new Address(outputs.get(payouts).getAddress()).getBytes(), view.vaultWallet()))) {
            refusals.add("output " + payouts + " is not the continuation at the vault smart wallet");
        }
        TransactionOutput change = outputs.getLast();
        if (!Arrays.equals(new Address(change.getAddress()).getBytes(), view.feeWalletAddress())
                || view.deployment().programmable(view.feeWalletAddress())) {
            refusals.add("the change does not go to the fee wallet's non-PLB address");
        }
        Set<String> members = new HashSet<>();
        view.memberKeys().forEach(key -> members.add(hex.formatHex(key)));
        for (byte[] signer : body.getRequiredSigners() == null ? List.<byte[]>of() : body.getRequiredSigners()) {
            if (!members.contains(hex.formatHex(signer))) refusals.add("a required signer is not a member");
        }
        return refusals;
    }

    /**
     * The whole member check of a co-signed CIP-113 {@code Settle}: 059-D8h §1 with the D13 changes. A held asset's
     * {@code Settle} is refused (D14, Q8).
     */
    public static SettleBodyCheck.Outcome checkSettle(byte[] fullUnsignedTx, VaultPins pins, View view,
                                                      byte[] ownKeyHash,
                                                      Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View,
                                                      SettleBodyCheck.Committed committed, Holds holds,
                                                      CostMdls costModels, SettleBodyCheck.Tip tip) {
        return SettleBodyCheck.guarded(() -> {
            Transaction tx = BodyChecks.requestForm(fullUnsignedTx, costModels, tip);
            Inputs in = inputs(tx, pins, ownKeyHash, l1View);
            if (in.shard() == null) throw BodyChecks.hazard("no shard input");
            for (ResolvedOutput vault : in.vault()) {
                if (!PayoutTxBuilder.accepted(vault, pins)) throw BodyChecks.hazard("a vault input is not accepted custody");
            }
            AnchorObservationV1.Anchored anchor = BodyChecks.pinnedAnchorInNormalMode(tx.getBody(), pins, l1View);
            structural(tx, view);
            if (!holds.held(pins.vaultId()).map(held -> !held).orElse(false)) {
                throw BodyChecks.hazard("the asset is in ISSUER_HOLD, or its hold cannot be read: no Settle (D14)");
            }
            PlutusData action = custodyRedeemer(tx, pins.vaultScriptHash());
            GatewayPlutus.DecodedSettle settle;
            try {
                settle = GatewayPlutus.decodeSettle(action);
            } catch (RuntimeException notSettle) {
                throw BodyChecks.hazard("h's redeemer is not Settle");
            }
            PlutusData shardRedeemer = spendRedeemer(tx, in.order(), in.shard().outpoint());
            List<Hash32> ids = SettleBodyCheck.settleAction(pins, in.vault(), in.shard(), in.shardIndex(), settle,
                    shardRedeemer, tx.getBody().getOutputs(), committed, anchor);
            BodyChecks.quorumOfRequiredSigners(tx.getBody(), anchor);
            return new SettleBodyCheck.Signable(new SettleBodyCheck.Verified(bodyHash(tx), ids, in.shardIndex(),
                    anchor));
        });
    }

    /**
     * The whole member check of a co-signed CIP-113 {@code Accept} (059-D8h §1 step 4 with origin identity, D13): every
     * vault input is a deposit of this vault holding only its asset and lovelace, its {@code DepositId} is computed
     * from its datum's origin, and its own {@code deposit/<DepositId>} record is {@code CREDITED} for this vault at or
     * below the referenced anchor's height; the live outpoint is never compared with the one the record keeps as
     * history. Output 0 is the exact empty continuation of their summed value.
     */
    public static SettleBodyCheck.Outcome checkAccept(byte[] fullUnsignedTx, VaultPins pins, View view,
                                                      byte[] ownKeyHash,
                                                      Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View,
                                                      AcceptBodyCheck.Deposits committed, CostMdls costModels,
                                                      SettleBodyCheck.Tip tip) {
        return SettleBodyCheck.guarded(() -> {
            Transaction tx = BodyChecks.requestForm(fullUnsignedTx, costModels, tip);
            Inputs in = inputs(tx, pins, ownKeyHash, l1View);
            if (in.shard() != null) throw BodyChecks.hazard("an Accept spends no shard");
            AnchorObservationV1.Anchored anchor = BodyChecks.pinnedAnchorInNormalMode(tx.getBody(), pins, l1View);
            structural(tx, view);
            if (!GatewayPlutus.isAcceptCosigned(custodyRedeemer(tx, pins.vaultScriptHash()))) {
                throw BodyChecks.hazard("h's redeemer is not AcceptCosigned");
            }
            BigInteger lovelace = BigInteger.ZERO;
            BigInteger asset = BigInteger.ZERO;
            Set<Hash32> ids = new HashSet<>();
            for (ResolvedOutput deposit : in.vault()) {
                Hash32 depositId = ProgrammableSettlement.depositId(pins, deposit).orElseThrow(() -> BodyChecks.hazard(
                        "vault input " + deposit.outpoint() + " is not a deposit holding only the vault's asset"));
                if (!ids.add(depositId)) throw BodyChecks.hazard("two inputs carry deposit " + depositId);
                DepositRecordV1 record;
                try {
                    record = committed.deposit(depositId).orElseThrow(() -> BodyChecks.hazard("unknown deposit "
                            + depositId));
                } catch (BodyChecks.RefusedException refused) {
                    throw refused;
                } catch (RuntimeException unavailable) {
                    throw BodyChecks.hazard("unknown deposit " + depositId);
                }
                if (record.status() != DepositRecordV1.Status.CREDITED || !record.depositId().equals(depositId)
                        || !record.vaultId().equals(pins.vaultId())) {
                    throw BodyChecks.hazard("deposit " + depositId + " is not CREDITED for this vault");
                }
                if (record.height() > anchor.height()) {
                    throw BodyChecks.hazard("deposit " + depositId + " is credited above the referenced anchor");
                }
                lovelace = lovelace.add(deposit.lovelace());
                asset = asset.add(deposit.quantityOf(pins.asset()));
            }
            TransactionOutput continuation = tx.getBody().getOutputs().getFirst();
            Map<AssetId, BigInteger> expectedAssets = asset.signum() == 0 ? Map.of() : Map.of(pins.asset(), asset);
            boolean exact = Arrays.equals(SettleBodyCheck.addressBytes(continuation), pins.vaultAddress())
                    && continuation.getScriptRef() == null
                    && continuation.getValue().getCoin().equals(lovelace)
                    && SettleBodyCheck.assets(continuation).equals(expectedAssets)
                    && continuation.getInlineDatum() != null && emptyContinuation(continuation.getInlineDatum());
            if (!exact) throw BodyChecks.hazard("output 0 is not the exact empty continuation of the deposits");
            BodyChecks.quorumOfRequiredSigners(tx.getBody(), anchor);
            return new SettleBodyCheck.Signable(new SettleBodyCheck.Verified(bodyHash(tx), List.of(), -1, anchor));
        });
    }

    /** True when the request's custody redeemer names {@code AcceptCosigned}; anything else is checked as Settle. */
    public static boolean isAcceptRequest(byte[] fullUnsignedTx, byte[] custodyHash) {
        try {
            return GatewayPlutus.isAcceptCosigned(custodyRedeemer(Transaction.deserialize(fullUnsignedTx),
                    custodyHash));
        } catch (Exception notAccept) {
            return false;
        }
    }

    /** The inputs, resolved: the vault inputs, the one shard input and its index, and the ledger order. */
    private record Inputs(List<ResolvedOutput> vault, ResolvedOutput shard, int shardIndex,
                          List<ResolvedOutput.Outpoint> order) {
    }

    private static Inputs inputs(Transaction tx, VaultPins pins, byte[] ownKeyHash,
                                 Function<ResolvedOutput.Outpoint, Optional<ResolvedOutput>> l1View) {
        TreeMap<ResolvedOutput.Outpoint, ResolvedOutput> spent = new TreeMap<>();
        List<ResolvedOutput> vault = new ArrayList<>();
        ResolvedOutput shard = null;
        int shardIndex = -1;
        for (TransactionInput input : tx.getBody().getInputs()) {
            ResolvedOutput resolved = BodyChecks.resolve(input, l1View);
            if (spent.put(resolved.outpoint(), resolved) != null) throw BodyChecks.hazard("duplicate input");
            ResolvedOutput.PaymentCredential credential = BodyChecks.credential(resolved, ownKeyHash);
            if (resolved.sameAddress(pins.vaultAddress())) {
                vault.add(resolved);
            } else if (credential.script() && Arrays.equals(credential.hash(), pins.shardScriptHash())
                    && shard == null) {
                shardIndex = SettleBodyCheck.shardTokenIndex(resolved, pins);
                if (shardIndex < 0 || !resolved.sameAddress(pins.shardAddress())
                        || !PayoutTxBuilder.shardOnly(resolved, pins, shardIndex)) {
                    throw BodyChecks.hazard("the shard input does not hold exactly one of this vault's thread tokens");
                }
                shard = resolved;
            } else if (credential.script()) {
                throw BodyChecks.hazard("it spends a script input other than this vault and its one shard");
            }
        }
        if (vault.isEmpty()) throw BodyChecks.hazard("no input is at the vault smart wallet");
        BodyChecks.keyCollateral(tx.getBody(), l1View, ownKeyHash);
        return new Inputs(vault, shard, shardIndex, new ArrayList<>(spent.keySet()));
    }

    private static void structural(Transaction tx, View view) {
        List<String> refusals = check(tx, view);
        if (!refusals.isEmpty()) throw BodyChecks.hazard(String.join("; ", refusals));
    }

    /** {@code h}'s reward redeemer, located by the position of {@code Script(h)} in ledger order (D4). */
    static PlutusData custodyRedeemer(Transaction tx, byte[] custodyHash) {
        List<String> keys = new ArrayList<>();
        for (Withdrawal w : tx.getBody().getWithdrawals() == null ? List.<Withdrawal>of()
                : tx.getBody().getWithdrawals()) {
            byte[] account = new Address(w.getRewardAddress()).getBytes();
            boolean script = ((account[0] & 0xff) >> 4) == 0xf;
            keys.add((script ? "0" : "1") + HexFormat.of().formatHex(Arrays.copyOfRange(account, 1, 29)));
        }
        keys.sort(ProgrammableVaultTxAssembler.LEDGER_ORDER);
        int index = keys.indexOf(ProgrammableVaultTxAssembler.scriptKey(custodyHash));
        if (index < 0) throw BodyChecks.hazard("h is not withdrawn");
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward && BigInteger.valueOf(index).equals(redeemer.getIndex())) {
                return redeemer.getData();
            }
        }
        throw BodyChecks.hazard("h has no redeemer");
    }

    private static PlutusData spendRedeemer(Transaction tx, List<ResolvedOutput.Outpoint> order,
                                            ResolvedOutput.Outpoint outpoint) {
        BigInteger index = BigInteger.valueOf(order.indexOf(outpoint));
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Spend && index.equals(redeemer.getIndex())) return redeemer.getData();
        }
        throw BodyChecks.hazard("the shard input has no redeemer");
    }

    private static boolean emptyContinuation(PlutusData datum) {
        try {
            return GatewayPlutus.decodeContinuation(datum).isEmpty();
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static byte[] bodyHash(Transaction tx) {
        try {
            return HexFormat.of().parseHex(TransactionUtil.getTxHash(tx));
        } catch (Exception failure) {
            throw BodyChecks.hazard("the transaction id cannot be computed");
        }
    }

    /**
     * Plutus Data equality by canonical bytes: a redeemer decoded from the request and one the member builds may differ
     * only in how the object model holds the same value.
     */
    private static boolean same(PlutusData expected, PlutusData actual) {
        if (actual == null) return false;
        try {
            return Arrays.equals(expected.serializeToBytes(), actual.serializeToBytes());
        } catch (Exception unserializable) {
            return false;
        }
    }

    /** A key-owned smart wallet of the pinned deployment: a payable recipient (D7). */
    private static boolean payout(byte[] address, View view) {
        return CardanoAddresses.payableSmartWallet(address, view.deployment().plbHash(), view.deployment().networkId());
    }
}
