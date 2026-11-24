package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AccountRecordV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.ReceiptV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.RegistryEntryV1;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppChainInfo;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateReader;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.SequencedL1Observation;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.CoseSign1Verifier;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthException;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The #109 multi-asset payment ledger, milestone M3 (ADR-059 D14): a genesis asset registry,
 * accounts owned by Cardano wallet credentials, session grants, transfers, owner rotation, and
 * deposit credit from finalized L1 observations. Withdrawals, settlement and governed registry
 * changes come in later milestones.
 *
 * <p><b>Inputs.</b> User intents are the bodies of ordinary messages on
 * {@link AssetPaymentsContract#INTENT_TOPIC} ({@link PaymentsMessageV1}). Deposits are credited
 * only from {@code ~l1/<deposit-observer-id>} observations in a finalized block, through
 * {@link DepositAdmission} and {@link AssetAccounts} in the same transition (D6, I1). No command
 * creates value (I3).
 *
 * <p><b>Determinism.</b> The only clock is the block timestamp. Every message runs against a
 * {@link StagedStore} that is committed only on success, so a rejection leaves no partial writes,
 * and nothing that a user or L1 sends can make {@code apply} throw (I2). An intent that does not
 * authenticate, has expired, or was already used writes nothing; an authenticated, unused intent
 * writes {@code used/<IntentId>} and exactly one receipt, accepted or rejected (D14).
 */
final class AssetPaymentsStateMachine implements AppStateMachine {
    private final AssetPaymentsSettings settings;
    private final WalletAuthorizer authorizer;
    private final long maxForwardSkewMs;
    private final byte[] domainRecord;

    AssetPaymentsStateMachine(AssetPaymentsSettings settings, long maxForwardSkewMs) {
        if (settings.maxIntentTtlMs() <= maxForwardSkewMs) {
            throw new IllegalArgumentException("max-intent-ttl-ms must exceed the chain's maximum forward clock skew");
        }
        this.settings = settings;
        this.maxForwardSkewMs = maxForwardSkewMs;
        this.authorizer = new WalletAuthorizer(settings.intentDomain(), settings.maxIntentTtlMs());
        this.domainRecord = settings.domainRecord().encode();
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
        if (settings.depositTopic().equals(message.getTopic())) return AdmissionResult.accept();
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
            if (!AssetPaymentsContract.M3_ACTIONS.contains(envelope.action())) {
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

    // ------------------------------------------------------------------ transition

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        AppBlock block = context.block();
        AssetAccounts.Store state = StagedStore.over(writer);
        ensureGenesis(state);
        pruneUsedIntents(state, block.timestamp());
        List<AppMessage> messages = context.messages();
        for (int visible = 0; visible < messages.size(); visible++) {
            AppMessage message = messages.get(visible);
            String topic = message.getTopic();
            if (settings.depositTopic().equals(topic)) {
                context.l1ObservationAt(context.originalMessageIndex(visible))
                        .map(SequencedL1Observation::observation)
                        .ifPresent(observation -> creditDeposit(state, observation, block.height()));
            } else if (AssetPaymentsContract.INTENT_TOPIC.equals(topic)) {
                processIntent(state, message.getBody(), block);
            }
            // Every other topic (framework '~' topics included) is not this ledger's.
        }
    }

    private void ensureGenesis(AssetAccounts.Store state) {
        if (state.get(AssetPaymentsContract.domainKey()).isPresent()) return;
        state.put(AssetPaymentsContract.domainKey(), domainRecord);
        for (RegistryEntryV1 vault : settings.vaults()) {
            state.put(AssetPaymentsContract.registryKey(vault.vaultId()), vault.encode());
        }
    }

    /** Deletes up to {@link AssetPaymentsContract#MAX_PRUNE_PER_BLOCK} expired single-use markers, oldest first. */
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

    // ------------------------------------------------------------------ deposits (D6)

    private void creditDeposit(AssetAccounts.Store state, L1Observation observation, long height) {
        StagedStore staged = new StagedStore(state);
        try {
            byte[] anchor = observation.anchor() instanceof L1Observation.TransactionAnchor tx
                    ? tx.transactionHash() : null;
            long slot = observation.slot();
            DepositAdmission admission = new DepositAdmission(AssetPaymentsContract.PREFIX,
                    settings.depositObserverId(), vaultId -> registry(staged, vaultId).map(entry ->
                    new DepositAdmission.VaultBinding(entry.vaultId(), entry.asset(),
                            entry.status().depositsEnabled() && slot >= entry.activeFromSlot())));
            AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, staged);
            DepositAdmission.Outcome outcome = admission.admit(staged, new DepositAdmission.ObservedDeposit(
                    observation.observerId(), anchor, observation.eventOrdinal(), slot, observation.blockHash(),
                    observation.claim()), height, candidate -> refuseCredit(staged, accounts, candidate));
            outcome.credit().ifPresent(credit -> accounts.credit(credit.vaultId(), credit.beneficiary(),
                    account(staged, credit.beneficiary()).orElseThrow().keyHash(), credit.amount(),
                    credit.minAdaCredit()));
            staged.commit();
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; the staged writes are dropped and the chain continues (I2).
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
        try {
            WalletAuthorizer.Authorized authorized;
            try {
                authorized = authorize(PaymentsMessageV1.decode(body), staged, block.timestamp(), true);
            } catch (RuntimeException unauthenticated) {
                return; // unauthenticated, expired or malformed: nothing is written (D14)
            }
            Hash32 intentId = authorized.intentId();
            if (staged.get(AssetPaymentsContract.usedKey(intentId)).isPresent()
                    || staged.get(AssetPaymentsContract.receiptKey(intentId)).isPresent()) {
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
        } catch (RuntimeException unexpected) {
            // Deterministic on every member; nothing of this intent is written and the chain continues.
        }
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
        if (!AssetPaymentsContract.M3_ACTIONS.contains(authorized.envelope().action())) {
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
        AccountRecordV1 account = account(state, accountId).orElseThrow();
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

    // ------------------------------------------------------------------ helpers

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
