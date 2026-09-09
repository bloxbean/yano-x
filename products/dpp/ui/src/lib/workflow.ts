/**
 * The operator view as a guided lifecycle (ADR-051 §2.5). Pure functions only: they derive what
 * the passport already shows, which step may run next, and which actor holds the role a step
 * needs, so the console can guide an operator instead of a document doing it.
 *
 * A step is never enabled by these functions alone. The chain authorizes every write by the
 * actor's signature under a policy; this is guidance, not authorization.
 */
import type { GatewayActor, PassportView } from './types';

export type StepId = 'register' | 'version' | 'claim' | 'event' | 'certify' | 'status';

/** DONE: already on chain. READY: runnable now. NEEDS_ROLE: the signer lacks it. BLOCKED: earlier step first. */
export type StepStatus = 'DONE' | 'READY' | 'NEEDS_ROLE' | 'BLOCKED';

export interface WorkflowStep {
  id: StepId;
  order: number;
  label: string;
  role: string;
  purpose: string;
  optional: boolean;
  done: boolean;
  status: StepStatus;
  detail: string;
  hint: string;
  /** Actor ids from the gateway that hold the role this step needs. */
  candidates: string[];
}

export const MANUFACTURER_ROLE = 'manufacturer';
export const OPERATOR_ROLE = 'operator';
export const CLAIM_ISSUER_ROLE = 'claim-issuer';
export const CERTIFIER_ROLE = 'certifier';
export const AUDITOR_ROLE = 'auditor';

interface Definition {
  id: StepId;
  label: string;
  role: string;
  purpose: string;
  optional: boolean;
}

const DEFINITIONS: Definition[] = [
  {
    id: 'register',
    label: 'Register the product',
    role: MANUFACTURER_ROLE,
    purpose: 'Creates the passport: product id, manufacturer organization, profile, and initial status.',
    optional: false
  },
  {
    id: 'version',
    label: 'Publish a passport version',
    role: MANUFACTURER_ROLE,
    purpose: 'Hashes the passport document in this browser and records the digest as the current version.',
    optional: false
  },
  {
    id: 'claim',
    label: 'Attach a claim',
    role: CLAIM_ISSUER_ROLE,
    purpose: 'Records a claim about the product, in the clear or as a salted commitment disclosed out of band.',
    optional: true
  },
  {
    id: 'event',
    label: 'Append a lifecycle event',
    role: OPERATOR_ROLE,
    purpose: 'Adds an event to the ledger-ordered trail: manufactured, shipped, received, repaired, recycled.',
    optional: true
  },
  {
    id: 'certify',
    label: 'Run a certification round',
    role: CERTIFIER_ROLE,
    purpose: 'A certifier proposes; two auditors from different organizations approve; anyone applies.',
    optional: true
  },
  {
    id: 'status',
    label: 'Change status or revoke',
    role: MANUFACTURER_ROLE,
    purpose: 'Moves the passport between DRAFT, ACTIVE, SUSPENDED, REPLACED, or tombstones it for good.',
    optional: true
  }
];

function count(list: unknown): number {
  return Array.isArray(list) ? list.length : 0;
}

function holders(actors: GatewayActor[], role: string): string[] {
  return actors
    .filter((actor) => Array.isArray(actor.roles) && actor.roles.includes(role))
    .map((actor) => actor.actorId);
}

function detailOf(id: StepId, view: PassportView | null): string {
  if (!view) return id === 'register' ? 'no passport on chain yet' : '';
  switch (id) {
    case 'register':
      return `registered, status ${view.status}`;
    case 'version': {
      const versions = count(view.versions);
      return versions ? `${versions} version(s) published` : 'no version published yet';
    }
    case 'claim': {
      const claims = count(view.claims);
      return claims ? `${claims} claim(s) attached` : 'no claims yet';
    }
    case 'event': {
      const events = count(view.events);
      return events ? `${events} event(s) in the trail` : 'no events yet';
    }
    case 'certify': {
      const certificates = count(view.certificates);
      return certificates ? `${certificates} certificate(s)` : 'no certificate yet';
    }
    case 'status':
      return `status ${view.status}`;
    default:
      return '';
  }
}

function isDone(id: StepId, view: PassportView | null): boolean {
  if (!view) return false;
  switch (id) {
    case 'register':
      return true;
    case 'version':
      return count(view.versions) > 0;
    case 'claim':
      return count(view.claims) > 0;
    case 'event':
      return count(view.events) > 0;
    case 'certify':
      return count(view.certificates) > 0;
    case 'status':
      return view.status !== 'DRAFT';
    default:
      return false;
  }
}

/** The step that must be done first, or null when the step stands alone. */
export function blockedBy(id: StepId, view: PassportView | null): StepId | null {
  if (id === 'register') return null;
  if (!view) return 'register';
  if (id === 'certify' && count(view.versions) === 0) return 'version';
  return null;
}

/**
 * Derives the guided lifecycle for one product. `view` is the portal's passport view, or null
 * when the product has no passport yet; `actors` is what the gateway signs for.
 */
export function deriveWorkflow(
  view: PassportView | null,
  actors: GatewayActor[],
  selectedActorId: string
): WorkflowStep[] {
  const selected = actors.find((actor) => actor.actorId === selectedActorId) ?? null;
  return DEFINITIONS.map((definition, index) => {
    const done = isDone(definition.id, view);
    const blocker = blockedBy(definition.id, view);
    const candidates = holders(actors, definition.role);
    const hasRole = !!selected && Array.isArray(selected.roles) && selected.roles.includes(definition.role);
    let status: StepStatus;
    let hint: string;
    if (blocker) {
      status = 'BLOCKED';
      hint = `Do "${DEFINITIONS.find((step) => step.id === blocker)?.label}" first.`;
    } else if (!hasRole) {
      status = done ? 'DONE' : 'NEEDS_ROLE';
      hint = candidates.length
        ? `Sign as ${candidates.join(' or ')} for this step; the role is ${definition.role}.`
        : `No connected actor holds the ${definition.role} role.`;
    } else {
      status = done ? 'DONE' : 'READY';
      hint = done
        ? 'Already on chain; running it again writes another revision.'
        : `${selectedActorId} holds the ${definition.role} role, so this step can run now.`;
    }
    return {
      ...definition,
      order: index + 1,
      done,
      status,
      detail: detailOf(definition.id, view),
      hint,
      candidates
    };
  });
}

/** The first step worth doing: the earliest required step that is not done, else the first ready one. */
export function nextStep(steps: WorkflowStep[]): WorkflowStep | null {
  const required = steps.find((step) => !step.optional && !step.done && step.status !== 'BLOCKED');
  if (required) return required;
  return steps.find((step) => !step.done && step.status !== 'BLOCKED') ?? null;
}

export type CertificationStage = 'propose' | 'approve' | 'apply' | 'done';

export interface Approval {
  actorId: string;
  organizationId: string;
}

export interface CertificationProgress {
  stage: CertificationStage;
  /** Distinct organizations that have approved in this browser session. */
  organizations: string[];
  remaining: number;
  message: string;
}

/**
 * Where a certification round stands. Approvals are counted from what this console collected in
 * this session; the chain is the authority and refuses an apply until the policy's clause is met.
 */
export function certificationProgress(
  hasRequest: boolean,
  approvals: Approval[],
  applied: boolean,
  requiredOrganizations = 2
): CertificationProgress {
  const organizations = [...new Set(approvals.map((approval) => approval.organizationId).filter(Boolean))];
  const remaining = Math.max(0, requiredOrganizations - organizations.length);
  if (applied) {
    return { stage: 'done', organizations, remaining: 0, message: 'The certificate is on chain with its approval consumption proven.' };
  }
  if (!hasRequest) {
    return {
      stage: 'propose',
      organizations,
      remaining: requiredOrganizations,
      message: 'A certifier proposes the certificate. The request document then travels to the auditors.'
    };
  }
  if (remaining > 0) {
    return {
      stage: 'approve',
      organizations,
      remaining,
      message: organizations.length
        ? `Approved by ${organizations.join(', ')}. ${remaining} more approval needed, from a different organization.`
        : `Two auditors from different organizations must approve. Switch the signer to an auditor and approve.`
    };
  }
  return {
    stage: 'apply',
    organizations,
    remaining: 0,
    message: `Approved by ${organizations.join(' and ')}. Apply writes the certificate to the chain.`
  };
}
