package org.yanoproject.x.trust.client;

import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.AuthenticatedMapProofBundle;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.composite.contracts.CompositeCommitmentV1;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.DirectRolePolicyV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleWorkflowIdentifiers;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Binds a governed write to the actor that made it (ADR-049 §4): the one-use consumption
 * record names the receipt's message and applied height, the actor authorization was signed
 * by the actor's active key under the map genesis id the chain holds, and the actor,
 * organization, and policy records at the revisions the consumption names were active.
 * Every fact is a state proof at the answer height verified under the trust context; the
 * equalities mirror the SDK bundle's direct-role checks with the genesis id split into the
 * state identity (proofs) and the map genesis id (authorizations).
 */
final class DirectRoleBinding {
    private static final HexFormat HEX = HexFormat.of();

    private DirectRoleBinding() {
    }

    static boolean verify(StatusAnswer answer, List<AuthenticatedMapProofBundle.Fact> facts,
                          ProofVerifier.TrustedStateRoot root,
                          List<String> checks, List<String> failures) {
        Map<String, AuthenticatedMapProofBundle.Fact> byName = facts.stream().collect(
                Collectors.toMap(AuthenticatedMapProofBundle.Fact::name, Function.identity()));
        for (String required : List.of(AuthenticatedMapProofBundle.DIRECT_CONSUMPTION,
                AuthenticatedMapProofBundle.DIRECT_POLICY,
                AuthenticatedMapProofBundle.DIRECT_POLICY_CURRENT,
                AuthenticatedMapProofBundle.ACTOR, AuthenticatedMapProofBundle.ACTOR_CURRENT,
                AuthenticatedMapProofBundle.ORGANIZATION,
                TrustRegistryClient.GENESIS_MARKER_FACT)) {
            AuthenticatedMapProofBundle.Fact fact = byName.get(required);
            if (fact == null || fact.expectedValue() == null) {
                failures.add("direct-role answer lacks the " + required + " fact");
                return false;
            }
            if (!presentValueMatches(fact.proof(), fact.expectedValue())
                    || !ProofVerifier.verify(fact.proof(), root)) {
                failures.add("fact " + required + " does not verify against the certified root");
                return false;
            }
        }
        try {
            var authorization = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1
                    .decode(answer.authorizationEvidence());
            var consumption = AuthenticatedMapAuthorizationContract.DirectConsumptionV1
                    .decode(byName.get(AuthenticatedMapProofBundle.DIRECT_CONSUMPTION).expectedValue());
            AuthenticatedMapContract.Receipt receipt = AuthenticatedMapContract.decodeReceipt(
                    byName.get(AuthenticatedMapProofBundle.RECEIPT).expectedValue());
            DirectRolePolicyV1 policy = DirectRolePolicyV1.decode(
                    byName.get(AuthenticatedMapProofBundle.DIRECT_POLICY).expectedValue());
            ActorRecordV1 actor = ActorRecordV1.decode(
                    byName.get(AuthenticatedMapProofBundle.ACTOR).expectedValue());
            OrganizationRecordV1 organization = OrganizationRecordV1.decode(
                    byName.get(AuthenticatedMapProofBundle.ORGANIZATION).expectedValue());
            byte[] mapGenesisId = byName.get(TrustRegistryClient.GENESIS_MARKER_FACT).expectedValue();
            ActorKeyEpochV1 key = actor.key(authorization.keyId());

            if (!Arrays.equals(byName.get(TrustRegistryClient.GENESIS_MARKER_FACT).expectedKey(),
                    CompositeCommitmentV1.componentKey(AuthenticatedMapContract.STATE_MACHINE_ID,
                            AuthenticatedMapContract.genesisMarkerKey()))
                    || mapGenesisId.length != 32) {
                failures.add("the genesis marker fact is not the map's genesis marker");
                return false;
            }
            boolean bound = authorization.verifyClaimedKey()
                    && authorization.chainId().equals(answer.chainId())
                    && MessageDigest.isEqual(authorization.genesisId(), mapGenesisId)
                    && MessageDigest.isEqual(authorization.actionCommitment(), answer.actionCommitment())
                    && MessageDigest.isEqual(consumption.actionCommitment(), answer.actionCommitment())
                    && MessageDigest.isEqual(receipt.messageId(), consumption.messageId())
                    && receipt.height() == consumption.appliedHeight()
                    && authorization.actorId().equals(consumption.actorId())
                    && MessageDigest.isEqual(authorization.authorizationId(), consumption.authorizationId())
                    && authorization.coveredMutationIndexes().equals(consumption.mutationIndexes())
                    && authorization.policyId().equals(consumption.policyId())
                    && authorization.policyRevision() == consumption.policyRevision()
                    && authorization.actorRevision() == consumption.actorRevision()
                    && authorization.keyId().equals(consumption.keyId())
                    && authorization.issuedHeight() <= consumption.appliedHeight()
                    && authorization.deadlineHeight() > consumption.appliedHeight()
                    && authorization.deadlineHeight() - authorization.issuedHeight()
                    <= policy.maximumAuthorizationLifetimeBlocks()
                    && MessageDigest.isEqual(authorization.statementDigest(), consumption.statementDigest())
                    && MessageDigest.isEqual(sha256(authorization.signature()), consumption.signatureDigest())
                    && policy.policyId().equals(consumption.policyId())
                    && policy.revision() == consumption.policyRevision()
                    && policy.status() == RecordStatus.ACTIVE
                    && policy.requiredRole().equals(consumption.role())
                    && pointer(byName.get(AuthenticatedMapProofBundle.DIRECT_POLICY_CURRENT)) == policy.revision()
                    && keyIs(byName.get(AuthenticatedMapProofBundle.DIRECT_CONSUMPTION),
                    AuthenticatedMapContract.STATE_MACHINE_ID,
                    AuthenticatedMapContract.directConsumptionKey(
                            consumption.actorId(), consumption.authorizationId()))
                    && keyIs(byName.get(AuthenticatedMapProofBundle.DIRECT_POLICY),
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID,
                    RoleWorkflowKeys.directPolicyRevision(policy.policyId(), policy.revision()))
                    && keyIs(byName.get(AuthenticatedMapProofBundle.DIRECT_POLICY_CURRENT),
                    RoleWorkflowIdentifiers.ROLE_APPROVALS_COMPONENT_ID,
                    RoleWorkflowKeys.directPolicyCurrent(policy.policyId()))
                    && actor.actorId().equals(consumption.actorId())
                    && actor.revision() == consumption.actorRevision()
                    && actor.status() == RecordStatus.ACTIVE
                    && actor.roles().contains(consumption.role())
                    && pointer(byName.get(AuthenticatedMapProofBundle.ACTOR_CURRENT)) == actor.revision()
                    && keyIs(byName.get(AuthenticatedMapProofBundle.ACTOR),
                    RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                    RoleWorkflowKeys.actorRevision(actor.actorId(), actor.revision()))
                    && keyIs(byName.get(AuthenticatedMapProofBundle.ACTOR_CURRENT),
                    RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                    RoleWorkflowKeys.actorCurrent(actor.actorId()))
                    && organization.organizationId().equals(consumption.organizationId())
                    && organization.revision() == consumption.organizationRevision()
                    && organization.status() == RecordStatus.ACTIVE
                    && actor.organizationId().equals(organization.organizationId())
                    && keyIs(byName.get(AuthenticatedMapProofBundle.ORGANIZATION),
                    RoleWorkflowIdentifiers.DOMAIN_ACTORS_COMPONENT_ID,
                    RoleWorkflowKeys.organizationRevision(
                            organization.organizationId(), organization.revision()))
                    && key != null && key.activeAt(consumption.appliedHeight())
                    && MessageDigest.isEqual(key.publicKey(), authorization.publicKey());
            if (!bound) {
                failures.add("the actor authorization, consumption, actor, organization, and "
                        + "policy records do not bind this write");
                return false;
            }
            StatusAnswer.Provenance claimed = answer.provenance();
            if (!consumption.actorId().equals(claimed.actorId())
                    || !consumption.organizationId().equals(claimed.organizationId())
                    || !consumption.policyId().equals(claimed.policyId())
                    || !consumption.keyId().equals(claimed.keyId())
                    || !consumption.role().equals(claimed.role())) {
                failures.add("the answer's provenance labels differ from the proven consumption");
                return false;
            }
            checks.add("one-use authorization by " + consumption.actorId() + " ("
                    + consumption.organizationId() + ", role " + consumption.role()
                    + ", key " + consumption.keyId() + ") under policy " + consumption.policyId()
                    + " revision " + consumption.policyRevision() + " binds the write");
            return true;
        } catch (RuntimeException malformed) {
            failures.add("direct-role records are malformed: " + malformed.getMessage());
            return false;
        }
    }

    private static boolean presentValueMatches(AppChainClient.Proof proof, byte[] expected) {
        return proof.presence() != AppChainClient.ProofPresence.ABSENT
                && proof.valueHex() != null
                && Arrays.equals(expected, HEX.parseHex(proof.valueHex()));
    }

    private static boolean keyIs(AuthenticatedMapProofBundle.Fact fact, String component,
                                 byte[] localKey) {
        return Arrays.equals(fact.expectedKey(),
                CompositeCommitmentV1.componentKey(component, localKey));
    }

    private static long pointer(AuthenticatedMapProofBundle.Fact fact) {
        byte[] value = fact.expectedValue();
        return value == null || value.length != Long.BYTES ? 0 : ByteBuffer.wrap(value).getLong();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
