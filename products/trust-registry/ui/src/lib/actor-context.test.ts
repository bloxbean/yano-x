import { describe, expect, it } from 'vitest';
import {
  activeKey,
  decodeActorRecord,
  decodeDirectPolicy,
  decodePointer,
  actorCurrentKey,
  actorRevisionKey,
  directPolicyCurrentKey
} from './actor-context';
import { encodeCbor, toHex } from './cbor';

const PUBLIC_KEY = 'd0'.repeat(32);

function actorBytes(keys: Array<[string, string, bigint, bigint, number]>): string {
  return toHex(encodeCbor([
    1n,
    'issuer-a',
    'issuer-org-a',
    2n,
    0n,
    ['issuer'],
    keys.map(([keyId, publicKeyHex, from, until, status]) => [
      keyId,
      Uint8Array.from(publicKeyHex.match(/../g)!.map((byte) => Number.parseInt(byte, 16))),
      from,
      until,
      BigInt(status)
    ]),
    new Uint8Array(0)
  ]));
}

describe('actor and policy records', () => {
  it('reads a current pointer as eight big-endian bytes', () => {
    expect(decodePointer('0000000000000002')).toBe(2n);
    expect(() => decodePointer('0002')).toThrow('eight bytes');
  });

  it('decodes an actor record with its keys', () => {
    const record = decodeActorRecord(actorBytes([['issuer-a-k1', PUBLIC_KEY, 0n, 0n, 0]]));
    expect(record.actorId).toBe('issuer-a');
    expect(record.organizationId).toBe('issuer-org-a');
    expect(record.revision).toBe(2n);
    expect(record.status).toBe(0);
    expect(record.roles).toEqual(['issuer']);
    expect(record.keys[0]).toEqual({
      keyId: 'issuer-a-k1', publicKeyHex: PUBLIC_KEY,
      validFromHeight: 0n, validUntilHeight: 0n, status: 0
    });
  });

  it('picks the key that is active at the height', () => {
    const record = decodeActorRecord(actorBytes([
      ['issuer-a-k1', PUBLIC_KEY, 0n, 5n, 0],
      ['issuer-a-k2', 'ee'.repeat(32), 6n, 0n, 0]
    ]));
    expect(activeKey(record, 3n).keyId).toBe('issuer-a-k1');
    expect(activeKey(record, 9n).keyId).toBe('issuer-a-k2');
    const revoked = decodeActorRecord(actorBytes([['issuer-a-k1', PUBLIC_KEY, 0n, 0n, 2]]));
    expect(() => activeKey(revoked, 1n)).toThrow('no active key');
  });

  it('decodes a direct role policy', () => {
    const policy = decodeDirectPolicy(toHex(encodeCbor([1n, 'issuer-write', 3n, 0n, 'issuer', 100n])));
    expect(policy).toEqual({
      policyId: 'issuer-write', revision: 3n, status: 0,
      requiredRole: 'issuer', maximumAuthorizationLifetimeBlocks: 100n
    });
  });

  it('builds the component keys the node serves', () => {
    expect(toHex(actorCurrentKey('issuer-a'))).not.toBe(toHex(actorRevisionKey('issuer-a', 1n)));
    expect(toHex(directPolicyCurrentKey('issuer-write')).length).toBeGreaterThan(0);
  });
});
