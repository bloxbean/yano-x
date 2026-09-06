export interface RuntimeEndpoint {
  id: string;
  nodeUrl: string;
  apiPrefix: string;
  label?: string;
}

export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'trust-registry';
  endpoints: RuntimeEndpoint[];
  defaultChainId: string;
  expectedNetwork: string;
  allowEndpointOverride: boolean;
  /** Base URL of a `yano-trust serve` instance whose status lists the console checks. */
  serviceUrl: string;
  /** Base URL of a `yano-trust gateway` instance that signs the operator view's writes. */
  gatewayUrl: string;
}

/** An actor the gateway holds a seed for, with the organization and roles read from the chain. */
export interface GatewayActor {
  actorId: string;
  organizationId?: string;
  roles?: string[];
  note?: string;
}

/** What a governed write did, as both the gateway and the CLI report it. */
export interface GatewayReceipt {
  messageId: string;
  height: number;
  status: 'APPLIED' | 'REJECTED';
  errorCode: number;
  results: Array<{ collection: string; key: string; revision: number; status: string }>;
  replayedHeight?: number;
  setCount?: number;
  listSha256?: string;
  mutationCount?: number;
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
  capabilityManifest?: AppCapabilityManifest;
}

export interface StateIdentity {
  profile: string;
  genesisId: string;
  version?: number;
  stateRoot?: string;
}

/** A chain the console can serve: a governed authenticated map declaring the registry profile. */
export interface DiscoveredChain {
  summary: ChainSummary;
  status: AppChainStatus;
  identity: StateIdentity;
  /** Collections found in the map genesis, or null when the genesis is not queryable yet. */
  collections: string[] | null;
  registry: boolean;
  mapGenesisIdHex: string | null;
}

export interface QueryResult {
  chainId: string;
  stateMachineId: string;
  committedHeight: number;
  stateRoot: string;
  payloadHex: string;
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

export interface FinalitySignature {
  signer: string;
  signature: string;
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
  finalityCertificate?: { scheme: number; signatures: FinalitySignature[] };
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
  tipHeight: number;
  blocks: BlockSummary[];
}

export type Collection = 'subjects' | 'status' | 'status-lists' | 'issuers' | 'schemas';

export interface MapEntry {
  status: 'ACTIVE' | 'REVOKED';
  revision: number;
  controllerHex: string;
  valueHex: string;
  logicalValueHashHex: string;
  createdHeight: number;
  lastMutationHeight: number;
}

export type Presence = 'ACTIVE' | 'REVOKED' | 'ABSENT';
export type Binding = 'BOUND' | 'MISMATCH';
export type ProvenanceKind = 'NONE' | 'GENESIS' | 'RECEIPT' | 'DIRECT_ROLE';

export interface Provenance {
  kind: ProvenanceKind;
  messageIdHex?: string;
  appliedHeight?: number;
  actorId?: string;
  organizationId?: string;
  keyId?: string;
  policyId?: string;
  policyRevision?: number;
  role?: string;
}

export interface SubjectValue { controllerOrganizationId: string; kind: string; metadataHashHex: string }
export interface StatusValue { bit: number; reasonCode: number }
export interface StatusListValue { purpose: string; bitLength: number; listSha256Hex: string; publishedHeight: number }
export interface IssuerValue { framework: string; authorizations: string[]; validFromHeight: number; validUntilHeight: number }
export type DecodedValue =
  | { kind: 'subject'; value: SubjectValue }
  | { kind: 'status'; value: StatusValue }
  | { kind: 'status-list'; value: StatusListValue }
  | { kind: 'issuer'; value: IssuerValue }
  | { kind: 'opaque'; valueHex: string };

/** One state proof with the key and value the answer expects it to bind. */
export interface AnswerFact {
  name: string;
  keyHex: string;
  valueHex: string | null;
  proof: StateProofEnvelope;
}

/** A proof-bound answer as the console reads it; the export is the `trust-registry-answer-v1` JSON. */
export interface RegistryAnswer {
  chainId: string;
  profile: string;
  genesisIdHex: string;
  height: number;
  stateRootHex: string;
  blockHashHex: string;
  collection: Collection;
  keyHex: string;
  keyText: string | null;
  presence: Presence;
  entry: MapEntry | null;
  decoded: DecodedValue | null;
  provenance: Provenance;
  actionCommitmentHex: string | null;
  authorizationEvidenceHex: string | null;
  facts: AnswerFact[];
  evidence: Record<string, unknown>;
  binding: Binding;
  certSignatures: number;
  notes: string[];
}
