import type { VerificationLevel } from './types';

export function short(value: string | null | undefined, width = 20): string {
  if (!value) return '';
  return value.length <= width ? value : `${value.slice(0, width - 6)}…${value.slice(-4)}`;
}

export function formatTime(value: number | undefined): string {
  if (!value) return '';
  try {
    return new Date(value).toISOString().replace('T', ' ').replace(/\.\d+Z$/, ' UTC');
  } catch {
    return String(value);
  }
}

export function failureMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback;
}

export interface LevelLabel {
  text: string;
  tone: 'ok' | 'warn' | 'muted';
  detail: string;
}

/** How to read a verification level (ADR-050 §2.1). */
export function levelLabel(level: VerificationLevel | string | undefined): LevelLabel {
  switch (level) {
    case 'VERIFIED_PINNED':
      return { text: 'verified, pinned members', tone: 'ok',
        detail: 'The certified block and its finality certificate verified under caller-pinned members at ingest.' };
    case 'VERIFIED_DECLARED':
      return { text: 'verified, declared members', tone: 'ok',
        detail: 'The certified block verified under the member set the evidence bundle declares: internal consistency at ingest.' };
    case 'HEADER_ONLY':
      return { text: 'header only', tone: 'muted', detail: 'No message, so no evidence bundle; the header is the node\'s JSON view.' };
    case 'JSON_ONLY':
      return { text: 'JSON only', tone: 'warn', detail: 'The node no longer retains evidence for this height; rows come from its JSON view and cannot be proven from the index.' };
    default:
      return { text: String(level ?? 'unknown'), tone: 'muted', detail: '' };
  }
}

export function availabilityLabel(value: string | undefined): LevelLabel {
  switch (value) {
    case 'CONTENT_VERIFIED':
      return { text: 'content verified', tone: 'ok', detail: 'An archived body hashes to the committed entry hash and is served by hash.' };
    case 'FINALIZED':
      return { text: 'finalized', tone: 'muted', detail: 'The command is finalized; no archived body matches its entry hash yet.' };
    default:
      return { text: String(value ?? ''), tone: 'muted', detail: '' };
  }
}

export function stateCheckLabel(status: unknown): LevelLabel {
  switch (status) {
    case 'MATCH':
      return { text: 'matches state', tone: 'ok', detail: 'The view derived from finalized commands equals the proof-backed authenticated value.' };
    case 'DIVERGES':
      return { text: 'diverges from state', tone: 'warn', detail: 'At least one finalized command did not apply; the authenticated value is shown.' };
    case 'READ':
      return { text: 'state read', tone: 'ok', detail: 'The authenticated value was read with a proof; the module derives no comparable view.' };
    default:
      return { text: 'unchecked', tone: 'muted', detail: 'The authenticated value was not read.' };
  }
}

export function fieldText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
