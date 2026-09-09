import { describe, expect, it } from 'vitest';
import {
  EMPTY_PROGRESS,
  deriveWriteSteps,
  publishBody,
  revokeBody,
  schemaBody,
  statusBody,
  subjectBody,
  type RegistryProgress
} from './write';
import type { GatewayActor } from './types';

const ACTORS: GatewayActor[] = [
  { actorId: 'issuer-a', organizationId: 'issuer-org-a', roles: ['issuer'] },
  { actorId: 'registrar-a', organizationId: 'registry-operator', roles: ['registrar'] },
  { actorId: 'registrar-b', organizationId: 'registrar-guild-b', roles: ['registrar'] },
  { actorId: 'registry-admin-a', organizationId: 'registry-operator', roles: ['registry-admin'] }
];

function progress(overrides: Partial<RegistryProgress> = {}): RegistryProgress {
  return { ...EMPTY_PROGRESS, listId: 'list-1', index: '5', ...overrides };
}

describe('deriveWriteSteps', () => {
  it('offers the issuer steps to an issuer and names who else to sign as', () => {
    const steps = deriveWriteSteps(progress({ indexPresence: 'ACTIVE' }), ACTORS, 'issuer-a');
    expect(steps.find((step) => step.id === 'status')?.status).toBe('READY');
    expect(steps.find((step) => step.id === 'revoke')?.status).toBe('READY');
    const subject = steps.find((step) => step.id === 'subject');
    expect(subject?.status).toBe('NEEDS_ROLE');
    expect(subject?.hint).toContain('registrar-a or registrar-b');
  });

  it('blocks a revoke unless the index is active now', () => {
    expect(deriveWriteSteps(progress({ indexPresence: 'ABSENT' }), ACTORS, 'issuer-a')
      .find((step) => step.id === 'revoke')?.status).toBe('BLOCKED');
    expect(deriveWriteSteps(progress({ indexPresence: 'REVOKED' }), ACTORS, 'issuer-a')
      .find((step) => step.id === 'revoke')?.hint).toContain('terminal');
    expect(deriveWriteSteps(progress(), ACTORS, 'issuer-a')
      .find((step) => step.id === 'revoke')?.hint).toContain('Read an active index first');
  });

  it('blocks publishing an empty list and allows it once an index exists', () => {
    expect(deriveWriteSteps(progress({ indexPresence: 'ABSENT' }), ACTORS, 'issuer-a')
      .find((step) => step.id === 'publish')?.status).toBe('BLOCKED');
    expect(deriveWriteSteps(progress({ indexPresence: 'ACTIVE' }), ACTORS, 'issuer-a')
      .find((step) => step.id === 'publish')?.status).toBe('READY');
    expect(deriveWriteSteps(progress({ indexPresence: 'ABSENT', listPublished: true }), ACTORS, 'issuer-a')
      .find((step) => step.id === 'publish')?.status).toBe('READY');
  });

  it('reports what the chain answered', () => {
    const steps = deriveWriteSteps(
      progress({ indexPresence: 'REVOKED', listPublished: true, publishedHeight: 9 }), ACTORS, 'issuer-a');
    expect(steps.find((step) => step.id === 'status')?.detail).toBe('list-1/5 is REVOKED');
    expect(steps.find((step) => step.id === 'publish')?.detail).toContain('replayed to height 9');
    expect(deriveWriteSteps(progress(), ACTORS, 'issuer-a')
      .find((step) => step.id === 'status')?.detail).toBe('index not read yet');
  });

  it('says so when no connected actor holds a role', () => {
    const steps = deriveWriteSteps(progress(), [ACTORS[3]], 'registry-admin-a');
    expect(steps.find((step) => step.id === 'status')?.hint).toContain('No connected actor holds the issuer role');
  });
});

describe('request bodies', () => {
  it('builds a status write', () => {
    expect(statusBody('issuer-a', ' list-1 ', '5', '1', '3')).toEqual({
      actorId: 'issuer-a', listId: 'list-1', index: 5, bit: 1, reasonCode: 3
    });
    expect(statusBody('issuer-a', 'list-1', '8', '0', '')).toEqual({
      actorId: 'issuer-a', listId: 'list-1', index: 8, bit: 0, reasonCode: 0
    });
  });

  it('refuses an index that is not a non-negative integer', () => {
    expect(() => statusBody('issuer-a', 'list-1', '-1', '1', '')).toThrow('non-negative integer');
    expect(() => revokeBody('issuer-a', 'list-1', 'x')).toThrow('non-negative integer');
  });

  it('builds a subject write and checks the metadata hash', () => {
    expect(subjectBody('registrar-a', 'did:example:s1', 'registry-operator', 'product', '11'.repeat(32)))
      .toEqual({
        actorId: 'registrar-a', subjectId: 'did:example:s1',
        controllerOrganizationId: 'registry-operator', kind: 'product',
        metadataHashHex: '11'.repeat(32)
      });
    expect(() => subjectBody('registrar-a', 'did:example:s1', 'o', 'product', 'abc'))
      .toThrow('32 bytes');
    expect(() => subjectBody('registrar-a', '', 'o', 'product', '11'.repeat(32)))
      .toThrow('subject id');
  });

  it('base64-encodes a schema and defaults the list purpose', () => {
    expect(schemaBody('registrar-a', 'schema-1', '{}')).toEqual({
      actorId: 'registrar-a', schemaId: 'schema-1', valueBase64: 'e30='
    });
    expect(() => schemaBody('registrar-a', 'schema-1', '')).toThrow('schema bytes');
    expect(publishBody('issuer-a', 'list-1', '')).toEqual({
      actorId: 'issuer-a', listId: 'list-1', purpose: 'revocation'
    });
  });
});
