/**
 * Binds a native state proof to the fact a view showed (#109 §7). A view names a state key, its value (or absence),
 * the committed height it was read at and that height's state root. The proof the node returns must be for exactly
 * that key, value, height and root, and the verifier is asked to check it against the view's root, never against a
 * root the proof itself names. A proof at another height is refused rather than compared with nothing.
 */
export interface Fact {
  keyHex: string;
  valueHex: string | null;
  height: number;
  stateRoot: string;
}

export interface NativeProof {
  key?: unknown;
  valueHex?: unknown;
  presence?: unknown;
  committedHeight?: unknown;
  stateRoot?: unknown;
}

/** Why {@code proof} is not a proof of {@code fact}, or null when it is for exactly that fact. */
export function proofMismatch(fact: Fact, proof: NativeProof): string | null {
  if (!/^[0-9a-f]{64}$/.test(fact.stateRoot)) return 'the view names no state root';
  if (proof.committedHeight !== fact.height) {
    return `the proof is at height ${String(proof.committedHeight)}, the view at ${fact.height}`;
  }
  if (proof.stateRoot !== fact.stateRoot) return 'the proof is against another state root';
  if (proof.key !== fact.keyHex) return 'the proof is for another key';
  const absent = proof.presence === 'ABSENT';
  if (fact.valueHex === null) return absent ? null : 'the view shows no value but the proof has one';
  if (absent) return 'the proof shows the key absent';
  if (proof.presence !== 'PRESENT') return `the proof's presence is ${String(proof.presence)}`;
  return proof.valueHex === fact.valueHex ? null : 'the proof has another value';
}

/** The host's native proof route for a fact, relative to /api/v1: built here, not taken from the node. */
export function proofPath(chainId: string, fact: Fact): string {
  if (!/^[0-9a-f]+$/.test(fact.keyHex) || !Number.isSafeInteger(fact.height) || fact.height < 0) {
    throw new Error('not a provable fact');
  }
  return `app-chain/chains/${encodeURIComponent(chainId)}/state/proof/${fact.keyHex}?height=${fact.height}`;
}
