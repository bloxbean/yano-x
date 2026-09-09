/**
 * Browser port of the role-workflow v1 contracts (capabilities/role-workflow-contracts) and the
 * composite key derivation, limited to what the desk reads and writes. Every encoder emits the
 * canonical CBOR the Java contracts emit; every decoder re-encodes and compares so a non-canonical
 * payload is rejected the way the chain rejects it.
 */
import { asArray, asBytes, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex, utf8, type CborValue } from './cbor';
import type {
  AcceptedDecision,
  ActorKeyEpoch,
  ActorRecord,
  ActorStatement,
  ApprovalStats,
  CommandResult,
  OrganizationRecord,
  PolicyClause,
  PolicyRecord,
  ProposalRecord,
  ProposalStatus,
  RecordStatus,
  ResultCode,
  SignedActorCommand,
  StatementAction
} from './types';

export const ROLE_APPROVALS_TOPIC = 'role-approvals.command.v1';
export const DOMAIN_ACTORS_COMPONENT = 'domain-actors';
export const ROLE_APPROVALS_COMPONENT = 'role-approvals';
export const MAX_COMMAND_BYTES = 16_384;
export const MAX_QUERY_PAGE_SIZE = 100;

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const PAYLOAD_DOMAIN = /^[a-z][a-z0-9.-]{0,63}$/;
const HEX_64 = /^[0-9a-f]{64}$/;
const HEX_128 = /^[0-9a-f]{128}$/;
const COMPOSITE_DOMAIN = utf8('yano-composite-state-v1\0');
const STATEMENT_DOMAIN = utf8('yano:role-approval:v1\0');

const RECORD_STATUS: RecordStatus[] = ['ACTIVE', 'SUSPENDED', 'REVOKED'];
const PROPOSAL_STATUS: ProposalStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'];
const ACTIONS: StatementAction[] = ['PROPOSE', 'APPROVE', 'REJECT', 'CANCEL'];
const DISTINCT_BY: PolicyClause['distinctBy'][] = ['ACTOR', 'ORGANIZATION'];
const REJECTION_MODE: PolicyRecord['rejectionMode'][] = ['DISABLED', 'ANY_ELIGIBLE'];
const RESULT_CODES: ResultCode[] = [
  'ACCEPTED', 'INVALID_PAYLOAD', 'UNSUPPORTED_VERSION', 'INVALID_SIGNATURE', 'UNAUTHORIZED_RELAY',
  'UNAUTHORIZED_ACTOR', 'UNKNOWN_RECORD', 'CONFLICT', 'EXACT_REPLAY', 'EXPIRED', 'TERMINAL',
  'ROLE_MISMATCH', 'DISTINCTNESS_DUPLICATE', 'GOVERNANCE_THRESHOLD_NOT_MET',
  'GOVERNANCE_PROOF_INVALID', 'LIMIT_EXCEEDED', 'CAPACITY_EXCEEDED', 'CRYPTO_WORK_EXCEEDED',
  'WRONG_GENESIS', 'WRONG_REVISION', 'NOT_READY', 'SUPERSEDED', 'GOVERNED_ROUTE_UNSUPPORTED'
];

export const RESULT_EXPLANATIONS: Record<ResultCode, string> = {
  ACCEPTED: 'The chain applied the statement.',
  INVALID_PAYLOAD: 'The command bytes were not a canonical role statement.',
  UNSUPPORTED_VERSION: 'The statement version is not supported by this chain.',
  INVALID_SIGNATURE: 'The signature did not verify against the actor\'s registered key.',
  UNAUTHORIZED_RELAY: 'The relaying member is not allowed to carry actor statements.',
  UNAUTHORIZED_ACTOR: 'The actor, its revision, key, or organization is not active.',
  UNKNOWN_RECORD: 'The proposal or policy named by the statement does not exist.',
  CONFLICT: 'The statement contradicts the proposal or an earlier decision by the same actor.',
  EXACT_REPLAY: 'The same statement was already applied; nothing changed.',
  EXPIRED: 'The proposal\'s deadline height has passed.',
  TERMINAL: 'The proposal is no longer pending.',
  ROLE_MISMATCH: 'The actor holds no role that the clause or policy requires.',
  DISTINCTNESS_DUPLICATE: 'Another actor from the same organization already decided this clause.',
  GOVERNANCE_THRESHOLD_NOT_MET: 'The governed mutation lacks administrator votes.',
  GOVERNANCE_PROOF_INVALID: 'A governance proof did not verify.',
  LIMIT_EXCEEDED: 'A deterministic limit (pending per actor or policy) was hit.',
  CAPACITY_EXCEEDED: 'The chain\'s pending capacity is full.',
  CRYPTO_WORK_EXCEEDED: 'The block\'s signature verification budget was exhausted; resubmit.',
  WRONG_GENESIS: 'The statement names another chain or genesis.',
  WRONG_REVISION: 'The actor or policy revision is not the current one.',
  NOT_READY: 'The record is not yet active at this height.',
  SUPERSEDED: 'A newer revision superseded this record.',
  GOVERNED_ROUTE_UNSUPPORTED: 'This chain does not accept that governed route.'
};

// ---- identifiers -------------------------------------------------------------------------------

export function requireId(value: string, field: string): string {
  if (!ID.test(value)) throw new Error(`${field} must match [a-z][a-z0-9-]{0,62}`);
  return value;
}

export function requirePayloadDomain(value: string): string {
  if (!PAYLOAD_DOMAIN.test(value)) throw new Error('payload domain must match [a-z][a-z0-9.-]{0,63}');
  return value;
}

export function requireHex64(value: string, field: string): string {
  const normalized = value.trim().toLowerCase();
  if (!HEX_64.test(normalized)) throw new Error(`${field} must be 32 bytes of hex`);
  return normalized;
}

// ---- composite keys ----------------------------------------------------------------------------

/** yano-composite-state-v1\0 + len(component) + component + len16(local) + local. */
export function physicalKey(componentId: string, localKey: Uint8Array): Uint8Array {
  requireId(componentId, 'componentId');
  const component = utf8(componentId);
  if (localKey.length === 0 || localKey.length > 65_535) throw new Error('local key length is out of range');
  const key = new Uint8Array(COMPOSITE_DOMAIN.length + 1 + component.length + 2 + localKey.length);
  let offset = 0;
  key.set(COMPOSITE_DOMAIN, offset); offset += COMPOSITE_DOMAIN.length;
  key[offset++] = component.length;
  key.set(component, offset); offset += component.length;
  key[offset++] = localKey.length >> 8;
  key[offset++] = localKey.length & 0xff;
  key.set(localKey, offset);
  return key;
}

export function physicalKeyHex(componentId: string, localKey: Uint8Array): string {
  return toHex(physicalKey(componentId, localKey));
}

export const roleKeys = {
  organizationCurrent: (id: string) => utf8(`o/${requireId(id, 'organizationId')}/current`),
  organizationRevision: (id: string, revision: number) => utf8(`o/${requireId(id, 'organizationId')}/r/${positive(revision)}`),
  actorCurrent: (id: string) => utf8(`a/${requireId(id, 'actorId')}/current`),
  actorRevision: (id: string, revision: number) => utf8(`a/${requireId(id, 'actorId')}/r/${positive(revision)}`),
  policyCurrent: (id: string) => utf8(`p/${requireId(id, 'policyId')}/current`),
  policyRevision: (id: string, revision: number) => utf8(`p/${requireId(id, 'policyId')}/r/${positive(revision)}`),
  proposal: (id: string) => utf8(`q/${requireId(id, 'proposalId')}`),
  stats: () => utf8('s/proposals/v1'),
  commandResult: (messageIdHex: string) => utf8(`r/c/${requireHex64(messageIdHex, 'messageId')}`)
};

/** Current pointers are 8-byte big-endian revisions; 0 or absent means no current revision. */
export function decodePointer(valueHex: string): number {
  const bytes = fromHex(valueHex);
  if (bytes.length === 0) return 0;
  if (bytes.length !== 8) throw new Error('A current pointer must be 8 bytes');
  let value = 0n;
  for (const byte of bytes) value = (value << 8n) | BigInt(byte);
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Pointer revision is out of range');
  return Number(value);
}

// ---- records -----------------------------------------------------------------------------------

export function decodeOrganization(valueHex: string): OrganizationRecord {
  const bytes = boundedRecord(valueHex);
  const items = asArray(decodeCbor(bytes), 5);
  requireVersion(items[0]);
  const record: OrganizationRecord = {
    organizationId: requireId(asText(items[1]), 'organizationId'),
    revision: asUnsigned(items[2]),
    status: enumValue(RECORD_STATUS, asUnsigned(items[3]), 'status'),
    metadataCommitmentHex: toHex(asBytes(items[4]))
  };
  requireCanonical(bytes, encodeOrganization(record));
  return record;
}

export function encodeOrganization(record: OrganizationRecord): Uint8Array {
  return encodeCbor([1n, record.organizationId, BigInt(record.revision),
    BigInt(RECORD_STATUS.indexOf(record.status)), fromHex(record.metadataCommitmentHex)]);
}

export function decodeActor(valueHex: string): ActorRecord {
  const bytes = boundedRecord(valueHex);
  const items = asArray(decodeCbor(bytes), 8);
  requireVersion(items[0]);
  const record: ActorRecord = {
    actorId: requireId(asText(items[1]), 'actorId'),
    organizationId: requireId(asText(items[2]), 'organizationId'),
    revision: asUnsigned(items[3]),
    status: enumValue(RECORD_STATUS, asUnsigned(items[4]), 'status'),
    roles: asArray(items[5]).map((role) => requireId(asText(role), 'role')),
    keys: asArray(items[6]).map(decodeKeyEpoch),
    metadataCommitmentHex: toHex(asBytes(items[7]))
  };
  requireCanonical(bytes, encodeActor(record));
  return record;
}

export function encodeActor(record: ActorRecord): Uint8Array {
  return encodeCbor([1n, record.actorId, record.organizationId, BigInt(record.revision),
    BigInt(RECORD_STATUS.indexOf(record.status)), record.roles,
    record.keys.map(encodeKeyEpoch), fromHex(record.metadataCommitmentHex)]);
}

function decodeKeyEpoch(value: CborValue): ActorKeyEpoch {
  const items = asArray(value, 5);
  return {
    keyId: requireId(asText(items[0]), 'keyId'),
    publicKeyHex: toHex(asBytes(items[1], 32)),
    validFromHeight: asUnsigned(items[2]),
    validUntilHeight: asUnsigned(items[3]),
    status: enumValue(RECORD_STATUS, asUnsigned(items[4]), 'key status')
  };
}

function encodeKeyEpoch(key: ActorKeyEpoch): CborValue {
  return [key.keyId, fromHex(key.publicKeyHex), BigInt(key.validFromHeight),
    BigInt(key.validUntilHeight), BigInt(RECORD_STATUS.indexOf(key.status))];
}

/**
 * The key epoch an actor signs with at a height: ACTIVE and inside its validity window
 * (a zero end height means open-ended). The chain re-checks this when the statement is applied.
 */
export function activeKey(actor: ActorRecord, height: number): ActorKeyEpoch | null {
  return actor.keys.find((key) => key.status === 'ACTIVE'
    && key.validFromHeight <= height
    && (key.validUntilHeight === 0 || height <= key.validUntilHeight)) ?? null;
}

export function decodePolicy(valueHex: string): PolicyRecord {
  const bytes = boundedRecord(valueHex);
  const items = asArray(decodeCbor(bytes), 8);
  requireVersion(items[0]);
  const record: PolicyRecord = {
    policyId: requireId(asText(items[1]), 'policyId'),
    revision: asUnsigned(items[2]),
    status: enumValue(RECORD_STATUS, asUnsigned(items[3]), 'status'),
    proposerRoles: asArray(items[4]).map((role) => requireId(asText(role), 'role')),
    clauses: asArray(items[5]).map(decodeClause),
    rejectionMode: enumValue(REJECTION_MODE, asUnsigned(items[6]), 'rejectionMode'),
    maximumLifetimeBlocks: asUnsigned(items[7])
  };
  requireCanonical(bytes, encodePolicy(record));
  return record;
}

export function encodePolicy(record: PolicyRecord): Uint8Array {
  return encodeCbor([1n, record.policyId, BigInt(record.revision),
    BigInt(RECORD_STATUS.indexOf(record.status)), record.proposerRoles,
    record.clauses.map((clause) => [clause.clauseId, clause.role, BigInt(clause.minimumCount),
      BigInt(DISTINCT_BY.indexOf(clause.distinctBy))]),
    BigInt(REJECTION_MODE.indexOf(record.rejectionMode)), BigInt(record.maximumLifetimeBlocks)]);
}

function decodeClause(value: CborValue): PolicyClause {
  const items = asArray(value, 4);
  return {
    clauseId: requireId(asText(items[0]), 'clauseId'),
    role: requireId(asText(items[1]), 'role'),
    minimumCount: asUnsigned(items[2]),
    distinctBy: enumValue(DISTINCT_BY, asUnsigned(items[3]), 'distinctBy')
  };
}

export function decodeProposal(valueHex: string): ProposalRecord {
  const bytes = boundedRecord(valueHex);
  const items = asArray(decodeCbor(bytes), 17);
  requireVersion(items[0]);
  const record: ProposalRecord = {
    proposalId: requireId(asText(items[1]), 'proposalId'),
    policyId: requireId(asText(items[2]), 'policyId'),
    policyRevision: asUnsigned(items[3]),
    policyDigestHex: toHex(asBytes(items[4], 32)),
    payloadDomain: requirePayloadDomain(asText(items[5])),
    payloadHashHex: toHex(asBytes(items[6], 32)),
    deadlineHeight: asUnsigned(items[7]),
    status: enumValue(PROPOSAL_STATUS, asUnsigned(items[8]), 'status'),
    proposerActorId: requireId(asText(items[9]), 'proposerActorId'),
    proposerOrganizationId: requireId(asText(items[10]), 'proposerOrganizationId'),
    proposerOrganizationRevision: asUnsigned(items[11]),
    proposerRole: requireId(asText(items[12]), 'proposerRole'),
    proposerActorRevision: asUnsigned(items[13]),
    proposerKeyId: requireId(asText(items[14]), 'proposerKeyId'),
    createdHeight: asUnsigned(items[15]),
    decisions: asArray(items[16]).map(decodeDecision)
  };
  requireCanonical(bytes, encodeProposal(record));
  return record;
}

export function encodeProposal(record: ProposalRecord): Uint8Array {
  return encodeCbor([1n, record.proposalId, record.policyId, BigInt(record.policyRevision),
    fromHex(record.policyDigestHex), record.payloadDomain, fromHex(record.payloadHashHex),
    BigInt(record.deadlineHeight), BigInt(PROPOSAL_STATUS.indexOf(record.status)),
    record.proposerActorId, record.proposerOrganizationId, BigInt(record.proposerOrganizationRevision),
    record.proposerRole, BigInt(record.proposerActorRevision), record.proposerKeyId,
    BigInt(record.createdHeight), record.decisions.map(encodeDecision)]);
}

function decodeDecision(value: CborValue): AcceptedDecision {
  const items = asArray(value, 11);
  const action = enumValue(ACTIONS, asUnsigned(items[0]), 'decision');
  if (action !== 'APPROVE' && action !== 'REJECT') throw new Error('A decision must approve or reject');
  return {
    action,
    actorId: requireId(asText(items[1]), 'actorId'),
    organizationId: requireId(asText(items[2]), 'organizationId'),
    organizationRevision: asUnsigned(items[3]),
    role: requireId(asText(items[4]), 'role'),
    actorRevision: asUnsigned(items[5]),
    keyId: requireId(asText(items[6]), 'keyId'),
    clauseId: requireId(asText(items[7]), 'clauseId'),
    statementDigestHex: toHex(asBytes(items[8], 32)),
    signatureHex: toHex(asBytes(items[9], 64)),
    acceptedHeight: asUnsigned(items[10])
  };
}

function encodeDecision(decision: AcceptedDecision): CborValue {
  return [BigInt(ACTIONS.indexOf(decision.action)), decision.actorId, decision.organizationId,
    BigInt(decision.organizationRevision), decision.role, BigInt(decision.actorRevision),
    decision.keyId, decision.clauseId, fromHex(decision.statementDigestHex),
    fromHex(decision.signatureHex), BigInt(decision.acceptedHeight)];
}

export function decodeStats(valueHex: string): ApprovalStats {
  const bytes = boundedRecord(valueHex);
  const items = asArray(decodeCbor(bytes), 7);
  requireVersion(items[0]);
  const record: ApprovalStats = {
    created: asUnsigned(items[1]),
    pending: asUnsigned(items[2]),
    approved: asUnsigned(items[3]),
    rejected: asUnsigned(items[4]),
    cancelled: asUnsigned(items[5]),
    expired: asUnsigned(items[6])
  };
  requireCanonical(bytes, encodeCbor([1n, ...[record.created, record.pending, record.approved,
    record.rejected, record.cancelled, record.expired].map(BigInt)]));
  return record;
}

export function decodeCommandResult(valueHex: string): CommandResult {
  const bytes = boundedRecord(valueHex);
  const items = asArray(decodeCbor(bytes), 7);
  requireVersion(items[0]);
  const record: CommandResult = {
    commandKind: asUnsigned(items[1]),
    subjectId: requireId(asText(items[2]), 'subjectId'),
    resultCode: enumValue(RESULT_CODES, asUnsigned(items[3]), 'resultCode'),
    appliedHeight: asUnsigned(items[4]),
    messageIdHex: toHex(asBytes(items[5], 32)),
    commandDigestHex: toHex(asBytes(items[6], 32))
  };
  requireCanonical(bytes, encodeCbor([1n, BigInt(record.commandKind), record.subjectId,
    BigInt(RESULT_CODES.indexOf(record.resultCode)), BigInt(record.appliedHeight),
    fromHex(record.messageIdHex), fromHex(record.commandDigestHex)]));
  return record;
}

// ---- statements --------------------------------------------------------------------------------

export function validateStatement(statement: ActorStatement): ActorStatement {
  if (!statement.chainId || statement.chainId.includes('\0') || utf8(statement.chainId).length > 128) {
    throw new Error('chainId must be 1..128 UTF-8 bytes without NUL');
  }
  requireId(statement.proposalId, 'proposalId');
  requireId(statement.policyId, 'policyId');
  requirePayloadDomain(statement.payloadDomain);
  requireId(statement.actorId, 'actorId');
  requireId(statement.keyId, 'keyId');
  const payloadHashHex = requireHex64(statement.payloadHashHex, 'payloadHash');
  const requiresClause = statement.action === 'APPROVE' || statement.action === 'REJECT';
  if (requiresClause !== (statement.clauseId !== '')) {
    throw new Error(requiresClause ? 'This action needs a clause id' : 'This action takes no clause id');
  }
  if (statement.clauseId) requireId(statement.clauseId, 'clauseId');
  if (statement.policyRevision < 1 || statement.actorRevision < 1 || statement.deadlineHeight < 1) {
    throw new Error('Revisions and the deadline height must be positive');
  }
  return { ...statement, payloadHashHex };
}

export function encodeStatement(statement: ActorStatement): Uint8Array {
  const valid = validateStatement(statement);
  return encodeCbor([1n, BigInt(ACTIONS.indexOf(valid.action)), valid.chainId, valid.proposalId,
    valid.policyId, BigInt(valid.policyRevision), valid.payloadDomain, fromHex(valid.payloadHashHex),
    BigInt(valid.deadlineHeight), valid.actorId, BigInt(valid.actorRevision), valid.keyId,
    valid.clauseId]);
}

/** yano:role-approval:v1\0 + 4-byte big-endian statement length + statement. */
export function statementPreimage(statement: ActorStatement): Uint8Array {
  const encoded = encodeStatement(statement);
  const preimage = new Uint8Array(STATEMENT_DOMAIN.length + 4 + encoded.length);
  preimage.set(STATEMENT_DOMAIN, 0);
  const offset = STATEMENT_DOMAIN.length;
  preimage[offset] = (encoded.length >>> 24) & 0xff;
  preimage[offset + 1] = (encoded.length >>> 16) & 0xff;
  preimage[offset + 2] = (encoded.length >>> 8) & 0xff;
  preimage[offset + 3] = encoded.length & 0xff;
  preimage.set(encoded, offset + 4);
  return preimage;
}

export function encodeSignedCommand(command: SignedActorCommand): Uint8Array {
  if (!HEX_128.test(command.signatureHex)) throw new Error('The signature must be 64 bytes of hex');
  const bytes = encodeCbor([1n, encodeStatement(command.statement), fromHex(command.signatureHex)]);
  if (bytes.length > MAX_COMMAND_BYTES) throw new Error('The signed command exceeds the chain bound');
  return bytes;
}

export function decodeSignedCommand(bodyHex: string): SignedActorCommand {
  const bytes = fromHex(bodyHex);
  if (bytes.length === 0 || bytes.length > MAX_COMMAND_BYTES) throw new Error('Command size is out of range');
  const items = asArray(decodeCbor(bytes), 3);
  requireVersion(items[0]);
  const statementBytes = asBytes(items[1]);
  const fields = asArray(decodeCbor(statementBytes), 13);
  requireVersion(fields[0]);
  const statement = validateStatement({
    action: enumValue(ACTIONS, asUnsigned(fields[1]), 'action'),
    chainId: asText(fields[2]),
    proposalId: asText(fields[3]),
    policyId: asText(fields[4]),
    policyRevision: asUnsigned(fields[5]),
    payloadDomain: asText(fields[6]),
    payloadHashHex: toHex(asBytes(fields[7], 32)),
    deadlineHeight: asUnsigned(fields[8]),
    actorId: asText(fields[9]),
    actorRevision: asUnsigned(fields[10]),
    keyId: asText(fields[11]),
    clauseId: asText(fields[12])
  });
  const command: SignedActorCommand = { statement, signatureHex: toHex(asBytes(items[2], 64)) };
  requireCanonical(bytes, encodeSignedCommand(command));
  return command;
}

// ---- policy evaluation (mirrors ActorApprovalProcessor.satisfied) --------------------------------

export interface ClauseProgress {
  clause: PolicyClause;
  approvals: AcceptedDecision[];
  distinctCount: number;
  satisfied: boolean;
}

/** Counts accepted approvals per clause, de-duplicated by the clause's distinctness rule. */
export function clauseProgress(policy: PolicyRecord, decisions: AcceptedDecision[]): ClauseProgress[] {
  return policy.clauses.map((clause) => {
    const approvals = decisions.filter((decision) => decision.clauseId === clause.clauseId && decision.action === 'APPROVE');
    const distinct = new Set(approvals.map((decision) => clause.distinctBy === 'ORGANIZATION'
      ? decision.organizationId : decision.actorId));
    return { clause, approvals, distinctCount: distinct.size, satisfied: distinct.size >= clause.minimumCount };
  });
}

/** Whether an actor could still add an accepted decision to a clause, per the processor's checks. */
export function decisionObstacle(
  policy: PolicyRecord,
  proposal: ProposalRecord,
  actor: ActorRecord,
  clauseId: string,
  tipHeight: number
): ResultCode | null {
  if (proposal.status !== 'PENDING') return 'TERMINAL';
  if (tipHeight > proposal.deadlineHeight) return 'EXPIRED';
  if (proposal.decisions.some((decision) => decision.actorId === actor.actorId)) return 'CONFLICT';
  const clause = policy.clauses.find((candidate) => candidate.clauseId === clauseId);
  if (!clause || !actor.roles.includes(clause.role)) return 'ROLE_MISMATCH';
  if (clause.distinctBy === 'ORGANIZATION' && proposal.decisions.some((decision) =>
    decision.clauseId === clause.clauseId && decision.organizationId === actor.organizationId)) {
    return 'DISTINCTNESS_DUPLICATE';
  }
  return null;
}

// ---- helpers -----------------------------------------------------------------------------------

function boundedRecord(valueHex: string): Uint8Array {
  const bytes = fromHex(valueHex);
  if (bytes.length === 0) throw new Error('The record is absent');
  if (bytes.length > MAX_COMMAND_BYTES) throw new Error('The record exceeds the chain bound');
  return bytes;
}

function requireVersion(value: CborValue): void {
  if (asUnsigned(value) !== 1) throw new Error('Unsupported record version');
}

function requireCanonical(actual: Uint8Array, expected: Uint8Array): void {
  if (actual.length !== expected.length || actual.some((byte, index) => byte !== expected[index])) {
    throw new Error('The record is not canonical CBOR');
  }
}

function enumValue<T>(values: readonly T[], code: number, what: string): T {
  const value = values[code];
  if (value === undefined) throw new Error(`Unknown ${what} code ${code}`);
  return value;
}

function positive(value: number): number {
  if (!Number.isInteger(value) || value < 1) throw new Error('Revision must be a positive integer');
  return value;
}
