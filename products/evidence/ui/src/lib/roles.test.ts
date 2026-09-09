// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
  DOCUMENT_REVIEW,
  actionCommitmentHex,
  decodeDocumentReview,
  decodeReceipt,
  documentEntityKey,
  encodeDocumentReview,
  receiptKey,
  selectAdapter
} from './adapters';
import { toHex } from './cbor';
import {
  clauseProgress,
  decisionObstacle,
  decodeActor,
  decodeCommandResult,
  decodeOrganization,
  decodePointer,
  decodePolicy,
  decodeProposal,
  decodeSignedCommand,
  decodeStats,
  encodeActor,
  encodeOrganization,
  encodePolicy,
  encodeProposal,
  encodeSignedCommand,
  encodeStatement,
  physicalKeyHex,
  roleKeys,
  statementPreimage,
  validateStatement
} from './roles';
import type { ActorStatement, StatementAction } from './types';

interface GoldenStatement extends Record<string, unknown> {
  action: StatementAction;
  chainId: string;
  proposalId: string;
  policyId: string;
  policyRevision: number;
  payloadDomain: string;
  payloadHashHex: string;
  deadlineHeight: number;
  actorId: string;
  actorRevision: number;
  keyId: string;
  clauseId: string;
  statementHex: string;
  preimageHex: string;
  signatureHex: string;
  commandHex: string;
}

interface Golden {
  chainId: string;
  organizations: Record<string, { revision: number; status: string; recordHex: string }>;
  actors: Record<string, { organizationId: string; revision: number; roles: string[]; keyId: string;
    publicKeyHex: string; seedHex: string; derivedPublicKeyHex: string; recordHex: string }>;
  policy: { policyId: string; revision: number; clauses: Array<{ clauseId: string; role: string; minimumCount: number; distinctBy: string }>;
    rejectionMode: string; maximumLifetimeBlocks: number; digestHex: string; recordHex: string };
  command: { proposalId: string; policyId: string; policyRevision: number; documentEntityId: string;
    documentHashHex: string; documentRef: string; encodedHex: string; actionCommitmentHex: string; topic: string; payloadDomain: string };
  propose: GoldenStatement;
  approveA: GoldenStatement;
  approveB: GoldenStatement;
  proposal: { status: string; createdHeight: number; decisionCount: number; recordHex: string };
  receipt: { appliedHeight: number; messageIdHex: string; recordHex: string };
  stats: { recordHex: string };
  commandResult: { messageIdHex: string; resultCode: string; appliedHeight: number; recordHex: string };
  physicalKeys: Record<string, string>;
}

const golden = JSON.parse(readFileSync(new URL('./fixtures/golden-role-workflow.json', import.meta.url), 'utf8')) as Golden;

function statementOf(entry: GoldenStatement): ActorStatement {
  const { action, chainId, proposalId, policyId, policyRevision, payloadDomain, payloadHashHex,
    deadlineHeight, actorId, actorRevision, keyId, clauseId } = entry;
  return { action, chainId, proposalId, policyId, policyRevision, payloadDomain, payloadHashHex,
    deadlineHeight, actorId, actorRevision, keyId, clauseId };
}

describe('role-workflow record port', () => {
  it('decodes and re-encodes genesis organizations and actors', () => {
    for (const [id, entry] of Object.entries(golden.organizations)) {
      const record = decodeOrganization(entry.recordHex);
      expect(record.organizationId).toBe(id);
      expect(record.revision).toBe(entry.revision);
      expect(record.status).toBe(entry.status);
      expect(toHex(encodeOrganization(record))).toBe(entry.recordHex);
    }
    for (const [id, entry] of Object.entries(golden.actors)) {
      const record = decodeActor(entry.recordHex);
      expect(record.actorId).toBe(id);
      expect(record.organizationId).toBe(entry.organizationId);
      expect(record.roles).toEqual(entry.roles);
      expect(record.keys[0].keyId).toBe(entry.keyId);
      expect(record.keys[0].publicKeyHex).toBe(entry.publicKeyHex);
      expect(entry.derivedPublicKeyHex).toBe(entry.publicKeyHex);
      expect(toHex(encodeActor(record))).toBe(entry.recordHex);
    }
  });

  it('decodes the policy, the proposal, statistics, and a command result', () => {
    const policy = decodePolicy(golden.policy.recordHex);
    expect(policy.policyId).toBe(golden.policy.policyId);
    expect(policy.clauses).toEqual(golden.policy.clauses);
    expect(policy.rejectionMode).toBe(golden.policy.rejectionMode);
    expect(policy.maximumLifetimeBlocks).toBe(golden.policy.maximumLifetimeBlocks);
    expect(toHex(encodePolicy(policy))).toBe(golden.policy.recordHex);

    const proposal = decodeProposal(golden.proposal.recordHex);
    expect(proposal.status).toBe(golden.proposal.status);
    expect(proposal.decisions).toHaveLength(golden.proposal.decisionCount);
    expect(proposal.policyDigestHex).toBe(golden.policy.digestHex);
    expect(proposal.payloadHashHex).toBe(golden.command.actionCommitmentHex);
    expect(proposal.decisions[0].signatureHex).toBe(golden.approveA.signatureHex);
    expect(proposal.decisions[0].statementDigestHex).toBe(golden.approveA.digestHex);
    expect(toHex(encodeProposal(proposal))).toBe(golden.proposal.recordHex);

    expect(decodeStats(golden.stats.recordHex)).toEqual({ created: 1, pending: 0, approved: 1, rejected: 0, cancelled: 0, expired: 0 });

    const result = decodeCommandResult(golden.commandResult.recordHex);
    expect(result.resultCode).toBe(golden.commandResult.resultCode);
    expect(result.messageIdHex).toBe(golden.commandResult.messageIdHex);
    expect(result.appliedHeight).toBe(golden.commandResult.appliedHeight);
    expect(result.subjectId).toBe(golden.command.proposalId);
  });

  it('rejects non-canonical and truncated records', () => {
    expect(() => decodeOrganization(golden.organizations['acme-manufacturing'].recordHex.slice(0, -2))).toThrow();
    // Non-minimal integer encoding of the version (0x1801 instead of 0x01).
    expect(() => decodePolicy(`88${'1801'}${golden.policy.recordHex.slice(4)}`)).toThrow();
    expect(() => decodePointer('00')).toThrow();
    expect(decodePointer('0000000000000001')).toBe(1);
    expect(decodePointer('')).toBe(0);
  });

  it('encodes statements, preimages, and signed commands exactly like the Java contract', () => {
    for (const entry of [golden.propose, golden.approveA, golden.approveB]) {
      const statement = statementOf(entry);
      expect(toHex(encodeStatement(statement))).toBe(entry.statementHex);
      expect(toHex(statementPreimage(statement))).toBe(entry.preimageHex);
      expect(toHex(encodeSignedCommand({ statement, signatureHex: entry.signatureHex }))).toBe(entry.commandHex);
      const decoded = decodeSignedCommand(entry.commandHex);
      expect(decoded.statement).toEqual(validateStatement(statement));
      expect(decoded.signatureHex).toBe(entry.signatureHex);
    }
    expect(() => validateStatement({ ...statementOf(golden.propose), clauseId: 'x' })).toThrow('no clause');
    expect(() => validateStatement({ ...statementOf(golden.approveA), clauseId: '' })).toThrow('needs a clause');
  });

  it('derives the physical composite keys the chain uses', () => {
    expect(physicalKeyHex('domain-actors', roleKeys.organizationCurrent('acme-manufacturing'))).toBe(golden.physicalKeys.organizationCurrent);
    expect(physicalKeyHex('domain-actors', roleKeys.actorCurrent('issuer-a'))).toBe(golden.physicalKeys.actorCurrent);
    expect(physicalKeyHex('domain-actors', roleKeys.actorRevision('issuer-a', 1))).toBe(golden.physicalKeys.actorRevision);
    expect(physicalKeyHex('role-approvals', roleKeys.policyCurrent('document-release'))).toBe(golden.physicalKeys.policyCurrent);
    expect(physicalKeyHex('role-approvals', roleKeys.policyRevision('document-release', 1))).toBe(golden.physicalKeys.policyRevision);
    expect(physicalKeyHex('role-approvals', roleKeys.proposal(golden.command.proposalId))).toBe(golden.physicalKeys.proposal);
    expect(physicalKeyHex('role-approvals', roleKeys.stats())).toBe(golden.physicalKeys.stats);
    expect(physicalKeyHex('role-approvals', roleKeys.commandResult(golden.commandResult.messageIdHex))).toBe(golden.physicalKeys.commandResult);
    expect(physicalKeyHex('document-review-receipts', receiptKey(golden.command.proposalId))).toBe(golden.physicalKeys.receipt);
    expect(physicalKeyHex('documents', documentEntityKey(golden.command.documentEntityId))).toBe(golden.physicalKeys.documentHead);
  });

  it('evaluates clauses and predicts the processor\'s obstacles', () => {
    const policy = decodePolicy(golden.policy.recordHex);
    const proposal = decodeProposal(golden.proposal.recordHex);
    const progress = clauseProgress(policy, proposal.decisions);
    expect(progress[0].distinctCount).toBe(2);
    expect(progress[0].satisfied).toBe(true);

    const pending = { ...proposal, status: 'PENDING' as const, decisions: [proposal.decisions[0]] };
    const auditorA = decodeActor(golden.actors['auditor-a'].recordHex);
    const auditorB = decodeActor(golden.actors['auditor-b'].recordHex);
    const issuer = decodeActor(golden.actors['issuer-a'].recordHex);
    const sameGuild = { ...auditorB, actorId: 'auditor-c', organizationId: auditorA.organizationId };
    expect(decisionObstacle(policy, pending, auditorB, 'independent-auditors', 10)).toBeNull();
    expect(decisionObstacle(policy, pending, auditorA, 'independent-auditors', 10)).toBe('CONFLICT');
    expect(decisionObstacle(policy, pending, issuer, 'independent-auditors', 10)).toBe('ROLE_MISMATCH');
    expect(decisionObstacle(policy, pending, sameGuild, 'independent-auditors', 10)).toBe('DISTINCTNESS_DUPLICATE');
    expect(decisionObstacle(policy, pending, auditorB, 'independent-auditors', pending.deadlineHeight + 1)).toBe('EXPIRED');
    expect(decisionObstacle(policy, proposal, auditorB, 'independent-auditors', 10)).toBe('TERMINAL');
  });
});

describe('document-review adapter', () => {
  it('encodes the release command and its commitment like the showcase', () => {
    const { proposalId, policyId, policyRevision, documentEntityId, documentHashHex, documentRef } = golden.command;
    const command = { proposalId, policyId, policyRevision, documentEntityId, documentHashHex, documentRef };
    expect(toHex(encodeDocumentReview(command))).toBe(golden.command.encodedHex);
    expect(actionCommitmentHex(command)).toBe(golden.command.actionCommitmentHex);
    expect(decodeDocumentReview(golden.command.encodedHex)).toEqual(command);
    expect(golden.command.topic).toBe(DOCUMENT_REVIEW.topic);
    expect(golden.command.payloadDomain).toBe(DOCUMENT_REVIEW.payloadDomain);
    expect(golden.propose.payloadHashHex).toBe(golden.command.actionCommitmentHex);
  });

  it('decodes the consumption receipt', () => {
    const receipt = decodeReceipt(golden.receipt.recordHex);
    expect(receipt.proposalId).toBe(golden.command.proposalId);
    expect(receipt.documentEntityId).toBe(golden.command.documentEntityId);
    expect(receipt.actionCommitmentHex).toBe(golden.command.actionCommitmentHex);
    expect(receipt.appliedHeight).toBe(golden.receipt.appliedHeight);
    expect(receipt.messageIdHex).toBe(golden.receipt.messageIdHex);
  });

  it('selects adapters from the capability manifest', () => {
    const manifest = (ids: string[]) => ({ capabilityManifest: { components: ids.map((id) => ({ id })) } });
    expect(selectAdapter(manifest(['domain-actors', 'role-approvals', 'documents', 'document-review-receipts']))).toBe('document-review');
    expect(selectAdapter(manifest(['registry', 'domain-actors', 'role-approvals', 'doc-trail', 'evidence']))).toBe('role-evidence');
    expect(selectAdapter(manifest(['domain-actors', 'role-approvals']))).toBe('approvals-only');
    expect(selectAdapter(manifest(['doc-trail']))).toBeNull();
    expect(selectAdapter(null)).toBeNull();
  });
});
