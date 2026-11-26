package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.ShardMirror;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The owner's {@code asset.settlement.batch} executor (ADR-059 D9): it turns one committed batch into one co-signed
 * {@code Settle}, submits it, and reports the attempt. Its results are telemetry for the effect runtime only: the
 * consumer's ledger closes or re-batches on them, and only the payout observer moves a claim to {@code PAID} (I12).
 *
 * <p>One attempt:
 * <ol>
 *   <li>read every claim of the batch from committed state and keep the {@code RESERVED} ones (the payload names ids
 *       only, D7); none left: {@code Confirmed};</li>
 *   <li>wait ({@code Retry}) until the live, pinned anchor covers every claim's reservation (V12) and the committed
 *       domain status is {@code ACTIVE};</li>
 *   <li>rebuild the shard mirror from the {@code PAID} claims plus this node's own unconfirmed inserts and check it
 *       against the live shard root, dropping the optimistic part on a mismatch (D8h §6);</li>
 *   <li>build the {@code Settle} with the node-local fee wallet, self-check it with the members' body check, run the
 *       co-sign round (fail closed on a missing signer), add the witnesses and submit through the node;</li>
 *   <li>on a later attempt, probe the recorded transaction: included is {@code Confirmed}; not included within the
 *       inclusion timeout is a non-retryable {@code Failed}, which re-batches the claims.</li>
 * </ol>
 * The journal is a cache, not a trust root: after a restart, committed status and the on-chain shard root decide what
 * is still payable.
 */
final class AssetSettlementExecutor implements AppEffectExecutor {
    static final String ID = "asset-settlement";
    static final Duration WAIT_FOR_ANCHOR = Duration.ofSeconds(20);

    private final AssetSettlementSettings settings;
    private final NodeL1View l1;
    private final CommittedClaims committed;
    private final AssetCosignService cosign;
    private final Supplier<ProtocolParams> protocolParams;
    private final TransactionEvaluator evaluator;
    private final Function<byte[], String> submit;
    private final LongSupplier clock;
    private final byte[] memberPublicKey;
    private final byte[] feeSeed;
    private final byte[] feePublicKey;
    private final byte[] feeAddress;
    private final Map<String, Journal> journal = new ConcurrentHashMap<>();
    /** This node's submitted, not yet confirmed inserts, by vault and shard (D8h §6). */
    private final Map<String, List<Hash32>> optimistic = new ConcurrentHashMap<>();
    private volatile String lastOutcome = "idle";

    record Journal(Hash32 txId, Hash32 vaultId, int shard, List<Hash32> claimIds, long deadlineMs) {
    }

    AssetSettlementExecutor(AssetSettlementSettings settings, NodeL1View l1, CommittedClaims committed,
                            AssetCosignService cosign, Supplier<ProtocolParams> protocolParams,
                            TransactionEvaluator evaluator, Function<byte[], String> submit, LongSupplier clock,
                            byte[] memberPublicKey) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.l1 = Objects.requireNonNull(l1, "l1");
        this.committed = Objects.requireNonNull(committed, "committed");
        this.cosign = Objects.requireNonNull(cosign, "cosign");
        this.protocolParams = Objects.requireNonNull(protocolParams, "protocol params");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.submit = Objects.requireNonNull(submit, "submit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.memberPublicKey = memberPublicKey.clone();
        this.feeSeed = settings.feeWalletSeed().orElseThrow(() ->
                new IllegalArgumentException("the settlement owner needs fee-wallet.key-file")).clone();
        this.feePublicKey = KeyGenUtil.getPublicKeyFromPrivateKey(feeSeed);
        this.feeAddress = new byte[29];
        feeAddress[0] = (byte) (0x60 | settings.vaults().domain().addressNetworkId());
        System.arraycopy(Blake2bUtil.blake2bHash224(feePublicKey), 0, feeAddress, 1, 28);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(SettlementBatchV1.EFFECT_TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return SettlementBatchV1.EFFECT_TYPE.equals(effectType);
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        return EffectExecutorOperationalSnapshot.unknown();
    }

    String lastOutcome() {
        return lastOutcome;
    }

    byte[] feeAddress() {
        return feeAddress.clone();
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception {
        String key = HexFormat.of().formatHex(effect.idHash());
        SettlementBatchV1 batch;
        try {
            batch = SettlementBatchV1.decode(effect.payload());
        } catch (RuntimeException malformed) {
            return outcome(EffectExecution.failed("malformed settlement batch", false), "malformed");
        }
        Journal entry = journal.get(key);
        if (entry == null && ctx.submittedRef().length == 32) {
            // A restart lost the journal: probe the transaction the runtime recorded.
            entry = new Journal(Hash32.of(ctx.submittedRef()), batch.vaultId(), batch.shardIndex(), batch.claimIds(),
                    clock.getAsLong() + settings.inclusionTimeout().toMillis());
        }
        if (entry != null) return probe(key, entry);

        VaultPins pins = settings.vault(batch.vaultId()).orElse(null);
        if (pins == null) return outcome(EffectExecution.failed("the batch names an unknown vault", false), "vault");
        List<ClaimLeafV1> claims = new ArrayList<>();
        for (Hash32 claimId : batch.claimIds()) {
            Optional<SettlementQueries.ClaimView> view = committed.claim(claimId);
            if (view.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "claim unavailable");
            if (view.get().status().status() == ClaimStatus.RESERVED) claims.add(view.get().leaf());
        }
        if (claims.isEmpty()) return outcome(EffectExecution.confirmed(new byte[0]), "nothing reserved");
        Optional<NodeL1View.Anchored> anchor = l1.anchor(pins);
        if (anchor.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "no live anchor");
        for (ClaimLeafV1 leaf : claims) {
            if (leaf.reservedHeight() > anchor.get().datum().height()) {
                return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "waiting for an anchor (V12)");
            }
        }
        if (committed.domainStatus().map(DomainStatusV1::status).orElse(null) != DomainStatusV1.Status.ACTIVE) {
            return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "domain not ACTIVE");
        }
        Optional<ResolvedOutput> exitMode = l1.exitMode(pins);
        Optional<ResolvedOutput> shardInput = l1.shard(pins, batch.shardIndex());
        AssetSettlementSettings.ScriptRefs refs = settings.scriptRefs().get(pins.vaultId());
        Optional<ResolvedOutput> vaultRef = l1.resolve(refs.vault());
        Optional<ResolvedOutput> shardRef = l1.resolve(refs.shard());
        if (exitMode.isEmpty() || shardInput.isEmpty() || vaultRef.isEmpty() || shardRef.isEmpty()) {
            return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "L1 inputs unavailable");
        }
        Optional<ShardMirror> mirror = mirror(pins, batch.shardIndex(), shardInput.get());
        if (mirror.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "shard mirror mismatch");
        claims.removeIf(leaf -> mirror.get().contains(leaf.claimId())); // paid on L1, confirmation pending
        if (claims.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "awaiting confirmation");

        List<byte[]> signerKeys = signers(anchor.get());
        List<ResolvedOutput> funding = funding();
        if (funding.isEmpty()) return outcome(EffectExecution.retry(WAIT_FOR_ANCHOR), "no fee wallet funds");
        AssetSettlementSettings.SlotClock slots = settings.slots().orElseThrow();
        long slot = slots.slotAt(clock.getAsLong());
        PayoutTxBuilder.Context context = new PayoutTxBuilder.Context(pins, l1.at(pins.vaultAddress()),
                shardInput.get(), anchor.get().output(), exitMode.get(), List.of(vaultRef.get(), shardRef.get()),
                new PayoutTxBuilder.Funding(funding, feeAddress), Math.max(0, slot - 60),
                slot + settings.validityWindowSlots(), protocolParams.get(), settings.maxVaultInputs());
        PayoutTxBuilder.Built built;
        try {
            built = PayoutTxBuilder.settle(context, claims, mirror.get(),
                    signerKeys.stream().map(Blake2bUtil::blake2bHash224).toList(), evaluator);
        } catch (PayoutTxBuilder.BuildException unbuildable) {
            return outcome(EffectExecution.failed("settle build: " + unbuildable.getMessage(), true), "build");
        }
        Set<String> requested = new LinkedHashSet<>();
        signerKeys.forEach(signer -> requested.add(HexFormat.of().formatHex(signer)));
        Optional<Map<String, byte[]>> signatures = cosign.collect(built.cbor(), requested);
        if (signatures.isEmpty()) {
            return outcome(EffectExecution.failed("the owner's own body check refused the batch", false), "refused");
        }
        if (!signatures.get().keySet().containsAll(requested)) {
            return outcome(EffectExecution.failed("co-sign round incomplete", true), "round incomplete");
        }
        byte[] signed = witness(built, signatures.get());
        String txHash = submit.apply(signed);
        if (txHash == null || !txHash.equalsIgnoreCase(built.txId().hex())) {
            return outcome(EffectExecution.failed("submission returned another transaction id", true), "submit");
        }
        optimistic.computeIfAbsent(shardKey(pins.vaultId(), batch.shardIndex()), ignored -> new ArrayList<>())
                .addAll(built.claimIds());
        Journal submitted = new Journal(built.txId(), pins.vaultId(), batch.shardIndex(), built.claimIds(),
                clock.getAsLong() + settings.inclusionTimeout().toMillis());
        journal.put(key, submitted);
        return outcome(EffectExecution.submitted(built.txId().bytes()), "submitted " + built.txId());
    }

    private EffectExecution probe(String key, Journal entry) {
        if (l1.included(entry.txId())) {
            journal.remove(key);
            return outcome(EffectExecution.confirmed(entry.txId().bytes()), "included " + entry.txId());
        }
        if (clock.getAsLong() > entry.deadlineMs()) {
            journal.remove(key);
            List<Hash32> pending = optimistic.get(shardKey(entry.vaultId(), entry.shard()));
            if (pending != null) pending.removeAll(entry.claimIds());
            // Not included: the claims are re-batched by the ledger. Any later inclusion still confirms through the
            // payout observer, and a second payment is impossible (I6).
            return outcome(EffectExecution.failed("settlement transaction not included", false), "timed out");
        }
        return outcome(EffectExecution.submitted(entry.txId().bytes()), "awaiting inclusion");
    }

    /** The mirror: confirmed inserts plus this node's own, falling back to confirmed only (D8h §6). */
    private Optional<ShardMirror> mirror(VaultPins pins, int shard, ResolvedOutput shardInput) {
        Optional<Hash32> liveRoot = shardInput.inlineDatum()
                .flatMap(org.yanoproject.x.assets.contracts.GatewayDatums::shardDatum)
                .map(org.yanoproject.x.assets.contracts.GatewayDatums.ShardDatum::nullifierRoot);
        Optional<List<Hash32>> paid = committed.paid(pins.vaultId(), shard);
        if (liveRoot.isEmpty() || paid.isEmpty()) return Optional.empty();
        List<Hash32> own = optimistic.computeIfAbsent(shardKey(pins.vaultId(), shard), ignored -> new ArrayList<>());
        own.removeAll(paid.get());
        LinkedHashSet<Hash32> withOwn = new LinkedHashSet<>(paid.get());
        withOwn.addAll(own);
        ShardMirror optimisticMirror = ShardMirror.of(shard, withOwn);
        if (optimisticMirror.matches(liveRoot.get())) return Optional.of(optimisticMirror);
        ShardMirror confirmed = ShardMirror.of(shard, paid.get());
        if (confirmed.matches(liveRoot.get())) {
            own.clear();
            return Optional.of(confirmed);
        }
        return Optional.empty();
    }

    /** The required signers: this node first when it is an anchor member, then the others in key order (V9). */
    private List<byte[]> signers(NodeL1View.Anchored anchor) {
        List<byte[]> keys = new ArrayList<>();
        Hash32 self = Hash32.of(memberPublicKey);
        if (anchor.datum().memberKeys().contains(self)) keys.add(memberPublicKey.clone());
        for (Hash32 member : anchor.datum().memberKeys()) {
            if (keys.size() == anchor.datum().threshold()) break;
            if (!member.equals(self)) keys.add(member.bytes());
        }
        return keys;
    }

    /** The fee wallet's lovelace-only UTxOs not spent by a pending submission, largest first, at most three. */
    private List<ResolvedOutput> funding() {
        List<ResolvedOutput> utxos = new ArrayList<>(l1.at(feeAddress).stream()
                .filter(out -> out.assets().isEmpty() && out.referenceScript().isEmpty()).toList());
        utxos.sort((a, b) -> b.lovelace().compareTo(a.lovelace()));
        return utxos.subList(0, Math.min(3, utxos.size()));
    }

    private byte[] witness(PayoutTxBuilder.Built built, Map<String, byte[]> signatures) throws Exception {
        Transaction tx = Transaction.deserialize(built.cbor());
        List<VkeyWitness> witnesses = new ArrayList<>();
        signatures.forEach((publicKey, signature) ->
                witnesses.add(new VkeyWitness(HexFormat.of().parseHex(publicKey), signature)));
        byte[] feeSignature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(built.txId().bytes(), feeSeed);
        witnesses.add(new VkeyWitness(feePublicKey, feeSignature));
        tx.getWitnessSet().setVkeyWitnesses(witnesses);
        return tx.serialize();
    }

    private static String shardKey(Hash32 vaultId, int shard) {
        return vaultId.hex() + "/" + shard;
    }

    private EffectExecution outcome(EffectExecution execution, String note) {
        lastOutcome = note;
        return execution;
    }
}
