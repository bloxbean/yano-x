import { fromHex } from './cbor';
import { computeMessageIdHex, extractBlockHeader, extractEnvelope, signedBodyBytes } from './envelope';
import { ed25519Verify } from './hash';
import { decodeFinalizedBlockMessageRecord, verifyMessageInclusionProof } from './message-proof';
import type { RowBundle } from './types';

export interface BrowserCheck {
  id: string;
  label: string;
  state: 'PASS' | 'FAIL' | 'SKIPPED' | 'UNAVAILABLE';
  detail: string;
}

export interface BrowserVerification {
  checks: BrowserCheck[];
  consistent: boolean;
}

function check(id: string, label: string, ok: boolean, detail: string): BrowserCheck {
  return { id, label, state: ok ? 'PASS' : 'FAIL', detail };
}

/**
 * The browser-side checks on an explorer row bundle (ADR-050 §2.5): envelope copy, message id,
 * sender signature where WebCrypto has Ed25519, the compact inclusion path against the certified
 * header, and the authenticated block record. Finality and anchor checks run in the CLI.
 */
export async function verifyRowBundleLocally(bundle: RowBundle): Promise<BrowserVerification> {
  const checks: BrowserCheck[] = [];
  const message = bundle.message;
  const blockHex = bundle.evidence?.blocksCbor?.find((candidate) => {
    try {
      return extractBlockHeader(candidate).height === bundle.height;
    } catch {
      return false;
    }
  });
  if (!blockHex) {
    checks.push(check('block', 'B1 Certified block', false, 'The evidence carries no block at the bundle height'));
    return { checks, consistent: false };
  }
  const header = extractBlockHeader(blockHex);
  const headerBound = header.chainId === bundle.chainId && header.stateRootHex === bundle.stateRoot
    && header.messagesRootHex === bundle.inclusionProof?.messagesRoot;
  checks.push(check('block', 'B1 Certified block', headerBound,
    headerBound ? `Block ${header.height} names this chain, the bundle's state root, and the inclusion proof's messages root`
      : 'The certified block header differs from the bundle'));

  let envelope: ReturnType<typeof extractEnvelope> | null = null;
  try {
    envelope = extractEnvelope(blockHex, bundle.index);
    const same = envelope.messageIdHex === message.messageId
      && envelope.chainId === bundle.chainId
      && envelope.topic === message.topic
      && envelope.senderHex === message.sender
      && envelope.senderSeq === message.senderSeq
      && envelope.bodyHex === message.bodyHex
      && (message.state === 'JSON' || (envelope.expiresAt === message.expiresAt
        && envelope.authScheme === message.authScheme && envelope.authProofHex === message.authProofHex));
    checks.push(check('envelope', 'B2a Envelope copy', same,
      same ? 'The bundle\'s message equals the signed envelope in the certified block'
        : 'The bundle\'s message differs from the certified block\'s envelope'));
    if (!same) envelope = null;
  } catch (cause) {
    checks.push(check('envelope', 'B2a Envelope copy', false,
      cause instanceof Error ? cause.message : 'The certified block could not be read'));
  }

  if (message.state === 'TOMBSTONE') {
    checks.push({ id: 'messageId', label: 'B2b Message id', state: 'SKIPPED',
      detail: 'Retention tombstone: the body is not retained, so the id cannot be recomputed' });
    checks.push({ id: 'signature', label: 'B2c Sender signature', state: 'SKIPPED', detail: 'No auth proof retained' });
  } else if (envelope) {
    const body = {
      chainId: bundle.chainId, topic: envelope.topic, senderHex: envelope.senderHex,
      senderSeq: envelope.senderSeq, expiresAt: envelope.expiresAt, bodyHex: envelope.bodyHex
    };
    const recomputed = computeMessageIdHex(body);
    checks.push(check('messageId', 'B2b Message id', recomputed === message.messageId,
      recomputed === message.messageId ? 'Blake2b-256 of the canonical signed body equals the message id'
        : 'The message id does not recompute from the signed body'));
    if (envelope.authScheme === 0) {
      const verified = await ed25519Verify(fromHex(envelope.senderHex), fromHex(envelope.authProofHex), signedBodyBytes(body));
      if (verified === null) {
        checks.push({ id: 'signature', label: 'B2c Sender signature', state: 'UNAVAILABLE',
          detail: 'This browser has no WebCrypto Ed25519; the CLI verifies the signature' });
      } else {
        checks.push(check('signature', 'B2c Sender signature', verified,
          verified ? 'The Ed25519 auth proof verifies under the sender key' : 'The auth proof does not verify under the sender key'));
      }
    } else {
      checks.push(check('signature', 'B2c Sender signature', false, 'Unsupported auth scheme'));
    }
  }

  const proof = bundle.inclusionProof;
  const included = !!proof && verifyMessageInclusionProof(proof)
    && proof.chainId === bundle.chainId
    && proof.blockHeight === bundle.height
    && proof.messageIndex === bundle.index
    && proof.messageId === message.messageId
    && proof.messagesRoot === header.messagesRootHex
    && proof.leafCount === header.messageCount;
  checks.push(check('inclusion', 'B3 Message inclusion', included,
    included ? 'The compact path recomputes the certified block\'s messages root at this index'
      : 'The inclusion proof does not bind the message to the certified block'));

  if (bundle.blockRecordProof?.valueHex) {
    try {
      const record = decodeFinalizedBlockMessageRecord(bundle.blockRecordProof.valueHex);
      const bound = record.height === bundle.height && record.messagesRoot === header.messagesRootHex
        && record.messageCount === header.messageCount
        && bundle.blockRecordProof.stateRoot === bundle.stateRoot;
      checks.push(check('record', 'B4 Block record', bound,
        bound ? 'The authenticated [height, messagesRoot, count] record names this block; the CLI verifies its native proof at the certified root'
          : 'The block record differs from the certified block'));
    } catch (cause) {
      checks.push(check('record', 'B4 Block record', false, cause instanceof Error ? cause.message : 'The block record does not decode'));
    }
  } else {
    checks.push({ id: 'record', label: 'B4 Block record', state: 'SKIPPED', detail: 'No block record proof carried; the message binds to the certified header directly' });
  }

  const evidenceConsistent = bundle.evidence.chainId === bundle.chainId && bundle.evidence.messageId === message.messageId;
  checks.push(check('evidenceIdentity', 'Evidence identity', evidenceConsistent,
    evidenceConsistent ? 'The evidence bundle names this chain and message' : 'The evidence bundle names another chain or message'));
  checks.push({ id: 'finality', label: 'B5 Finality', state: 'SKIPPED',
    detail: `Threshold ${bundle.evidence.threshold} of ${bundle.evidence.members.length} member signatures are verified by yano-explorer verify` });
  checks.push({ id: 'anchor', label: 'B6 Anchor', state: 'SKIPPED',
    detail: bundle.evidence.anchor ? `Node reference to L1 transaction ${bundle.evidence.anchor.txHash}; the CLI checks it independently`
      : 'The evidence carries no anchor reference' });

  return { checks, consistent: checks.every((item) => item.state !== 'FAIL') };
}
