package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import org.yanoproject.x.assets.contracts.Cip113Requirements;
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

/**
 * The CIP-113 delta of the members' body check (ADR-061 D13, I41) for a co-signed vault transaction ({@code Settle}
 * or {@code AcceptCosigned}). It runs next to 059-D8h's checks of the action itself and adds the steps a CIP-113
 * vault needs. Every rule is derived from the member's own L1 view, never from the request:
 *
 * <ol>
 *   <li>every input and reference input resolves in the member's view;</li>
 *   <li>the vault inputs are exactly the inputs at the vault smart wallet; no other PLB input; the only other script
 *       input is the shard ({@code Settle});</li>
 *   <li>no mint, certificate, vote, proposal or treasury donation; the withdrawals are exactly the live dispatcher
 *       (which must be an approved tuple's: drift refuses, step 8), its transfer delegate, the merged hooks of the
 *       registered policies present, and {@code h}; no key-credential withdrawal; each amount equals the live reward
 *       balance where the member can read it;</li>
 *   <li>(3a) the redeemers are exactly one {@code BaseSpendRedeemer} per vault input with the sorted indexes, the
 *       shard's, {@code TransferAct}, the {@code TransferRedeemer} the member's own {@link Cip113Requirements#of}
 *       derives, the hook redeemers byte-equal to the member's own adapters', and the custody action;</li>
 *   <li>(3b) the reference inputs are exactly the anchor, the exit-mode thread, the live params UTxO, the registry
 *       nodes and adapter state of the requirements, and reference-script UTxOs;</li>
 *   <li>(7) the only PLB outputs are the payouts and the continuation; the change goes to the fee wallet's non-PLB
 *       address; the only required signers are members.</li>
 * </ol>
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
        for (TransactionInput input : body.getReferenceInputs() == null ? List.<TransactionInput>of()
                : body.getReferenceInputs()) {
            ResolvedOutput.Outpoint at = new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()),
                    input.getIndex());
            references.add(at);
            if (view.l1().resolve(new ProgrammableDepositDatumV1.Outpoint(at.txId(), at.index())).isEmpty()) {
                refusals.add("reference input " + at + " is unknown to this member");
            }
        }
        if (!refusals.isEmpty()) return refusals;
        inputs.sort(null);
        references.sort(null);

        // Step 2: the vault inputs, no other PLB input, script inputs only the vault's and the shard.
        List<ResolvedOutput> vaultInputs = new ArrayList<>();
        for (ResolvedOutput out : resolved.values()) {
            boolean script = out.paymentCredential().map(ResolvedOutput.PaymentCredential::script).orElse(false);
            if (Arrays.equals(out.address(), view.vaultWallet())) {
                vaultInputs.add(out);
            } else if (view.deployment().programmable(out.address())) {
                refusals.add("PLB input " + out.outpoint() + " is not the vault's");
            } else if (script && !(view.shardAddress().isPresent()
                    && Arrays.equals(out.address(), view.shardAddress().get()))) {
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
        TreeMap<String, PlutusData> expected = new TreeMap<>(ProgrammableVaultTxAssembler.LEDGER_ORDER);
        List<PlutusData> proofs = new ArrayList<>();
        for (Cip113Requirements.Proof proof : requirements.proofs()) {
            int index = references.indexOf(ProgrammableVaultTxAssembler.outpoint(proof.node().outpoint()));
            proofs.add(ProgrammableVaultTxAssembler.constr(proof.registered() ? 0 : 1,
                    com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(index)));
        }
        expected.put(ProgrammableVaultTxAssembler.scriptKey(view.wiring().dispatcher()),
                ProgrammableVaultTxAssembler.constr(0));
        expected.put(ProgrammableVaultTxAssembler.scriptKey(view.wiring().transferDelegate()),
                ProgrammableVaultTxAssembler.constr(0, GatewayPlutus.list(proofs)));
        for (Cip113Requirements.Hook hook : requirements.hooks()) {
            if (!hook.credential().script()) {
                refusals.add("a hook is a key credential");
                continue;
            }
            try {
                expected.put(ProgrammableVaultTxAssembler.scriptKey(hook.credential().hash()),
                        PlutusData.deserialize(hook.redeemer()));
            } catch (Exception undecodable) {
                refusals.add("a hook redeemer does not decode");
            }
        }
        expected.put(ProgrammableVaultTxAssembler.scriptKey(view.custodyHash()), null);
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

        // Step 3a: the redeemers.
        Map<String, PlutusData> spend = new LinkedHashMap<>();
        Map<Integer, PlutusData> reward = new LinkedHashMap<>();
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            int index = redeemer.getIndex().intValue();
            if (redeemer.getTag() == RedeemerTag.Spend) spend.put(inputs.get(index).toString(), redeemer.getData());
            else if (redeemer.getTag() == RedeemerTag.Reward) reward.put(index, redeemer.getData());
            else refusals.add("an unexpected redeemer " + redeemer.getTag());
        }
        PlutusData base = ProgrammableVaultTxAssembler.constr(0,
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(paramsIndex),
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(dispatcherIndex));
        for (ResolvedOutput vault : vaultInputs) {
            if (!base.equals(spend.get(vault.outpoint().toString()))) {
                refusals.add("vault input " + vault.outpoint() + " does not carry BaseSpendRedeemer" + "{" + paramsIndex
                        + ", " + dispatcherIndex + "}");
            }
        }
        if (spend.size() != vaultInputs.size() + (view.shardAddress().isPresent() ? 1 : 0)) {
            refusals.add("spend redeemers are not exactly the vault inputs' and the shard's");
        }
        if (reward.size() != order.size()) refusals.add("reward redeemers are not one per withdrawal");
        for (int i = 0; i < order.size(); i++) {
            PlutusData want = expected.get(order.get(i));
            if (want != null && !want.equals(reward.get(i))) {
                refusals.add("the redeemer of withdrawal " + order.get(i) + " is not the derived one");
            }
        }

        // Step 3b: the reference inputs.
        Set<ResolvedOutput.Outpoint> allowed = new HashSet<>();
        view.anchorAndMode().forEach(out -> allowed.add(out.outpoint()));
        allowed.add(view.wiring().params().outpoint());
        requirements.references().forEach(ref -> allowed.add(ProgrammableVaultTxAssembler.outpoint(ref)));
        Set<ResolvedOutput.Outpoint> required = new TreeSet<>(allowed);
        for (ResolvedOutput.Outpoint ref : references) {
            ResolvedOutput out = view.l1().resolve(new ProgrammableDepositDatumV1.Outpoint(ref.txId(), ref.index()))
                    .orElseThrow();
            if (!allowed.contains(ref) && out.referenceScript().isEmpty()) {
                refusals.add("reference input " + ref + " is not one the transaction needs");
            }
            required.remove(ref);
        }
        if (!required.isEmpty()) refusals.add("missing reference inputs " + required);

        // Step 7: PLB outputs, change and signers.
        for (TransactionOutput out : body.getOutputs()) {
            byte[] address = new Address(out.getAddress()).getBytes();
            if (view.deployment().programmable(address) && !Arrays.equals(address, view.vaultWallet())
                    && !payout(address, view)) {
                refusals.add("a PLB output that is neither a payout nor the continuation");
            }
        }
        TransactionOutput change = body.getOutputs().getLast();
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

    /** A key-owned smart wallet of the pinned deployment: a payable recipient (D7). */
    private static boolean payout(byte[] address, View view) {
        return org.yanoproject.x.assets.contracts.CardanoAddresses.payableSmartWallet(address,
                view.deployment().plbHash(), view.deployment().networkId());
    }
}
