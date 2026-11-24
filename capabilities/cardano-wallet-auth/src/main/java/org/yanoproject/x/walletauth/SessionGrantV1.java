package org.yanoproject.x.walletauth;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An owner-authorized session (ADR-059 D13 §4). {@code grantId} is the {@code IntentId} of the
 * authorizing {@code AUTHORIZE_SESSION} intent, so re-granting identical permissions still yields
 * a new id. By default a grant never covers withdrawal or further delegation; the owner must list
 * those actions explicitly.
 *
 * <p>Wire form: {@code [1, grant_id, account_id(28), session_key(32), [* action], [* limit],
 * recipients, not_after_ms]}, where {@code limit = [vault_id, per_payment, cumulative]} and
 * {@code recipients} is {@code []} for "any" or a list of raw address bytes.
 */
public record SessionGrantV1(Hash32 grantId, byte[] accountId, byte[] sessionKey, List<String> actions,
                             List<Limit> limits, List<byte[]> recipients, long notAfterMs) {
    public static final int VERSION = 1;
    private static final int MAX_LIST = 16;

    /** Per-asset limits for one vault. A vault with no limit entry is not allowed at all. */
    public record Limit(Hash32 vaultId, BigInteger perPayment, BigInteger cumulative) {
        public Limit {
            Objects.requireNonNull(vaultId, "vault id");
            Quantities.requirePositiveU64(perPayment, "per-payment limit");
            Quantities.requirePositiveU64(cumulative, "cumulative limit");
            if (perPayment.compareTo(cumulative) > 0) {
                throw new IllegalArgumentException("per-payment limit exceeds the cumulative limit");
            }
        }
    }

    public SessionGrantV1 {
        Objects.requireNonNull(grantId, "grant id");
        if (accountId == null || accountId.length != IntentEnvelopeV1.ACCOUNT_ID_BYTES) {
            throw new IllegalArgumentException("account id must be 28 bytes");
        }
        if (sessionKey == null || sessionKey.length != 32) {
            throw new IllegalArgumentException("session key must be 32 bytes");
        }
        actions = List.copyOf(actions);
        limits = List.copyOf(limits);
        if (actions.isEmpty() || actions.size() > MAX_LIST || limits.size() > MAX_LIST
                || recipients.size() > MAX_LIST) {
            throw new IllegalArgumentException("grant lists are bounded to 16 entries and need an action");
        }
        List<byte[]> copied = new ArrayList<>();
        for (byte[] recipient : recipients) copied.add(recipient.clone());
        recipients = List.copyOf(copied);
        accountId = accountId.clone();
        sessionKey = sessionKey.clone();
    }

    public boolean allows(String action) {
        return actions.contains(action);
    }

    public Optional<Limit> limitFor(Hash32 vaultId) {
        return limits.stream().filter(limit -> limit.vaultId().equals(vaultId)).findFirst();
    }

    public boolean recipientAllowed(byte[] recipient) {
        return recipients.isEmpty() || recipients.stream().anyMatch(r -> Arrays.equals(r, recipient));
    }

    /**
     * Checks one payment against the grant. {@code usedSoFar} is the cumulative amount already
     * spent under this grant in this vault, read through the staged writer so payments in the same
     * block count (I11). Throws {@link WalletAuthException} when any limit would be exceeded.
     */
    public void checkPayment(String action, Hash32 vaultId, BigInteger amount, BigInteger usedSoFar,
                             byte[] recipient, long blockTimeMs) {
        if (blockTimeMs > notAfterMs) throw new WalletAuthException("session expired");
        if (!allows(action)) throw new WalletAuthException("action not granted to the session");
        Limit limit = limitFor(vaultId).orElseThrow(() -> new WalletAuthException("asset not granted"));
        if (amount.compareTo(limit.perPayment()) > 0) throw new WalletAuthException("per-payment limit exceeded");
        if (usedSoFar.add(amount).compareTo(limit.cumulative()) > 0) {
            throw new WalletAuthException("cumulative limit exceeded");
        }
        if (!recipientAllowed(recipient)) throw new WalletAuthException("recipient not allowed");
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(grantId.bytes()));
        array.add(AssetCbor.bstr(accountId));
        array.add(AssetCbor.bstr(sessionKey));
        Array actionList = new Array();
        actions.forEach(action -> actionList.add(new UnicodeString(action)));
        array.add(actionList);
        Array limitList = new Array();
        for (Limit limit : limits) {
            Array entry = new Array();
            entry.add(AssetCbor.bstr(limit.vaultId().bytes()));
            entry.add(AssetCbor.uint(limit.perPayment()));
            entry.add(AssetCbor.uint(limit.cumulative()));
            limitList.add(entry);
        }
        array.add(limitList);
        Array recipientList = new Array();
        recipients.forEach(recipient -> recipientList.add(AssetCbor.bstr(recipient)));
        array.add(recipientList);
        array.add(AssetCbor.uint(notAfterMs));
        return AssetCbor.encode(array);
    }

    public static SessionGrantV1 decode(byte[] bytes) {
        try {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 8).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != VERSION) throw new WalletAuthException("grant version");
            List<String> actions = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(4), 1, MAX_LIST).getDataItems()) {
                actions.add(AssetCbor.text(item, 64));
            }
            List<Limit> limits = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(5), 0, MAX_LIST).getDataItems()) {
                List<DataItem> entry = AssetCbor.array(item, 3, 3).getDataItems();
                limits.add(new Limit(Hash32.of(AssetCbor.bytes(entry.get(0), 32, 32)),
                        AssetCbor.uint(entry.get(1), Quantities.MAX_U64),
                        AssetCbor.uint(entry.get(2), Quantities.MAX_U64)));
            }
            List<byte[]> recipients = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(6), 0, MAX_LIST).getDataItems()) {
                recipients.add(AssetCbor.bytes(item, 1, 128));
            }
            return new SessionGrantV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                    AssetCbor.bytes(items.get(2), 28, 28), AssetCbor.bytes(items.get(3), 32, 32),
                    actions, limits, recipients, AssetCbor.uintLong(items.get(7)));
        } catch (IllegalArgumentException invalid) {
            throw new WalletAuthException("invalid session grant: " + invalid.getMessage());
        }
    }

    @Override
    public byte[] accountId() {
        return accountId.clone();
    }

    @Override
    public byte[] sessionKey() {
        return sessionKey.clone();
    }
}
