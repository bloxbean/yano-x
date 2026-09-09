export interface RuntimeEndpoint {
  id: string;
  serviceUrl: string;
  label?: string;
}

export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'explorer';
  /** Base URL of a `yano-explorer serve` instance to open on load, or empty. */
  serviceUrl: string;
  endpoints: RuntimeEndpoint[];
  defaultChainId: string;
  allowServiceOverride: boolean;
}

export interface ActiveConnection {
  endpointId: string;
  serviceUrl: string;
  label: string;
}

export type VerificationLevel = 'VERIFIED_PINNED' | 'VERIFIED_DECLARED' | 'HEADER_ONLY' | 'JSON_ONLY';

export interface ChainView {
  chainId: string;
  applicationId?: string;
  profile?: string;
  stateGenesisId?: string;
  identityDigest?: string;
  checkpointHeight?: number;
  checkpointBlockHash?: string;
  tipHeight?: number;
  lagBlocks?: number;
  levels?: Record<string, number>;
  topics?: Record<string, number>;
  diagnostic?: string;
}

export interface BlockSummary {
  height: number;
  blockHash: string;
  prevHash: string;
  timestamp: number;
  messagesRoot: string;
  stateRoot: string;
  proposer: string;
  messageCount: number;
  certSignatures: number;
  level: VerificationLevel;
  provable: boolean;
  blockRecordCaptured: boolean;
  memberKeysHex?: string[];
  threshold?: number;
  anchor?: string;
  diagnostic?: string;
}

export interface BlockPage {
  chainId: string;
  checkpointHeight: number;
  blocks: BlockSummary[];
}

export interface RowView {
  height: number;
  index: number;
  ordinal: number;
  messageId: string;
  module: string;
  kind: string;
  subject: string;
  op: string;
  fields: Record<string, unknown>;
  level: VerificationLevel;
}

export interface MessageView {
  messageId: string;
  height: number;
  index: number;
  topic: string;
  sender: string;
  senderSeq: number;
  expiresAt: number;
  bodyHex: string;
  authScheme: number;
  authProofHex: string;
  state: 'FULL' | 'TOMBSTONE' | 'JSON';
  level: VerificationLevel;
  rows: RowView[];
  evidence: { messageId: string; height: number; index: number };
  blockHash?: string;
  stateRoot?: string;
  timestamp?: number;
  provable?: boolean;
}

export interface BlockDetail extends BlockSummary {
  messages: MessageView[];
}

export interface SearchHit {
  type: 'block' | 'message' | 'subject';
  module: string;
  subject: string;
  height: number;
  index: number;
  messageIdHex: string;
  detail: string;
}

export interface SubjectSummary {
  module: string;
  kind: string;
  subject: string;
  firstHeight: number;
  lastHeight: number;
  rowCount: number;
}

export interface SubjectView {
  chainId: string;
  module: string;
  kind: string;
  subject: string;
  rows: RowView[];
  derived: Record<string, unknown>;
  stateCheck: Record<string, unknown>;
}

export interface TrailRevision {
  revision: number;
  entryHashHex: string;
  reference: string;
  authorHex: string;
  height: number;
  index: number;
  messageId: string;
  level: VerificationLevel;
  availability: 'FINALIZED' | 'CONTENT_VERIFIED';
  contentSha256?: string;
}

/** The node-shaped compact inclusion proof carried by a row bundle. */
export interface InclusionProof {
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

export interface RowBundle {
  schema: 'explorer-row-proof-v1';
  chainId: string;
  applicationId: string;
  profile: string;
  stateGenesisId: string;
  height: number;
  index: number;
  blockHash: string;
  stateRoot: string;
  message: {
    messageId: string;
    height: number;
    index: number;
    topic: string;
    sender: string;
    senderSeq: number;
    expiresAt: number;
    bodyHex: string;
    authScheme: number;
    authProofHex: string;
    state: 'FULL' | 'TOMBSTONE' | 'JSON';
  };
  inclusionProof: InclusionProof;
  blockRecordProof?: { presence?: string; valueHex?: string; stateRoot?: string; committedHeight?: number };
  evidence: {
    chainId: string;
    messageId: string;
    blocksCbor: string[];
    members: string[];
    threshold: number;
    anchor?: { anchoredHeight: number; anchoredBlockHash: string; txHash: string; l1Slot: number } | null;
  };
  ingestLevel: VerificationLevel;
  verification?: Record<string, unknown>;
}

export interface StateBundle {
  schema: 'explorer-state-proof-v1';
  chainId: string;
  height: number;
  subjectId: string;
  coordinates: Record<string, string>;
  keyHex: string;
  decodedFact: Record<string, unknown>;
  verification?: Record<string, unknown>;
}

export interface ContentMeta {
  sha256Hex: string;
  blake2bHex: string;
  size: number;
  source: string;
  status: string;
  entryHashHex: string;
  storedAt: number;
  available: boolean;
}
