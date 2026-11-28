import { describe, expect, it } from 'vitest';
import { isStale, type StoredSession } from './session';

const now = 1_000_000_000;
const session = { id: 's', chainId: 'c', accountId: 'a', grantId: 'g1', publicKeyHex: '', privateKey: {} as CryptoKey,
  notAfterMs: now + 3_600_000, createdAtMs: now - 1_000 } satisfies StoredSession;

describe('local session key pruning', () => {
  it('keeps a key whose grant is committed or whose intent is still pending', () => {
    expect(isStale(session, new Set(['g1']), true, now, 600_000)).toBe(false);
    expect(isStale(session, new Set(), false, now, 600_000)).toBe(false);
    expect(isStale(session, new Set(), undefined, now, 600_000)).toBe(false);
  });

  it('drops an expired key, a rejected or revoked grant, and an intent that never executed', () => {
    expect(isStale({ ...session, notAfterMs: now }, new Set(['g1']), true, now, 600_000)).toBe(true);
    expect(isStale(session, new Set(), true, now, 600_000)).toBe(true);
    expect(isStale({ ...session, createdAtMs: now - 700_000 }, new Set(), false, now, 600_000)).toBe(true);
  });
});
