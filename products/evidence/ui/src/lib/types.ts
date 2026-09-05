export interface RuntimeEndpoint {
  id: string;
  nodeUrl: string;
  apiPrefix: string;
  label?: string;
}

export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'evidence';
  endpoints: RuntimeEndpoint[];
  defaultChainId: string;
  expectedNetwork: string;
  allowEndpointOverride: boolean;
  /** Optional per-chain identifiers to offer in the directory before any command has been seen. */
  directoryHints?: Record<string, DirectoryHints>;
}

export interface ActiveConnection {
  endpointId: string;
  nodeUrl: string;
  apiPrefix: string;
  apiBase: string;
  apiKey: string;
  label: string;
}

export interface NodeConfig {
  protocolMagic?: number;
  network?: string;
  version?: string;
}

export interface NodeStatus {
  running?: boolean;
  syncing?: boolean;
  initialSyncComplete?: boolean;
  runtimeDegraded?: boolean;
  statusMessage?: string;
}

export interface ChainSummary {
  chainId: string;
  tipHeight: number;
  stateRoot: string;
}

export interface AppCapabilityComponent {
  id: string;
  version?: string;
  stateNamespace?: string;
  topics?: string[];
  querySubjects?: string[];
}

export interface AppCapabilityManifest {
  schemaVersion?: number;
  applicationId?: string;
  applicationVersion?: string;
  manifestDigest?: string;
  components: AppCapabilityComponent[];
}

export interface AppChainStatus {
  chainId?: string;
  running?: boolean;
  stalled?: boolean;
  submissionsPaused?: boolean;
  tipHeight?: number;
  stateRoot?: string;
  stateMachine?: string;
  members?: number;
  threshold?: number;
  anchor?: { mode?: string; [key: string]: unknown };
  capabilityManifest?: AppCapabilityManifest;
}

/** Release adapter chosen from the manifest (ADR-048 §2). */
export type ReleaseAdapter = 'document-review' | 'role-evidence' | 'approvals-only';

export interface DiscoveredChain {
  summary: ChainSummary;
  status: AppChainStatus;
  adapter: ReleaseAdapter;
}

export interface MessageSubmitResult {
  messageId: string;
  chainId: string;
  topic: string;
}

export interface FinalizedMessage {
  messageId: string;
  chainId: string;
  height: number;
  index: number;
  topic: string;
  sender: string;
  senderSeq: number;
  bodyHex: string;
}

export interface CertifiedBlockHeader {
  version: number;
  height: number;
  prevHash: string;
  l1Slot: number;
  l1BlockHash: string;
  timestamp: number;
  messagesRoot: string;
  stateRoot: string;
  blockHash: string;
}

export interface StateProofEnvelope {
  key: string;
  chainId: string;
  stateRoot: string;
  proofWireHex: string;
  valueHex?: string;
  committedHeight: number;
  presence: 'PRESENT' | 'ABSENT' | 'TOMBSTONED';
  profile: string;
  genesisId: string;
  blockHash: string;
  block?: CertifiedBlockHeader;
  [key: string]: unknown;
}

export interface FinalizedBlockMessage {
  messageId: string;
  topic: string;
  sender: string;
  senderSeq: number;
  bodyHex: string;
}

export interface FinalizedBlock {
  height: number;
  chainId: string;
  prevHash: string;
  timestamp: number;
  messagesRoot: string;
  stateRoot: string;
  proposer: string;
  certSignatures: number;
  messages: FinalizedBlockMessage[];
}

export interface BlockSummary {
  height: number;
  timestamp: number;
  stateRoot: string;
  messageCount: number;
  certSignatures: number;
}

export interface BlockPage {
  chainId: string;
  from: number;
  tipHeight: number;
  blocks: BlockSummary[];
}

export type RecordStatus = 'ACTIVE' | 'SUSPENDED' | 'REVOKED';

export interface OrganizationRecord {
  organizationId: string;
  revision: number;
  status: RecordStatus;
  metadataCommitmentHex: string;
}

export interface ActorKeyEpoch {
  keyId: string;
  publicKeyHex: string;
  validFromHeight: number;
  validUntilHeight: number;
  status: RecordStatus;
}

export interface ActorRecord {
  actorId: string;
  organizationId: string;
  revision: number;
  status: RecordStatus;
  roles: string[];
  keys: ActorKeyEpoch[];
  metadataCommitmentHex: string;
}

export interface PolicyClause {
  clauseId: string;
  role: string;
  minimumCount: number;
  distinctBy: 'ACTOR' | 'ORGANIZATION';
}

export interface PolicyRecord {
  policyId: string;
  revision: number;
  status: RecordStatus;
  proposerRoles: string[];
  clauses: PolicyClause[];
  rejectionMode: 'DISABLED' | 'ANY_ELIGIBLE';
  maximumLifetimeBlocks: number;
}

export type StatementAction = 'PROPOSE' | 'APPROVE' | 'REJECT' | 'CANCEL';

export type ProposalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

export interface AcceptedDecision {
  action: 'APPROVE' | 'REJECT';
  actorId: string;
  organizationId: string;
  organizationRevision: number;
  role: string;
  actorRevision: number;
  keyId: string;
  clauseId: string;
  statementDigestHex: string;
  signatureHex: string;
  acceptedHeight: number;
}

export interface ProposalRecord {
  proposalId: string;
  policyId: string;
  policyRevision: number;
  policyDigestHex: string;
  payloadDomain: string;
  payloadHashHex: string;
  deadlineHeight: number;
  status: ProposalStatus;
  proposerActorId: string;
  proposerOrganizationId: string;
  proposerOrganizationRevision: number;
  proposerRole: string;
  proposerActorRevision: number;
  proposerKeyId: string;
  createdHeight: number;
  decisions: AcceptedDecision[];
}

export interface ApprovalStats {
  created: number;
  pending: number;
  approved: number;
  rejected: number;
  cancelled: number;
  expired: number;
}

export type ResultCode =
  | 'ACCEPTED' | 'INVALID_PAYLOAD' | 'UNSUPPORTED_VERSION' | 'INVALID_SIGNATURE'
  | 'UNAUTHORIZED_RELAY' | 'UNAUTHORIZED_ACTOR' | 'UNKNOWN_RECORD' | 'CONFLICT' | 'EXACT_REPLAY'
  | 'EXPIRED' | 'TERMINAL' | 'ROLE_MISMATCH' | 'DISTINCTNESS_DUPLICATE'
  | 'GOVERNANCE_THRESHOLD_NOT_MET' | 'GOVERNANCE_PROOF_INVALID' | 'LIMIT_EXCEEDED'
  | 'CAPACITY_EXCEEDED' | 'CRYPTO_WORK_EXCEEDED' | 'WRONG_GENESIS' | 'WRONG_REVISION'
  | 'NOT_READY' | 'SUPERSEDED' | 'GOVERNED_ROUTE_UNSUPPORTED';

export interface CommandResult {
  commandKind: number;
  subjectId: string;
  resultCode: ResultCode;
  appliedHeight: number;
  messageIdHex: string;
  commandDigestHex: string;
}

export interface ActorStatement {
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
}

export interface SignedActorCommand {
  statement: ActorStatement;
  signatureHex: string;
}

export interface DocumentReviewCommand {
  proposalId: string;
  policyId: string;
  policyRevision: number;
  documentEntityId: string;
  documentHashHex: string;
  documentRef: string;
}

export interface DocumentReviewReceipt {
  proposalId: string;
  documentEntityId: string;
  actionCommitmentHex: string;
  policyId: string;
  policyRevision: number;
  appliedHeight: number;
  messageIdHex: string;
}

export interface TrailHead {
  count: number;
  headHashHex: string;
}

/**
 * BOUND: the node's state proof names this key, this height, this root, and the block header at
 * that height carries the same root. MISMATCH: one of those disagreed. The MPF path itself is
 * verified by the JVM verifier on the exported bundle, as in ADR-047.
 */
export type ProofState = 'BOUND' | 'MISMATCH';

/** A decoded record together with the root it was read at and the outcome of its proof binding. */
export interface ProofBound<T> {
  record: T;
  committedHeight: number;
  stateRootHex: string;
  blockHashHex: string;
  physicalKeyHex: string;
  valueHex: string;
  proof: ProofState;
  detail: string;
  envelope: StateProofEnvelope;
}

/** One finalized role or release command found by the bounded block scan. */
export interface ScannedCommand {
  height: number;
  index: number;
  messageIdHex: string;
  topic: string;
  proposalId: string;
  actorId: string;
  action: StatementAction | 'RELEASE';
  documentEntityId?: string;
}

export interface DirectoryHints {
  organizations?: string[];
  actors?: string[];
  policies?: string[];
}

export type CheckState = 'PASS' | 'FAIL' | 'SKIPPED' | 'UNAVAILABLE';

export interface LocalCheck {
  id: string;
  label: string;
  state: CheckState;
  detail: string;
}
