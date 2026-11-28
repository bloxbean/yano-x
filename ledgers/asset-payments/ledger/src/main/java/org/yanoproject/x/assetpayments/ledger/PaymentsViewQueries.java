package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AccountRecordV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.RegistryEntryV1;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.ClaimKind;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.assets.contracts.SettlementBatchV1;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;
import org.yanoproject.x.walletauth.SessionGrantV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only committed views behind the payments domain API (#109 §7, ADR-059 D9/D14). Every path reads one
 * root-fixed committed snapshot and writes nothing, so it cannot affect consensus; the domain API renders the
 * result as JSON together with the exact state keys a client fetches proofs for.
 *
 * <pre>
 * asset-payments/info       h''                       -> [domain_record, max_intent_ttl_ms, deposit_observer_id,
 *                                                           payout_observer_id / "", min_ada_payout,
 *                                                           payout_lovelace_cap, [* registry_entry],
 *                                                           domain_status / h'']
 * asset-payments/account    account_id(28)            -> [account_record / h'', [* [vault_id, exit_leaf / h'']],
 *                                                           [* [grant, [* [vault_id, used_u64]]]]]
 * asset-payments/receipt    intent_id(32)             -> [receipt / h'']
 * asset-payments/claim      claim_id(32)              -> [claim_leaf / h'', claim_status / h'', in_open_batch]
 * asset-payments/claims-of  [account_id, before, limit] -> [[* [sequence, claim_leaf, claim_status, in_open_batch]],
 *                                                           next_before, scanned]
 * </pre>
 *
 * <p>{@code claims-of} finds an account's withdrawal claims by recomputing each claim's {@code ClaimId} from its
 * leaf, the account and its dense sequence (the leaf names no account). It scans newest first from {@code before}
 * (exclusive; 0 means from the newest claim), at most {@link #MAX_CLAIM_SCAN} claims per call; {@code next_before}
 * is 0 when the scan reached the first claim. {@code in_open_batch} is 1 while the claim is part of a settlement
 * batch effect whose result has not been recorded yet (operational telemetry, not a consensus status), judged from
 * the last {@link #MAX_BATCH_SCAN} batch sequences.
 */
final class PaymentsViewQueries {
    static final String INFO = "asset-payments/info";
    static final String ACCOUNT = "asset-payments/account";
    static final String RECEIPT = "asset-payments/receipt";
    static final String CLAIM = "asset-payments/claim";
    static final String CLAIMS_OF = "asset-payments/claims-of";
    static final int MAX_CLAIM_SCAN = 2_048;
    static final int MAX_CLAIMS_PER_PAGE = 64;
    static final int MAX_BATCH_SCAN = 64;

    private final AssetPaymentsSettings settings;

    PaymentsViewQueries(AssetPaymentsSettings settings) {
        this.settings = settings;
    }

    /** The view for {@code path}, or empty when the path is not one of these views. */
    Optional<byte[]> query(String path, byte[] request, AssetAccounts.Store state) {
        return switch (path) {
            case INFO -> Optional.of(info(state));
            case ACCOUNT -> Optional.of(account(state, account(request)));
            case RECEIPT -> Optional.of(single(state.get(AssetPaymentsContract.receiptKey(Hash32.of(request)))));
            case CLAIM -> Optional.of(claim(state, Hash32.of(request)));
            case CLAIMS_OF -> Optional.of(claimsOf(state, request));
            default -> Optional.empty();
        };
    }

    // ------------------------------------------------------------------ views

    private byte[] info(AssetAccounts.Store state) {
        Array out = new Array();
        // The genesis record is written by the first block; before it, the settings are what genesis will write.
        out.add(AssetCbor.bstr(state.get(AssetPaymentsContract.domainKey())
                .orElseGet(() -> settings.domainRecord().encode())));
        out.add(AssetCbor.uint(settings.maxIntentTtlMs()));
        out.add(new UnicodeString(settings.depositObserverId()));
        out.add(new UnicodeString(settings.payoutObserverId().orElse("")));
        out.add(AssetCbor.uint(settings.minAdaPayout()));
        out.add(AssetCbor.uint(settings.payoutLovelaceCap()));
        Array vaults = new Array();
        for (RegistryEntryV1 vault : settings.vaults()) {
            vaults.add(AssetCbor.bstr(state.get(AssetPaymentsContract.registryKey(vault.vaultId()))
                    .orElseGet(vault::encode)));
        }
        out.add(vaults);
        out.add(AssetCbor.bstr(state.get(AssetPaymentsContract.domainStatusKey()).orElse(new byte[0])));
        return AssetCbor.encode(out);
    }

    private byte[] account(AssetAccounts.Store state, byte[] accountId) {
        Optional<byte[]> record = state.get(AssetPaymentsContract.accountKey(accountId));
        AssetAccounts accounts = new AssetAccounts(AssetPaymentsContract.PREFIX, state);
        Array out = new Array();
        out.add(AssetCbor.bstr(record.orElse(new byte[0])));
        Array leaves = new Array();
        for (RegistryEntryV1 vault : settings.vaults()) {
            Array entry = new Array();
            entry.add(AssetCbor.bstr(vault.vaultId().bytes()));
            entry.add(AssetCbor.bstr(state.get(accounts.exitLeafKey(vault.vaultId(), accountId)).orElse(new byte[0])));
            leaves.add(entry);
        }
        out.add(leaves);
        Array grants = new Array();
        if (record.isPresent()) {
            for (Hash32 grantId : AccountRecordV1.decode(record.get()).grants()) {
                Optional<byte[]> grant = state.get(AssetPaymentsContract.grantKey(grantId));
                if (grant.isEmpty()) continue;
                Array entry = new Array();
                entry.add(AssetCbor.bstr(grant.get()));
                Array usage = new Array();
                for (SessionGrantV1.Limit limit : SessionGrantV1.decode(grant.get()).limits()) {
                    Array used = new Array();
                    used.add(AssetCbor.bstr(limit.vaultId().bytes()));
                    used.add(AssetCbor.uint(state.get(AssetPaymentsContract.usageKey(grantId, limit.vaultId()))
                            .map(PaymentsRecords::decodeAmount).orElse(BigInteger.ZERO)));
                    usage.add(used);
                }
                entry.add(usage);
                grants.add(entry);
            }
        }
        out.add(grants);
        return AssetCbor.encode(out);
    }

    private byte[] claim(AssetAccounts.Store state, Hash32 claimId) {
        PayoutConfirmation layout = layout();
        Array out = new Array();
        out.add(AssetCbor.bstr(state.get(layout.claimLeafKey(claimId)).orElse(new byte[0])));
        out.add(AssetCbor.bstr(state.get(layout.claimStatusKey(claimId)).orElse(new byte[0])));
        out.add(AssetCbor.uint(openBatchClaims(state).contains(claimId) ? 1 : 0));
        return AssetCbor.encode(out);
    }

    private byte[] claimsOf(AssetAccounts.Store state, byte[] request) {
        List<DataItem> items = AssetCbor.decodeArray(request, 3).getDataItems();
        byte[] accountId = account(AssetCbor.bytes(items.get(0), 28, 28));
        long before = AssetCbor.uintLong(items.get(1));
        long limit = AssetCbor.uintLong(items.get(2));
        if (limit < 1 || limit > MAX_CLAIMS_PER_PAGE) throw new IllegalArgumentException("limit is 1..64");
        long count = state.get(AssetPaymentsContract.claimSequenceKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L);
        long start = before == 0 ? count : Math.min(before, count);
        PayoutConfirmation layout = layout();
        Set<Hash32> open = openBatchClaims(state);
        Array matches = new Array();
        long sequence = start;
        int scanned = 0;
        while (sequence > 0 && scanned < MAX_CLAIM_SCAN && matches.getDataItems().size() < limit) {
            sequence--;
            scanned++;
            Optional<byte[]> id = state.get(AssetPaymentsContract.claimIndexKey(sequence));
            if (id.isEmpty()) continue;
            Hash32 claimId = Hash32.of(id.get());
            Optional<byte[]> leafBytes = state.get(layout.claimLeafKey(claimId));
            Optional<byte[]> status = state.get(layout.claimStatusKey(claimId));
            if (leafBytes.isEmpty() || status.isEmpty()) continue;
            ClaimLeafV1 leaf = ClaimLeafV1.decode(leafBytes.get());
            if (leaf.kind() != ClaimKind.USER_WITHDRAWAL || !claimedBy(leaf, accountId, sequence)) continue;
            Array entry = new Array();
            entry.add(AssetCbor.uint(sequence));
            entry.add(AssetCbor.bstr(leafBytes.get()));
            entry.add(AssetCbor.bstr(status.get()));
            entry.add(AssetCbor.uint(open.contains(claimId) ? 1 : 0));
            matches.add(entry);
        }
        Array out = new Array();
        out.add(matches);
        out.add(AssetCbor.uint(sequence));
        out.add(AssetCbor.uint(scanned));
        return AssetCbor.encode(out);
    }

    // ------------------------------------------------------------------ helpers

    /** Whether {@code accountId} reserved this claim: its id binds the withdrawal key and the sequence (D7). */
    private boolean claimedBy(ClaimLeafV1 leaf, byte[] accountId, long sequence) {
        try {
            return GatewayIds.claimId(settings.domainId(), leaf.vaultId(), leaf.kind(), accountId, leaf.recipient(),
                    leaf.value(), leaf.vaultLovelace(), sequence).equals(leaf.claimId());
        } catch (IllegalArgumentException mismatch) {
            return false;
        }
    }

    /** The claims of the settlement batches whose result is still open, from the newest batch sequences. */
    private static Set<Hash32> openBatchClaims(AssetAccounts.Store state) {
        long next = state.get(AssetPaymentsContract.settleBatchSequenceKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L);
        Set<Hash32> claims = new HashSet<>();
        for (long sequence = next - 1; sequence >= 0 && sequence >= next - MAX_BATCH_SCAN; sequence--) {
            state.get(AssetPaymentsContract.settleBatchKey(sequence))
                    .map(SettlementBatchV1::decode)
                    .ifPresent(batch -> claims.addAll(batch.claimIds()));
        }
        return claims;
    }

    /** The claim records' key layout, exactly as the ledger writes them. */
    private PayoutConfirmation layout() {
        return new PayoutConfirmation(AssetPaymentsContract.PREFIX, settings.payoutObserverId().orElse("payouts"),
                settings.domainId(), settings.exitDelayMs(), vaultId -> Optional.empty());
    }

    private static byte[] single(Optional<byte[]> value) {
        Array out = new Array();
        out.add(AssetCbor.bstr(value.orElse(new byte[0])));
        return AssetCbor.encode(out);
    }

    private static byte[] account(byte[] request) {
        if (request == null || request.length != 28) throw new IllegalArgumentException("account id is 28 bytes");
        return request.clone();
    }

    // ------------------------------------------------------------------ request and response codecs

    static byte[] claimsOfRequest(byte[] accountId, long before, int limit) {
        Array array = new Array();
        array.add(AssetCbor.bstr(accountId));
        array.add(AssetCbor.uint(before));
        array.add(AssetCbor.uint(limit));
        return AssetCbor.encode(array);
    }

    record Info(byte[] domainRecord, long maxIntentTtlMs, String depositObserverId, String payoutObserverId,
                BigInteger minAdaPayout, BigInteger payoutLovelaceCap, List<RegistryEntryV1> vaults,
                Optional<byte[]> domainStatus) {
        static Info decode(byte[] payload) {
            List<DataItem> items = AssetCbor.decodeArray(payload, 8).getDataItems();
            List<RegistryEntryV1> vaults = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(6), 0, AssetPaymentsContract.MAX_VAULTS).getDataItems()) {
                vaults.add(RegistryEntryV1.decode(AssetCbor.bytes(item, 1, AssetCbor.MAX_WIRE_BYTES)));
            }
            byte[] status = AssetCbor.bytes(items.get(7), 0, AssetCbor.MAX_WIRE_BYTES);
            return new Info(AssetCbor.bytes(items.get(0), 1, AssetCbor.MAX_WIRE_BYTES),
                    AssetCbor.uintLong(items.get(1)), AssetCbor.text(items.get(2), 160),
                    AssetCbor.text(items.get(3), 160), AssetCbor.uint(items.get(4), Quantities.MAX_U64),
                    AssetCbor.uint(items.get(5), Quantities.MAX_U64), vaults,
                    status.length == 0 ? Optional.empty() : Optional.of(status));
        }
    }

    record Leaf(Hash32 vaultId, Optional<byte[]> exitLeaf) { }

    record Grant(SessionGrantV1 grant, byte[] encoded, List<Usage> usage) { }

    record Usage(Hash32 vaultId, BigInteger used) { }

    record Account(Optional<byte[]> record, List<Leaf> leaves, List<Grant> grants) {
        static Account decode(byte[] payload) {
            List<DataItem> items = AssetCbor.decodeArray(payload, 3).getDataItems();
            byte[] record = AssetCbor.bytes(items.get(0), 0, AssetCbor.MAX_WIRE_BYTES);
            List<Leaf> leaves = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(1), 0, AssetPaymentsContract.MAX_VAULTS).getDataItems()) {
                List<DataItem> pair = AssetCbor.array(item, 2, 2).getDataItems();
                byte[] leaf = AssetCbor.bytes(pair.get(1), 0, AssetCbor.MAX_WIRE_BYTES);
                leaves.add(new Leaf(Hash32.of(AssetCbor.bytes(pair.get(0), 32, 32)),
                        leaf.length == 0 ? Optional.empty() : Optional.of(leaf)));
            }
            List<Grant> grants = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(2), 0, AssetPaymentsContract.MAX_GRANTS_PER_ACCOUNT)
                    .getDataItems()) {
                List<DataItem> pair = AssetCbor.array(item, 2, 2).getDataItems();
                byte[] encoded = AssetCbor.bytes(pair.get(0), 1, AssetCbor.MAX_WIRE_BYTES);
                List<Usage> usage = new ArrayList<>();
                for (DataItem used : AssetCbor.array(pair.get(1), 0, 16).getDataItems()) {
                    List<DataItem> entry = AssetCbor.array(used, 2, 2).getDataItems();
                    usage.add(new Usage(Hash32.of(AssetCbor.bytes(entry.get(0), 32, 32)),
                            AssetCbor.uint(entry.get(1), Quantities.MAX_U64)));
                }
                grants.add(new Grant(SessionGrantV1.decode(encoded), encoded, usage));
            }
            return new Account(record.length == 0 ? Optional.empty() : Optional.of(record), leaves, grants);
        }
    }

    /** One claim: its immutable leaf and status records, exactly as stored, and the open-batch flag. */
    record Claim(long sequence, byte[] leaf, byte[] status, boolean inOpenBatch) {
        static Optional<Claim> decodeSingle(byte[] payload) {
            List<DataItem> items = AssetCbor.decodeArray(payload, 3).getDataItems();
            byte[] leaf = AssetCbor.bytes(items.get(0), 0, AssetCbor.MAX_WIRE_BYTES);
            byte[] status = AssetCbor.bytes(items.get(1), 0, AssetCbor.MAX_WIRE_BYTES);
            if (leaf.length == 0 || status.length == 0) return Optional.empty();
            return Optional.of(new Claim(-1, leaf, status, AssetCbor.uintLong(items.get(2)) == 1));
        }
    }

    record ClaimsPage(List<Claim> claims, long nextBefore, long scanned) {
        static ClaimsPage decode(byte[] payload) {
            List<DataItem> items = AssetCbor.decodeArray(payload, 3).getDataItems();
            List<Claim> claims = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(0), 0, MAX_CLAIMS_PER_PAGE).getDataItems()) {
                List<DataItem> entry = AssetCbor.array(item, 4, 4).getDataItems();
                claims.add(new Claim(AssetCbor.uintLong(entry.get(0)),
                        AssetCbor.bytes(entry.get(1), 1, AssetCbor.MAX_WIRE_BYTES),
                        AssetCbor.bytes(entry.get(2), 1, AssetCbor.MAX_WIRE_BYTES),
                        AssetCbor.uintLong(entry.get(3)) == 1));
            }
            return new ClaimsPage(claims, AssetCbor.uintLong(items.get(1)), AssetCbor.uintLong(items.get(2)));
        }
    }

    static Optional<byte[]> decodeSingle(byte[] payload) {
        byte[] value = AssetCbor.bytes(AssetCbor.decodeArray(payload, 1).getDataItems().getFirst(), 0,
                AssetCbor.MAX_WIRE_BYTES);
        return value.length == 0 ? Optional.empty() : Optional.of(value);
    }
}
