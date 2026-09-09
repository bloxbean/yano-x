/**
 * Desk flows (ADR-048 §2, §3, §5): proof-bound reads through the state proof endpoint, the
 * bounded block scan that lists proposals, statement construction, submission with finality, and
 * the export bundle. Everything here is pure over the API client so the live test can drive it.
 */
import { DOCUMENT_REVIEW, actionCommitmentHex, decodeDocumentReview, decodeReceipt, decodeTrailHead, documentEntityKey, encodeDocumentReview, payloadDomainFor, receiptKey } from './adapters';
import type { YanoApi } from './api';
import { fromHex, toHex, utf8 } from './cbor';
import {
  DOMAIN_ACTORS_COMPONENT,
  ROLE_APPROVALS_COMPONENT,
  ROLE_APPROVALS_TOPIC,
  activeKey,
  decodeActor,
  decodeCommandResult,
  decodeOrganization,
  decodePointer,
  decodePolicy,
  decodeProposal,
  decodeSignedCommand,
  decodeStats,
  physicalKeyHex,
  roleKeys
} from './roles';
import type {
  ActorRecord,
  ActorStatement,
  ApprovalStats,
  CommandResult,
  DocumentReviewCommand,
  DocumentReviewReceipt,
  FinalizedMessage,
  OrganizationRecord,
  PolicyRecord,
  ProofBound,
  ProposalRecord,
  ReleaseAdapter,
  ScannedCommand,
  StateProofEnvelope,
  TrailHead
} from './types';

export const FINALITY_TIMEOUT_MS = 90_000;
export const FINALITY_POLL_MS = 1_000;
/** Blocks looked back by the proposal scan and the most block bodies fetched per pass. */
export const SCAN_WINDOW_BLOCKS = 2_000;
export const SCAN_MAX_BLOCK_FETCHES = 200;
const SCAN_PAGE = 100;
/** Proposal deadline offset the desk proposes (bounded by the policy's maximum lifetime). */
export const PROPOSAL_LIFETIME_CAP = 300;

// ---- proof-bound reads -------------------------------------------------------------------------

/**
 * Reads a record straight from the authenticated state at a height and binds the envelope: the key
 * must be the one asked for, the value present, and the block header at that height must carry
 * the envelope's root. Returns null when the key is absent.
 */
export async function readBound<T>(
  api: YanoApi,
  chainId: string,
  componentId: string,
  localKey: Uint8Array,
  decode: (valueHex: string) => T,
  height?: number
): Promise<ProofBound<T> | null> {
  const keyHex = physicalKeyHex(componentId, localKey);
  const envelope = await api.stateProof(chainId, keyHex, height);
  if (!envelope || envelope.presence !== 'PRESENT' || !envelope.valueHex) return null;
  const record = decode(envelope.valueHex);
  const binding = await bindEnvelope(api, chainId, envelope, keyHex, height);
  return {
    record,
    committedHeight: envelope.committedHeight,
    stateRootHex: envelope.stateRoot,
    blockHashHex: envelope.blockHash,
    physicalKeyHex: keyHex,
    valueHex: envelope.valueHex,
    proof: binding.state,
    detail: binding.detail,
    envelope
  };
}

async function bindEnvelope(
  api: YanoApi,
  chainId: string,
  envelope: StateProofEnvelope,
  keyHex: string,
  height?: number
): Promise<{ state: 'BOUND' | 'MISMATCH'; detail: string }> {
  const problems: string[] = [];
  if (envelope.key.toLowerCase() !== keyHex) problems.push('the proof names another key');
  if (envelope.chainId !== chainId) problems.push('the proof names another chain');
  if (height !== undefined && envelope.committedHeight !== height) problems.push('the proof is for another height');
  if (envelope.block && envelope.block.stateRoot !== envelope.stateRoot) problems.push('the certified header carries another root');
  if (envelope.block && envelope.block.blockHash !== envelope.blockHash) problems.push('the certified header hash differs');
  const header = await api.block(chainId, envelope.committedHeight);
  if (!header) problems.push(`the node has no block ${envelope.committedHeight}`);
  else if (header.stateRoot !== envelope.stateRoot) problems.push('the finalized block carries another root');
  return problems.length
    ? { state: 'MISMATCH', detail: problems.join('; ') }
    : { state: 'BOUND', detail: `bound to root ${envelope.stateRoot.slice(0, 12)}… at height ${envelope.committedHeight}` };
}

export function readOrganization(api: YanoApi, chainId: string, id: string, revision?: number) {
  return revision
    ? readBound(api, chainId, DOMAIN_ACTORS_COMPONENT, roleKeys.organizationRevision(id, revision), decodeOrganization)
    : readCurrent(api, chainId, DOMAIN_ACTORS_COMPONENT, roleKeys.organizationCurrent(id),
      (current) => roleKeys.organizationRevision(id, current), decodeOrganization);
}

export function readActor(api: YanoApi, chainId: string, id: string, revision?: number) {
  return revision
    ? readBound(api, chainId, DOMAIN_ACTORS_COMPONENT, roleKeys.actorRevision(id, revision), decodeActor)
    : readCurrent(api, chainId, DOMAIN_ACTORS_COMPONENT, roleKeys.actorCurrent(id),
      (current) => roleKeys.actorRevision(id, current), decodeActor);
}

export function readPolicy(api: YanoApi, chainId: string, id: string, revision?: number) {
  return revision
    ? readBound(api, chainId, ROLE_APPROVALS_COMPONENT, roleKeys.policyRevision(id, revision), decodePolicy)
    : readCurrent(api, chainId, ROLE_APPROVALS_COMPONENT, roleKeys.policyCurrent(id),
      (current) => roleKeys.policyRevision(id, current), decodePolicy);
}

export function readProposal(api: YanoApi, chainId: string, id: string, height?: number) {
  return readBound(api, chainId, ROLE_APPROVALS_COMPONENT, roleKeys.proposal(id), decodeProposal, height);
}

export function readStats(api: YanoApi, chainId: string) {
  return readBound(api, chainId, ROLE_APPROVALS_COMPONENT, roleKeys.stats(), decodeStats);
}

export function readCommandResult(api: YanoApi, chainId: string, messageIdHex: string, height?: number) {
  return readBound(api, chainId, ROLE_APPROVALS_COMPONENT, roleKeys.commandResult(messageIdHex), decodeCommandResult, height);
}

export function readReceipt(api: YanoApi, chainId: string, proposalId: string, height?: number) {
  return readBound(api, chainId, DOCUMENT_REVIEW.receiptsComponent, receiptKey(proposalId), decodeReceipt, height);
}

export function readDocumentHead(api: YanoApi, chainId: string, entityId: string, height?: number) {
  return readBound(api, chainId, DOCUMENT_REVIEW.documentsComponent, documentEntityKey(entityId), decodeTrailHead, height);
}

/** Resolves a current pointer and then the revision it names, both at the pointer's height. */
async function readCurrent<T>(
  api: YanoApi,
  chainId: string,
  componentId: string,
  pointerKey: Uint8Array,
  revisionKey: (revision: number) => Uint8Array,
  decode: (valueHex: string) => T
): Promise<ProofBound<T> | null> {
  const pointer = await readBound(api, chainId, componentId, pointerKey, decodePointer);
  if (!pointer || pointer.record === 0) return null;
  const record = await readBound(api, chainId, componentId, revisionKey(pointer.record), decode, pointer.committedHeight);
  if (!record) return null;
  if (pointer.proof === 'MISMATCH') {
    return { ...record, proof: 'MISMATCH', detail: `current pointer: ${pointer.detail}` };
  }
  return record;
}

// ---- block scan --------------------------------------------------------------------------------

export interface ScanResult {
  commands: ScannedCommand[];
  fromHeight: number;
  toHeight: number;
  truncated: boolean;
}

/**
 * Walks recent block summaries and decodes the role and release commands in blocks that carry
 * messages. Bounded by SCAN_WINDOW_BLOCKS and SCAN_MAX_BLOCK_FETCHES; newest blocks first.
 */
export async function scanCommands(
  api: YanoApi,
  chainId: string,
  tipHeight: number,
  adapter: ReleaseAdapter,
  window = SCAN_WINDOW_BLOCKS
): Promise<ScanResult> {
  const releaseTopic = adapter === 'document-review' ? DOCUMENT_REVIEW.topic : '';
  const commands: ScannedCommand[] = [];
  const from = Math.max(1, tipHeight - window + 1);
  let fetched = 0;
  let truncated = false;
  let cursor = tipHeight;
  while (cursor >= from && !truncated) {
    const pageFrom = Math.max(from, cursor - SCAN_PAGE + 1);
    const page = await api.blocks(chainId, pageFrom, cursor - pageFrom + 1);
    const withMessages = page.blocks.filter((block) => block.messageCount > 0 && block.height <= cursor)
      .sort((a, b) => b.height - a.height);
    for (const summary of withMessages) {
      if (fetched >= SCAN_MAX_BLOCK_FETCHES) { truncated = true; break; }
      fetched++;
      const block = await api.block(chainId, summary.height);
      if (!block) continue;
      block.messages.forEach((message, index) => {
        if (message.topic === ROLE_APPROVALS_TOPIC) {
          try {
            const { statement } = decodeSignedCommand(message.bodyHex);
            commands.push({ height: block.height, index, messageIdHex: message.messageId, topic: message.topic,
              proposalId: statement.proposalId, actorId: statement.actorId, action: statement.action });
          } catch {
            // Malformed finalized commands are no-ops on chain; the scan skips them the same way.
          }
        } else if (releaseTopic && message.topic === releaseTopic) {
          try {
            const command = decodeDocumentReview(message.bodyHex);
            commands.push({ height: block.height, index, messageIdHex: message.messageId, topic: message.topic,
              proposalId: command.proposalId, actorId: '', action: 'RELEASE', documentEntityId: command.documentEntityId });
          } catch {
            // Skipped like the chain skips it.
          }
        }
      });
    }
    cursor = pageFrom - 1;
  }
  return { commands, fromHeight: from, toHeight: tipHeight, truncated };
}

export function proposalIds(commands: ScannedCommand[]): string[] {
  return [...new Set(commands.map((command) => command.proposalId))];
}

export function actorIds(commands: ScannedCommand[]): string[] {
  return [...new Set(commands.map((command) => command.actorId).filter(Boolean))];
}

// ---- statements --------------------------------------------------------------------------------

export interface SigningIdentity {
  actor: ActorRecord;
  keyId: string;
  publicKeyHex: string;
}

/** Matches a signer's public key to the actor's ACTIVE key epoch at the tip; null when none. */
export function matchSigningKey(actor: ActorRecord, publicKeyHex: string, tipHeight: number): SigningIdentity | null {
  const key = activeKey(actor, tipHeight);
  if (!key || key.publicKeyHex !== publicKeyHex.toLowerCase()) return null;
  if (actor.status !== 'ACTIVE') return null;
  return { actor, keyId: key.keyId, publicKeyHex: key.publicKeyHex };
}

export function proposalStatement(
  chainId: string,
  identity: SigningIdentity,
  policy: PolicyRecord,
  adapter: ReleaseAdapter,
  proposalId: string,
  payloadHashHex: string,
  tipHeight: number
): ActorStatement {
  if (!policy.proposerRoles.some((role) => identity.actor.roles.includes(role))) {
    throw new Error(`${identity.actor.actorId} holds none of the proposer roles ${policy.proposerRoles.join(', ')}`);
  }
  return {
    action: 'PROPOSE',
    chainId,
    proposalId,
    policyId: policy.policyId,
    policyRevision: policy.revision,
    payloadDomain: payloadDomainFor(adapter),
    payloadHashHex,
    deadlineHeight: tipHeight + Math.min(policy.maximumLifetimeBlocks, PROPOSAL_LIFETIME_CAP),
    actorId: identity.actor.actorId,
    actorRevision: identity.actor.revision,
    keyId: identity.keyId,
    clauseId: ''
  };
}

/** APPROVE or REJECT: every bound field is copied from the proposal record (ADR-048 §2 step 5). */
export function decisionStatement(
  chainId: string,
  identity: SigningIdentity,
  proposal: ProposalRecord,
  action: 'APPROVE' | 'REJECT',
  clauseId: string,
  tipHeight: number
): ActorStatement {
  if (proposal.status !== 'PENDING') throw new Error(`The proposal is ${proposal.status.toLowerCase()}, not pending`);
  if (tipHeight > proposal.deadlineHeight) throw new Error(`The proposal expired at height ${proposal.deadlineHeight}`);
  return {
    action,
    chainId,
    proposalId: proposal.proposalId,
    policyId: proposal.policyId,
    policyRevision: proposal.policyRevision,
    payloadDomain: proposal.payloadDomain,
    payloadHashHex: proposal.payloadHashHex,
    deadlineHeight: proposal.deadlineHeight,
    actorId: identity.actor.actorId,
    actorRevision: identity.actor.revision,
    keyId: identity.keyId,
    clauseId
  };
}

// ---- release -----------------------------------------------------------------------------------

export function documentReviewCommand(
  proposalId: string,
  policy: PolicyRecord,
  documentEntityId: string,
  documentHashHex: string,
  documentRef: string
): DocumentReviewCommand {
  return { proposalId, policyId: policy.policyId, policyRevision: policy.revision, documentEntityId, documentHashHex, documentRef };
}

/** The release is only submitted when the command re-derives the proposal's payload hash. */
export function releaseBodyHex(command: DocumentReviewCommand, proposal: ProposalRecord): string {
  const commitment = actionCommitmentHex(command);
  if (commitment !== proposal.payloadHashHex) {
    throw new Error('These release inputs do not hash to the proposal\'s payload hash; use the exact inputs the proposal was made with');
  }
  if (proposal.status !== 'APPROVED') throw new Error(`The proposal is ${proposal.status.toLowerCase()}, not approved`);
  return toHex(encodeDocumentReview(command));
}

// ---- submission --------------------------------------------------------------------------------

export async function awaitFinalized(api: YanoApi, chainId: string, messageId: string, timeoutMs = FINALITY_TIMEOUT_MS): Promise<FinalizedMessage> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const finalized = await api.finalizedMessage(chainId, messageId);
    if (finalized && finalized.height > 0) return finalized;
    await new Promise((resolve) => setTimeout(resolve, FINALITY_POLL_MS));
  }
  throw new Error(`Message ${messageId} was not finalized within ${timeoutMs / 1000} seconds`);
}

export interface StatementOutcome {
  messageIdHex: string;
  height: number;
  result: ProofBound<CommandResult> | null;
}

/** Submits a signed statement, waits for finality, then reads the workflow's result record. */
export async function submitStatement(api: YanoApi, chainId: string, bodyHex: string): Promise<StatementOutcome> {
  const submitted = await api.submitMessage(chainId, ROLE_APPROVALS_TOPIC, bodyHex);
  const finalized = await awaitFinalized(api, chainId, submitted.messageId);
  const result = await readCommandResult(api, chainId, submitted.messageId, finalized.height);
  return { messageIdHex: submitted.messageId, height: finalized.height, result };
}

// ---- export ------------------------------------------------------------------------------------

export const EXPORT_SCHEMA = 'yano-x-evidence-desk-export-v1';

export interface DeskExport {
  schema: typeof EXPORT_SCHEMA;
  generator: string;
  issuedAt: string;
  chainId: string;
  adapter: ReleaseAdapter;
  proposal: ProofBound<ProposalRecord>;
  policy: ProofBound<PolicyRecord> | null;
  receipt: ProofBound<DocumentReviewReceipt> | null;
  documentHead: ProofBound<TrailHead> | null;
  stats: ProofBound<ApprovalStats> | null;
  organizations: Array<ProofBound<OrganizationRecord>>;
  actors: Array<ProofBound<ActorRecord>>;
}

export function buildExport(input: Omit<DeskExport, 'schema' | 'generator' | 'issuedAt'>): DeskExport {
  return { schema: EXPORT_SCHEMA, generator: 'yano-x-evidence-ui/1', issuedAt: new Date().toISOString(), ...input };
}

/** Utility for tests and the page: hex of UTF-8 text, used for query parameters. */
export function textParamHex(value: string): string {
  return toHex(utf8(value));
}

export function hexEquals(left: string, right: string): boolean {
  return toHex(fromHex(left)) === toHex(fromHex(right));
}
