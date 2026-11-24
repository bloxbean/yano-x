package org.yanoproject.x.walletauth;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The exact bytes a wallet or session key signs (ADR-059 D13 §3). It binds the network, the
 * deployment, the action, the account, the authorizer, a single-use nonce, an expiry and the
 * canonical action body. Every field a state machine executes must come from this envelope.
 *
 * <p>Wire form (canonical array subset): {@code ["yano-intent-v1", network_magic,
 * address_network_id, chain_id, genesis_id, application_id, action, account_id(28),
 * authorizer, nonce(16), not_after_ms, body]}, where {@code authorizer} is {@code [0]} for the
 * owner or {@code [1, grant_id(32)]} for a session.
 */
public record IntentEnvelopeV1(Domain domain, String action, byte[] accountId, Authorizer authorizer,
                               byte[] nonce, long notAfterMs, byte[] body) {
    public static final String TAG = "yano-intent-v1";
    public static final int ACCOUNT_ID_BYTES = 28;
    public static final int NONCE_BYTES = 16;
    public static final int MAX_BODY_BYTES = 4_096;
    private static final int MAX_TEXT_BYTES = 64;

    /** The deployment an intent is bound to. Address network id and magic are checked separately. */
    public record Domain(long networkMagic, int addressNetworkId, String chainId, Hash32 genesisId,
                         String applicationId) {
        public Domain {
            if (networkMagic < 0) throw new IllegalArgumentException("network magic must be non-negative");
            if (addressNetworkId < 0 || addressNetworkId > 15) {
                throw new IllegalArgumentException("address network id is 4 bits");
            }
            requireText(chainId, "chain id");
            Objects.requireNonNull(genesisId, "genesis id");
            requireText(applicationId, "application id");
        }
    }

    /** Who signed: the account owner through CIP-8, or a session key under a registered grant. */
    public sealed interface Authorizer permits Owner, Session { }

    public record Owner() implements Authorizer { }

    public record Session(Hash32 grantId) implements Authorizer {
        public Session {
            Objects.requireNonNull(grantId, "grant id");
        }
    }

    public IntentEnvelopeV1 {
        Objects.requireNonNull(domain, "domain");
        requireText(action, "action");
        if (accountId == null || accountId.length != ACCOUNT_ID_BYTES) {
            throw new IllegalArgumentException("account id must be 28 bytes");
        }
        Objects.requireNonNull(authorizer, "authorizer");
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be 16 bytes");
        }
        if (notAfterMs < 0) throw new IllegalArgumentException("notAfter must be non-negative");
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("body must be 1-4096 bytes");
        }
        // The action body is itself canonical: one array in the strict contract subset (D13 §3).
        AssetCbor.decodeArray(body);
        accountId = accountId.clone();
        nonce = nonce.clone();
        body = body.clone();
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(new UnicodeString(TAG));
        array.add(AssetCbor.uint(domain.networkMagic()));
        array.add(AssetCbor.uint(domain.addressNetworkId()));
        array.add(new UnicodeString(domain.chainId()));
        array.add(AssetCbor.bstr(domain.genesisId().bytes()));
        array.add(new UnicodeString(domain.applicationId()));
        array.add(new UnicodeString(action));
        array.add(AssetCbor.bstr(accountId));
        Array who = new Array();
        if (authorizer instanceof Session session) {
            who.add(AssetCbor.uint(1));
            who.add(AssetCbor.bstr(session.grantId().bytes()));
        } else {
            who.add(AssetCbor.uint(0));
        }
        array.add(who);
        array.add(AssetCbor.bstr(nonce));
        array.add(AssetCbor.uint(notAfterMs));
        array.add(AssetCbor.bstr(body));
        return AssetCbor.encode(array);
    }

    /** Decodes canonical envelope bytes; anything else is rejected. */
    public static IntentEnvelopeV1 decode(byte[] bytes) {
        try {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 12).getDataItems();
            if (!TAG.equals(AssetCbor.text(items.get(0), MAX_TEXT_BYTES))) {
                throw new WalletAuthException("not a yano-intent-v1 envelope");
            }
            long addressNetworkId = AssetCbor.uintLong(items.get(2));
            if (addressNetworkId > 15) throw new WalletAuthException("address network id is 4 bits");
            Domain domain = new Domain(
                    AssetCbor.uintLong(items.get(1)),
                    (int) addressNetworkId,
                    AssetCbor.text(items.get(3), MAX_TEXT_BYTES),
                    Hash32.of(AssetCbor.bytes(items.get(4), 32, 32)),
                    AssetCbor.text(items.get(5), MAX_TEXT_BYTES));
            Array who = AssetCbor.array(items.get(8), 1, 2);
            long kind = AssetCbor.uintLong(who.getDataItems().get(0));
            Authorizer authorizer;
            if (kind == 0 && who.getDataItems().size() == 1) {
                authorizer = new Owner();
            } else if (kind == 1 && who.getDataItems().size() == 2) {
                authorizer = new Session(Hash32.of(AssetCbor.bytes(who.getDataItems().get(1), 32, 32)));
            } else {
                throw new WalletAuthException("unknown authorizer");
            }
            return new IntentEnvelopeV1(domain,
                    AssetCbor.text(items.get(6), MAX_TEXT_BYTES),
                    AssetCbor.bytes(items.get(7), ACCOUNT_ID_BYTES, ACCOUNT_ID_BYTES),
                    authorizer,
                    AssetCbor.bytes(items.get(9), NONCE_BYTES, NONCE_BYTES),
                    AssetCbor.uintLong(items.get(10)),
                    AssetCbor.bytes(items.get(11), 0, MAX_BODY_BYTES));
        } catch (IllegalArgumentException invalid) {
            throw new WalletAuthException("invalid intent envelope: " + invalid.getMessage());
        }
    }

    private static void requireText(String value, String name) {
        int length;
        try {
            length = value == null ? 0 : StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value)).remaining();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException(name + " is not valid Unicode text");
        }
        if (length == 0 || length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(name + " must be 1-64 bytes of UTF-8");
        }
    }

    @Override
    public byte[] accountId() {
        return accountId.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IntentEnvelopeV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
