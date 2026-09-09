export interface RuntimeEndpoint {
  id: string;
  nodeUrl: string;
  apiPrefix: string;
  label?: string;
}

export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'attest';
  endpoints: RuntimeEndpoint[];
  defaultChainId: string;
  expectedNetwork: string;
  allowEndpointOverride: boolean;
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

export interface StateCommitmentStatus {
  profile?: string;
  backend?: string;
  formatFingerprint?: string;
  genesisId?: string;
  stateRoot?: string;
  oldestProvableHeight?: number;
}

export interface AppCapabilityComponent {
  id: string;
  version: string;
  stateNamespace?: string;
}

export interface AppCapabilityManifest {
  schemaVersion: number;
  applicationId: string;
  applicationVersion: string;
  manifestDigest: string;
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
  stateCommitment?: StateCommitmentStatus;
  capabilityManifest?: AppCapabilityManifest;
}

export interface DiscoveredChain {
  summary: ChainSummary;
  status: AppChainStatus;
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

export interface MessageInclusionProof {
  schemaVersion: number;
  treeId: string;
  chainId: string;
  blockHeight: number;
  blockHash: string;
  messagesRoot: string;
  messageId: string;
  messageIndex: number;
  leafCount: number;
  siblings: string[];
}

export interface EvidenceAnchorRef {
  anchoredHeight: number;
  anchoredBlockHash: string;
  txHash: string;
  l1Slot: number;
}

export interface EvidenceStateCommitment {
  schemaVersion: number;
  profile: string;
  backend: string;
  commitmentFormatId: string;
  proofEncodingId: string;
  nativeVersioning: boolean;
  physicalDelete: boolean;
  formatFingerprint: string;
  genesisId: string;
  version: number;
  stateRoot: string;
}

export interface EvidenceBundle {
  chainId: string;
  messageId: string;
  blocksCbor: string[];
  members: string[];
  threshold: number;
  anchor?: EvidenceAnchorRef;
  stateCommitment?: EvidenceStateCommitment;
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

export interface FinalizedBlock {
  height: number;
  chainId: string;
  prevHash: string;
  timestamp: number;
  messagesRoot: string;
  stateRoot: string;
  proposer: string;
  certSignatures: number;
  messages: Array<{ messageId: string; topic: string; sender: string; senderSeq: number; bodyHex: string }>;
}

export interface AnchorCommitment {
  chainId: string;
  mode: string;
  anchoredHeight: number;
  stateRoot: string;
  blockHash: string;
  transactionHash: string;
  l1Slot: number;
}

export interface SignedEnvelope {
  version: number;
  messageIdHex: string;
  chainId: string;
  topic: string;
  senderHex: string;
  senderSeq: number;
  expiresAt: number;
  bodyHex: string;
  authScheme: number;
  authProofHex: string;
}

export interface AppendCommand {
  entityId: string;
  entryHashHex: string;
  reference: string;
}

export interface TrailHeadValue {
  count: number;
  headHashHex: string;
}

export interface CertificateSubject {
  entityId: string;
  entryHashHex: string;
  hashAlgorithm: 'sha-256';
  fileName?: string;
  sizeBytes?: number;
  mediaType?: string;
  reference?: string;
  label?: string;
}

export interface CertificateMessage {
  messageIdHex: string;
  height: number;
  index: number;
  topic: string;
  senderHex: string;
  senderSeq: number;
  expiresAt: number;
  bodyHex: string;
  authScheme: number;
  authProofHex: string;
}

export interface CertificateTrailHead {
  stateProof: StateProofEnvelope;
  revision: number;
  headDigestHex: string;
}

export interface CertificateAnchorReference {
  chainId: string;
  mode: string;
  anchoredHeight: number;
  stateRootHex: string;
  blockHashHex: string;
  transactionHash: string;
  l1Slot: number;
}

export interface AttestCertificate {
  schema: 'yano-x-attest-certificate-v1';
  generator: string;
  issuedAt: string;
  chainId: string;
  applicationId: string | null;
  status: 'FINALIZED' | 'ANCHORED';
  subject: CertificateSubject;
  message: CertificateMessage;
  messageProof: MessageInclusionProof;
  evidence: EvidenceBundle;
  trailHead: CertificateTrailHead | null;
  anchorReference: CertificateAnchorReference | null;
}

export type CheckState = 'PASS' | 'FAIL' | 'SKIPPED' | 'UNAVAILABLE';

export interface LocalCheck {
  id: string;
  label: string;
  state: CheckState;
  detail: string;
}

export interface LocalVerification {
  checks: LocalCheck[];
  consistent: boolean;
  digest: 'NOT_SUPPLIED' | 'MATCH' | 'MISMATCH';
  signature: 'VERIFIED' | 'INVALID' | 'UNAVAILABLE';
}
