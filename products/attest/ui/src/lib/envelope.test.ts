// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { fromHex, toHex } from './cbor';
import {
  DOC_TRAIL_TOPIC,
  computeMessageIdHex,
  decodeAppendCommand,
  decodeTrailHead,
  derivedEntityId,
  encodeAppendCommand,
  entityKeyHex,
  extractBlockStateRootHex,
  extractEnvelope,
  signedBodyBytes,
  validateEntityId
} from './envelope';
import { ed25519Verify } from './hash';
import type { AttestCertificate } from './types';

const golden = JSON.parse(readFileSync(new URL('./fixtures/golden-certificate.json', import.meta.url), 'utf8')) as AttestCertificate;
const signed = { ...golden.message, chainId: golden.chainId };

describe('signed envelope walker against the golden certificate', () => {
  it('reads the envelope at the message index without a block codec', () => {
    const envelope = extractEnvelope(golden.evidence.blocksCbor[0], golden.message.index);
    expect(envelope.version).toBe(2);
    expect(envelope.messageIdHex).toBe(golden.message.messageIdHex);
    expect(envelope.chainId).toBe(golden.chainId);
    expect(envelope.topic).toBe(DOC_TRAIL_TOPIC);
    expect(envelope.senderHex).toBe(golden.message.senderHex);
    expect(envelope.senderSeq).toBe(golden.message.senderSeq);
    expect(envelope.expiresAt).toBe(golden.message.expiresAt);
    expect(envelope.bodyHex).toBe(golden.message.bodyHex);
    expect(envelope.authScheme).toBe(0);
    expect(envelope.authProofHex).toBe(golden.message.authProofHex);
    expect(() => extractEnvelope(golden.evidence.blocksCbor[0], 5)).toThrow(/outside/);
  });

  it('recomputes the message id from the canonical signed body', () => {
    expect(computeMessageIdHex(signed)).toBe(golden.message.messageIdHex);
    expect(computeMessageIdHex({ ...signed, expiresAt: signed.expiresAt + 1 }))
      .not.toBe(golden.message.messageIdHex);
  });

  it('verifies the member signature with WebCrypto Ed25519', async () => {
    const verified = await ed25519Verify(fromHex(golden.message.senderHex),
      fromHex(golden.message.authProofHex), signedBodyBytes(signed));
    expect(verified).toBe(true);
    const tampered = fromHex(golden.message.authProofHex);
    tampered[0] ^= 1;
    expect(await ed25519Verify(fromHex(golden.message.senderHex), tampered, signedBodyBytes(signed)))
      .toBe(false);
  });

  it('decodes and re-encodes the doc-trail command byte for byte', () => {
    const command = decodeAppendCommand(golden.message.bodyHex);
    expect(command.entityId).toBe(golden.subject.entityId);
    expect(command.entryHashHex).toBe(golden.subject.entryHashHex);
    expect(command.reference).toBe(golden.subject.reference ?? '');
    expect(toHex(encodeAppendCommand(command.entityId, command.entryHashHex, command.reference)))
      .toBe(golden.message.bodyHex);
  });

  it('decodes the trail head and derives the state key the node used', () => {
    const trailHead = golden.trailHead!;
    const head = decodeTrailHead(trailHead.stateProof.valueHex!);
    expect(head.count).toBe(trailHead.revision);
    expect(head.headHashHex).toBe(trailHead.headDigestHex);
    expect(entityKeyHex(golden.subject.entityId)).toBe(trailHead.stateProof.key);
    expect(extractBlockStateRootHex(golden.evidence.blocksCbor[0])).toBe(trailHead.stateProof.stateRoot);
  });

  it('bounds entity ids and derives one from the digest', () => {
    expect(derivedEntityId('ab'.repeat(32))).toBe(`sha256:${'ab'.repeat(32)}`);
    expect(() => validateEntityId('')).toThrow();
    expect(() => validateEntityId(' padded')).toThrow();
    expect(() => validateEntityId('x'.repeat(255))).toThrow();
    expect(() => validateEntityId('x'.repeat(254))).not.toThrow();
    expect(() => encodeAppendCommand('e1', 'zz'.repeat(32))).toThrow(/entry hash/);
  });
});
