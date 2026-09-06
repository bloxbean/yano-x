/**
 * The operator view as a guided set of writes (ADR-053 §2.2 and §2.3). Pure functions over what
 * the chain already answers and which actors the gateway holds, so the console can say what a
 * step needs before it is attempted.
 *
 * Guidance only. The chain authorizes every write by the actor's signature under its policy and
 * refuses anything else with its own error code, which the console shows.
 */
import type { GatewayActor } from './types';

export type WriteStepId = 'subject' | 'status' | 'publish' | 'revoke' | 'schema';
export type WriteStepStatus = 'READY' | 'NEEDS_ROLE' | 'BLOCKED';

export const ISSUER_ROLE = 'issuer';
export const REGISTRAR_ROLE = 'registrar';

/** What the console read from the chain for the list and index the operator entered. */
export interface RegistryProgress {
  listId: string;
  index: string;
  /** Presence of `status/<listId>/<index>`, or '' when it was not read. */
  indexPresence: 'ACTIVE' | 'REVOKED' | 'ABSENT' | '';
  /** Whether `status-lists/<listId>` is active. */
  listPublished: boolean;
  publishedHeight: number;
}

export const EMPTY_PROGRESS: RegistryProgress = {
  listId: '',
  index: '',
  indexPresence: '',
  listPublished: false,
  publishedHeight: 0
};

export interface WriteStep {
  id: WriteStepId;
  order: number;
  label: string;
  role: string;
  purpose: string;
  status: WriteStepStatus;
  detail: string;
  hint: string;
  candidates: string[];
}

interface Definition {
  id: WriteStepId;
  label: string;
  role: string;
  purpose: string;
}

const DEFINITIONS: Definition[] = [
  {
    id: 'subject',
    label: 'Record a subject',
    role: REGISTRAR_ROLE,
    purpose: 'Registers a subject with its controlling organization, kind, and metadata hash.'
  },
  {
    id: 'status',
    label: 'Set a credential status',
    role: ISSUER_ROLE,
    purpose: 'Writes one index of a status list: the bit at that position and a reason code.'
  },
  {
    id: 'publish',
    label: 'Publish the status list',
    role: ISSUER_ROLE,
    purpose: 'Replays the applied status writes into a bitstring and records its SHA-256 on chain.'
  },
  {
    id: 'revoke',
    label: 'Revoke an index',
    role: ISSUER_ROLE,
    purpose: 'Tombstones the index. Revocation is terminal: the entry can never return to active.'
  },
  {
    id: 'schema',
    label: 'Register a schema',
    role: REGISTRAR_ROLE,
    purpose: 'Stores opaque schema bytes under a schema id for verifiers to fetch by proof.'
  }
];

function holders(actors: GatewayActor[], role: string): string[] {
  return actors
    .filter((actor) => Array.isArray(actor.roles) && actor.roles.includes(role))
    .map((actor) => actor.actorId);
}

function blockerOf(id: WriteStepId, progress: RegistryProgress): string {
  if (id === 'revoke') {
    if (progress.indexPresence === 'REVOKED') return 'That index is already revoked, and revocation is terminal.';
    if (progress.indexPresence !== 'ACTIVE') {
      return 'Read an active index first: only an entry that is active now can be revoked.';
    }
  }
  if (id === 'publish' && progress.indexPresence === 'ABSENT' && !progress.listPublished) {
    return 'Write a status index for this list first, so there is something to publish.';
  }
  return '';
}

function detailOf(id: WriteStepId, progress: RegistryProgress): string {
  switch (id) {
    case 'status':
    case 'revoke':
      if (!progress.indexPresence) return 'index not read yet';
      return `${progress.listId}/${progress.index} is ${progress.indexPresence}`;
    case 'publish':
      return progress.listPublished
        ? `published, replayed to height ${progress.publishedHeight}`
        : 'not published yet';
    default:
      return '';
  }
}

export function deriveWriteSteps(
  progress: RegistryProgress,
  actors: GatewayActor[],
  selectedActorId: string
): WriteStep[] {
  const selected = actors.find((actor) => actor.actorId === selectedActorId) ?? null;
  return DEFINITIONS.map((definition, index) => {
    const candidates = holders(actors, definition.role);
    const hasRole = !!selected && Array.isArray(selected.roles) && selected.roles.includes(definition.role);
    const blocker = blockerOf(definition.id, progress);
    let status: WriteStepStatus;
    let hint: string;
    if (blocker) {
      status = 'BLOCKED';
      hint = blocker;
    } else if (!hasRole) {
      status = 'NEEDS_ROLE';
      hint = candidates.length
        ? `Sign as ${candidates.join(' or ')} for this step; the role is ${definition.role}.`
        : `No connected actor holds the ${definition.role} role.`;
    } else {
      status = 'READY';
      hint = `${selectedActorId} holds the ${definition.role} role, so this step can run now.`;
    }
    return {
      ...definition,
      order: index + 1,
      status,
      detail: detailOf(definition.id, progress),
      hint,
      candidates
    };
  });
}

/** The request body for each write, so the shapes are tested without a running gateway. */
export function statusBody(actorId: string, listId: string, index: string, bit: string, reason: string) {
  return {
    actorId,
    listId: listId.trim(),
    index: requireIndex(index),
    bit: bit === '1' ? 1 : 0,
    reasonCode: reason.trim() ? requireIndex(reason) : 0
  };
}

export function revokeBody(actorId: string, listId: string, index: string) {
  return { actorId, listId: listId.trim(), index: requireIndex(index) };
}

export function subjectBody(
  actorId: string,
  subjectId: string,
  controllerOrganizationId: string,
  kind: string,
  metadataHashHex: string
) {
  const hex = metadataHashHex.trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(hex)) {
    throw new Error('The metadata hash is 32 bytes as 64 hex characters');
  }
  if (!subjectId.trim()) throw new Error('Enter a subject id');
  return {
    actorId,
    subjectId: subjectId.trim(),
    controllerOrganizationId: controllerOrganizationId.trim(),
    kind: kind.trim(),
    metadataHashHex: hex
  };
}

export function schemaBody(actorId: string, schemaId: string, value: string) {
  if (!schemaId.trim()) throw new Error('Enter a schema id');
  if (!value) throw new Error('Enter the schema bytes');
  return { actorId, schemaId: schemaId.trim(), valueBase64: base64(value) };
}

export function publishBody(actorId: string, listId: string, purpose: string) {
  if (!listId.trim()) throw new Error('Enter a list id');
  return { actorId, listId: listId.trim(), purpose: purpose.trim() || 'revocation' };
}

function requireIndex(value: string): number {
  const trimmed = value.trim();
  if (!/^[0-9]{1,19}$/.test(trimmed)) throw new Error('An index is a non-negative integer');
  return Number(trimmed);
}

function base64(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
