package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.api.impl.BouncyCastleEd25519Verifier;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;

import java.util.Objects;

/**
 * Default in-config {@link SignerProvider}: the Ed25519 signing identity of an
 * app-chain member from a 32-byte seed (envelope auth scheme 0). The public key
 * is derived from the seed and is the member identity in the envelope's
 * {@code sender} field. Custom backends (KMS/HSM) plug in via
 * {@link org.yanoproject.x.framework.api.appchain.signer.SignerProviderFactory}.
 *
 * <p>Public for the optional Cardano module within this framework release.
 * This is an implementation collaboration surface, not the Yano Host API
 * or a plugin extension contract.</p>
 */
public final class AppMessageSigner implements SignerProvider {
    /**
     * Consensus verification: libsodium's {@code crypto_sign_ed25519_verify_detached} outcomes on BouncyCastle field
     * arithmetic, pinned rather than read from the replaceable global {@link CryptoConfiguration} verifier, so a
     * component that reconfigures CCL can never change which envelopes, votes or certificates a member accepts.
     */
    private static final SigningProvider VERIFIER = new EdDSASigningProvider(new BouncyCastleEd25519Verifier());

    private final byte[] privateKey;
    private final byte[] publicKey;
    private final SigningProvider signingProvider;

    AppMessageSigner(String privateKeyHex) {
        Objects.requireNonNull(privateKeyHex, "app-chain signing key is required");
        this.privateKey = HexUtil.decodeHexString(privateKeyHex.trim());
        if (privateKey.length != 32)
            throw new IllegalArgumentException("App-chain signing key must be a 32-byte Ed25519 seed (hex), got "
                    + privateKey.length + " bytes");
        this.publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(privateKey);
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
    }

    @Override
    public byte[] publicKey() {
        return publicKey;
    }

    @Override
    public String publicKeyHex() {
        return HexUtil.encodeHexString(publicKey);
    }

    @Override
    public byte[] sign(byte[] message) {
        return signingProvider.sign(message, privateKey);
    }

    /** Envelopes verified recently; about 50 s of traffic at 2,500 msg/s (ADR-060 D3). */
    private static final VerifiedEnvelopeCache VERIFIED_ENVELOPES = new VerifiedEnvelopeCache(131_072);

    /**
     * The one Ed25519 check for app-message envelopes (ADR-060 D3): the envelope's {@code authProof} verifies over
     * its signed body under its {@code sender}. A relayed copy or a block's envelope that already verified on this
     * node is a cache hit keyed by {@code (messageId, authProof)}; the content-derived message id is recomputed first,
     * so a hit covers exactly these signed bytes. Only successful verifications are cached. Scheme, chain and
     * membership are the caller's checks.
     */
    public static boolean verifyEnvelope(AppMessage message) {
        if (message == null || message.getAuthProof() == null || message.getSender() == null
                || message.getSender().length != 32 || message.getMessageId() == null
                || !message.hasValidMessageId()) {
            return false;
        }
        byte[] messageId = message.getMessageId();
        byte[] proof = message.getAuthProof();
        if (VERIFIED_ENVELOPES.contains(messageId, proof)) {
            return true;
        }
        if (!verify(proof, message.signedBodyBytes(), message.getSender())) {
            return false;
        }
        VERIFIED_ENVELOPES.add(messageId, proof);
        return true;
    }

    static int verifiedEnvelopeCacheSize() {
        return VERIFIED_ENVELOPES.size();
    }

    public static boolean verify(byte[] signature, byte[] message, byte[] publicKey) {
        try {
            return VERIFIER.verify(signature, message, publicKey);
        } catch (Exception e) {
            return false;
        }
    }
}
