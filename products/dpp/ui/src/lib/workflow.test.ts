import { describe, expect, it } from 'vitest';
import { certificationProgress, deriveWorkflow, nextStep } from './workflow';
import type { GatewayActor, PassportView } from './types';

const ACTORS: GatewayActor[] = [
  { actorId: 'maker-a', organizationId: 'acme-manufacturing', roles: ['manufacturer', 'operator'] },
  { actorId: 'logistics-a', organizationId: 'swift-logistics', roles: ['operator'] },
  { actorId: 'issuer-a', organizationId: 'green-labs', roles: ['claim-issuer'] },
  { actorId: 'certifier-a', organizationId: 'cert-body-a', roles: ['certifier'] },
  { actorId: 'auditor-a', organizationId: 'cert-body-a', roles: ['auditor'] },
  { actorId: 'auditor-b', organizationId: 'audit-guild-b', roles: ['auditor'] }
] as GatewayActor[];

function view(overrides: Partial<PassportView> = {}): PassportView {
  return {
    productId: 'gtin:09506000134352',
    chainId: 'dpp-starter-chain',
    height: 12,
    status: 'ACTIVE',
    flags: [],
    product: {},
    versions: [],
    claims: [],
    events: [],
    certificates: [],
    timeline: [],
    ...overrides
  } as PassportView;
}

describe('deriveWorkflow', () => {
  it('blocks every step but registration when the product has no passport', () => {
    const steps = deriveWorkflow(null, ACTORS, 'maker-a');
    expect(steps.map((step) => step.status)).toEqual(['READY', 'BLOCKED', 'BLOCKED', 'BLOCKED', 'BLOCKED', 'BLOCKED']);
    expect(steps[0].hint).toContain('maker-a holds the manufacturer role');
    expect(nextStep(steps)?.id).toBe('register');
  });

  it('marks registration done and opens the version step once a passport exists', () => {
    const steps = deriveWorkflow(view(), ACTORS, 'maker-a');
    expect(steps[0].status).toBe('DONE');
    expect(steps[1].status).toBe('READY');
    expect(nextStep(steps)?.id).toBe('version');
  });

  it('tells a signer without the role who to switch to', () => {
    const steps = deriveWorkflow(view(), ACTORS, 'logistics-a');
    const version = steps.find((step) => step.id === 'version');
    expect(version?.status).toBe('NEEDS_ROLE');
    expect(version?.hint).toContain('maker-a');
    const event = steps.find((step) => step.id === 'event');
    expect(event?.status).toBe('READY');
  });

  it('keeps certification blocked until a version is published', () => {
    const before = deriveWorkflow(view(), ACTORS, 'certifier-a').find((step) => step.id === 'certify');
    expect(before?.status).toBe('BLOCKED');
    expect(before?.hint).toContain('Publish a passport version');
    const after = deriveWorkflow(view({ versions: [{ version: '1' }] }), ACTORS, 'certifier-a')
      .find((step) => step.id === 'certify');
    expect(after?.status).toBe('READY');
  });

  it('reports what each step already holds', () => {
    const steps = deriveWorkflow(
      view({ versions: [{ version: '1' }], claims: [{ id: 'rc-1' }, { id: 'cf-1' }], events: [{ type: 'SHIPPED' }] }),
      ACTORS,
      'maker-a'
    );
    expect(steps.find((step) => step.id === 'version')?.detail).toBe('1 version(s) published');
    expect(steps.find((step) => step.id === 'claim')?.detail).toBe('2 claim(s) attached');
    expect(steps.find((step) => step.id === 'event')?.detail).toBe('1 event(s) in the trail');
    expect(steps.find((step) => step.id === 'certify')?.detail).toBe('no certificate yet');
  });

  it('suggests the optional steps once the required ones are done', () => {
    const steps = deriveWorkflow(view({ versions: [{ version: '1' }] }), ACTORS, 'issuer-a');
    expect(nextStep(steps)?.id).toBe('claim');
  });

  it('says so when no connected actor holds a role', () => {
    const steps = deriveWorkflow(view(), [ACTORS[1]], 'logistics-a');
    expect(steps.find((step) => step.id === 'claim')?.hint).toContain('No connected actor holds the claim-issuer role');
  });
});

describe('certificationProgress', () => {
  it('starts at propose', () => {
    const progress = certificationProgress(false, [], false);
    expect(progress.stage).toBe('propose');
    expect(progress.remaining).toBe(2);
  });

  it('asks for a second organization after one approval', () => {
    const progress = certificationProgress(true, [{ actorId: 'auditor-a', organizationId: 'cert-body-a' }], false);
    expect(progress.stage).toBe('approve');
    expect(progress.remaining).toBe(1);
    expect(progress.message).toContain('different organization');
  });

  it('does not count two approvals from one organization twice', () => {
    const progress = certificationProgress(true, [
      { actorId: 'auditor-a', organizationId: 'cert-body-a' },
      { actorId: 'certifier-a', organizationId: 'cert-body-a' }
    ], false);
    expect(progress.organizations).toEqual(['cert-body-a']);
    expect(progress.stage).toBe('approve');
    expect(progress.remaining).toBe(1);
  });

  it('reaches apply with two distinct organizations', () => {
    const progress = certificationProgress(true, [
      { actorId: 'auditor-a', organizationId: 'cert-body-a' },
      { actorId: 'auditor-b', organizationId: 'audit-guild-b' }
    ], false);
    expect(progress.stage).toBe('apply');
    expect(progress.message).toContain('Apply writes the certificate');
  });

  it('reports done once applied', () => {
    const progress = certificationProgress(true, [], true);
    expect(progress.stage).toBe('done');
    expect(progress.remaining).toBe(0);
  });
});
