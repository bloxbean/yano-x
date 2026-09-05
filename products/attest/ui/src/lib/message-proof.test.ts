// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { verifyMessageInclusionProof, type BrowserMessageInclusionProof } from './message-proof';
import type { AttestCertificate } from './types';

interface Vectors {
  treeId: string;
  leafCount: number;
  messagesRoot: string;
  vectors: Array<{ index: number; messageId: string; siblings: string[] }>;
}

const vectors = JSON.parse(readFileSync(new URL('./fixtures/adr037-message-proof-v1.json', import.meta.url), 'utf8')) as Vectors;
const golden = JSON.parse(readFileSync(new URL('./fixtures/golden-certificate.json', import.meta.url), 'utf8')) as AttestCertificate;

describe('ADR-037 message proof port', () => {
  it('verifies every shared odd-tree vector and rejects mutations', () => {
    for (const vector of vectors.vectors) {
      const proof: BrowserMessageInclusionProof = {
        schemaVersion: 1,
        treeId: vectors.treeId,
        chainId: 'vectors',
        blockHeight: 1,
        blockHash: '00'.repeat(32),
        messagesRoot: vectors.messagesRoot,
        messageId: vector.messageId,
        messageIndex: vector.index,
        leafCount: vectors.leafCount,
        siblings: vector.siblings
      };
      expect(verifyMessageInclusionProof(proof)).toBe(true);
      expect(verifyMessageInclusionProof({ ...proof, messagesRoot: `00${proof.messagesRoot.slice(2)}` })).toBe(false);
      expect(verifyMessageInclusionProof({ ...proof, siblings: [
        `00${proof.siblings[0].slice(2)}`, ...proof.siblings.slice(1)
      ] })).toBe(false);
    }
  });

  it('verifies the golden certificate proof produced by a real cluster', () => {
    expect(verifyMessageInclusionProof(golden.messageProof)).toBe(true);
    expect(golden.messageProof.messageId).toBe(golden.message.messageIdHex);
    expect(verifyMessageInclusionProof({ ...golden.messageProof, messageIndex: golden.messageProof.messageIndex + 1 }))
      .toBe(false);
  });
});
