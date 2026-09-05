import { describe, expect, it } from 'vitest';
import goldenRowBundle from './fixtures/golden-row-bundle.json';
import { computeMessageIdHex, extractBlockHeader, extractEnvelope } from './envelope';
import { verifyRowBundleLocally } from './verify';
import type { RowBundle } from './types';

function golden(): RowBundle {
  return JSON.parse(JSON.stringify(goldenRowBundle)) as RowBundle;
}

describe('row bundle browser checks', () => {
  it('reads the certified block and envelope the bundle carries', () => {
    const bundle = golden();
    const header = extractBlockHeader(bundle.evidence.blocksCbor[0]);
    expect(header.height).toBe(bundle.height);
    expect(header.chainId).toBe(bundle.chainId);
    expect(header.stateRootHex).toBe(bundle.stateRoot);
    const envelope = extractEnvelope(bundle.evidence.blocksCbor[0], bundle.index);
    expect(envelope.messageIdHex).toBe(bundle.message.messageId);
    expect(envelope.bodyHex).toBe(bundle.message.bodyHex);
    expect(computeMessageIdHex({
      chainId: bundle.chainId, topic: envelope.topic, senderHex: envelope.senderHex,
      senderSeq: envelope.senderSeq, expiresAt: envelope.expiresAt, bodyHex: envelope.bodyHex
    })).toBe(bundle.message.messageId);
  });

  it('passes every browser check on the golden bundle', async () => {
    const result = await verifyRowBundleLocally(golden());
    const byId = Object.fromEntries(result.checks.map((check) => [check.id, check.state]));
    expect(result.consistent).toBe(true);
    expect(byId.block).toBe('PASS');
    expect(byId.envelope).toBe('PASS');
    expect(byId.messageId).toBe('PASS');
    expect(['PASS', 'UNAVAILABLE']).toContain(byId.signature);
    expect(byId.inclusion).toBe('PASS');
    expect(byId.record).toBe('PASS');
    expect(byId.finality).toBe('SKIPPED');
  });

  it('fails a bundle whose message copy was altered', async () => {
    const bundle = golden();
    bundle.message.bodyHex = 'ff' + bundle.message.bodyHex.slice(2);
    const result = await verifyRowBundleLocally(bundle);
    expect(result.consistent).toBe(false);
    expect(result.checks.find((check) => check.id === 'envelope')?.state).toBe('FAIL');
  });

  it('fails a bundle whose inclusion proof names another index', async () => {
    const bundle = golden();
    bundle.inclusionProof.messageIndex = bundle.inclusionProof.messageIndex + 1;
    const result = await verifyRowBundleLocally(bundle);
    expect(result.checks.find((check) => check.id === 'inclusion')?.state).toBe('FAIL');
  });

  it('fails a bundle whose state root was altered', async () => {
    const bundle = golden();
    bundle.stateRoot = '00'.repeat(32);
    const result = await verifyRowBundleLocally(bundle);
    expect(result.checks.find((check) => check.id === 'block')?.state).toBe('FAIL');
  });
});
