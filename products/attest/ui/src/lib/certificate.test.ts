// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import { YanoApi } from './api';
import { assembleCertificate, confirmWithNode, parseCertificate, verifyLocally } from './certificate';
import { decodeTrailHead, extractEnvelope } from './envelope';
import type { ActiveConnection, AttestCertificate } from './types';

const goldenText = readFileSync(new URL('./fixtures/golden-certificate.json', import.meta.url), 'utf8');
const golden = parseCertificate(goldenText);
const document = new Uint8Array(readFileSync(new URL('./fixtures/golden-document.txt', import.meta.url)));

function states(result: Awaited<ReturnType<typeof verifyLocally>>): Record<string, string> {
  return Object.fromEntries(result.checks.map((item) => [item.id, item.state]));
}

describe('certificate parsing', () => {
  it('accepts the golden certificate and rejects malformed files', () => {
    expect(golden.schema).toBe('yano-x-attest-certificate-v1');
    expect(golden.applicationId).toBe('doc-trail');
    expect(() => parseCertificate('')).toThrow(/empty/);
    expect(() => parseCertificate('{')).toThrow(/well-formed/);
    expect(() => parseCertificate('{"schema":"other"}')).toThrow(/not a Yano attest/);
    expect(() => parseCertificate(JSON.stringify({ ...golden, status: 'PENDING' }))).toThrow(/status/);
    expect(() => parseCertificate(JSON.stringify({ ...golden, evidence: undefined }))).toThrow(/section/);
    expect(() => parseCertificate(JSON.stringify({ ...golden, status: 'ANCHORED' }))).toThrow(/anchor reference/);
    expect(() => parseCertificate(JSON.stringify({
      ...golden, message: { ...golden.message, messageIdHex: 'abc' }
    }))).toThrow(/message section/);
  });
});

describe('local verification', () => {
  it('passes every local check on the golden certificate with its document', async () => {
    const result = await verifyLocally(golden, document);
    expect(states(result)).toEqual({
      digest: 'PASS', envelope: 'PASS', messageId: 'PASS', signature: 'PASS', command: 'PASS',
      inclusion: 'PASS', evidenceIdentity: 'PASS', finality: 'SKIPPED', anchor: 'SKIPPED'
    });
    expect(result.consistent).toBe(true);
    expect(result.digest).toBe('MATCH');
    expect(result.signature).toBe('VERIFIED');
  });

  it('skips the digest without a document and fails it for other bytes', async () => {
    expect(states(await verifyLocally(golden, null)).digest).toBe('SKIPPED');
    const other = await verifyLocally(golden, new Uint8Array([1, 2, 3]));
    expect(states(other).digest).toBe('FAIL');
    expect(other.consistent).toBe(false);
  });

  it('fails only the matching check for each tampering', async () => {
    const subjectTamper: AttestCertificate = {
      ...golden, subject: { ...golden.subject, entryHashHex: '11'.repeat(32) }
    };
    const subjectResult = states(await verifyLocally(subjectTamper, null));
    expect(subjectResult.command).toBe('FAIL');
    expect(subjectResult.envelope).toBe('PASS');
    expect(subjectResult.inclusion).toBe('PASS');

    const envelopeTamper: AttestCertificate = {
      ...golden, message: { ...golden.message, expiresAt: golden.message.expiresAt + 1 }
    };
    const envelopeResult = states(await verifyLocally(envelopeTamper, null));
    expect(envelopeResult.envelope).toBe('FAIL');
    expect(envelopeResult.messageId).toBe('FAIL');
    expect(envelopeResult.signature).toBe('FAIL');

    const proofTamper: AttestCertificate = {
      ...golden, messageProof: { ...golden.messageProof, messagesRoot: '22'.repeat(32) }
    };
    const proofResult = states(await verifyLocally(proofTamper, null));
    expect(proofResult.inclusion).toBe('FAIL');
    expect(proofResult.envelope).toBe('PASS');

    const evidenceTamper: AttestCertificate = {
      ...golden, evidence: { ...golden.evidence, messageId: '33'.repeat(32) }
    };
    expect(states(await verifyLocally(evidenceTamper, null)).evidenceIdentity).toBe('FAIL');
  });
});

describe('assembly', () => {
  it('rebuilds the golden certificate from its node documents', () => {
    const envelope = extractEnvelope(golden.evidence.blocksCbor[0], golden.message.index);
    const trailHead = decodeTrailHead(golden.trailHead!.stateProof.valueHex!);
    const rebuilt = assembleCertificate({
      chainId: golden.chainId,
      applicationId: golden.applicationId,
      finalized: {
        messageId: golden.message.messageIdHex, chainId: golden.chainId, height: golden.message.height,
        index: golden.message.index, topic: golden.message.topic, sender: golden.message.senderHex,
        senderSeq: golden.message.senderSeq, bodyHex: golden.message.bodyHex
      },
      envelope,
      messageProof: golden.messageProof,
      evidence: golden.evidence,
      stateProof: golden.trailHead!.stateProof,
      trailHead,
      anchorMode: null,
      lastBlockStateRootHex: null,
      metadata: {
        fileName: golden.subject.fileName, sizeBytes: golden.subject.sizeBytes,
        mediaType: golden.subject.mediaType, label: golden.subject.label
      }
    });
    expect(rebuilt.status).toBe('FINALIZED');
    expect(rebuilt.subject).toEqual(golden.subject);
    expect(rebuilt.message).toEqual(golden.message);
    expect(rebuilt.trailHead).toEqual(golden.trailHead);
    expect(rebuilt.anchorReference).toBeNull();
    expect(rebuilt.generator).toBe('yano-x-attest-ui/1');
  });
});

describe('node confirmation', () => {
  const connection: ActiveConnection = {
    endpointId: 'test', nodeUrl: 'https://node.example.com', apiPrefix: '/api/v1',
    apiBase: 'https://node.example.com/api/v1', apiKey: '', label: 'Test node'
  };

  it('labels the block and anchor answers as node-confirmed', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith(`/blocks/${golden.message.height}`)) {
        return new Response(JSON.stringify({
          height: golden.message.height, chainId: golden.chainId, prevHash: '00'.repeat(32), timestamp: 1,
          messagesRoot: golden.messageProof.messagesRoot, stateRoot: golden.trailHead!.stateProof.stateRoot,
          proposer: golden.message.senderHex, certSignatures: 3,
          messages: [{ messageId: golden.message.messageIdHex, topic: golden.message.topic,
            sender: golden.message.senderHex, senderSeq: golden.message.senderSeq, bodyHex: golden.message.bodyHex }]
        }), { status: 200 });
      }
      return new Response(JSON.stringify({ error: 'no anchor' }), { status: 404 });
    });
    const result = await confirmWithNode(new YanoApi(connection, fetcher), golden);
    expect(result.block.state).toBe('PASS');
    expect(result.block.label).toContain('NODE_CONFIRMED_L1_REFERENCE');
    expect(result.anchor.state).toBe('UNAVAILABLE');
  });
});
