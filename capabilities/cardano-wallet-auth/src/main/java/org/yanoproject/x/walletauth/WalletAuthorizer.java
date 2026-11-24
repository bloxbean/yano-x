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
            if (registered.isPresent()) throw new WalletAuthException("account already registered");
            if (!Arrays.equals(accountId(signer), envelope.accountId())) {
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
        if (!grant.allows(envelope.action())) throw new WalletAuthException("action not granted to the session");
        if (!WalletEd25519.verify(signature, envelopeBytes, grant.sessionKey())) {
            throw new WalletAuthException("session signature does not verify");
        }
        return new Authorized(GatewayIds.intentId(envelopeBytes), envelope, Optional.empty(), Optional.of(grant));
    }

    /**
     * {@code ROTATE_OWNER} (D14a): two CIP-8 signatures over the same envelope, the first by the
     * current owner and the second by the new credential. Returns the new credential; the caller
     * checks it equals the credential named in the action body.
     */
    public CardanoCredential authorizeRotation(byte[] currentSign1, byte[] currentKey, byte[] newSign1,
                                               byte[] newKey, OwnerLookup owners, long blockTimeMs) {
        Authorized current = authorizeOwner(currentSign1, currentKey, owners, blockTimeMs);
        CoseSign1Verifier.Verified second = CoseSign1Verifier.verify(newSign1, newKey);
        if (!Arrays.equals(second.payload(), current.envelope().encode())) {
            throw new WalletAuthException("the two rotation signatures cover different envelopes");
        }
        if (second.credential().addressNetworkId() != domain.addressNetworkId()) {
            throw new WalletAuthException("new credential is on another network");
        }
        return second.credential();
    }

    private void checkEnvelope(IntentEnvelopeV1 envelope, long blockTimeMs) {
        if (!envelope.domain().equals(domain)) {
            throw new WalletAuthException("intent is bound to another network, chain or application");
        }
        if (blockTimeMs > envelope.notAfterMs()) throw new WalletAuthException("intent expired");
        if (envelope.notAfterMs() > blockTimeMs + maxIntentTtlMs) {
            throw new WalletAuthException("intent expiry is further out than the maximum TTL");
        }
    }
}
