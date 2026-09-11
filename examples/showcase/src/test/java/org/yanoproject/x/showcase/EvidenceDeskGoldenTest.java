package org.yanoproject.x.showcase;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.composite.contracts.CompositeCommitmentV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.GenesisActorV1;
import org.yanoproject.x.roles.contracts.GovernedGenesisV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RoleApprovalStatsV1;
import org.yanoproject.x.roles.contracts.RoleCommandResultV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.RoleWorkflowResultCode;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden bytes for the Evidence Desk browser port of the role workflow contracts (ADR-048 §9).
 * The fixture is produced by the Java contracts the chain runs and committed under the UI; the
 * UI's unit tests decode, re-encode, and sign against it. Regenerate with
 * {@code ./gradlew :examples:showcase:test --tests '*EvidenceDeskGoldenTest*' -PevidenceGoldenWrite=true}.
 */
class EvidenceDeskGoldenTest {
    private static final String CHAIN_ID = "document-review-chain";
    private static final String PROPOSAL_ID = "review-golden-1";
    private static final String ENTITY_ID = "document-golden-1";
    private static final String DOCUMENT_REF = "showcase://documents/document-golden-1";
    private static final long DEADLINE = 300;
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void goldenFixtureMatchesTheJavaContracts() throws IOException {
        ObjectNode expected = fixture();
        Path directory = Path.of(System.getProperty("yano.evidence.golden.dir",
                "../../products/evidence/ui/src/lib/fixtures"));
        Path file = directory.resolve("golden-role-workflow.json");
        if (Boolean.getBoolean("yano.evidence.golden.write")) {
            Files.createDirectories(directory);
            Files.writeString(file, JSON.writeValueAsString(expected) + "\n", StandardCharsets.UTF_8);
        }
        assertThat(file).as("committed golden fixture; regenerate with -PevidenceGoldenWrite=true")
                .exists();
        JsonNode committed = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        // Round-trip the in-memory tree so numeric node types match what readTree produces.
        assertThat(committed).isEqualTo(JSON.readTree(JSON.writeValueAsString(expected)));
    }

    private static ObjectNode fixture() {
        GovernedGenesisV1 genesis = ShowcaseAuthenticatedMapConfig.documentReviewGenesis(CHAIN_ID);
        ApprovalPolicyV1 policy = genesis.approvalPolicy(DocumentReviewPreset.POLICY_ID);
        ObjectNode root = JSON.createObjectNode();
        root.put("chainId", CHAIN_ID);

        ObjectNode organizations = root.putObject("organizations");
        for (OrganizationRecordV1 organization : genesis.organizations()) {
            ObjectNode node = organizations.putObject(organization.organizationId());
            node.put("revision", organization.revision());
            node.put("status", organization.status().name());
            node.put("recordHex", HEX.formatHex(organization.encode()));
        }

        ObjectNode actors = root.putObject("actors");
        for (GenesisActorV1 genesisActor : genesis.actors()) {
            ActorRecordV1 actor = genesisActor.actor();
            byte[] seed = ShowcaseAuthenticatedMapConfig.demoActorSeed(actor.actorId());
            ObjectNode node = actors.putObject(actor.actorId());
            node.put("organizationId", actor.organizationId());
            node.put("revision", actor.revision());
            node.put("status", actor.status().name());
            ArrayNode roles = node.putArray("roles");
            actor.roles().forEach(roles::add);
            node.put("keyId", actor.keys().getFirst().keyId());
            node.put("publicKeyHex", HEX.formatHex(actor.keys().getFirst().publicKey()));
            node.put("seedHex", HEX.formatHex(seed));
            node.put("derivedPublicKeyHex",
                    HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
            node.put("recordHex", HEX.formatHex(actor.encode()));
        }

        ObjectNode policyNode = root.putObject("policy");
        policyNode.put("policyId", policy.policyId());
        policyNode.put("revision", policy.revision());
        policyNode.put("status", policy.status().name());
        ArrayNode proposers = policyNode.putArray("proposerRoles");
        policy.proposerRoles().forEach(proposers::add);
        ArrayNode clauses = policyNode.putArray("clauses");
        for (ApprovalPolicyV1.RequiredClause clause : policy.clauses()) {
            ObjectNode node = clauses.addObject();
            node.put("clauseId", clause.clauseId());
            node.put("role", clause.role());
            node.put("minimumCount", clause.minimumCount());
            node.put("distinctBy", clause.distinctBy().name());
        }
        policyNode.put("rejectionMode", policy.rejectionMode().name());
        policyNode.put("maximumLifetimeBlocks", policy.maximumLifetimeBlocks());
        policyNode.put("digestHex", HEX.formatHex(policy.digest()));
        policyNode.put("recordHex", HEX.formatHex(policy.encode()));

        byte[] documentHash = Blake2bUtil.blake2bHash256(
                "golden document bytes".getBytes(StandardCharsets.UTF_8));
        DocumentReviewCommandV1 command = new DocumentReviewCommandV1(
                PROPOSAL_ID, DocumentReviewPreset.POLICY_ID, 1, ENTITY_ID, documentHash,
                DOCUMENT_REF);
        byte[] commandBytes = command.encode();
        byte[] commitment = command.actionCommitment();
        ObjectNode commandNode = root.putObject("command");
        commandNode.put("proposalId", PROPOSAL_ID);
        commandNode.put("policyId", DocumentReviewPreset.POLICY_ID);
        commandNode.put("policyRevision", 1);
        commandNode.put("documentEntityId", ENTITY_ID);
        commandNode.put("documentHashHex", HEX.formatHex(documentHash));
        commandNode.put("documentRef", DOCUMENT_REF);
        commandNode.put("encodedHex", HEX.formatHex(commandBytes));
        commandNode.put("actionCommitmentHex", HEX.formatHex(commitment));
        commandNode.put("topic", DocumentReviewCommandV1.TOPIC);
        commandNode.put("payloadDomain", DocumentReviewCommandV1.PAYLOAD_DOMAIN);

        ActorStatementV1 propose = new ActorStatementV1(ActorStatementV1.Action.PROPOSE,
                CHAIN_ID, PROPOSAL_ID, DocumentReviewPreset.POLICY_ID, 1,
                DocumentReviewCommandV1.PAYLOAD_DOMAIN, commitment, DEADLINE,
                "issuer-a", 1, "issuer-a-k1", "");
        ActorStatementV1 approveA = new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                CHAIN_ID, PROPOSAL_ID, DocumentReviewPreset.POLICY_ID, 1,
                DocumentReviewCommandV1.PAYLOAD_DOMAIN, commitment, DEADLINE,
                "auditor-a", 1, "auditor-a-k1", "independent-auditors");
        ActorStatementV1 approveB = new ActorStatementV1(ActorStatementV1.Action.APPROVE,
                CHAIN_ID, PROPOSAL_ID, DocumentReviewPreset.POLICY_ID, 1,
                DocumentReviewCommandV1.PAYLOAD_DOMAIN, commitment, DEADLINE,
                "auditor-b", 1, "auditor-b-k1", "independent-auditors");
        SignedActorCommandV1 signedPropose = SignedActorCommandV1.sign(propose,
                ShowcaseAuthenticatedMapConfig.demoActorSeed("issuer-a"));
        SignedActorCommandV1 signedApproveA = SignedActorCommandV1.sign(approveA,
                ShowcaseAuthenticatedMapConfig.demoActorSeed("auditor-a"));
        SignedActorCommandV1 signedApproveB = SignedActorCommandV1.sign(approveB,
                ShowcaseAuthenticatedMapConfig.demoActorSeed("auditor-b"));
        root.set("propose", statement(propose, signedPropose));
        root.set("approveA", statement(approveA, signedApproveA));
        root.set("approveB", statement(approveB, signedApproveB));

        ApprovalProposalV1 proposal = new ApprovalProposalV1(PROPOSAL_ID,
                DocumentReviewPreset.POLICY_ID, 1, policy.digest(),
                DocumentReviewCommandV1.PAYLOAD_DOMAIN, commitment, DEADLINE,
                ApprovalProposalV1.ProposalStatus.APPROVED,
                "issuer-a", "acme-manufacturing", 1, "issuer", 1, "issuer-a-k1", 1,
                List.of(
                        new ApprovalProposalV1.AcceptedDecisionV1(
                                ActorStatementV1.Action.APPROVE, "auditor-a",
                                "auditor-guild-a", 1, "auditor", 1, "auditor-a-k1",
                                "independent-auditors", approveA.digest(),
                                signedApproveA.signature(), 2),
                        new ApprovalProposalV1.AcceptedDecisionV1(
                                ActorStatementV1.Action.APPROVE, "auditor-b",
                                "auditor-guild-b", 1, "auditor", 1, "auditor-b-k1",
                                "independent-auditors", approveB.digest(),
                                signedApproveB.signature(), 3)));
        ObjectNode proposalNode = root.putObject("proposal");
        proposalNode.put("status", proposal.status().name());
        proposalNode.put("createdHeight", proposal.createdHeight());
        proposalNode.put("decisionCount", proposal.decisions().size());
        proposalNode.put("recordHex", HEX.formatHex(proposal.encode()));

        byte[] messageId = Blake2bUtil.blake2bHash256(
                "golden release message".getBytes(StandardCharsets.UTF_8));
        DocumentReviewReceiptV1 receipt = new DocumentReviewReceiptV1(PROPOSAL_ID, ENTITY_ID,
                commitment, DocumentReviewPreset.POLICY_ID, 1, 4, messageId);
        ObjectNode receiptNode = root.putObject("receipt");
        receiptNode.put("appliedHeight", 4);
        receiptNode.put("messageIdHex", HEX.formatHex(messageId));
        receiptNode.put("recordHex", HEX.formatHex(receipt.encode()));

        RoleApprovalStatsV1 stats = new RoleApprovalStatsV1(1, 0, 1, 0, 0, 0);
        ObjectNode statsNode = root.putObject("stats");
        statsNode.put("recordHex", HEX.formatHex(stats.encode()));

        byte[] proposeMessageId = Blake2bUtil.blake2bHash256(
                "golden propose message".getBytes(StandardCharsets.UTF_8));
        RoleCommandResultV1 result = new RoleCommandResultV1(RoleCommandResultV1.KIND_APPROVAL,
                PROPOSAL_ID, RoleWorkflowResultCode.ACCEPTED, 1, proposeMessageId,
                RoleCommandResultV1.commandDigest(signedPropose.encode()));
        ObjectNode resultNode = root.putObject("commandResult");
        resultNode.put("messageIdHex", HEX.formatHex(proposeMessageId));
        resultNode.put("resultCode", result.resultCode().name());
        resultNode.put("appliedHeight", 1);
        resultNode.put("recordHex", HEX.formatHex(result.encode()));

        ObjectNode keys = root.putObject("physicalKeys");
        keys.put("organizationCurrent", physical("domain-actors",
                RoleWorkflowKeys.organizationCurrent("acme-manufacturing")));
        keys.put("actorCurrent", physical("domain-actors", RoleWorkflowKeys.actorCurrent("issuer-a")));
        keys.put("actorRevision", physical("domain-actors", RoleWorkflowKeys.actorRevision("issuer-a", 1)));
        keys.put("policyCurrent", physical("role-approvals",
                RoleWorkflowKeys.policyCurrent(DocumentReviewPreset.POLICY_ID)));
        keys.put("policyRevision", physical("role-approvals",
                RoleWorkflowKeys.policyRevision(DocumentReviewPreset.POLICY_ID, 1)));
        keys.put("proposal", physical("role-approvals", RoleWorkflowKeys.proposal(PROPOSAL_ID)));
        keys.put("stats", physical("role-approvals", RoleWorkflowKeys.approvalStats()));
        keys.put("commandResult", physical("role-approvals",
                RoleWorkflowKeys.commandResult(proposeMessageId)));
        keys.put("receipt", physical("document-review-receipts",
                DocumentReviewReceiptStateMachine.receiptKey(PROPOSAL_ID)));
        keys.put("documentHead", physical("documents", DocTrailContract.entityKey(ENTITY_ID)));
        return root;
    }

    private static ObjectNode statement(ActorStatementV1 statement, SignedActorCommandV1 signed) {
        ObjectNode node = JSON.createObjectNode();
        node.put("action", statement.action().name());
        node.put("chainId", statement.chainId());
        node.put("proposalId", statement.proposalId());
        node.put("policyId", statement.policyId());
        node.put("policyRevision", statement.policyRevision());
        node.put("payloadDomain", statement.payloadDomain());
        node.put("payloadHashHex", HEX.formatHex(statement.payloadHash()));
        node.put("deadlineHeight", statement.deadlineHeight());
        node.put("actorId", statement.actorId());
        node.put("actorRevision", statement.actorRevision());
        node.put("keyId", statement.keyId());
        node.put("clauseId", statement.clauseId());
        node.put("statementHex", HEX.formatHex(statement.encode()));
        node.put("preimageHex", HEX.formatHex(statement.signingPreimage()));
        node.put("digestHex", HEX.formatHex(statement.digest()));
        node.put("signatureHex", HEX.formatHex(signed.signature()));
        node.put("commandHex", HEX.formatHex(signed.encode()));
        return node;
    }

    private static String physical(String component, byte[] localKey) {
        return HEX.formatHex(CompositeCommitmentV1.componentKey(component, localKey));
    }
}
