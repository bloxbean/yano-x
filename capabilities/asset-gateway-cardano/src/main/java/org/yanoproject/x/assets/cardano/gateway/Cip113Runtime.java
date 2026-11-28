package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.yanoproject.x.assets.cardano.support.settlement.Cip113L1;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableBodyCheck;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableSettlement;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableVaultTxAssembler;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a CIP-113 vault's owner, relayer and members read of the pinned deployment from this node's own L1 view and
 * committed state (ADR-061 D10, D13): the live protocol-params UTxO and the approved tuple of its dispatcher, the
 * registry, the asset's substandard adapter, and the reference scripts a vault transaction runs.
 *
 * <p>Reward balances: the host gives an effect context no reward-account read, so the runtime withdraws a zero
 * balance from every credential and members leave the amounts to L1 (D13 step 3: "where its node can read reward
 * balances"). A positive balance makes such a transaction fail on L1 and the owner retries; reading live balances
 * needs a host read and is deferred.
 */
final class Cip113Runtime {
    private Cip113Runtime() {
    }

    /** No reward balance read: zero from every credential (see the class documentation). */
    static final ProgrammableVaultTxAssembler.RewardBalances ZERO_BALANCES = hash -> BigInteger.ZERO;

    /**
     * The CIP-113 build context of {@code pins}: empty when the live params UTxO is not in this node's view.
     *
     * @throws ProgrammableVaultTxAssembler.Drift when the live dispatcher is in no approved tuple
     */
    static Optional<ProgrammableSettlement.Cip113> context(VaultPins pins, NodeL1View l1, CommittedClaims committed) {
        VaultPins.Programmable programmable = pins.programmable().orElseThrow();
        ProgrammableVaultTxAssembler.Deployment deployment = programmable.deployment();
        Optional<ResolvedOutput> params = Cip113L1.params(l1.at(programmable.paramsAddress()),
                deployment.paramsPolicy());
        if (params.isEmpty()) return Optional.empty();
        ProgrammableVaultTxAssembler.LiveWiring wiring = ProgrammableVaultTxAssembler.LiveWiring.of(deployment,
                params.get(), committed::approvedForDispatcher);
        ProgrammableVaultTxAssembler.L1 registry = Cip113L1.l1(l1.at(programmable.registryAddress()),
                deployment.registryNodePolicy(), l1::resolve);
        return Optional.of(new ProgrammableSettlement.Cip113(wiring, registry,
                Cip113L1.adapter(programmable.substandard(), pins.asset().policyId()), ZERO_BALANCES));
    }

    /**
     * The reference-script UTxOs of a vault transaction: {@code h}'s, the shard's when it runs, and the deployment's;
     * empty when one is not in this node's view.
     */
    static Optional<List<ResolvedOutput>> referenceScripts(VaultPins pins, NodeL1View l1,
                                                           AssetSettlementSettings.ScriptRefs refs, boolean shard) {
        List<ResolvedOutput.Outpoint> outpoints = new ArrayList<>();
        outpoints.add(refs.vault());
        if (shard) outpoints.add(refs.shard());
        outpoints.addAll(pins.programmable().orElseThrow().referenceScripts());
        List<ResolvedOutput> resolved = new ArrayList<>();
        for (ResolvedOutput.Outpoint outpoint : outpoints) {
            Optional<ResolvedOutput> out = l1.resolve(outpoint);
            if (out.isEmpty()) return Optional.empty();
            if (resolved.stream().noneMatch(r -> r.outpoint().equals(outpoint))) resolved.add(out.get());
        }
        return Optional.of(resolved);
    }

    /**
     * A member's view for {@link ProgrammableBodyCheck}: its own live wiring, registry and adapter, the pinned anchor
     * and exit-mode thread it sees, the anchor's members as the only permitted signers, and the request's change
     * address when it is a key address off PLB (a member does not know the owner's fee wallet; the rule it enforces
     * is that change never lands on PLB). Empty when its view lacks the params UTxO, the anchor or the mode thread.
     */
    static Optional<ProgrammableBodyCheck.View> memberView(VaultPins pins, NodeL1View l1, CommittedClaims committed,
                                                           Transaction tx) {
        Optional<ProgrammableSettlement.Cip113> context = context(pins, l1, committed);
        Optional<NodeL1View.Anchored> anchor = l1.anchor(pins);
        Optional<ResolvedOutput> mode = l1.exitMode(pins);
        if (context.isEmpty() || anchor.isEmpty() || mode.isEmpty()) return Optional.empty();
        List<byte[]> members = new ArrayList<>();
        for (Hash32 member : anchor.get().datum().memberKeys()) members.add(Blake2bUtil.blake2bHash224(member.bytes()));
        byte[] change = new Address(tx.getBody().getOutputs().getLast().getAddress()).getBytes();
        boolean keyPayment = change.length >= 29 && ((change[0] & 0xff) >> 4) % 2 == 0 && (change[0] & 0xff) >> 4 < 8;
        byte[] fee = keyPayment && !pins.programmable().orElseThrow().deployment().programmable(change) ? change
                : new byte[] {0x00};
        return Optional.of(new ProgrammableBodyCheck.View(pins.programmable().get().deployment(),
                context.get().wiring(), context.get().l1(), context.get().adapter(), pins.vaultScriptHash(),
                pins.vaultAddress(), Optional.of(pins.shardAddress()),
                List.of(anchor.get().output(), mode.get()), members, fee, Optional.empty()));
    }
}
