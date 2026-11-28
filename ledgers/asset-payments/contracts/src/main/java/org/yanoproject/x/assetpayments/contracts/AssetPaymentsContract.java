package org.yanoproject.x.assetpayments.contracts;

import org.yanoproject.x.assets.contracts.Hash32;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Identity, topics, actions and state layout of the {@code asset-payments} ledger (ADR-059 D14).
 *
 * <p>Every key lives under {@link #PREFIX}. The balance leaves and per-vault counters are
 * {@code AssetAccounts}' ({@code ap/x/…}, {@code ap/c/…}), the deposit records are
 * {@code DepositAdmission}'s ({@code ap/deposit/<DepositId>}), and the claim leaves, claim status,
 * payout records, escape redemptions and the domain status leaf are {@code PayoutConfirmation}'s
 * ({@code ap/claim/…}, {@code ap/claim-status/…}, {@code ap/payout/…}, {@code ap/escape/…},
 * {@code ap/domain-status}); the rest are this ledger's:
 *
 * <pre>
 * ap/domain                      DomainRecordV1      genesis domain and settings digest
 * ap/registry/&lt;VaultId&gt;          RegistryEntryV1     asset registry, one vault per asset; genesis set,
 *                                                    status changed only by governance (PaymentsGovernanceV1)
 * ap/account/&lt;AccountId&gt;         AccountRecordV1     owner credential and active grant ids
 * ap/grant/&lt;GrantId&gt;             SessionGrantV1      registered, unrevoked grants
 * ap/usage/&lt;GrantId&gt;&lt;VaultId&gt;    u64                 cumulative session spend
 * ap/used/&lt;IntentId&gt;             [not_after_ms]      single-use marker, pruned after expiry
 * ap/used-queue/&lt;u64 be&gt;         [intent_id, not_after_ms]
 * ap/used-queue-meta             [head, tail]
 * ap/receipt/&lt;IntentId&gt;          ReceiptV1           write-once, kept
 * ap/claim-seq                   [next]              the next claim sequence (M4)
 * ap/claim-index/&lt;u64 be&gt;        claim_id            dense index of every reserved claim, from 0
 * ap/settle-queue/&lt;VaultId&gt;&lt;shard&gt;&lt;u64 be&gt;   [claim_id, queued_at_ms]   unbatched RESERVED claims
 * ap/settle-queue-meta/&lt;VaultId&gt;&lt;shard&gt;      [head, tail]
 * ap/settle-batch-seq            [next]              the next settlement batch sequence
 * ap/settle-batch/&lt;u64 be&gt;       SettlementBatchV1   an open asset.settlement.batch effect
 * ap/gov-seq                     [next]              governance commands decided so far (PaymentsGovernanceV1)
 * ap/gov-proposals               [* Proposal]        open governance proposals, at most one per proposing member
 * ap/gov-history/&lt;u64 be&gt;       DecisionV1          one write-once record per decided governance command
 * ap/wind-down/&lt;VaultId&gt;         [height, block_ms]  when the vault last entered WIND_DOWN; present only while
 *                                                    it is in WIND_DOWN (RETIRED waits out the window from here)
 * </pre>
 *
 * <p>The used-intent queue is FIFO by insertion. Because every accepted intent expires within
 * {@code max-intent-ttl-ms} of its block, a marker becomes prunable at most
 * {@code max-intent-ttl-ms} plus the forward clock skew after the block that wrote it, even when an
 * older marker that expires later sits ahead of it. Expired session grants are dropped from an
 * account (with their usage counters) when the owner authorizes a new session, so they never hold
 * one of the {@link #MAX_GRANTS_PER_ACCOUNT} slots.
 */
public final class AssetPaymentsContract {
    public static final String STATE_MACHINE_ID = "asset-payments";
    /** User intents arrive as the body of an ordinary member-signed message on this topic (D13). */
    public static final String INTENT_TOPIC = "asset-payments.intents";

    public static final String TRANSFER = "TRANSFER";
    /** Owner only (D14): checks feasibility, reserves and writes the claim (D7). */
    public static final String REQUEST_WITHDRAWAL = "REQUEST_WITHDRAWAL";
    /** The user actions this ledger executes; governed commands ride {@link PaymentsGovernanceV1#TOPIC} instead. */
    public static final Set<String> ACTIONS = Set.of("REGISTER_ACCOUNT", "AUTHORIZE_SESSION",
            "REVOKE_SESSION", "ROTATE_OWNER", TRANSFER, REQUEST_WITHDRAWAL);
    /** The only action a session grant may carry: a session never withdraws (I11). */
    public static final Set<String> SESSION_ACTIONS = Set.of(TRANSFER);

    public static final int MAX_VAULTS = 16;
    public static final int MAX_GRANTS_PER_ACCOUNT = 16;
    /** Bound on expired {@code used/} entries pruned per block (D14). */
    public static final int MAX_PRUNE_PER_BLOCK = 64;

    public static final byte[] PREFIX = ascii("ap/");

    private AssetPaymentsContract() {
    }

    public static byte[] domainKey() {
        return concat(PREFIX, ascii("domain"));
    }

    public static byte[] registryKey(Hash32 vaultId) {
        return concat(PREFIX, ascii("registry/"), vaultId.bytes());
    }

    public static byte[] accountKey(byte[] accountId) {
        return concat(PREFIX, ascii("account/"), requireAccount(accountId));
    }

    public static byte[] grantKey(Hash32 grantId) {
        return concat(PREFIX, ascii("grant/"), grantId.bytes());
    }

    public static byte[] usageKey(Hash32 grantId, Hash32 vaultId) {
        return concat(PREFIX, ascii("usage/"), grantId.bytes(), vaultId.bytes());
    }

    public static byte[] usedKey(Hash32 intentId) {
        return concat(PREFIX, ascii("used/"), intentId.bytes());
    }

    public static byte[] usedQueueKey(long sequence) {
        return concat(PREFIX, ascii("used-queue/"), u64(sequence));
    }

    public static byte[] usedQueueMetaKey() {
        return concat(PREFIX, ascii("used-queue-meta"));
    }

    /** The domain status leaf (D8a): the vault script's pinned status key, written ACTIVE at genesis. */
    public static byte[] domainStatusKey() {
        return concat(PREFIX, ascii("domain-status"));
    }

    public static byte[] receiptKey(Hash32 intentId) {
        return concat(PREFIX, ascii("receipt/"), intentId.bytes());
    }

    /**
     * The claim an accepted {@code REQUEST_WITHDRAWAL} reserved: {@code intent-claim/<IntentId> -> ClaimId}. A
     * {@code ClaimId} binds a dense claim sequence, not the intent, so this is the only exact link from a receipt to
     * its claim when one account withdraws more than once in a block.
     */
    public static byte[] intentClaimKey(Hash32 intentId) {
        return concat(PREFIX, ascii("intent-claim/"), intentId.bytes());
    }

    public static byte[] claimSequenceKey() {
        return concat(PREFIX, ascii("claim-seq"));
    }

    public static byte[] claimIndexKey(long sequence) {
        return concat(PREFIX, ascii("claim-index/"), u64(sequence));
    }

    public static byte[] settleQueueKey(Hash32 vaultId, int shard, long sequence) {
        return concat(PREFIX, ascii("settle-queue/"), vaultId.bytes(), new byte[] {(byte) shard}, u64(sequence));
    }

    public static byte[] settleQueueMetaKey(Hash32 vaultId, int shard) {
        return concat(PREFIX, ascii("settle-queue-meta/"), vaultId.bytes(), new byte[] {(byte) shard});
    }

    public static byte[] settleBatchSequenceKey() {
        return concat(PREFIX, ascii("settle-batch-seq"));
    }

    public static byte[] settleBatchKey(long sequence) {
        return concat(PREFIX, ascii("settle-batch/"), u64(sequence));
    }

    public static byte[] governanceSequenceKey() {
        return concat(PREFIX, ascii("gov-seq"));
    }

    public static byte[] governanceProposalsKey() {
        return concat(PREFIX, ascii("gov-proposals"));
    }

    public static byte[] governanceHistoryKey(long sequence) {
        return concat(PREFIX, ascii("gov-history/"), u64(sequence));
    }

    /** When the vault entered {@code WIND_DOWN}: the deciding block's height and timestamp (D14 retirement). */
    public static byte[] windDownKey(Hash32 vaultId) {
        return concat(PREFIX, ascii("wind-down/"), vaultId.bytes());
    }

    static byte[] u64(long value) {
        byte[] index = new byte[8];
        for (int i = 0; i < 8; i++) index[i] = (byte) (value >>> (56 - 8 * i));
        return index;
    }

    static byte[] requireAccount(byte[] accountId) {
        if (accountId == null || accountId.length != 28) throw new IllegalArgumentException("account id must be 28 bytes");
        return accountId;
    }

    static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
