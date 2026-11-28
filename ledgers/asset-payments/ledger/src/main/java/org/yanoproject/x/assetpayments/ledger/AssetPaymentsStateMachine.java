package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AccountRecordV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.ReceiptV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.RegistryEntryV1;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.CardanoAddresses;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.Cip113Wiring;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositAdmission;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppChainInfo;
import org.yanoproject.x.framework.api.appchain.AppChainMembershipView;
import org.yanoproject.x.framework.api.appchain.AppQueryContext;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateReader;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.SequencedL1Observation;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.effects.EffectIntent;
import org.yanoproject.x.framework.api.appchain.effects.EffectResult;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.effects.ResultPolicy;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.CoseSign1Verifier;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthException;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The #109 multi-asset payment ledger (ADR-059 D14): a genesis asset registry, accounts owned by
 * Cardano wallet credentials, session grants, transfers, owner rotation, deposit credit from
 * finalized L1 observations, withdrawal reservation (D7), payout confirmation (D10), and the threshold-governed
 * asset lifecycle and domain {@code PAUSE} / {@code RESUME} ({@link PaymentsGovernance}). The escape hatch comes in a
 * later stage.
 *
 * <p><b>Inputs.</b> User intents are the bodies of ordinary messages on
 * {@link AssetPaymentsContract#INTENT_TOPIC} ({@link PaymentsMessageV1}). Governed commands are privileged member
 * submissions on {@link PaymentsGovernanceV1#TOPIC}, decided once the membership epoch's threshold approves the exact
 * command bytes. Deposits are credited
 * only from {@code ~l1/<deposit-observer-id>} observations in a finalized block, through
 * {@link DepositAdmission} and {@link AssetAccounts} in the same transition (D6, I1). No command
 * creates value (I3). Claims are confirmed only from {@code ~l1/<payout-observer-id>}
 * observations, through {@link PayoutConfirmation} (D10, I12, I18): {@code RESERVED → PAID} on a
 * thread-authenticated payout that matches, a recorded no-op for an already-paid claim, and
 * {@code PAID_MISMATCH} with the domain status leaf moved to {@code HALTED} otherwise. Genesis
 * writes that status leaf {@code ACTIVE} with the configured exit delay (D8a).
 *
 * <p><b>Withdrawals</b> (D7, D11). An owner-signed {@code REQUEST_WITHDRAWAL} checks feasibility (the recipient is
 * {@link CardanoAddresses#payableRecipient payable} on the domain's network, an ADA claim or a credit-only claim is at
 * least {@code min-ada-payout}, a token claim's {@code vaultLovelace} is at most {@code min(credit,
 * payout-lovelace-cap)}, the balances cover it), moves the amount from available to reserved, and writes the
 * immutable {@link ClaimLeafV1} at {@code claim/<ClaimId>} with a {@code RESERVED} status, all in the intent's staged
 * transition. Its {@code ClaimId} binds the account (the withdrawal key) and a dense claim sequence. On a chain with
 * effects, the claim joins its shard's queue (a token claim only when its {@code vaultLovelace} reaches
 * {@code settlement.min-queued-vault-lovelace}; below it the claim is paid by {@code Exit} only, D11); a queue emits
 * one {@code asset.settlement.batch} effect
 * ({@code APP_FINAL}, {@code CHAIN}) of at most {@code settlement.batch-size} claims (and at most what fits the
 * chain's effect payload limit) when it reaches that size or its oldest claim has waited
 * {@code settlement.batch-wait-ms}. With the default wait of 0 a claim is batched in the block that reserves it; a
 * non-zero wait is judged only when a later block runs, so it needs regular traffic. Whatever result closes a batch
 * (confirmed, failed or expired), every claim of it still {@code RESERVED} in committed state is queued again: only a
 * committed payout observation takes a claim out of settlement, so a Settle included and then rolled back is batched
 * again. Effect results never change claim status (I12).
 *
 * <p><b>Determinism.</b> The only clock is the block timestamp. Every message runs against a
 * {@link StagedStore} that is committed only on success, so a rejection leaves no partial writes,
 * and nothing that a user or L1 sends can make {@code apply} throw (I2). An intent that does not
 * authenticate, has expired, or was already used writes nothing; an authenticated, unused intent
 * writes {@code used/<IntentId>} and exactly one receipt, accepted or rejected (D14).
 *
 * <p><b>Diagnostics</b> (D15a). {@link #operationalStatus()} reports node-local counts of what this node executed
 * (deposits credited, uncredited or unattributed by reason, payouts paid, no-op or mismatched by reason, intents by
 * outcome, governance decisions, settlement batches), the reservation-to-{@code PAID} latency, the per-vault withdrawal
 * backlog and the domain status. A {@code RuntimeException} that a transition drops (its staged writes discarded, so
 * the chain continues, I2) is counted under {@code dropped.*} and logged at WARN; it never changes the outcome.
 */
final class AssetPaymentsStateMachine implements AppStateMachine {
    private static final Logger LOG = LoggerFactory.getLogger(AssetPaymentsStateMachine.class);
    private final AssetPaymentsSettings settings;
    private final WalletAuthorizer authorizer;
    private final long maxForwardSkewMs;
    private final byte[] domainRecord;
    private final boolean batching;
    /** The claims of one batch: {@code settlement.batch-size}, bounded by what the effect payload limit admits. */
    private final int batchCap;
    private final PaymentsViewQueries views;
    private final PaymentsGovernance governance;
    private final LedgerDiagnostics diagnostics = new LedgerDiagnostics();

    AssetPaymentsStateMachine(AssetPaymentsSettings settings, long maxForwardSkewMs, boolean batching, int batchCap,
                              AppChainMembershipView membership) {
        if (settings.maxIntentTtlMs() <= maxForwardSkewMs) {
            throw new IllegalArgumentException("max-intent-ttl-ms must exceed the chain's maximum forward clock skew");
        }
        this.settings = settings;
        this.maxForwardSkewMs = maxForwardSkewMs;
        this.authorizer = new WalletAuthorizer(settings.intentDomain(), settings.maxIntentTtlMs());
        this.domainRecord = settings.domainRecord().encode();
        this.batching = batching;
        if (batchCap < 1 || batchCap > settings.settlement().batchSize()) {
            throw new IllegalArgumentException("the settlement batch cap is 1..settlement.batch-size");
        }
        this.batchCap = batchCap;
        this.views = new PaymentsViewQueries(settings);
        this.governance = new PaymentsGovernance(membership, AssetPaymentsContract.domainStatusKey(),
                settings.retireAfterWindDownMs(), policy -> settings.cip113ParamsPolicy()
                .filter(pinned -> Arrays.equals(pinned, policy)).map(this::wiring));
    }

    @Override
    public String id() {
        return AssetPaymentsContract.STATE_MACHINE_ID;
    }

    @Override
    public void init(AppStateReader state, AppChainInfo info) {
        Optional<byte[]> stored = state.get(AssetPaymentsContract.domainKey());
        if (stored.isPresent() && !Arrays.equals(stored.get(), domainRecord)) {
            throw new IllegalStateException(
                    "asset-payments settings differ from the domain and registry this chain was created with");
        }
    }

    // ------------------------------------------------------------------ admission (advisory)

    @Override
    public AdmissionResult validate(AppMessage message) {
        if (PaymentsGovernanceV1.TOPIC.equals(message.getTopic())) {
            return validatePrivilegedSystemSubmission(message.getTopic(), message.getBody());
        }
        if (settings.depositTopic().equals(message.getTopic())
                || settings.payoutTopic().filter(message.getTopic()::equals).isPresent()) {
            return AdmissionResult.accept();
        }
        if (!AssetPaymentsContract.INTENT_TOPIC.equals(message.getTopic())) {
            return AdmissionResult.reject("asset-payments accepts only topic " + AssetPaymentsContract.INTENT_TOPIC);
        }
        try {
            IntentEnvelopeV1 envelope = switch (PaymentsMessageV1.decode(message.getBody())) {
                case PaymentsMessageV1.Owner owner ->
                        IntentEnvelopeV1.decode(CoseSign1Verifier.verify(owner.coseSign1(), owner.coseKey()).payload());
                case PaymentsMessageV1.Session session -> IntentEnvelopeV1.decode(session.envelope());
                case PaymentsMessageV1.Rotation rotation -> IntentEnvelopeV1.decode(
                        CoseSign1Verifier.verify(rotation.currentSign1(), rotation.currentKey()).payload());
            };
            if (!envelope.domain().equals(settings.intentDomain())) return AdmissionResult.reject("WRONG_DOMAIN");
            if (!AssetPaymentsContract.ACTIONS.contains(envelope.action())) {
                return AdmissionResult.reject("UNSUPPORTED_ACTION");
            }
            return AdmissionResult.accept();
        } catch (RuntimeException invalid) {
            return AdmissionResult.reject("INVALID_INTENT");
        }
    }

    @Override
    public AdmissionResult validateForBlock(AppMessage message, long candidateHeight, AppStateReader committed) {
        AdmissionResult structural = validate(message);
        if (!structural.isAccepted() || !AssetPaymentsContract.INTENT_TOPIC.equals(message.getTopic())) {
            return structural;
        }
        try {
            AssetAccounts.Store store = StagedStore.readOnly(committed);
            // Advisory only: the clock check happens again, against the block timestamp, in apply().
            WalletAuthorizer.Authorized authorized = authorize(PaymentsMessageV1.decode(message.getBody()), store,
                    Long.MAX_VALUE / 4, false);
            if (store.get(AssetPaymentsContract.usedKey(authorized.intentId())).isPresent()
                    || store.get(AssetPaymentsContract.receiptKey(authorized.intentId())).isPresent()) {
                return AdmissionResult.reject("REPLAYED");
            }
            return AdmissionResult.accept();
        } catch (WalletAuthException unauthenticated) {
            return AdmissionResult.reject("UNAUTHENTICATED");
        } catch (RuntimeException invalid) {
            return AdmissionResult.reject("INVALID_INTENT");
        }
    }

    /**
     * The local operator's submission check for a governed command (D14): a decodable {@link PaymentsGovernanceV1}
     * command whose vault, if any, is in this chain's registry. Authority is decided in {@code apply()} by the
     * membership epoch's threshold, never here.
     */
    @Override
    public AdmissionResult validatePrivilegedSystemSubmission(String topic, byte[] body) {
        if (!PaymentsGovernanceV1.TOPIC.equals(topic)) {
            return AdmissionResult.reject("asset-payments governs only topic " + PaymentsGovernanceV1.TOPIC);
        }
        try {
            PaymentsGovernanceV1.Command command = PaymentsGovernanceV1.Command.decode(body);
            Optional<Hash32> subject = switch (command.action()) {
                case PaymentsGovernanceV1.AssetStatusChange change -> Optional.of(change.vaultId());
                case PaymentsGovernanceV1.AssetHold hold -> Optional.of(hold.vaultId());
                case PaymentsGovernanceV1.AssetResume resume -> Optional.of(resume.vaultId());
                default -> Optional.empty();
            };
            if (subject.isPresent()
                    && settings.vaults().stream().noneMatch(vault -> vault.vaultId().equals(subject.get()))) {
                return AdmissionResult.reject("UNKNOWN_VAULT");
            }
            if (command.action() instanceof PaymentsGovernanceV1.AssetRewire rewire && settings.cip113ParamsPolicy()
                    .filter(policy -> Arrays.equals(policy, rewire.paramsPolicy())).isEmpty()) {
                return AdmissionResult.reject("UNKNOWN_DEPLOYMENT");
            }
            return AdmissionResult.accept();
        } catch (RuntimeException invalid) {
            return AdmissionResult.reject("INVALID_GOVERNANCE_COMMAND");
        }
    }

    // ------------------------------------------------------------------ transition

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        AppBlock block = context.block();
        diagnostics.begin(block.height());
        AssetAccounts.Store state = StagedStore.over(writer);
        ensureGenesis(state, block);
        pruneUsedIntents(state, block.timestamp());
        List<AppMessage> messages = context.messages();
        for (int visible = 0; visible < messages.size(); visible++) {
            AppMessage message = messages.get(visible);
            String topic = message.getTopic();
            if (settings.depositTopic().equals(topic)) {
                context.l1ObservationAt(context.originalMessageIndex(visible))
                        .map(SequencedL1Observation::observation)
                        .ifPresent(observation -> creditDeposit(state, observation, block.height()));
            } else if (settings.wiringTopic().filter(topic::equals).isPresent()) {
                // ADR-061 I48: the canonical order puts a block's wiring observations before its deposits.
                context.l1ObservationAt(context.originalMessageIndex(visible))
                        .map(SequencedL1Observation::observation)
                        .ifPresent(observation -> recordWiring(state, observation, block.height()));
            } else if (settings.payoutTopic().filter(topic::equals).isPresent()) {
                context.l1ObservationAt(context.originalMessageIndex(visible))
                        .map(SequencedL1Observation::observation)
                        .ifPresent(observation -> confirmPayout(state, observation, block));
            } else if (AssetPaymentsContract.INTENT_TOPIC.equals(topic)) {
                processIntent(state, message.getBody(), block);
            } else if (PaymentsGovernanceV1.TOPIC.equals(topic)) {
                processGovernance(state, message, block);
            }
            // Every other topic (framework '~' topics included) is not this ledger's.
        }
        flushSettlementBatches(state, block, effects);
        recordBacklog(state);
    }

    @Override
    public Map<String, Object> operationalStatus() {
        return diagnostics.snapshot();
    }

    /** Diagnostics only: the vaults' reserved value and queue depth, and the domain status, after this block. */
    private void recordBacklog(AssetAccounts.Store state) {
        try {
            AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, state);
            Map<String, Object> vaults = new LinkedHashMap<>();
            for (RegistryEntryV1 vault : settings.vaults()) {
                AssetAccounts.VaultCounters counters = accounts.counters(vault.vaultId());
                long queued = 0;
                for (int shard = 0; shard < SettlementBatchV1.SHARDS; shard++) {
                    Optional<byte[]> meta = state.get(AssetPaymentsContract.settleQueueMetaKey(vault.vaultId(), shard));
                    if (meta.isPresent()) {
                        long[] headTail = PaymentsRecords.decodeU64s(meta.get(), 2);
                        queued += Math.max(0, headTail[1] - headTail[0]);
                    }
                }
                String status = registry(state, vault.vaultId()).map(entry -> entry.status().name()).orElse("UNKNOWN");
                Map<String, Object> backlog = new LinkedHashMap<>(LedgerDiagnostics.backlog(vault.ticker(),
                        counters.reserved(), counters.lovelaceReserved(), counters.available(), queued));
                backlog.put("status", status);
                vaults.put(vault.vaultId().hex(), backlog);
            }
            String domain = state.get(AssetPaymentsContract.domainStatusKey())
                    .map(bytes -> DomainStatusV1.decode(bytes).status().name()).orElse("UNKNOWN");
            diagnostics.end(vaults, domain);
        } catch (RuntimeException unreadable) {
            diagnostics.end(Map.of(), "UNKNOWN");
            dropped("backlog", -1, unreadable);
        }
    }

    /**
     * Counts what a transition that already committed did. It runs after the transition's try block, so a failure
     * here can neither drop the committed writes nor be logged as a dropped transition; it is only logged.
     */
    private void observe(String path, Runnable record) {
        try {
            record.run();
        } catch (RuntimeException failure) {
            LOG.debug("asset-payments could not count a committed {} transition: {}", path, failure.toString());
        }
    }

    /** A transition dropped an unexpected exception: count it and say so, never silently (D15a). */
    private void dropped(String path, long height, RuntimeException failure) {
        diagnostics.count("dropped." + path);
        LOG.warn("asset-payments dropped a {} transition at height {} (its staged writes are discarded): {}", path,
                height, failure.toString());
    }

    private void ensureGenesis(AssetAccounts.Store state, AppBlock block) {
        if (state.get(AssetPaymentsContract.domainKey()).isPresent()) return;
        state.put(AssetPaymentsContract.domainKey(), domainRecord);
        for (RegistryEntryV1 vault : settings.vaults()) {
            state.put(AssetPaymentsContract.registryKey(vault.vaultId()), vault.encode());
            // ADR-061 D12: wiring version 0 of a CIP-113 vault's deployment, and its approved tuple.
            // The vaults of the one deployment share it: a repeated genesis with the same pins changes nothing, and
            // one with other pins is rejected (settings refuse it first).
            vault.custody().ifPresent(custody -> {
                Cip113WiringClaimV1.Fields fields = custody.wiring0();
                wiring(custody.paramsPolicy()).genesis(state, new Cip113Wiring.Version(
                        new ProgrammableDepositDatumV1.Outpoint(custody.params0TxId(), custody.params0Index()), 0, 0,
                        Optional.of(fields)), new Cip113Wiring.ApprovedTuple(fields, custody.transferDelegate(),
                        custody.thirdPartyDelegate(), custody.unfrackingDelegate()));
            });
            if (vault.status() == PaymentsRecords.AssetStatus.WIND_DOWN) {
                // A vault configured in WIND_DOWN starts its retirement window at genesis (D14).
                state.put(AssetPaymentsContract.windDownKey(vault.vaultId()),
                        PaymentsRecords.encodeU64s(block.height(), block.timestamp()));
            }
        }
        state.put(AssetPaymentsContract.domainStatusKey(),
                new DomainStatusV1(DomainStatusV1.Status.ACTIVE, 0, 0, settings.exitDelayMs()).encode());
    }

    /**
     * Deletes up to {@link AssetPaymentsContract#MAX_PRUNE_PER_BLOCK} expired single-use markers,
     * oldest first.
     *
     * <p>The queue is FIFO by insertion, not ordered by expiry: the state offers only point reads, so
     * an expiry-ordered queue would need its own persisted index. A marker with a short
     * {@code notAfterMs} can therefore wait behind an older one that expires later, but only for a
     * bounded time. Every intent is accepted only with {@code notAfterMs <= blockTime + maxIntentTtlMs}
     * ({@link WalletAuthorizer}) and block timestamps never decrease, so every entry ahead of a marker
     * written at block time {@code t} expires by {@code t + maxIntentTtlMs + maxForwardSkewMs}. A marker
     * is thus prunable no later than that (at {@code MAX_PRUNE_PER_BLOCK} per block): at most
     * {@code maxIntentTtlMs} longer than an expiry-ordered queue would keep it. Keeping a marker longer
     * is safe, since it only rejects replays of an intent that has expired anyway.
     */
    private void pruneUsedIntents(AssetAccounts.Store state, long blockTimeMs) {
        long[] meta = state.get(AssetPaymentsContract.usedQueueMetaKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 2)).orElse(new long[] {0, 0});
        long head = meta[0];
        int pruned = 0;
        while (head < meta[1] && pruned < AssetPaymentsContract.MAX_PRUNE_PER_BLOCK) {
            byte[] queueKey = AssetPaymentsContract.usedQueueKey(head);
            PaymentsRecords.QueuedIntent queued = state.get(queueKey).map(PaymentsRecords.QueuedIntent::decode)
                    .orElseThrow(() -> new IllegalStateException("used-intent queue entry missing"));
            long expiry = queued.notAfterMs() > Long.MAX_VALUE - maxForwardSkewMs
                    ? Long.MAX_VALUE : queued.notAfterMs() + maxForwardSkewMs;
            if (blockTimeMs <= expiry) break;
            state.delete(AssetPaymentsContract.usedKey(queued.intentId()));
            state.delete(queueKey);
            head++;
            pruned++;
        }
        if (pruned > 0) {
            state.put(AssetPaymentsContract.usedQueueMetaKey(), PaymentsRecords.encodeU64s(head, meta[1]));
        }
    }

    // ------------------------------------------------------------------ governance (D14, D8a)

    private void processGovernance(AssetAccounts.Store state, AppMessage message, AppBlock block) {
        StagedStore staged = new StagedStore(state);
        PaymentsGovernance.Result result;
        try {
            result = governance.process(staged, message.getSender(), message.getBody(), block);
            staged.commit();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; the staged writes are dropped and the chain continues.
            dropped("governance", block.height(), unexpected);
            return;
        }
        observe("governance", () -> diagnostics.count("governance." + result.name()));
    }

    // ------------------------------------------------------------------ deposits (D6)

    /** The wiring state of a CIP-113 deployment, keyed by its params policy. */
    private Cip113Wiring wiring(PaymentsRecords.Cip113Custody custody) {
        return wiring(custody.paramsPolicy());
    }

    private Cip113Wiring wiring(byte[] paramsPolicy) {
        byte[] prefix = new byte[AssetPaymentsContract.PREFIX.length + 3 + 28];
        System.arraycopy(AssetPaymentsContract.PREFIX, 0, prefix, 0, AssetPaymentsContract.PREFIX.length);
        System.arraycopy("cw/".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, prefix,
                AssetPaymentsContract.PREFIX.length, 3);
        System.arraycopy(paramsPolicy, 0, prefix, AssetPaymentsContract.PREFIX.length + 3, 28);
        return new Cip113Wiring(prefix, settings.wiringObserverId().orElseThrow());
    }

    /**
     * ADR-061 D6 (r5): records one wiring version for the domain's one CIP-113 deployment. The wiring observer's
     * {@code params-policy} is that deployment's, which the settings check at startup, so its observations are the
     * outputs carrying that deployment's params token and are recorded for it alone.
     */
    private void recordWiring(AssetAccounts.Store state, L1Observation observation, long height) {
        StagedStore staged = new StagedStore(state);
        try {
            byte[] anchor = observation.anchor() instanceof L1Observation.TransactionAnchor tx
                    ? tx.transactionHash() : null;
            DepositAdmission.ObservedDeposit observed = new DepositAdmission.ObservedDeposit(observation.observerId(),
                    anchor, observation.eventOrdinal(), observation.slot(), observation.blockHash(),
                    observation.claim());
            settings.cip113ParamsPolicy().ifPresent(policy -> wiring(policy).record(staged, observed));
            staged.commit();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; the staged writes are dropped and the chain continues (I2).
            dropped("wiring", height, unexpected);
        }
    }

    /** {@code vault-output/<outpoint>} by the outpoint's CBOR {@code [tx_id, index]}. */
    private byte[] cip113OutputKey(byte[] outpointCbor) {
        java.util.List<co.nstant.in.cbor.model.DataItem> items = org.yanoproject.x.assets.contracts.internal.AssetCbor
                .decodeArray(outpointCbor, 2).getDataItems();
        ProgrammableDepositDatumV1.Outpoint outpoint = new ProgrammableDepositDatumV1.Outpoint(Hash32.of(
                org.yanoproject.x.assets.contracts.internal.AssetCbor.bytes(items.get(0), 32, 32)),
                org.yanoproject.x.assets.contracts.internal.AssetCbor.uintLong(items.get(1)));
        return new ProgrammableDepositAdmission(AssetPaymentsContract.PREFIX, settings.depositObserverId(),
                vaultId -> Optional.empty(), wiring(settings.cip113ParamsPolicy().orElseThrow())).outputKey(outpoint);
    }

    /** ADR-061 D6: a CIP-113 vault's deposit claim, admitted outpoint first and classified by its wiring version. */
    private boolean creditProgrammableDeposit(StagedStore staged, L1Observation observation, long height) {
        ProgrammableDepositClaimV1 claim;
        try {
            claim = ProgrammableDepositClaimV1.decode(observation.claim());
        } catch (IllegalArgumentException | ArithmeticException notProgrammable) {
            return false;
        }
        Optional<RegistryEntryV1> entry = registry(staged, claim.vaultId());
        if (entry.isEmpty() || entry.get().custody().isEmpty()) return false;
        PaymentsRecords.Cip113Custody custody = entry.get().custody().get();
        long slot = observation.slot();
        ProgrammableDepositAdmission admission = new ProgrammableDepositAdmission(AssetPaymentsContract.PREFIX,
                settings.depositObserverId(), vaultId -> registry(staged, vaultId)
                .filter(found -> found.custody().isPresent())
                .map(found -> new ProgrammableDepositAdmission.VaultBinding(found.vaultId(), found.asset(),
                        found.status().depositsEnabled() && slot >= found.activeFromSlot(), found.status().held())),
                wiring(custody));
        AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, staged);
        byte[] anchor = observation.anchor() instanceof L1Observation.TransactionAnchor tx
                ? tx.transactionHash() : null;
        ProgrammableDepositAdmission.Outcome outcome = admission.admit(staged, new DepositAdmission.ObservedDeposit(
                observation.observerId(), anchor, observation.eventOrdinal(), slot, observation.blockHash(),
                observation.claim()), height, candidate -> refuseCredit(staged, accounts, candidate));
        outcome.credit().ifPresent(credit -> accounts.credit(credit.vaultId(), credit.beneficiary(),
                account(staged, credit.beneficiary()).orElseThrow().keyHash(), credit.amount(),
                credit.minAdaCredit()));
        return true;
    }

    private void creditDeposit(AssetAccounts.Store state, L1Observation observation, long height) {
        StagedStore staged = new StagedStore(state);
        DepositAdmission.Outcome outcome;
        try {
            if (creditProgrammableDeposit(staged, observation, height)) {
                staged.commit();
                return;
            }
            byte[] anchor = observation.anchor() instanceof L1Observation.TransactionAnchor tx
                    ? tx.transactionHash() : null;
            long slot = observation.slot();
            DepositAdmission admission = new DepositAdmission(AssetPaymentsContract.PREFIX,
                    settings.depositObserverId(), vaultId -> registry(staged, vaultId).map(entry ->
                    new DepositAdmission.VaultBinding(entry.vaultId(), entry.asset(),
                            entry.status().depositsEnabled() && slot >= entry.activeFromSlot())));
            AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, staged);
            outcome = admission.admit(staged, new DepositAdmission.ObservedDeposit(
                    observation.observerId(), anchor, observation.eventOrdinal(), slot, observation.blockHash(),
                    observation.claim()), height, candidate -> refuseCredit(staged, accounts, candidate));
            outcome.credit().ifPresent(credit -> accounts.credit(credit.vaultId(), credit.beneficiary(),
                    account(staged, credit.beneficiary()).orElseThrow().keyHash(), credit.amount(),
                    credit.minAdaCredit()));
            staged.commit();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; the staged writes are dropped and the chain continues (I2).
            dropped("deposit", height, unexpected);
            return;
        }
        observe("deposit", () -> diagnostics.count(depositKey(outcome)));
    }

    private static String depositKey(DepositAdmission.Outcome outcome) {
        return switch (outcome.disposition()) {
            case RECORDED -> outcome.record().map(record -> switch (record.status()) {
                case CREDITED -> "deposits.credited";
                case OPERATIONAL -> "deposits.operational";
                case UNCREDITED -> "deposits.uncredited." + record.reason().name();
                case UNATTRIBUTED -> "deposits.unattributed." + record.reason().name();
            }).orElse("deposits.recorded");
            case DUPLICATE -> "deposits.duplicate";
            case IGNORED -> "deposits.ignored";
            case VAULT_CUSTODY -> "deposits.vaultCustody";
        };
    }

    // ------------------------------------------------------------------ payouts (D10)

    /**
     * Confirms one finalized payout observation in one staged transition. A governed claim's reserved
     * value would sit in separate governed counters (D8d); this ledger reserves none yet, so it has none
     * to settle.
     */
    private void confirmPayout(AssetAccounts.Store state, L1Observation observation, AppBlock block) {
        StagedStore staged = new StagedStore(state);
        PayoutConfirmation.Result result;
        try {
            byte[] anchor = observation.anchor() instanceof L1Observation.TransactionAnchor tx
                    ? tx.transactionHash() : null;
            PayoutConfirmation confirmation = new PayoutConfirmation(AssetPaymentsContract.PREFIX,
                    settings.payoutObserverId().orElseThrow(), settings.domainId(), settings.exitDelayMs(),
                    vaultId -> registry(staged, vaultId).map(entry ->
                            new PayoutConfirmation.VaultBinding(entry.vaultId(), entry.asset())));
            result = confirmation.apply(staged, new PayoutConfirmation.ObservedPayout(
                    observation.observerId(), anchor, observation.eventOrdinal(), observation.slot(),
                    observation.blockHash(), observation.claim()), block.height(), block.timestamp());
            staged.commit();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; the staged writes are dropped and the chain continues (I2).
            dropped("payout", block.height(), unexpected);
            return;
        }
        observe("payout", () -> countPayout(result, block.timestamp()));
    }

    private void countPayout(PayoutConfirmation.Result result, long blockTimeMs) {
        if (result.disposition() != PayoutConfirmation.Disposition.RECORDED) {
            diagnostics.count("payouts." + result.disposition().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        if (result.claims().isEmpty()) diagnostics.count("payouts.balanceExit");
        if (result.halted()) diagnostics.count("payouts.halted");
        for (PayoutConfirmation.ClaimResult claim : result.claims()) {
            switch (claim.outcome()) {
                case PAID -> {
                    diagnostics.count("payouts.paid");
                    claim.leaf().ifPresent(leaf -> diagnostics.paidAfter(blockTimeMs - leaf.reservedAtMs()));
                }
                case NO_OP -> diagnostics.count("payouts.noop." + claim.reason().name());
                case PAID_MISMATCH -> diagnostics.count("payouts.mismatch." + claim.reason().name());
            }
        }
    }

    /** The consumer's check: the beneficiary is a registered account and the credit cannot overflow. */
    private Optional<DepositAdmission.Reason> refuseCredit(AssetAccounts.Store state, AssetAccounts accounts,
                                                           DepositAdmission.CreditInstruction candidate) {
        if (account(state, candidate.beneficiary()).isEmpty()) {
            return Optional.of(DepositAdmission.Reason.UNKNOWN_BENEFICIARY);
        }
        Optional<ExitLeafV1> leaf = accounts.balance(candidate.vaultId(), candidate.beneficiary());
        BigInteger amount = leaf.map(ExitLeafV1::amount).orElse(BigInteger.ZERO).add(candidate.amount());
        BigInteger lovelace = leaf.map(ExitLeafV1::vaultLovelace).orElse(BigInteger.ZERO).add(candidate.minAdaCredit());
        AssetAccounts.VaultCounters counters = accounts.counters(candidate.vaultId());
        if (amount.compareTo(Quantities.MAX_U64) > 0 || lovelace.compareTo(Quantities.MAX_U64) > 0
                || counters.credited().add(candidate.amount()).compareTo(Quantities.MAX_U128) > 0
                || counters.lovelaceCredited().add(candidate.minAdaCredit()).compareTo(Quantities.MAX_U128) > 0) {
            return Optional.of(DepositAdmission.Reason.BALANCE_OVERFLOW);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ intents (D13, D14)

    private record Result(String rejection, Optional<AssetId> asset, BigInteger amount) {
        static Result accepted() {
            return new Result("", Optional.empty(), BigInteger.ZERO);
        }

        static Result rejected(String reason) {
            return new Result(reason, Optional.empty(), BigInteger.ZERO);
        }

        Result about(AssetId asset, BigInteger amount) {
            return new Result(rejection, Optional.of(asset), amount);
        }
    }

    private void processIntent(AssetAccounts.Store state, byte[] body, AppBlock block) {
        StagedStore staged = new StagedStore(state);
        String counted;
        try {
            WalletAuthorizer.Authorized authorized;
            try {
                authorized = authorize(PaymentsMessageV1.decode(body), staged, block.timestamp(), true);
            } catch (RuntimeException unauthenticated) {
                diagnostics.count("intents.unauthenticated");
                return; // unauthenticated, expired or malformed: nothing is written (D14)
            }
            Hash32 intentId = authorized.intentId();
            if (staged.get(AssetPaymentsContract.usedKey(intentId)).isPresent()
                    || staged.get(AssetPaymentsContract.receiptKey(intentId)).isPresent()) {
                diagnostics.count("intents.replayed");
                return; // replayed: skipped, so a receipt is never overwritten
            }
            IntentEnvelopeV1 envelope = authorized.envelope();
            StagedStore action = new StagedStore(staged);
            Result result;
            try {
                result = execute(action, authorized, block);
            } catch (WalletAuthException | IllegalArgumentException | ArithmeticException invalid) {
                result = Result.rejected("INVALID_COMMAND");
            }
            if (result.rejection().isEmpty()) action.commit();
            markUsed(staged, intentId, envelope.notAfterMs());
            staged.put(AssetPaymentsContract.receiptKey(intentId), new ReceiptV1(intentId, envelope.accountId(),
                    envelope.action(), result.asset(), result.amount(),
                    result.rejection().isEmpty() ? PaymentsRecords.Outcome.ACCEPTED : PaymentsRecords.Outcome.REJECTED,
                    result.rejection(), block.height()).encode());
            staged.commit();
            counted = result.rejection().isEmpty() ? "intents.accepted." + envelope.action()
                    : "intents.rejected." + result.rejection();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; nothing of this intent is written and the chain continues.
            dropped("intent", block.height(), unexpected);
            return;
        }
        observe("intent", () -> diagnostics.count(counted));
    }

    private WalletAuthorizer.Authorized authorize(PaymentsMessageV1 message, AssetAccounts.Store state,
                                                  long blockTimeMs, boolean checkClock) {
        WalletAuthorizer.OwnerLookup owners = accountId -> account(state, accountId).map(record ->
                new CardanoCredential(CardanoCredential.Kind.values()[record.credentialKind()], record.keyHash(),
                        settings.addressNetworkId()));
        WalletAuthorizer.GrantLookup grants = grantId ->
                state.get(AssetPaymentsContract.grantKey(grantId)).map(SessionGrantV1::decode);
        WalletAuthorizer auth = checkClock ? authorizer : new WalletAuthorizer(settings.intentDomain(), Long.MAX_VALUE);
        long time = checkClock ? blockTimeMs : 0;
        WalletAuthorizer.Authorized authorized = switch (message) {
            case PaymentsMessageV1.Owner owner -> auth.authorizeOwner(owner.coseSign1(), owner.coseKey(), owners, time);
            case PaymentsMessageV1.Session session ->
                    auth.authorizeSession(session.envelope(), session.signature(), grants, time);
            case PaymentsMessageV1.Rotation rotation -> auth.authorizeRotation(rotation.currentSign1(),
                    rotation.currentKey(), rotation.newSign1(), rotation.newKey(), owners, time);
        };
        if (!AssetPaymentsContract.ACTIONS.contains(authorized.envelope().action())) {
            throw new WalletAuthException("action is not supported by this ledger");
        }
        return authorized;
    }

    private Result execute(StagedStore state, WalletAuthorizer.Authorized authorized, AppBlock block) {
        IntentEnvelopeV1 envelope = authorized.envelope();
        byte[] accountId = envelope.accountId();
        return switch (envelope.action()) {
            case WalletAuthorizer.REGISTER_ACCOUNT -> registerAccount(state, authorized, block.height());
            case WalletAuthorizer.AUTHORIZE_SESSION -> authorizeSession(state, authorized, block.timestamp());
            case WalletAuthorizer.REVOKE_SESSION -> revokeSession(state, accountId, envelope.body());
            case WalletAuthorizer.ROTATE_OWNER -> rotateOwner(state, authorized);
            case AssetPaymentsContract.TRANSFER -> transfer(state, authorized, block.timestamp());
            case AssetPaymentsContract.REQUEST_WITHDRAWAL -> requestWithdrawal(state, authorized, block);
            default -> Result.rejected("UNSUPPORTED_ACTION");
        };
    }

    private Result registerAccount(StagedStore state, WalletAuthorizer.Authorized authorized, long height) {
        if (!PaymentsBodies.isEmpty(authorized.envelope().body())) return Result.rejected("BAD_BODY");
        byte[] accountId = authorized.envelope().accountId();
        if (account(state, accountId).isPresent()) return Result.accepted(); // idempotent for its owner
        CardanoCredential owner = authorized.owner().orElseThrow();
        state.put(AssetPaymentsContract.accountKey(accountId),
                new AccountRecordV1(owner.kind().code(), owner.keyHash(), height, 0, List.of()).encode());
        return Result.accepted();
    }

    private Result authorizeSession(StagedStore state, WalletAuthorizer.Authorized authorized, long blockTimeMs) {
        PaymentsBodies.AuthorizeSession body;
        try {
            body = PaymentsBodies.AuthorizeSession.decode(authorized.envelope().body());
        } catch (IllegalArgumentException invalid) {
            return Result.rejected("BAD_BODY");
        }
        if (!AssetPaymentsContract.SESSION_ACTIONS.containsAll(body.actions())) {
            return Result.rejected("ACTION_NOT_DELEGABLE");
        }
        for (SessionGrantV1.Limit limit : body.limits()) {
            if (registry(state, limit.vaultId()).isEmpty()) return Result.rejected("UNKNOWN_VAULT");
        }
        if (body.notAfterMs() < blockTimeMs) return Result.rejected("GRANT_EXPIRED");
        byte[] accountId = authorized.envelope().accountId();
        AccountRecordV1 account = pruneExpiredGrants(state, account(state, accountId).orElseThrow(), accountId,
                blockTimeMs);
        if (account.grants().size() >= AssetPaymentsContract.MAX_GRANTS_PER_ACCOUNT) {
            return Result.rejected("TOO_MANY_GRANTS");
        }
        Hash32 grantId = authorized.intentId();
        SessionGrantV1 grant = new SessionGrantV1(grantId, accountId, body.sessionKey(), body.actions(),
                body.limits(), body.recipients(), body.notAfterMs());
        state.put(AssetPaymentsContract.grantKey(grantId), grant.encode());
        List<Hash32> grants = new ArrayList<>(account.grants());
        grants.add(grantId);
        state.put(AssetPaymentsContract.accountKey(accountId), account.withGrants(grants).encode());
        return Result.accepted();
    }

    /**
     * Drops the account's grants that expired before {@code blockTimeMs} (the block timestamp, the
     * only clock), with their usage counters, in the account's own grant order, so an expired grant
     * never holds one of the {@link AssetPaymentsContract#MAX_GRANTS_PER_ACCOUNT} slots. Such a grant
     * can no longer authorize anything ({@link SessionGrantV1#checkPayment} uses the same bound). The
     * writes land in the action's staged store, so they persist only if the new grant is accepted.
     */
    private AccountRecordV1 pruneExpiredGrants(StagedStore state, AccountRecordV1 account, byte[] accountId,
                                               long blockTimeMs) {
        List<Hash32> live = new ArrayList<>();
        for (Hash32 grantId : account.grants()) {
            Optional<SessionGrantV1> grant = state.get(AssetPaymentsContract.grantKey(grantId))
                    .map(SessionGrantV1::decode);
            if (grant.isEmpty()) continue; // no grant record: the id authorizes nothing
            if (grant.get().notAfterMs() < blockTimeMs) {
                deleteGrant(state, grant.get());
            } else {
                live.add(grantId);
            }
        }
        if (live.size() == account.grants().size()) return account;
        AccountRecordV1 pruned = account.withGrants(live);
        state.put(AssetPaymentsContract.accountKey(accountId), pruned.encode());
        return pruned;
    }

    private Result revokeSession(StagedStore state, byte[] accountId, byte[] body) {
        Hash32 grantId;
        try {
            grantId = PaymentsBodies.RevokeSession.decode(body).grantId();
        } catch (IllegalArgumentException invalid) {
            return Result.rejected("BAD_BODY");
        }
        Optional<SessionGrantV1> grant = state.get(AssetPaymentsContract.grantKey(grantId)).map(SessionGrantV1::decode);
        if (grant.isEmpty() || !Arrays.equals(grant.get().accountId(), accountId)) {
            return Result.rejected("UNKNOWN_GRANT");
        }
        AccountRecordV1 account = account(state, accountId).orElseThrow();
        deleteGrant(state, grant.get());
        state.put(AssetPaymentsContract.accountKey(accountId), account.withGrants(
                account.grants().stream().filter(id -> !id.equals(grantId)).toList()).encode());
        return Result.accepted();
    }

    /** D14a: replace the owner, rewrite the key in every exit leaf, revoke every grant. */
    private Result rotateOwner(StagedStore state, WalletAuthorizer.Authorized authorized) {
        byte[] accountId = authorized.envelope().accountId();
        AccountRecordV1 account = account(state, accountId).orElseThrow();
        CardanoCredential next = authorized.owner().orElseThrow();
        if (next.kind().code() == account.credentialKind() && Arrays.equals(next.keyHash(), account.keyHash())) {
            return Result.rejected("SAME_OWNER");
        }
        for (Hash32 grantId : account.grants()) {
            state.get(AssetPaymentsContract.grantKey(grantId)).map(SessionGrantV1::decode)
                    .ifPresent(grant -> deleteGrant(state, grant));
        }
        AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, state);
        for (RegistryEntryV1 vault : settings.vaults()) {
            accounts.rotateHolderKey(vault.vaultId(), accountId, next.keyHash());
        }
        state.put(AssetPaymentsContract.accountKey(accountId), new AccountRecordV1(next.kind().code(), next.keyHash(),
                account.createdHeight(), account.rotations() + 1, List.of()).encode());
        return Result.accepted();
    }

    private Result transfer(StagedStore state, WalletAuthorizer.Authorized authorized, long blockTimeMs) {
        PaymentsBodies.Transfer body;
        try {
            body = PaymentsBodies.Transfer.decode(authorized.envelope().body());
        } catch (IllegalArgumentException invalid) {
            return Result.rejected("BAD_BODY");
        }
        Hash32 vaultId = GatewayIds.vaultId(settings.domainId(), body.asset(), 0);
        Optional<RegistryEntryV1> entry = registry(state, vaultId);
        if (entry.isEmpty()) return Result.rejected("UNKNOWN_ASSET").about(body.asset(), body.amount());
        if (!entry.get().status().transfersEnabled()) {
            return Result.rejected("TRANSFERS_PAUSED").about(body.asset(), body.amount());
        }
        byte[] from = authorized.envelope().accountId();
        byte[] to = body.toAccount();
        if (Arrays.equals(from, to)) return Result.rejected("SELF_TRANSFER").about(body.asset(), body.amount());
        Optional<AccountRecordV1> recipient = account(state, to);
        if (recipient.isEmpty()) return Result.rejected("UNKNOWN_RECIPIENT").about(body.asset(), body.amount());
        if (authorized.grant().isPresent()) {
            SessionGrantV1 grant = authorized.grant().get();
            byte[] usageKey = AssetPaymentsContract.usageKey(grant.grantId(), vaultId);
            BigInteger used = state.get(usageKey).map(PaymentsRecords::decodeAmount).orElse(BigInteger.ZERO);
            try {
                grant.checkPayment(AssetPaymentsContract.TRANSFER, vaultId, body.amount(), used, to, blockTimeMs);
            } catch (WalletAuthException limit) {
                return Result.rejected("SESSION_LIMIT").about(body.asset(), body.amount());
            }
            state.put(usageKey, PaymentsRecords.encodeAmount(Quantities.addU64(used, body.amount(), "usage")));
        }
        AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, state);
        BigInteger available = accounts.balance(vaultId, from).map(ExitLeafV1::amount).orElse(BigInteger.ZERO);
        if (available.compareTo(body.amount()) < 0) {
            return Result.rejected("INSUFFICIENT_BALANCE").about(body.asset(), body.amount());
        }
        BigInteger received = accounts.balance(vaultId, to).map(ExitLeafV1::amount).orElse(BigInteger.ZERO);
        if (received.add(body.amount()).compareTo(Quantities.MAX_U64) > 0) {
            return Result.rejected("BALANCE_OVERFLOW").about(body.asset(), body.amount());
        }
        accounts.transfer(vaultId, from, to, recipient.get().keyHash(), body.amount());
        return Result.accepted().about(body.asset(), body.amount());
    }

    // ------------------------------------------------------------------ withdrawals (D7, D11)

    /**
     * {@code REQUEST_WITHDRAWAL}: feasibility, reservation and the claim records, in the intent's staged transition,
     * so a rejection writes nothing but the receipt. Every check reads finalized state or the signed body; nothing
     * depends on local configuration beyond the genesis settings (I13).
     */
    private Result requestWithdrawal(StagedStore state, WalletAuthorizer.Authorized authorized, AppBlock block) {
        PaymentsBodies.RequestWithdrawal body;
        try {
            body = PaymentsBodies.RequestWithdrawal.decode(authorized.envelope().body());
        } catch (IllegalArgumentException invalid) {
            return Result.rejected("BAD_BODY");
        }
        Optional<RegistryEntryV1> entry = registry(state, body.vaultId());
        if (entry.isEmpty()) return Result.rejected("UNKNOWN_VAULT");
        AssetId asset = entry.get().asset();
        BigInteger amount = body.amount();
        BigInteger lovelace = body.vaultLovelace();
        // A session never withdraws (I11); the grant model cannot carry this action, and this keeps it explicit.
        if (authorized.grant().isPresent()) return Result.rejected("OWNER_ONLY").about(asset, amount);
        // Only a payout observer can confirm a claim (D10); without one a reservation could never resolve.
        if (settings.payoutObserverId().isEmpty()) return Result.rejected("WITHDRAWALS_DISABLED").about(asset, amount);
        if (entry.get().status() == PaymentsRecords.AssetStatus.RETIRED) {
            return Result.rejected("ASSET_RETIRED").about(asset, amount);
        }
        // ADR-061 D14: voluntary withdrawals of a held asset are refused; forced withdrawals are not.
        if (entry.get().status().held()) return Result.rejected("ASSET_HELD").about(asset, amount);
        // Feasibility (D7 step 1): a Shelley key-payment address on this network; a pointer only in canonical form
        // within the Conway limits. For a CIP-113 vault (ADR-061 D7): a key-owned smart wallet of the pinned
        // deployment for a claim that carries the asset; a lovelace-only claim may also pay a key address.
        boolean feasible = entry.get().custody().isPresent()
                ? CardanoAddresses.payableProgrammableRecipient(body.recipient(), entry.get().custody().get().plbHash(),
                settings.addressNetworkId(), amount.signum() > 0)
                : CardanoAddresses.payableRecipient(body.recipient(), settings.addressNetworkId());
        if (!feasible) {
            return Result.rejected("INFEASIBLE_RECIPIENT").about(asset, amount);
        }
        BigInteger minAda = BigInteger.valueOf(settings.minAdaPayout());
        if (asset.isAda()) {
            // V5: an ADA claim carries no vaultLovelace; its amount is the payout.
            if (lovelace.signum() != 0) return Result.rejected("BAD_BODY").about(asset, amount);
            if (amount.compareTo(minAda) < 0) return Result.rejected("BELOW_MINIMUM").about(asset, amount);
        } else if (amount.signum() == 0) {
            // A credit-only lovelace claim from a token vault (D11).
            if (lovelace.compareTo(minAda) < 0) return Result.rejected("BELOW_MINIMUM").about(asset, amount);
        } else if (lovelace.compareTo(BigInteger.valueOf(settings.payoutLovelaceCap())) > 0) {
            return Result.rejected("VAULT_LOVELACE_CAP").about(asset, amount);
        }
        byte[] accountId = authorized.envelope().accountId();
        AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, state);
        Optional<ExitLeafV1> balance = accounts.balance(body.vaultId(), accountId);
        if (balance.map(ExitLeafV1::amount).orElse(BigInteger.ZERO).compareTo(amount) < 0) {
            return Result.rejected("INSUFFICIENT_BALANCE").about(asset, amount);
        }
        if (balance.map(ExitLeafV1::vaultLovelace).orElse(BigInteger.ZERO).compareTo(lovelace) < 0) {
            return Result.rejected("INSUFFICIENT_MIN_ADA_CREDIT").about(asset, amount);
        }

        long sequence = state.get(AssetPaymentsContract.claimSequenceKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L);
        AssetValue value = amount.signum() == 0 ? AssetValue.EMPTY : AssetValue.single(asset, amount);
        Hash32 claimId = GatewayIds.claimId(settings.domainId(), body.vaultId(), ClaimKind.USER_WITHDRAWAL, accountId,
                body.recipient(), value, lovelace, sequence);
        ClaimLeafV1 leaf = new ClaimLeafV1(claimId, body.vaultId(), ClaimKind.USER_WITHDRAWAL, value, lovelace,
                body.recipient(), block.height(), block.timestamp());
        accounts.reserve(body.vaultId(), accountId, amount, lovelace);
        payouts(state).writeReserved(state, leaf);
        state.put(AssetPaymentsContract.claimIndexKey(sequence), claimId.bytes());
        state.put(AssetPaymentsContract.intentClaimKey(authorized.intentId()), claimId.bytes());
        state.put(AssetPaymentsContract.claimSequenceKey(), PaymentsRecords.encodeU64s(sequence + 1));
        if (batching && queued(asset, amount, lovelace)) enqueue(state, body.vaultId(), claimId, block.timestamp());
        return Result.accepted().about(asset, amount);
    }

    /** The claim records' layout ({@code claim/}, {@code claim-status/}, {@code domain-status}). */
    private PayoutConfirmation payouts(AssetAccounts.Store state) {
        return new PayoutConfirmation(AssetPaymentsContract.PREFIX, settings.payoutObserverId().orElse("payouts"),
                settings.domainId(), settings.exitDelayMs(), vaultId -> registry(state, vaultId).map(entry ->
                new PayoutConfirmation.VaultBinding(entry.vaultId(), entry.asset())));
    }

    // ------------------------------------------------------------------ settlement batches (D7 step 4)

    /**
     * Whether a reserved claim joins a settlement queue. A token claim whose {@code vaultLovelace} is below
     * {@code settlement.min-queued-vault-lovelace} could be paid by a co-signed batch only with a supplement from the
     * operator's fee wallet (D11), which the executor refuses by default; batching it would only cycle effects, so it
     * stays {@code RESERVED} and payable by {@code Exit}, where the relayer or the user supplements it. ADA and
     * credit-only claims pay at least {@code min-ada-payout} of lovelace and always queue.
     */
    private boolean queued(AssetId asset, BigInteger amount, BigInteger vaultLovelace) {
        return asset.isAda() || amount.signum() == 0
                || vaultLovelace.compareTo(BigInteger.valueOf(settings.settlement().minQueuedVaultLovelace())) >= 0;
    }

    private void enqueue(AssetAccounts.Store state, Hash32 vaultId, Hash32 claimId, long queuedAtMs) {
        int shard = SettlementBatchV1.shardOf(claimId);
        byte[] metaKey = AssetPaymentsContract.settleQueueMetaKey(vaultId, shard);
        long[] meta = state.get(metaKey).map(bytes -> PaymentsRecords.decodeU64s(bytes, 2)).orElse(new long[] {0, 0});
        state.put(AssetPaymentsContract.settleQueueKey(vaultId, shard, meta[1]), queueEntry(claimId, queuedAtMs));
        state.put(metaKey, PaymentsRecords.encodeU64s(meta[0], meta[1] + 1));
    }

    /**
     * Emits at most one batch per shard queue per block, for every queue that holds a full batch or whose oldest claim
     * has waited {@code settlement.batch-wait-ms}. Queues are visited in registry and shard order, so every member
     * emits the same effects in the same order. An emission the runtime refuses (a per-block limit) leaves that queue
     * as it was for a later block, and the other queues still emit.
     */
    private void flushSettlementBatches(AssetAccounts.Store state, AppBlock block, AppEffectEmitter effects) {
        if (!batching || effects == null) return;
        AssetPaymentsSettings.Settlement policy = settings.settlement();
        for (RegistryEntryV1 vault : settings.vaults()) {
            for (int shard = 0; shard < SettlementBatchV1.SHARDS; shard++) {
                StagedStore staged = new StagedStore(state);
                try {
                    if (!flushQueue(staged, vault.vaultId(), shard, block, effects, policy)) continue;
                    staged.commit();
                } catch (RuntimeException refused) {
                    // Deterministic on every member; this shard's staged writes are dropped and the next shard runs.
                    // A per-block effect limit lands here by design, so it is counted but not logged as a failure.
                    diagnostics.count("batches.notEmitted");
                }
            }
        }
    }

    private boolean flushQueue(StagedStore state, Hash32 vaultId, int shard, AppBlock block, AppEffectEmitter effects,
                               AssetPaymentsSettings.Settlement policy) {
        byte[] metaKey = AssetPaymentsContract.settleQueueMetaKey(vaultId, shard);
        Optional<byte[]> stored = state.get(metaKey);
        if (stored.isEmpty()) return false;
        long[] meta = PaymentsRecords.decodeU64s(stored.get(), 2);
        long pending = meta[1] - meta[0];
        if (pending <= 0) return false;
        long oldest = queueEntryTime(state.get(AssetPaymentsContract.settleQueueKey(vaultId, shard, meta[0]))
                .orElseThrow(() -> new IllegalStateException("settlement queue entry missing")));
        boolean due = pending >= batchCap || block.timestamp() - oldest >= policy.batchWaitMs();
        if (!due) return false;
        List<Hash32> ids = new ArrayList<>();
        long head = meta[0];
        PayoutConfirmation records = payouts(state);
        while (head < meta[1] && ids.size() < batchCap) {
            byte[] key = AssetPaymentsContract.settleQueueKey(vaultId, shard, head);
            Hash32 claimId = queueEntryClaim(state.get(key).orElseThrow(() ->
                    new IllegalStateException("settlement queue entry missing")));
            state.delete(key);
            head++;
            // A claim paid by a proof Exit while queued needs no co-signed batch.
            boolean reserved = records.claimStatus(state, claimId)
                    .filter(status -> status.status() == SettlementRecords.ClaimStatus.RESERVED).isPresent();
            if (reserved && !ids.contains(claimId)) ids.add(claimId);
        }
        state.put(metaKey, PaymentsRecords.encodeU64s(head, meta[1]));
        if (ids.isEmpty()) return true;
        long sequence = state.get(AssetPaymentsContract.settleBatchSequenceKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L);
        SettlementBatchV1 batch = new SettlementBatchV1(vaultId, shard, ids);
        effects.emit(EffectIntent.of(SettlementBatchV1.EFFECT_TYPE, batch.encode())
                .scope(SettlementBatchV1.scope(sequence))
                .gate(FinalityGate.APP_FINAL)
                .result(ResultPolicy.CHAIN)
                .expiryBlocks(policy.expiryBlocks())
                .build());
        state.put(AssetPaymentsContract.settleBatchKey(sequence), batch.encode());
        state.put(AssetPaymentsContract.settleBatchSequenceKey(), PaymentsRecords.encodeU64s(sequence + 1));
        diagnostics.count("batches.emitted");
        return true;
    }

    /**
     * Closes one batch on any result, {@code CONFIRMED}, {@code FAILED} or {@code EXPIRED}: it drops the batch record
     * and re-queues every claim of the batch that is still {@code RESERVED} in committed state, so it is batched again
     * (D7 step 4). A confirmed result is one member's attestation and may come from a Settle that rolled back, or
     * have paid only part of the batch; only the payout observer moves a claim to {@code PAID} (D10, I12), and only
     * that takes it out of settlement.
     */
    @Override
    public void onEffectResult(AppBlockExecutionContext context, EffectResult result, AppStateWriter writer,
                               AppEffectEmitter effects) {
        if (!SettlementBatchV1.EFFECT_TYPE.equals(result.type())) return;
        long sequence = SettlementBatchV1.sequenceOf(result.scope());
        if (sequence < 0) return;
        diagnostics.begin(context.block().height());
        AssetAccounts.Store state = StagedStore.over(writer);
        StagedStore staged = new StagedStore(state);
        try {
            byte[] key = AssetPaymentsContract.settleBatchKey(sequence);
            Optional<byte[]> record = staged.get(key);
            if (record.isEmpty()) return;
            staged.delete(key);
            if (batching) {
                SettlementBatchV1 batch = SettlementBatchV1.decode(record.get());
                PayoutConfirmation records = payouts(staged);
                for (Hash32 claimId : batch.claimIds()) {
                    boolean reserved = records.claimStatus(staged, claimId)
                            .filter(status -> status.status() == SettlementRecords.ClaimStatus.RESERVED).isPresent();
                    if (reserved) enqueue(staged, batch.vaultId(), claimId, context.block().timestamp());
                }
            }
            staged.commit();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; nothing is written.
            dropped("effect-result", context.block().height(), unexpected);
            return;
        }
        observe("effect-result", () -> diagnostics.count("batches.closed." + result.outcome().name()));
    }

    private static byte[] queueEntry(Hash32 claimId, long queuedAtMs) {
        Array array = new Array();
        array.add(AssetCbor.bstr(claimId.bytes()));
        array.add(AssetCbor.uint(queuedAtMs));
        return AssetCbor.encode(array);
    }

    private static Hash32 queueEntryClaim(byte[] entry) {
        return Hash32.of(AssetCbor.bytes(AssetCbor.decodeArray(entry, 2).getDataItems().getFirst(), 32, 32));
    }

    private static long queueEntryTime(byte[] entry) {
        List<DataItem> items = AssetCbor.decodeArray(entry, 2).getDataItems();
        return AssetCbor.uintLong(items.get(1));
    }

    // ------------------------------------------------------------------ committed queries (D9)

    /**
     * The {@link SettlementQueries} the settlement executor, the deposit acceptor, the exit relayer and the
     * reconciliation (D15) read, and the governance status the members' governance route reads
     * ({@link PaymentsGovernanceV1#STATUS_QUERY}).
     */
    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        AssetAccounts.Store store = StagedStore.readOnly(state);
        PayoutConfirmation records = payouts(store);
        byte[] request = params == null ? new byte[0] : params;
        try {
            return switch (path) {
                case SettlementQueries.CLAIM -> {
                    Hash32 claimId = Hash32.of(request);
                    yield SettlementQueries.encodeClaim(store.get(records.claimLeafKey(claimId)),
                            store.get(records.claimStatusKey(claimId)));
                }
                case SettlementQueries.DOMAIN_STATUS -> store.get(records.domainStatusKey()).orElse(new byte[0]);
                case SettlementQueries.CLAIM_COUNT -> SettlementQueries.encodeCount(
                        store.get(AssetPaymentsContract.claimSequenceKey())
                                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L));
                case SettlementQueries.CLAIM_BY_SEQUENCE -> store.get(AssetPaymentsContract.claimIndexKey(
                        SettlementQueries.decodeSequence(request))).orElse(new byte[0]);
                case SettlementQueries.DEPOSIT -> store.get(depositRecordKey(Hash32.of(request)))
                        .orElse(new byte[0]);
                case SettlementQueries.REGISTRY -> store.get(AssetPaymentsContract.registryKey(Hash32.of(request)))
                        .orElse(new byte[0]);
                // ADR-061 D14: a CIP-113 asset's hold, which members read before they co-sign a Settle.
                case SettlementQueries.VAULT_HOLD -> registry(store, Hash32.of(request))
                        .filter(entry -> entry.custody().isPresent())
                        .map(entry -> SettlementQueries.encodeHold(entry.status().held())).orElse(new byte[0]);
                // ADR-061 D10 rule 6: the approved tuple of a dispatcher, which builders and members use.
                case SettlementQueries.CIP113_WIRING -> settings.cip113ParamsPolicy()
                        .flatMap(policy -> wiring(policy).approvedForDispatcher(store, request))
                        .map(SettlementQueries::encodeWiring).orElse(new byte[0]);
                case SettlementQueries.VAULT_OUTPUT -> settings.cip113ParamsPolicy().flatMap(policy -> store.get(
                        cip113OutputKey(request))).orElse(new byte[0]);
                case SettlementQueries.VAULT_COUNTERS -> {
                    Hash32 vaultId = Hash32.of(request);
                    if (registry(store, vaultId).isEmpty()) {
                        throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST, "unknown vault");
                    }
                    yield SettlementQueries.encodeCounters(state.committedHeight(),
                            new AssetAccounts(AssetPaymentsContract.PREFIX, store).counters(vaultId));
                }
                case PaymentsGovernanceV1.STATUS_QUERY -> new PaymentsGovernanceV1.Status(
                        PaymentsGovernance.sequence(store),
                        store.get(AssetPaymentsContract.governanceProposalsKey())
                                .map(PaymentsGovernanceV1::decodeProposals).orElse(List.of())).encode();
                // The read-only views of the payments domain API (#109 §7) live in their own class.
                default -> views.query(path, request, store).orElseThrow(() -> new AppQueryException(
                        AppQueryException.Code.UNSUPPORTED, "unknown asset-payments query " + path));
            };
        } catch (AppQueryException unsupported) {
            throw unsupported;
        } catch (RuntimeException invalid) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST, "invalid " + path + " request");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The deposit record's key, as {@link DepositAdmission} writes it ({@code ap/deposit/<DepositId>}). */
    private byte[] depositRecordKey(Hash32 depositId) {
        return new DepositAdmission(AssetPaymentsContract.PREFIX, settings.depositObserverId(),
                vaultId -> Optional.empty()).recordKey(depositId);
    }

    private void markUsed(AssetAccounts.Store state, Hash32 intentId, long notAfterMs) {
        state.put(AssetPaymentsContract.usedKey(intentId), PaymentsRecords.encodeU64s(notAfterMs));
        long[] meta = state.get(AssetPaymentsContract.usedQueueMetaKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 2)).orElse(new long[] {0, 0});
        state.put(AssetPaymentsContract.usedQueueKey(meta[1]),
                new PaymentsRecords.QueuedIntent(intentId, notAfterMs).encode());
        state.put(AssetPaymentsContract.usedQueueMetaKey(), PaymentsRecords.encodeU64s(meta[0], meta[1] + 1));
    }

    private void deleteGrant(AssetAccounts.Store state, SessionGrantV1 grant) {
        state.delete(AssetPaymentsContract.grantKey(grant.grantId()));
        for (SessionGrantV1.Limit limit : grant.limits()) {
            state.delete(AssetPaymentsContract.usageKey(grant.grantId(), limit.vaultId()));
        }
    }

    private static Optional<AccountRecordV1> account(AssetAccounts.Store state, byte[] accountId) {
        return state.get(AssetPaymentsContract.accountKey(accountId)).map(AccountRecordV1::decode);
    }

    private static Optional<RegistryEntryV1> registry(AssetAccounts.Store state, Hash32 vaultId) {
        return state.get(AssetPaymentsContract.registryKey(vaultId)).map(RegistryEntryV1::decode);
    }
}
