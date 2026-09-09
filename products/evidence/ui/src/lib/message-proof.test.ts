// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { blake2b256, verifyMessageInclusionProof, type BrowserMessageInclusionProof } from './message-proof';
import { fromHex, toHex, utf8 } from './cbor';

interface Vectors {
  treeId: string;
  leafCount: number;
  messagesRoot: string;
  vectors: Array<{ index: number; messageId: string; siblings: string[] }>;
}

const vectors = JSON.parse(readFileSync(new URL('./fixtures/adr037-message-proof-v1.json', import.meta.url), 'utf8')) as Vectors;

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
    }
  });

  it('computes blake2b-256 like the showcase codec', () => {
    // python3 -c 'import hashlib;print(hashlib.blake2b(b"abc",digest_size=32).hexdigest())'
    expect(toHex(blake2b256(utf8('abc'))))
      .toBe('bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319');
    expect(toHex(blake2b256(fromHex(''))))
      .toBe('0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8');
  });
});
