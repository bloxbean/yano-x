import { describe, expect, it } from 'vitest';
import { proofMismatch, proofPath, type Fact } from './proof';

const root = '3c'.repeat(32);
const fact: Fact = { keyHex: 'aa01', valueHex: 'beef', height: 12, stateRoot: root };
const proof = { key: 'aa01', valueHex: 'beef', presence: 'PRESENT', committedHeight: 12, stateRoot: root };

describe('state proof binding', () => {
  it('accepts a proof of exactly the fact the view showed', () => {
    expect(proofMismatch(fact, proof)).toBeNull();
    expect(proofMismatch({ ...fact, valueHex: null }, { ...proof, presence: 'ABSENT', valueHex: undefined }))
      .toBeNull();
  });

  it('refuses a proof at another height instead of skipping the root comparison', () => {
    expect(proofMismatch(fact, { ...proof, committedHeight: 13 })).toMatch(/height 13/);
    expect(proofMismatch(fact, { ...proof, committedHeight: 13, stateRoot: '00'.repeat(32) })).toMatch(/height 13/);
  });

  it('refuses another root, key or value, and a view without a root', () => {
    expect(proofMismatch(fact, { ...proof, stateRoot: '00'.repeat(32) })).toMatch(/another state root/);
    expect(proofMismatch(fact, { ...proof, key: 'aa02' })).toMatch(/another key/);
    expect(proofMismatch(fact, { ...proof, valueHex: 'bee0' })).toMatch(/another value/);
    expect(proofMismatch(fact, { ...proof, presence: 'ABSENT', valueHex: undefined })).toMatch(/absent/);
    expect(proofMismatch({ ...fact, valueHex: null }, proof)).toMatch(/has one/);
    expect(proofMismatch({ ...fact, stateRoot: '' }, proof)).toMatch(/no state root/);
  });

  it('builds the proof route from the fact itself', () => {
    expect(proofPath('pay ments', fact)).toBe('app-chain/chains/pay%20ments/state/proof/aa01?height=12');
    expect(() => proofPath('c', { ...fact, keyHex: '../x' })).toThrow();
  });
});
