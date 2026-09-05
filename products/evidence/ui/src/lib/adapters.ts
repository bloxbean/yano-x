/**
 * Release adapters (ADR-048 §2): the showcase document-review composite and the role-evidence
 * profile. Adapter selection reads the capability manifest; command and receipt codecs mirror
 * the Java contracts byte for byte.
 */
import { asArray, asBytes, asText, asUnsigned, decodeCbor, encodeCbor, fromHex, toHex, utf8 } from './cbor';
import { blake2b256 } from './message-proof';
import { DOMAIN_ACTORS_COMPONENT, ROLE_APPROVALS_COMPONENT, requireHex64 } from './roles';
import type {
  AppChainStatus,
  DocumentReviewCommand,
  DocumentReviewReceipt,
  ReleaseAdapter,
  TrailHead
} from './types';

export const DOCUMENT_REVIEW = {
  adapter: 'document-review' as const,
  topic: 'document-review.release.v1',
  payloadDomain: 'document.review.release.v1',
  policyId: 'document-release',
  documentsComponent: 'documents',
  receiptsComponent: 'document-review-receipts'
};

export const ROLE_EVIDENCE = {
  adapter: 'role-evidence' as const,
  topic: 'evidence.release.v1',
  payloadDomain: 'evidence.release.v1',
  policyId: 'evidence-release',
  evidenceComponent: 'evidence'
};

const DOCUMENT_ID = /^[a-z0-9][a-z0-9._-]{0,127}$/;
const MAX_REF_BYTES = 512;

export function componentIds(status: AppChainStatus | null): Set<string> {
  return new Set((status?.capabilityManifest?.components ?? []).map((component) => component.id));
}

/** A chain is eligible when both role components are present; the adapter follows the rest. */
export function selectAdapter(status: AppChainStatus | null): ReleaseAdapter | null {
  const ids = componentIds(status);
  if (!ids.has(DOMAIN_ACTORS_COMPONENT) || !ids.has(ROLE_APPROVALS_COMPONENT)) return null;
  if (ids.has(DOCUMENT_REVIEW.documentsComponent) && ids.has(DOCUMENT_REVIEW.receiptsComponent)) {
    return 'document-review';
  }
  if (ids.has(ROLE_EVIDENCE.evidenceComponent)) return 'role-evidence';
  return 'approvals-only';
}

export function payloadDomainFor(adapter: ReleaseAdapter): string {
  return adapter === 'document-review' ? DOCUMENT_REVIEW.payloadDomain
    : adapter === 'role-evidence' ? ROLE_EVIDENCE.payloadDomain : '';
}

// ---- document-review command and receipt -------------------------------------------------------

export function validateDocumentReview(command: DocumentReviewCommand): DocumentReviewCommand {
  for (const [field, value] of [['proposalId', command.proposalId], ['policyId', command.policyId],
    ['documentEntityId', command.documentEntityId]] as const) {
    if (!DOCUMENT_ID.test(value)) throw new Error(`${field} must match [a-z0-9][a-z0-9._-]{0,127}`);
  }
  if (!Number.isInteger(command.policyRevision) || command.policyRevision < 1) {
    throw new Error('policyRevision must be a positive integer');
  }
  if (utf8(command.documentRef).length > MAX_REF_BYTES) throw new Error('The reference exceeds 512 bytes');
  return { ...command, documentHashHex: requireHex64(command.documentHashHex, 'documentHash') };
}

export function encodeDocumentReview(command: DocumentReviewCommand): Uint8Array {
  const valid = validateDocumentReview(command);
  return encodeCbor([1n, valid.proposalId, valid.policyId, BigInt(valid.policyRevision),
    valid.documentEntityId, fromHex(valid.documentHashHex), valid.documentRef]);
}

export function decodeDocumentReview(bodyHex: string): DocumentReviewCommand {
  const bytes = fromHex(bodyHex);
  const items = asArray(decodeCbor(bytes), 7);
  if (asUnsigned(items[0]) !== 1) throw new Error('Unsupported document-review command version');
  const command = validateDocumentReview({
    proposalId: asText(items[1]),
    policyId: asText(items[2]),
    policyRevision: asUnsigned(items[3]),
    documentEntityId: asText(items[4]),
    documentHashHex: toHex(asBytes(items[5], 32)),
    documentRef: asText(items[6])
  });
  const canonical = encodeDocumentReview(command);
  if (toHex(canonical) !== toHex(bytes)) throw new Error('The document-review command is not canonical');
  return command;
}

/** blake2b-256 of the canonical command: the proposal payload hash and the receipt commitment. */
export function actionCommitmentHex(command: DocumentReviewCommand): string {
  return toHex(blake2b256(encodeDocumentReview(command)));
}

export function receiptKey(proposalId: string): Uint8Array {
  if (!DOCUMENT_ID.test(proposalId)) throw new Error('proposalId must match [a-z0-9][a-z0-9._-]{0,127}');
  return utf8(`approval/${proposalId}`);
}

export function decodeReceipt(valueHex: string): DocumentReviewReceipt {
  const bytes = fromHex(valueHex);
  if (bytes.length === 0) throw new Error('The receipt is absent');
  const items = asArray(decodeCbor(bytes), 8);
  if (asUnsigned(items[0]) !== 1) throw new Error('Unsupported receipt version');
  const receipt: DocumentReviewReceipt = {
    proposalId: asText(items[1]),
    documentEntityId: asText(items[2]),
    actionCommitmentHex: toHex(asBytes(items[3], 32)),
    policyId: asText(items[4]),
    policyRevision: asUnsigned(items[5]),
    appliedHeight: asUnsigned(items[6]),
    messageIdHex: toHex(asBytes(items[7], 32))
  };
  const canonical = encodeCbor([1n, receipt.proposalId, receipt.documentEntityId,
    fromHex(receipt.actionCommitmentHex), receipt.policyId, BigInt(receipt.policyRevision),
    BigInt(receipt.appliedHeight), fromHex(receipt.messageIdHex)]);
  if (toHex(canonical) !== toHex(bytes)) throw new Error('The receipt is not canonical');
  return receipt;
}

// ---- doc-trail head (stdlib DocTrailContract) --------------------------------------------------

export function documentEntityKey(entityId: string): Uint8Array {
  if (!DOCUMENT_ID.test(entityId)) throw new Error('entityId must match [a-z0-9][a-z0-9._-]{0,127}');
  return utf8(`e/${entityId}`);
}

export function decodeTrailHead(valueHex: string): TrailHead {
  const items = asArray(decodeCbor(fromHex(valueHex)), 2);
  return { count: asUnsigned(items[0]), headHashHex: toHex(asBytes(items[1], 32)) };
}
