package org.yanoproject.x.walletauth;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Deterministic authorization of user intents (ADR-059 D13, I10, I11). Everything here is a pure
 * function of its inputs, so every member reaches the same decision. Single-use enforcement
 * ({@code used/<IntentId>}), cumulative usage and receipts are the consuming state machine's job;
 * this class returns the {@link Hash32 IntentId} it must record.
 */
public final class WalletAuthorizer {
    public static final String REGISTER_ACCOUNT = "REGISTER_ACCOUNT";
    public static final String ROTATE_OWNER = "ROTATE_OWNER";
    public static final String AUTHORIZE_SESSION = "AUTHORIZE_SESSION";
    public static final String REVOKE_SESSION = "REVOKE_SESSION";
    /** Actions a session key can never perform, whatever its grant lists (D13 §4, D14). */
    public static final java.util.Set<String> OWNER_ONLY =
            java.util.Set.of(REGISTER_ACCOUNT, ROTATE_OWNER, AUTHORIZE_SESSION, REVOKE_SESSION);
    private static final byte[] ACCOUNT_TAG = "acct\0".getBytes(StandardCharsets.US_ASCII);

    /** Looks up an account's current owner credential, if the account exists. */
    @FunctionalInterface
    public interface OwnerLookup {
        Optional<CardanoCredential> owner(byte[] accountId);
    }

    /** Looks up a registered, unrevoked session grant. */
    @FunctionalInterface
    public interface GrantLookup {
        Optional<SessionGrantV1> grant(Hash32 grantId);
    }

    /** An authorized intent: its id, its decoded envelope and the credential or grant behind it. */
    public record Authorized(Hash32 intentId, IntentEnvelopeV1 envelope, Optional<CardanoCredential> owner,
                             Optional<SessionGrantV1> grant) { }

    private final IntentEnvelopeV1.Domain domain;
    private final long maxIntentTtlMs;

    public WalletAuthorizer(IntentEnvelopeV1.Domain domain, long maxIntentTtlMs) {
        this.domain = domain;
        if (maxIntentTtlMs <= 0) throw new IllegalArgumentException("max intent TTL must be positive");
        this.maxIntentTtlMs = maxIntentTtlMs;
    }

    /** {@code AccountId = blake2b-224("acct\0" ‖ credential kind ‖ key hash)}, fixed at registration. */
    public static byte[] accountId(CardanoCredential credential) {
        byte[] preimage = new byte[ACCOUNT_TAG.length + 1 + CardanoCredential.KEY_HASH_BYTES];
        System.arraycopy(ACCOUNT_TAG, 0, preimage, 0, ACCOUNT_TAG.length);
        preimage[ACCOUNT_TAG.length] = (byte) credential.kind().code();
        System.arraycopy(credential.keyHash(), 0, preimage, ACCOUNT_TAG.length + 1, CardanoCredential.KEY_HASH_BYTES);
        return Blake2bUtil.blake2bHash224(preimage);
    }

    /**
     * Authorizes an owner-signed intent from a CIP-30 {@code signData} result. For
     * {@code REGISTER_ACCOUNT} the account must not exist yet and its id must be derived from the
     * signing credential; for every other action the signing credential must equal the account's
     * registered owner.
     */
    public Authorized authorizeOwner(byte[] coseSign1, byte[] coseKey, OwnerLookup owners, long blockTimeMs) {
        CoseSign1Verifier.Verified verified = CoseSign1Verifier.verify(coseSign1, coseKey);
        IntentEnvelopeV1 envelope = IntentEnvelopeV1.decode(verified.payload());
        if (ROTATE_OWNER.equals(envelope.action())) {
            throw new WalletAuthException("ROTATE_OWNER needs both signatures; use authorizeRotation");
        }
        return authorizeOwnerVerified(verified, envelope, owners, blockTimeMs);
    }

    private Authorized authorizeOwnerVerified(CoseSign1Verifier.Verified verified, IntentEnvelopeV1 envelope,
                                              OwnerLookup owners, long blockTimeMs) {
        checkEnvelope(envelope, blockTimeMs);
        if (!(envelope.authorizer() instanceof IntentEnvelopeV1.Owner)) {
            throw new WalletAuthException("a wallet signature must name the owner as authorizer");
        }
        CardanoCredential signer = verified.credential();
        if (signer.addressNetworkId() != domain.addressNetworkId()) {
            throw new WalletAuthException("address network id does not match the domain");
        }
        Optional<CardanoCredential> registered = owners.owner(envelope.accountId());
        if (REGISTER_ACCOUNT.equals(envelope.action())) {
            if (registered.isPresent()) {
                // Idempotent for the current owner (the ledger records a no-op); any other signer fails.
                if (!registered.get().sameAs(signer)) throw new WalletAuthException("account already registered");
            } else if (!Arrays.equals(accountId(signer), envelope.accountId())) {
                throw new WalletAuthException("account id is not derived from the signing credential");
            }
        } else {
            CardanoCredential owner = registered
                    .orElseThrow(() -> new WalletAuthException("unknown account"));
            if (!owner.sameAs(signer)) {
                throw new WalletAuthException("signer is not the account's registered owner");
            }
        }
        return new Authorized(GatewayIds.intentId(verified.payload()), envelope, Optional.of(signer), Optional.empty());
    }

    /**
     * Authorizes a session-signed intent: the envelope names {@code SESSION(grantId)}, the grant is
     * registered for the same account, not expired, grants the action, and its key signed the
     * exact envelope bytes. Amount limits are checked by the caller with
     * {@link SessionGrantV1#checkPayment}.
     */
    public Authorized authorizeSession(byte[] envelopeBytes, byte[] signature, GrantLookup grants, long blockTimeMs) {
        IntentEnvelopeV1 envelope = IntentEnvelopeV1.decode(envelopeBytes);
        checkEnvelope(envelope, blockTimeMs);
        if (!(envelope.authorizer() instanceof IntentEnvelopeV1.Session session)) {
            throw new WalletAuthException("a session signature must name its grant");
        }
        SessionGrantV1 grant = grants.grant(session.grantId())
                .orElseThrow(() -> new WalletAuthException("unknown or revoked grant"));
        if (!Arrays.equals(grant.accountId(), envelope.accountId())) {
            throw new WalletAuthException("grant belongs to another account");
        }
        if (blockTimeMs > grant.notAfterMs()) throw new WalletAuthException("session expired");
        if (OWNER_ONLY.contains(envelope.action())) {
            throw new WalletAuthException(envelope.action() + " needs the owner's signature");
        }
        if (!grant.allows(envelope.action())) throw new WalletAuthException("action not granted to the session");
        if (!WalletEd25519.verify(signature, envelopeBytes, grant.sessionKey())) {
            throw new WalletAuthException("session signature does not verify");
        }
        return new Authorized(GatewayIds.intentId(envelopeBytes), envelope, Optional.empty(), Optional.of(grant));
    }

    /**
     * {@code ROTATE_OWNER} (D14a): two CIP-8 signatures over the same envelope, the first by the
     * current owner and the second by the new credential. The action must be {@code ROTATE_OWNER},
     * and its body must be {@code [kind, key_hash]} naming exactly the second signer.
     */
    public Authorized authorizeRotation(byte[] currentSign1, byte[] currentKey, byte[] newSign1,
                                        byte[] newKey, OwnerLookup owners, long blockTimeMs) {
        CoseSign1Verifier.Verified first = CoseSign1Verifier.verify(currentSign1, currentKey);
        IntentEnvelopeV1 envelope = IntentEnvelopeV1.decode(first.payload());
        if (!ROTATE_OWNER.equals(envelope.action())) throw new WalletAuthException("not a ROTATE_OWNER intent");
        Authorized current = authorizeOwnerVerified(first, envelope, owners, blockTimeMs);
        CoseSign1Verifier.Verified second = CoseSign1Verifier.verify(newSign1, newKey);
        if (!Arrays.equals(second.payload(), first.payload())) {
            throw new WalletAuthException("the two rotation signatures cover different envelopes");
        }
        if (second.credential().addressNetworkId() != domain.addressNetworkId()) {
            throw new WalletAuthException("new credential is on another network");
        }
        CardanoCredential named = rotationTarget(envelope.body(), domain.addressNetworkId());
        if (!named.sameAs(second.credential())) {
            throw new WalletAuthException("the second signer is not the credential named in the body");
        }
        return new Authorized(current.intentId(), envelope, Optional.of(second.credential()), Optional.empty());
    }

    /** Encodes a {@code ROTATE_OWNER} body: {@code [kind_code, key_hash(28)]}. */
    public static byte[] rotationBody(CardanoCredential target) {
        co.nstant.in.cbor.model.Array array = new co.nstant.in.cbor.model.Array();
        array.add(org.yanoproject.x.assets.contracts.internal.AssetCbor.uint(target.kind().code()));
        array.add(org.yanoproject.x.assets.contracts.internal.AssetCbor.bstr(target.keyHash()));
        return org.yanoproject.x.assets.contracts.internal.AssetCbor.encode(array);
    }

    private static CardanoCredential rotationTarget(byte[] body, int networkId) {
        try {
            var items = org.yanoproject.x.assets.contracts.internal.AssetCbor.decodeArray(body, 2).getDataItems();
            long kind = org.yanoproject.x.assets.contracts.internal.AssetCbor.uintLong(items.get(0));
            if (kind > 1) throw new WalletAuthException("unknown credential kind");
            return new CardanoCredential(CardanoCredential.Kind.values()[(int) kind],
                    org.yanoproject.x.assets.contracts.internal.AssetCbor.bytes(items.get(1), 28, 28), networkId);
        } catch (IllegalArgumentException invalid) {
            throw new WalletAuthException("invalid ROTATE_OWNER body");
        }
    }

    private void checkEnvelope(IntentEnvelopeV1 envelope, long blockTimeMs) {
        if (!envelope.domain().equals(domain)) {
            throw new WalletAuthException("intent is bound to another network, chain or application");
        }
        if (blockTimeMs > envelope.notAfterMs()) throw new WalletAuthException("intent expired");
        if (envelope.notAfterMs() - blockTimeMs > maxIntentTtlMs) {
            throw new WalletAuthException("intent expiry is further out than the maximum TTL");
        }
    }
}
