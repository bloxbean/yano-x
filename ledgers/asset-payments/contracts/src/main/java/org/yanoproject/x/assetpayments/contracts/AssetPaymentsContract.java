package org.yanoproject.x.assetpayments.contracts;

import org.yanoproject.x.assets.contracts.Hash32;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Identity, topics, actions and state layout of the {@code asset-payments} ledger (ADR-059 D14).
 *
 * <p>Every key lives under {@link #PREFIX}. The balance leaves and per-vault counters are
 * {@code AssetAccounts}' ({@code ap/x/…}, {@code ap/c/…}) and the deposit records are
 * {@code DepositAdmission}'s ({@code ap/deposit/<DepositId>}); the rest are this ledger's:
 *
 * <pre>
 * ap/domain                      DomainRecordV1      genesis domain and settings digest
 * ap/registry/&lt;VaultId&gt;          RegistryEntryV1     genesis asset registry, one vault per asset
 * ap/account/&lt;AccountId&gt;         AccountRecordV1     owner credential and active grant ids
 * ap/grant/&lt;GrantId&gt;             SessionGrantV1      registered, unrevoked grants
 * ap/usage/&lt;GrantId&gt;&lt;VaultId&gt;    u64                 cumulative session spend
 * ap/used/&lt;IntentId&gt;             [not_after_ms]      single-use marker, pruned after expiry
 * ap/used-queue/&lt;u64 be&gt;         [intent_id, not_after_ms]
 * ap/used-queue-meta             [head, tail]
 * ap/receipt/&lt;IntentId&gt;          ReceiptV1           write-once, kept
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
    /** M3 actions; withdrawal and governed asset commands come in later milestones. */
    public static final Set<String> M3_ACTIONS = Set.of("REGISTER_ACCOUNT", "AUTHORIZE_SESSION",
            "REVOKE_SESSION", "ROTATE_OWNER", TRANSFER);
    /** The only action a session grant may carry in M3. */
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
        byte[] index = new byte[8];
        for (int i = 0; i < 8; i++) index[i] = (byte) (sequence >>> (56 - 8 * i));
        return concat(PREFIX, ascii("used-queue/"), index);
    }

    public static byte[] usedQueueMetaKey() {
        return concat(PREFIX, ascii("used-queue-meta"));
    }

    public static byte[] receiptKey(Hash32 intentId) {
        return concat(PREFIX, ascii("receipt/"), intentId.bytes());
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
