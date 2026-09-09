import { fromHex } from './cbor';
import {
  DOC_TRAIL_TOPIC,
  computeMessageIdHex,
  decodeAppendCommand,
  extractEnvelope,
  signedBodyBytes
} from './envelope';
import { ed25519Verify, sha256Hex } from './hash';
import { verifyMessageInclusionProof } from './message-proof';
import type { YanoApi } from './api';
import type {
  AnchorCommitment,
  AttestCertificate,
  CertificateSubject,
  EvidenceBundle,
  FinalizedMessage,
  LocalCheck,
  LocalVerification,
  MessageInclusionProof,
  SignedEnvelope,
  StateProofEnvelope
} from './types';

export const CERTIFICATE_SCHEMA = 'yano-x-attest-certificate-v1';
export const GENERATOR = 'yano-x-attest-ui/1';
/** Above the node's 40 MiB evidence bound plus the remaining sections. */
export const MAX_CERTIFICATE_BYTES = 44 * 1024 * 1024;
const HEX_32 = /^[0-9a-f]{64}$/;
const HEX_64 = /^[0-9a-f]{128}$/;
const MAX_TEXT = 1024;

export interface AssemblyInput {
  chainId: string;
  applicationId: string | null;
  finalized: FinalizedMessage;
  envelope: SignedEnvelope;
  messageProof: MessageInclusionProof;
  evidence: EvidenceBundle;
  stateProof: StateProofEnvelope | null;
  trailHead: { count: number; headHashHex: string } | null;
  anchorMode: string | null;
  lastBlockStateRootHex: string | null;
  metadata: { fileName?: string; sizeBytes?: number; mediaType?: string; label?: string };
}

/** Builds the ADR-047 §5 document from node documents the browser already validated. */
export function assembleCertificate(input: AssemblyInput): AttestCertificate {
  const command = decodeAppendCommand(input.envelope.bodyHex);
  const subject: CertificateSubject = {
    entityId: command.entityId,
    entryHashHex: command.entryHashHex,
    hashAlgorithm: 'sha-256',
    ...(input.metadata.fileName ? { fileName: input.metadata.fileName } : {}),
    ...(input.metadata.sizeBytes !== undefined ? { sizeBytes: input.metadata.sizeBytes } : {}),
    ...(input.metadata.mediaType ? { mediaType: input.metadata.mediaType } : {}),
    ...(command.reference ? { reference: command.reference } : {}),
    ...(input.metadata.label ? { label: input.metadata.label } : {})
  };
  const anchor = input.evidence.anchor;
  const anchorReference = anchor && input.lastBlockStateRootHex
    ? {
      chainId: input.chainId,
      mode: input.anchorMode ?? 'unknown',
      anchoredHeight: anchor.anchoredHeight,
      stateRootHex: input.lastBlockStateRootHex,
      blockHashHex: anchor.anchoredBlockHash,
      transactionHash: anchor.txHash,
      l1Slot: anchor.l1Slot
    }
    : null;
  return {
    schema: CERTIFICATE_SCHEMA,
    generator: GENERATOR,
    issuedAt: new Date().toISOString(),
    chainId: input.chainId,
    applicationId: input.applicationId,
    status: anchorReference ? 'ANCHORED' : 'FINALIZED',
    subject,
    message: {
      messageIdHex: input.envelope.messageIdHex,
      height: input.finalized.height,
      index: input.finalized.index,
      topic: input.envelope.topic,
      senderHex: input.envelope.senderHex,
      senderSeq: input.envelope.senderSeq,
      expiresAt: input.envelope.expiresAt,
      bodyHex: input.envelope.bodyHex,
      authScheme: input.envelope.authScheme,
      authProofHex: input.envelope.authProofHex
    },
    messageProof: input.messageProof,
    evidence: input.evidence,
    trailHead: input.stateProof && input.trailHead
      ? { stateProof: input.stateProof, revision: input.trailHead.count, headDigestHex: input.trailHead.headHashHex }
      : null,
    anchorReference
  };
}

/** Bounded structural validation of a certificate file before any check runs. */
export function parseCertificate(text: string): AttestCertificate {
  if (!text || text.length > MAX_CERTIFICATE_BYTES) {
    throw new Error('The certificate is empty or exceeds 44 MiB');
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error('The certificate is not well-formed JSON');
  }
  if (!isRecord(value) || value.schema !== CERTIFICATE_SCHEMA) {
    throw new Error('This file is not a Yano attest certificate');
  }
  const status = value.status;
  if (status !== 'FINALIZED' && status !== 'ANCHORED') throw new Error('Unknown certificate status');
  const subject = value.subject;
  const message = value.message;
  const proof = value.messageProof;
  const evidence = value.evidence;
  if (!isRecord(subject) || !isRecord(message) || !isRecord(proof) || !isRecord(evidence)) {
    throw new Error('The certificate is missing a required section');
  }
  if (typeof subject.entityId !== 'string' || !HEX_32.test(String(subject.entryHashHex))
      || subject.hashAlgorithm !== 'sha-256') {
    throw new Error('The certificate subject is malformed');
  }
  if (!HEX_32.test(String(message.messageIdHex)) || !HEX_32.test(String(message.senderHex))
      || !HEX_64.test(String(message.authProofHex)) || !isSafeCount(message.height)
      || (message.height as number) < 1 || !isSafeCount(message.index)
      || typeof message.bodyHex !== 'string' || !/^(?:[0-9a-f]{2})+$/.test(message.bodyHex)
      || typeof message.topic !== 'string' || message.topic.length > MAX_TEXT) {
    throw new Error('The certificate message section is malformed');
  }
  if (!Array.isArray(evidence.blocksCbor) || evidence.blocksCbor.length === 0
      || !Array.isArray(evidence.members) || typeof evidence.chainId !== 'string'
      || !HEX_32.test(String(evidence.messageId))) {
    throw new Error('The certificate evidence section is malformed');
  }
  if (typeof value.chainId !== 'string' || value.chainId.length > MAX_TEXT
      || (value.applicationId !== null && typeof value.applicationId !== 'string')) {
    throw new Error('The certificate chain identity is malformed');
  }
  if (status === 'ANCHORED' && !isRecord(value.anchorReference)) {
    throw new Error('An anchored certificate must carry an anchor reference');
  }
  return value as unknown as AttestCertificate;
}

/**
 * Local checks that need no node: digest, envelope copy, message id, sender
 * signature, command binding, and the compact inclusion path. Finality and
 * anchor verification remain the CLI's job.
 */
export async function verifyLocally(
  certificate: AttestCertificate,
  document: Uint8Array | null
): Promise<LocalVerification> {
  const checks: LocalCheck[] = [];
  let digest: LocalVerification['digest'] = 'NOT_SUPPLIED';
  let signature: LocalVerification['signature'] = 'UNAVAILABLE';

  if (document) {
    const actual = await sha256Hex(document);
    digest = actual === certificate.subject.entryHashHex ? 'MATCH' : 'MISMATCH';
    checks.push(check('digest', 'C1 Document digest', digest === 'MATCH',
      digest === 'MATCH' ? 'SHA-256 of the supplied file equals the attested digest'
        : `The file hashes to ${actual}, not the attested digest`));
  } else {
    checks.push({ id: 'digest', label: 'C1 Document digest', state: 'SKIPPED', detail: 'No file supplied' });
  }

  const message = certificate.message;
  let envelope: SignedEnvelope | null = null;
  try {
    envelope = extractEnvelope(certificate.evidence.blocksCbor[0], message.index);
    const same = envelope.messageIdHex === message.messageIdHex
      && envelope.chainId === certificate.chainId
      && envelope.topic === message.topic
      && envelope.senderHex === message.senderHex
      && envelope.senderSeq === message.senderSeq
      && envelope.expiresAt === message.expiresAt
      && envelope.bodyHex === message.bodyHex
      && envelope.authScheme === message.authScheme
      && envelope.authProofHex === message.authProofHex;
    checks.push(check('envelope', 'C2a Envelope copy', same,
      same ? 'The message section equals the signed envelope in the first evidence block'
        : 'The message section differs from the evidence block envelope'));
    if (!same) envelope = null;
  } catch (cause) {
    checks.push(check('envelope', 'C2a Envelope copy', false,
      cause instanceof Error ? cause.message : 'The evidence block could not be read'));
  }

  const recomputed = computeMessageIdHex({
    chainId: certificate.chainId, topic: message.topic, senderHex: message.senderHex,
    senderSeq: message.senderSeq, expiresAt: message.expiresAt, bodyHex: message.bodyHex
  });
  checks.push(check('messageId', 'C2b Message id', recomputed === message.messageIdHex,
    recomputed === message.messageIdHex ? 'Blake2b-256 of the canonical signed body equals the message id'
      : 'The message id does not recompute from the signed body'));

  if (message.authScheme === 0) {
    const verified = await ed25519Verify(fromHex(message.senderHex), fromHex(message.authProofHex),
      signedBodyBytes({
        chainId: certificate.chainId, topic: message.topic, senderHex: message.senderHex,
        senderSeq: message.senderSeq, expiresAt: message.expiresAt, bodyHex: message.bodyHex
      }));
    if (verified === null) {
      signature = 'UNAVAILABLE';
      checks.push({ id: 'signature', label: 'C2c Sender signature', state: 'UNAVAILABLE',
        detail: 'This browser has no WebCrypto Ed25519; the CLI verifies the signature' });
    } else {
      signature = verified ? 'VERIFIED' : 'INVALID';
      checks.push(check('signature', 'C2c Sender signature', verified,
        verified ? 'The Ed25519 auth proof verifies under the sender key'
          : 'The auth proof does not verify under the sender key'));
    }
  } else {
    signature = 'INVALID';
    checks.push(check('signature', 'C2c Sender signature', false, 'Unsupported auth scheme'));
  }

  try {
    const command = decodeAppendCommand(message.bodyHex);
    const reference = certificate.subject.reference ?? '';
    const bound = message.topic === DOC_TRAIL_TOPIC
      && command.entityId === certificate.subject.entityId
      && command.entryHashHex === certificate.subject.entryHashHex
      && command.reference === reference;
    checks.push(check('command', 'C2d Command binding', bound,
      bound ? 'The doc-trail command carries the certificate subject'
        : 'The doc-trail command does not carry the certificate subject'));
  } catch (cause) {
    checks.push(check('command', 'C2d Command binding', false,
      cause instanceof Error ? cause.message : 'The command could not be decoded'));
  }

  const proof = certificate.messageProof;
  const included = verifyMessageInclusionProof(proof)
    && proof.chainId === certificate.chainId
    && proof.blockHeight === message.height
    && proof.messageIndex === message.index
    && proof.messageId === message.messageIdHex;
  checks.push(check('inclusion', 'C3 Message inclusion', included,
    included ? 'The compact path recomputes the block messages root'
      : 'The message proof does not bind the message to the stated block'));

  const evidenceConsistent = certificate.evidence.chainId === certificate.chainId
    && certificate.evidence.messageId === message.messageIdHex;
  checks.push(check('evidenceIdentity', 'Evidence identity', evidenceConsistent,
    evidenceConsistent ? 'The evidence bundle names this chain and message'
      : 'The evidence bundle names another chain or message'));

  checks.push({ id: 'finality', label: 'C4 Finality', state: 'SKIPPED',
    detail: 'Threshold signature verification runs in the yano-attest CLI' });
  checks.push({ id: 'anchor', label: 'C5 Anchor linkage', state: 'SKIPPED',
    detail: certificate.anchorReference
      ? `Node reference to L1 tx ${certificate.anchorReference.transactionHash}; independent check runs in the CLI`
      : 'The evidence segment carries no anchor reference' });

  const consistent = checks.every((item) => item.state !== 'FAIL');
  return { checks, consistent, digest, signature };
}

export interface NodeConfirmation {
  block: LocalCheck;
  anchor: LocalCheck;
}

/** Asks the connected node about the certified block and the latest anchor; labels both as node-confirmed. */
export async function confirmWithNode(api: YanoApi, certificate: AttestCertificate): Promise<NodeConfirmation> {
  let block: LocalCheck;
  try {
    const live = await api.block(certificate.chainId, certificate.message.height);
    if (!live) {
      block = { id: 'nodeBlock', label: 'Node block', state: 'UNAVAILABLE',
        detail: 'The node does not retain this block' };
    } else {
      const same = live.messagesRoot === certificate.messageProof.messagesRoot
        && live.stateRoot === (certificate.trailHead?.stateProof.stateRoot ?? live.stateRoot)
        && live.messages.some((item) => item.messageId === certificate.message.messageIdHex);
      block = check('nodeBlock', 'Node block (NODE_CONFIRMED_L1_REFERENCE)', same,
        same ? `The connected node's block ${live.height} carries the same messages root and message`
          : 'The connected node reports a different block at this height');
    }
  } catch (cause) {
    block = { id: 'nodeBlock', label: 'Node block', state: 'UNAVAILABLE',
      detail: cause instanceof Error ? cause.message : 'The node could not be asked' };
  }
  let anchor: LocalCheck;
  try {
    const commitment: AnchorCommitment | null = await api.anchorCommitment(certificate.chainId);
    if (!commitment) {
      anchor = { id: 'nodeAnchor', label: 'Node anchor', state: 'UNAVAILABLE',
        detail: 'The node reports no confirmed Cardano anchor' };
    } else if (!certificate.anchorReference) {
      const covers = commitment.anchoredHeight >= certificate.message.height;
      anchor = { id: 'nodeAnchor', label: 'Node anchor (NODE_CONFIRMED_L1_REFERENCE)',
        state: covers ? 'PASS' : 'UNAVAILABLE',
        detail: covers
          ? `The node's latest anchor at height ${commitment.anchoredHeight} (tx ${commitment.transactionHash}) covers this message; request an anchored certificate to bind it`
          : `The node's latest anchor at height ${commitment.anchoredHeight} predates this message` };
    } else {
      const reference = certificate.anchorReference;
      const same = commitment.anchoredHeight !== reference.anchoredHeight
        || (commitment.blockHash === reference.blockHashHex
          && commitment.transactionHash === reference.transactionHash);
      anchor = check('nodeAnchor', 'Node anchor (NODE_CONFIRMED_L1_REFERENCE)', same,
        same ? `The node confirms an anchor at height ${commitment.anchoredHeight} in mode ${commitment.mode}`
          : 'The node reports a different transaction for the certificate anchor height');
    }
  } catch (cause) {
    anchor = { id: 'nodeAnchor', label: 'Node anchor', state: 'UNAVAILABLE',
      detail: cause instanceof Error ? cause.message : 'The node could not be asked' };
  }
  return { block, anchor };
}

function check(id: string, label: string, pass: boolean, detail: string): LocalCheck {
  return { id, label, state: pass ? 'PASS' : 'FAIL', detail };
}

function isSafeCount(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
