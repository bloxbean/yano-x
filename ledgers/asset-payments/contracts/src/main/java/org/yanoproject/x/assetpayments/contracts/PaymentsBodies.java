package org.yanoproject.x.assetpayments.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;
import org.yanoproject.x.walletauth.SessionGrantV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical action bodies carried in {@code IntentEnvelopeV1.body} (ADR-059 D13 §3, D14). Each is
 * one array in the strict contract subset; the envelope signature covers these exact bytes.
 *
 * <pre>
 * register_account_body  = []
 * transfer_body          = [asset: [policy_id, asset_name], to_account: bytes .size 28, amount: positive_u64]
 * authorize_session_body = [session_key: bytes .size 32, [+ action: tstr], [* [vault_id, per_payment, cumulative]],
 *                           [* recipient_account: bytes .size 28], not_after_ms: uint]
 * revoke_session_body    = [grant_id: hash32]
 * request_withdrawal_body = [vault_id: hash32, recipient: bytes .size (1..128), amount: u64, vault_lovelace: u64]
 * rotate_owner_body      = [credential_kind: 0 / 1, key_hash: bytes .size 28]   ; WalletAuthorizer.rotationBody
 * </pre>
 */
public final class PaymentsBodies {
    /** The {@code REGISTER_ACCOUNT} body: an empty array. */
    public static final byte[] REGISTER_ACCOUNT = {(byte) 0x80};
    private static final int MAX_LIST = 16;

    private PaymentsBodies() {
    }

    public static boolean isEmpty(byte[] body) {
        return Arrays.equals(body, REGISTER_ACCOUNT);
    }

    /** One asset, one recipient account, one positive amount. */
    public record Transfer(AssetId asset, byte[] toAccount, BigInteger amount) {
        public Transfer {
            Objects.requireNonNull(asset, "asset");
            AssetPaymentsContract.requireAccount(toAccount);
            Quantities.requirePositiveU64(amount, "amount");
            toAccount = toAccount.clone();
        }

        @Override
        public byte[] toAccount() {
            return toAccount.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Transfer that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }

        public byte[] encode() {
            Array array = new Array();
            Array assetItem = new Array();
            assetItem.add(AssetCbor.bstr(asset.policyId()));
            assetItem.add(AssetCbor.bstr(asset.assetName()));
            array.add(assetItem);
            array.add(AssetCbor.bstr(toAccount));
            array.add(AssetCbor.uint(amount));
            return AssetCbor.encode(array);
        }

        public static Transfer decode(byte[] body) {
            List<DataItem> items = AssetCbor.decodeArray(body, 3).getDataItems();
            try {
                return new Transfer(PaymentsBodies.asset(items.get(0)), AssetCbor.bytes(items.get(1), 28, 28),
                        AssetCbor.uint(items.get(2), Quantities.MAX_U64));
            } catch (ArithmeticException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }
    }

    /**
     * The permissions of a new session grant. The grant id is the authorizing intent's id, assigned
     * by the ledger, so it is not in the body. Recipients are 28-byte account ids.
     */
    public record AuthorizeSession(byte[] sessionKey, List<String> actions, List<SessionGrantV1.Limit> limits,
                                   List<byte[]> recipients, long notAfterMs) {
        public AuthorizeSession {
            if (sessionKey == null || sessionKey.length != 32) throw new IllegalArgumentException("session key is 32 bytes");
            actions = List.copyOf(actions);
            limits = List.copyOf(limits);
            if (actions.isEmpty() || actions.size() > MAX_LIST || limits.size() > MAX_LIST
                    || recipients.size() > MAX_LIST || notAfterMs < 0) {
                throw new IllegalArgumentException("session grant lists are bounded and need an action");
            }
            // The grant built from this body rejects duplicates too; rejecting them here makes such a
            // body BAD_BODY at decode time instead of a later failure.
            if (new HashSet<>(actions).size() != actions.size()) {
                throw new IllegalArgumentException("duplicate session action");
            }
            Set<Hash32> vaults = new HashSet<>();
            for (SessionGrantV1.Limit limit : limits) {
                if (!vaults.add(limit.vaultId())) throw new IllegalArgumentException("duplicate limit for a vault");
            }
            List<byte[]> copied = new ArrayList<>();
            for (byte[] recipient : recipients) copied.add(AssetPaymentsContract.requireAccount(recipient).clone());
            recipients = List.copyOf(copied);
            sessionKey = sessionKey.clone();
        }

        @Override
        public byte[] sessionKey() {
            return sessionKey.clone();
        }

        @Override
        public List<byte[]> recipients() {
            return recipients.stream().map(byte[]::clone).toList();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AuthorizeSession that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.bstr(sessionKey));
            Array actionList = new Array();
            actions.forEach(action -> actionList.add(new UnicodeString(action)));
            array.add(actionList);
            Array limitList = new Array();
            for (SessionGrantV1.Limit limit : limits) {
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

        public static AuthorizeSession decode(byte[] body) {
            List<DataItem> items = AssetCbor.decodeArray(body, 5).getDataItems();
            try {
                List<String> actions = new ArrayList<>();
                for (DataItem item : AssetCbor.array(items.get(1), 1, MAX_LIST).getDataItems()) {
                    actions.add(AssetCbor.text(item, 64));
                }
                List<SessionGrantV1.Limit> limits = new ArrayList<>();
                for (DataItem item : AssetCbor.array(items.get(2), 0, MAX_LIST).getDataItems()) {
                    List<DataItem> entry = AssetCbor.array(item, 3, 3).getDataItems();
                    limits.add(new SessionGrantV1.Limit(Hash32.of(AssetCbor.bytes(entry.get(0), 32, 32)),
                            AssetCbor.uint(entry.get(1), Quantities.MAX_U64),
                            AssetCbor.uint(entry.get(2), Quantities.MAX_U64)));
                }
                List<byte[]> recipients = new ArrayList<>();
                for (DataItem item : AssetCbor.array(items.get(3), 0, MAX_LIST).getDataItems()) {
                    recipients.add(AssetCbor.bytes(item, 28, 28));
                }
                return new AuthorizeSession(AssetCbor.bytes(items.get(0), 32, 32), actions, limits, recipients,
                        AssetCbor.uintLong(items.get(4)));
            } catch (ArithmeticException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }
    }

    /**
     * A withdrawal of the signing account from one vault to one exact recipient (D7, D11, D14). The envelope binds
     * every field the claim records (I10): the vault (named explicitly, R3-F9), the raw Shelley recipient address,
     * the amount of the vault's asset (lovelace for the ADA vault) and the min-ADA credit spent as the payout's
     * {@code vaultLovelace} (always 0 for the ADA vault; with a zero amount, a token vault claim withdraws the credit
     * alone).
     */
    public record RequestWithdrawal(Hash32 vaultId, byte[] recipient, BigInteger amount, BigInteger vaultLovelace) {
        public static final int MAX_RECIPIENT_BYTES = 128;

        public RequestWithdrawal {
            Objects.requireNonNull(vaultId, "vault id");
            if (recipient == null || recipient.length == 0 || recipient.length > MAX_RECIPIENT_BYTES) {
                throw new IllegalArgumentException("recipient must be 1-128 bytes");
            }
            Quantities.requireU64(amount, "amount");
            Quantities.requireU64(vaultLovelace, "vault lovelace");
            if (amount.signum() == 0 && vaultLovelace.signum() == 0) {
                throw new IllegalArgumentException("a withdrawal pays something");
            }
            recipient = recipient.clone();
        }

        @Override
        public byte[] recipient() {
            return recipient.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RequestWithdrawal that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.bstr(vaultId.bytes()));
            array.add(AssetCbor.bstr(recipient));
            array.add(AssetCbor.uint(amount));
            array.add(AssetCbor.uint(vaultLovelace));
            return AssetCbor.encode(array);
        }

        public static RequestWithdrawal decode(byte[] body) {
            List<DataItem> items = AssetCbor.decodeArray(body, 4).getDataItems();
            try {
                return new RequestWithdrawal(Hash32.of(AssetCbor.bytes(items.get(0), 32, 32)),
                        AssetCbor.bytes(items.get(1), 1, MAX_RECIPIENT_BYTES),
                        AssetCbor.uint(items.get(2), Quantities.MAX_U64),
                        AssetCbor.uint(items.get(3), Quantities.MAX_U64));
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }
    }

    /** Revokes one grant of the signing account. */
    public record RevokeSession(Hash32 grantId) {
        public RevokeSession {
            Objects.requireNonNull(grantId, "grant id");
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.bstr(grantId.bytes()));
            return AssetCbor.encode(array);
        }

        public static RevokeSession decode(byte[] body) {
            return new RevokeSession(Hash32.of(AssetCbor.bytes(AssetCbor.decodeArray(body, 1).getDataItems().getFirst(),
                    32, 32)));
        }
    }

    static AssetId asset(DataItem item) {
        List<DataItem> pair = AssetCbor.array(item, 2, 2).getDataItems();
        try {
            return AssetId.of(AssetCbor.bytes(pair.get(0), 0, 28), AssetCbor.bytes(pair.get(1), 0, 32));
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    static Array assetItem(AssetId asset) {
        Array array = new Array();
        array.add(AssetCbor.bstr(asset.policyId()));
        array.add(AssetCbor.bstr(asset.assetName()));
        return array;
    }
}
