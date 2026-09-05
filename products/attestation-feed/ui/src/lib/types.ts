export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'attestation-feed';
  /** Base URL of the public portal (`yano-feed serve`) the Round view reads. */
  serviceUrl: string;
  /** Base URL of a signing gateway (`yano-feed gateway`); empty on a public deployment. */
  gatewayUrl: string;
  allowServiceOverride: boolean;
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
  blockHash?: string;
  block?: CertifiedBlockHeader;
  finalityCertificate?: { scheme: number; signatures: Array<{ signer: string; signature: string }> };
  [key: string]: unknown;
}

export interface MapEntry {
  status: 'ACTIVE' | 'REVOKED';
  revision: number;
  controllerHex: string;
  valueHex: string;
  logicalValueHash: string;
  createdHeight: number;
  lastMutationHeight: number;
}

export type Presence = 'ACTIVE' | 'REVOKED' | 'ABSENT';
export type ProvenanceKind = 'NONE' | 'GENESIS' | 'RECEIPT' | 'DIRECT_ROLE';

export interface Provenance {
  kind: ProvenanceKind;
  messageId?: string;
  appliedHeight?: number;
  actorId?: string;
  organizationId?: string;
  keyId?: string;
  policyId?: string;
  policyRevision?: number;
  role?: string;
}

export interface AnswerFact {
  name: string;
  keyHex: string;
  valueHex?: string;
  proof: StateProofEnvelope;
}

export type Collection = 'feeds' | 'observations' | 'rounds';

/** One `trust-registry-answer-v1` document inside a round bundle. */
export interface AnswerDocument {
  schemaVersion: number;
  type: string;
  chainId: string;
  profile: string;
  genesisId: string;
  height: number;
  stateRoot: string;
  blockHash: string;
  collection: Collection;
  keyHex: string;
  key?: string;
  presence: Presence;
  entry?: MapEntry;
  provenance: Provenance;
  actionCommitmentHex?: string;
  facts: AnswerFact[];
  evidence: Record<string, unknown>;
}

/** The `feed-round-v1` bundle the portal serves at `/feeds/{id}/rounds/{n}/proof`. */
export interface RoundDocument {
  schemaVersion: 1;
  type: 'feed-round-v1';
  chainId: string;
  profile: string;
  genesisId: string;
  feedId: string;
  round: number;
  observationHeight: number;
  recordHeight: number;
  stateRoot: string;
  status: string;
  starter?: string;
  answers: AnswerDocument[];
}

/** The portal's round view, rendered as the JVM assembled it. */
export type RoundViewDocument = Record<string, unknown> & {
  feedId: string;
  round: number;
  chainId: string;
  status: string;
  observationHeight: number;
  recordHeight: number;
  feed: Record<string, unknown>;
  sources: Array<Record<string, unknown>>;
  recomputed: Record<string, unknown>;
  record: Record<string, unknown>;
  datum: Record<string, unknown> | null;
};

export interface FeedSummary {
  feedId: string;
  chainId: string;
  height: number;
  presence: Presence;
  feed?: Record<string, unknown>;
  roundsWithRecords: number[];
  currentRoundByPortalClock?: number;
  replayedHeight?: number;
  note?: string;
}

export interface RoundRequestDocument {
  schemaVersion: 1;
  type: 'feed-round-request-v1';
  chainId: string;
  genesisId: string;
  policyId: string;
  policyRevision: number;
  proposalId: string;
  commandHex: string;
  payloadHash: string;
  deadlineHeight: number;
  feedId: string;
  round: number;
  proposeMessageId: string;
  record?: Record<string, unknown> | null;
}

export interface ReceiptDocument {
  messageId: string;
  status: 'APPLIED' | 'REJECTED';
  height: number;
  errorCode: number;
  errorName: string;
  results: Array<{ collection: string; key: string; revision: number; status: string }>;
  feedId?: string;
  round?: number;
  sourceId?: string;
}

export interface GatewayActor {
  actorId: string;
  organizationId?: string;
  roles?: string[];
  note?: string;
}

export type FeedValue = {
  description: string; unit: string; scale: number; epochStart: bigint; roundSeconds: bigint;
  sources: string[]; minimumSources: number; maximumDeviationPpm: bigint; maximumDeviationAbsolute: bigint;
  minimumValue: bigint; maximumValue: bigint; status: number;
};
export type ObservationValue = { value: bigint; observedAt: bigint; evidenceSha256: string; note: string };
export type RecordValue = {
  status: number; closedAtHeight: number; aggregate: bigint; scale: number; acceptedSources: string[];
  policySha256: string; datumSha256: string;
};
