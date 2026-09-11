package org.yanoproject.x.trust.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Builds the stock authenticated-map commands the registry writes: governed-role writes
 * carrying one actor authorization, approval-routed writes carrying a proposal reference,
 * and the signed role statements that drive an approval. Only the contracts sign; seeds are
 * passed in and never retained.
 */
public final class TrustRegistrySigner {
    private static final SecureRandom RANDOM = new SecureRandom();

    private TrustRegistrySigner() {
    }

    /** An actor as the chain knows it, paired with the seed of one of its active keys. */
    public record ActorContext(String actorId, long actorRevision, String keyId, byte[] publicKey,
                               byte[] seed) {
        public ActorContext {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(keyId, "keyId");
            publicKey = Objects.requireNonNull(publicKey, "publicKey").clone();
            seed = Objects.requireNonNull(seed, "seed").clone();
            if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
        }

        @Override public byte[] publicKey() { return publicKey.clone(); }
        @Override public byte[] seed() { return seed.clone(); }
    }

    /** Matches a seed against the actor's active key epochs at {@code height}. */
    public static ActorContext actorContext(ActorRecordV1 actor, byte[] seed, long height) {
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        for (ActorKeyEpochV1 key : actor.keys()) {
            if (key.status() == RecordStatus.ACTIVE && key.activeAt(height)
                    && Arrays.equals(key.publicKey(), publicKey)) {
                return new ActorContext(actor.actorId(), actor.revision(), key.keyId(),
                        publicKey, seed);
            }
        }
        throw new IllegalArgumentException("the seed does not match an active key of "
                + actor.actorId());
    }

    /**
     * Actor context from a genesis descriptor, for the first write on a chain that has not
     * finalized a block yet (its actor records cannot be read until then): revision 1 and the
     * descriptor's key.
     */
    public static ActorContext genesisActorContext(
            org.yanoproject.x.trust.profile.TrustRegistryGenesis.Descriptor descriptor,
            String actorId, byte[] seed) {
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        for (var actor : descriptor.actors()) {
            if (actor.id().equals(actorId)) {
                if (!actor.publicKeyHex().equals(java.util.HexFormat.of().formatHex(publicKey))) {
                    throw new IllegalArgumentException(
                            "the seed does not match the genesis key of " + actorId);
                }
                return new ActorContext(actorId, 1, actor.keyId(), publicKey, seed);
            }
        }
        throw new IllegalArgumentException("actor " + actorId + " is not in the genesis descriptor");
    }

    public static byte[] randomAuthorizationId() {
        byte[] id = new byte[32];
        RANDOM.nextBytes(id);
        return id;
    }

    /** Governed-role command: every mutation authorized by one actor authorization. */
    public static byte[] governedCommand(
            AuthenticatedMapContract.Command command,
            String policyId,
            long policyRevision,
            ActorContext actor,
            String chainId,
            byte[] genesisId,
            long issuedHeight,
            long deadlineHeight,
            byte[] authorizationId
    ) {
        List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1> assignments =
                new ArrayList<>();
        for (int index = 0; index < command.mutations().size(); index++) {
            assignments.add(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                    index, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, policyId, 1));
        }
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(
                command.batch(), command.mutations(), assignments);
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        var authorization = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1.sign(
                authorizationId, chainId, genesisId, commitment,
                IntStream.range(0, command.mutations().size()).boxed().toList(),
                policyId, policyRevision, actor.actorId(), actor.actorRevision(), actor.keyId(),
                actor.publicKey(), issuedHeight, deadlineHeight, actor.seed());
        return AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                        action, List.of(authorization)));
    }

    /** The approval-routed action for a command, whose commitment the proposal's payload hash binds. */
    public static AuthenticatedMapAuthorizationContract.MapActionV1 approvalAction(
            AuthenticatedMapContract.Command command, String policyId) {
        List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1> assignments =
                new ArrayList<>();
        for (int index = 0; index < command.mutations().size(); index++) {
            assignments.add(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                    index, AuthenticatedMapContract.AUTH_APPROVAL, policyId, 1));
        }
        return new AuthenticatedMapAuthorizationContract.MapActionV1(
                command.batch(), command.mutations(), assignments);
    }

    /** Approval-routed command referencing an approved proposal. */
    public static byte[] approvalCommand(
            AuthenticatedMapAuthorizationContract.MapActionV1 action,
            String proposalId,
            String policyId,
            long policyRevision
    ) {
        byte[] commitment = AuthenticatedMapAuthorizationContract.actionCommitment(action);
        var reference = new AuthenticatedMapAuthorizationContract.MapApprovalReferenceV1(
                proposalId, commitment,
                IntStream.range(0, action.mutations().size()).boxed().toList(),
                policyId, policyRevision);
        return AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                        action, List.of(reference)));
    }

    /** Payload hash a proposal must carry to approve {@code action} on this genesis. */
    public static byte[] approvalPayloadHash(
            byte[] genesisId, AuthenticatedMapAuthorizationContract.MapActionV1 action) {
        return AuthenticatedMapAuthorizationContract.approvalPayloadHash(genesisId,
                AuthenticatedMapAuthorizationContract.actionCommitment(action));
    }

    /** A signed proposal, approval, or rejection statement ready to submit. */
    public static byte[] signedStatement(
            ActorStatementV1.Action action,
            String chainId,
            String proposalId,
            String policyId,
            long policyRevision,
            byte[] payloadHash,
            long deadlineHeight,
            ActorContext actor,
            String clauseId
    ) {
        ActorStatementV1 statement = new ActorStatementV1(action, chainId, proposalId, policyId,
                policyRevision, AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN,
                payloadHash, deadlineHeight, actor.actorId(), actor.actorRevision(),
                actor.keyId(), clauseId);
        return SignedActorCommandV1.sign(statement, actor.seed()).encode();
    }
}
