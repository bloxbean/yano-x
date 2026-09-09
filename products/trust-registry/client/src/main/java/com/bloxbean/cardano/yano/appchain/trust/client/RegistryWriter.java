package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusBitstring;
import com.bloxbean.cardano.yano.appchain.trust.profile.StatusProjection;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryProfile;
import com.bloxbean.cardano.yano.appchain.trust.profile.TrustRegistryValues;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Governed writes to a trust registry chain (ADR-053 §2.2). One signing path for every caller:
 * the CLI and the operator gateway both go through {@link #submitGoverned}, which signs a one-use
 * actor authorization with {@link TrustRegistrySigner#governedCommand} and waits for the receipt.
 *
 * <p>Nothing here decides what an actor may do. The chain checks the signature, the actor's role,
 * and the policy on every write, and rejects the command with its own error code otherwise.
 */
public final class RegistryWriter {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final TrustRegistryClient chain;
    private final Duration timeout;

    public RegistryWriter(TrustRegistryClient chain) {
        this(chain, DEFAULT_TIMEOUT);
    }

    public RegistryWriter(TrustRegistryClient chain, Duration timeout) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public TrustRegistryClient chain() {
        return chain;
    }

    /**
     * An actor's signing material. The seed is copied in and out so no caller keeps a reference to
     * the array this record holds.
     *
     * @param genesisIdHexOverride the generated {@code state.genesis-id}, required before the
     *                             chain's first block because no record can be read yet
     */
    public record Signer(String actorId, byte[] seed, String genesisIdHexOverride, String keyIdOverride) {
        public Signer {
            Objects.requireNonNull(actorId, "actorId");
            seed = Objects.requireNonNull(seed, "seed").clone();
            if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
            if (genesisIdHexOverride != null && !genesisIdHexOverride.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("genesis id override must be 32 bytes of hex");
            }
        }

        @Override public byte[] seed() { return seed.clone(); }

        public static Signer of(String actorId, byte[] seed) {
            return new Signer(actorId, seed, null, null);
        }
    }

    public record WriteResult(String messageIdHex, AuthenticatedMapContract.Receipt receipt) {
        public boolean applied() {
            return receipt.status() == AuthenticatedMapContract.RECEIPT_APPLIED;
        }
    }

    private record Resolved(TrustRegistrySigner.ActorContext actor, byte[] genesisId, long tip) {
    }

    private Resolved resolve(Signer signer) {
        long tip = chain.tipHeight();
        if (tip < 1) {
            if (signer.genesisIdHexOverride() == null) {
                throw TrustRegistryException.usage("the chain has no block yet; pass the generated"
                        + " state.genesis-id (and the key id when it is not <actor>-k1)");
            }
            byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(signer.seed());
            String keyId = signer.keyIdOverride() != null ? signer.keyIdOverride()
                    : signer.actorId() + "-k1";
            return new Resolved(new TrustRegistrySigner.ActorContext(signer.actorId(), 1, keyId,
                    publicKey, signer.seed()), HEX.parseHex(signer.genesisIdHexOverride()), tip);
        }
        byte[] genesisId = signer.genesisIdHexOverride() != null
                ? HEX.parseHex(signer.genesisIdHexOverride()) : chain.mapGenesisId();
        TrustRegistrySigner.ActorContext actor;
        try {
            actor = TrustRegistrySigner.actorContext(chain.actor(signer.actorId()), signer.seed(), tip);
        } catch (IllegalArgumentException mismatch) {
            throw TrustRegistryException.invalid(mismatch.getMessage());
        }
        return new Resolved(actor, genesisId, tip);
    }

    /** Signs the command for the collection's direct-role policy and waits for its receipt. */
    public WriteResult submitGoverned(Signer signer, String policyId,
                                      AuthenticatedMapContract.Command command) {
        if (TrustRegistryProfile.ONBOARDING_POLICY.equals(policyId)) {
            throw TrustRegistryException.usage("issuers are written through the approval route, "
                    + "not through a direct-role authorization (ADR-049 §8)");
        }
        Resolved resolved = resolve(signer);
        long policyRevision = 1;
        long lifetime = TrustRegistryProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS;
        if (resolved.tip() >= 1) {
            DirectRolePolicyV1 policy = chain.directPolicy(policyId);
            policyRevision = policy.revision();
            lifetime = Math.min(lifetime, policy.maximumAuthorizationLifetimeBlocks());
        }
        long issued = Math.max(resolved.tip(), 1);
        byte[] authorizationId = new byte[32];
        RANDOM.nextBytes(authorizationId);
        byte[] bytes = TrustRegistrySigner.governedCommand(command, policyId, policyRevision,
                resolved.actor(), chain.chainId(), resolved.genesisId(), issued, issued + lifetime,
                authorizationId);
        String messageId = chain.submit(bytes);
        return new WriteResult(messageId, chain.awaitReceipt(messageId, timeout));
    }

    private WriteResult write(Signer signer, String collection,
                              AuthenticatedMapContract.Mutation mutation) {
        return submitGoverned(signer, TrustRegistryProfile.policyOf(collection),
                AuthenticatedMapContract.Command.single(mutation));
    }

    // ------------------------------------------------------------------ typed writes

    /** Sets one index of a status list; {@code bit} is the value of that position. */
    public WriteResult putStatus(Signer signer, String listId, long index, int bit, int reasonCode) {
        return write(signer, TrustRegistryProfile.STATUS, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey(listId, index),
                new TrustRegistryValues.StatusValue(bit, reasonCode).encode()));
    }

    public WriteResult putSubject(Signer signer, String subjectId, String controllerOrganizationId,
                                  String kind, byte[] metadataHash) {
        return write(signer, TrustRegistryProfile.SUBJECTS, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.SUBJECTS, TrustRegistryProfile.subjectKey(subjectId),
                new TrustRegistryValues.SubjectValue(controllerOrganizationId, kind, metadataHash).encode()));
    }

    public WriteResult putSchema(Signer signer, String schemaId, byte[] value) {
        return write(signer, TrustRegistryProfile.SCHEMAS, AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.SCHEMAS, TrustRegistryProfile.schemaKey(schemaId), value.clone()));
    }

    /**
     * Tombstones an entry. Revocation is terminal in every collection, so the entry must be active
     * now and the revision read here is what the mutation compares against.
     */
    public WriteResult revoke(Signer signer, String collection, byte[] key) {
        StatusAnswer current = chain.answer(collection, key, null);
        if (current.presence() != StatusAnswer.Presence.ACTIVE) {
            throw TrustRegistryException.invalid("entry is " + current.presence() + " at height "
                    + current.height() + "; only an active entry can be revoked");
        }
        return write(signer, collection, AuthenticatedMapContract.Mutation.revoke(
                collection, key, current.entry().revision(), null));
    }

    public WriteResult revokeStatus(Signer signer, String listId, long index) {
        return revoke(signer, TrustRegistryProfile.STATUS,
                TrustRegistryProfile.statusKey(listId, index));
    }

    public WriteResult revokeSubject(Signer signer, String subjectId) {
        return revoke(signer, TrustRegistryProfile.SUBJECTS,
                TrustRegistryProfile.subjectKey(subjectId));
    }

    /** What a list publication wrote, so a caller can report the replay without redoing it. */
    public record PublishedList(WriteResult write, long replayedHeight, long setCount,
                                String listSha256Hex, long mutationCount) {
    }

    /**
     * Replays the chain's applied status writes into a bitstring and records its SHA-256. The hash
     * is of the raw bitstring; the served list gzips it, and gzip output is never hashed.
     */
    public PublishedList publishList(Signer signer, String listId, String purpose, long bitLength) {
        String list = TrustRegistryProfile.requireListId(listId);
        long tip = chain.tipHeight();
        StatusProjection projection = new StatusProjection();
        long replayed = chain.replay(projection, tip);
        StatusBitstring bits = projection.bitstring(list, bitLength);
        TrustRegistryValues.StatusListValue value = new TrustRegistryValues.StatusListValue(
                purpose, bitLength, bits.sha256(), replayed);
        WriteResult result = write(signer, TrustRegistryProfile.STATUS_LISTS,
                AuthenticatedMapContract.Mutation.put(TrustRegistryProfile.STATUS_LISTS,
                        TrustRegistryProfile.listKey(list), value.encode()));
        return new PublishedList(result, replayed, bits.setCount(), bits.sha256Hex(),
                projection.mutationCount());
    }
}
