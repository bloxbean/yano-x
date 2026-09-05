export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'dpp';
  /** Base URL of the public portal (`yano-dpp serve`) the Passport view reads. */
  serviceUrl: string;
  /** Base URL of an operator gateway (`yano-dpp gateway`); empty on a public deployment. */
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

export type Collection = 'products' | 'product-versions' | 'claims' | 'events' | 'certificates';

/** One `trust-registry-answer-v1` document inside a passport bundle. */
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

export interface TimelineEntry {
  height: number;
  position: number;
  messageId: string;
  collection: Collection;
  keyHex: string;
  key: string;
  operation: string;
}

/** The `dpp-passport-v1` bundle the portal serves at `/passports/{id}/proof`. */
export interface PassportDocument {
  schemaVersion: 1;
  type: 'dpp-passport-v1';
  chainId: string;
  profile: string;
  genesisId: string;
  height: number;
  stateRoot: string;
  blockHash: string;
  productId: string;
  prototype?: string;
  answers: AnswerDocument[];
  timeline: TimelineEntry[];
}

/** The portal's passport view, rendered as the JVM assembled it. */
export type PassportView = Record<string, unknown> & {
  productId: string;
  chainId: string;
  height: number;
  status: string;
  flags: string[];
  product: Record<string, unknown>;
  versions: Array<Record<string, unknown>>;
  claims: Array<Record<string, unknown>>;
  events: Array<Record<string, unknown>>;
  certificates: Array<Record<string, unknown>>;
  timeline: TimelineEntry[];
};

export interface DisclosureDocument {
  schemaVersion: 1;
  type: 'dpp-disclosure-v1';
  chainId: string;
  productId: string;
  claimType: string;
  claimId: string;
  saltHex: string;
  text: string;
  commitment?: string;
}

export interface CertificationRequestDocument {
  schemaVersion: 1;
  type: 'dpp-certification-request-v1';
  chainId: string;
  genesisId: string;
  policyId: string;
  policyRevision: number;
  proposalId: string;
  commandHex: string;
  payloadHash: string;
  deadlineHeight: number;
  productId: string;
  certificateId: string;
  operation: string;
  proposeMessageId: string;
}

export interface ReceiptDocument {
  messageId: string;
  status: 'APPLIED' | 'REJECTED';
  height: number;
  errorCode: number;
  errorName: string;
  results: Array<{ collection: string; key: string; revision: number; status: string }>;
  documentSha256?: string;
  disclosure?: DisclosureDocument;
}

export interface GatewayActor {
  actorId: string;
  organizationId?: string;
  roles?: string[];
  note?: string;
}

export type ProductValue = {
  manufacturerOrganizationId: string; status: number; currentVersion: number;
  successorProductId: string; passportProfileId: string;
};
export type VersionValue = { documentSha256: string; mediaType: string; reference: string; byteLength: number };
export type ClaimValue = {
  visibility: number; valueHex: string; text: string | null; issuerOrganizationId: string;
  validFromHeight: number; validUntilHeight: number; evidenceSha256: string;
};
export type EventValue = {
  eventType: string; actorOrganizationId: string; observedAt: number; location: string;
  evidenceSha256: string; note: string;
};
export type CertificateValue = {
  productId: string; certificateType: string; issuerOrganizationId: string; evidenceSha256: string;
  validFromHeight: number; validUntilHeight: number;
};
