import { DOC_TRAIL_STATE_MACHINE } from './envelope';
import type { AppChainStatus, DiscoveredChain } from './types';

const HEX_64 = /^[0-9a-f]{64}$/;

/** Application namespace of a standalone stdlib component; composite embeddings use their own. */
export const STANDALONE_STATE_NAMESPACE = 'application/v1';

/**
 * A chain is eligible when its status names doc-trail as the state machine, or when its manifest
 * lists a standalone doc-trail component. Composite embeddings carry another namespace and are
 * not supported by this version (ADR-047 §9).
 */
export function isDocTrailChain(status: AppChainStatus | null): boolean {
  return status?.stateMachine === DOC_TRAIL_STATE_MACHINE
    || status?.capabilityManifest?.components.some(
      (component) => component.id === DOC_TRAIL_STATE_MACHINE
        && component.stateNamespace === STANDALONE_STATE_NAMESPACE) === true;
}

export function withLiveStatus(chain: DiscoveredChain, status: AppChainStatus): DiscoveredChain {
  if (status.chainId && status.chainId !== chain.summary.chainId) {
    throw new Error('Live app-chain status does not match the selected chain');
  }
  const mergedStatus = { ...chain.status, ...status };
  return {
    ...chain,
    status: mergedStatus,
    summary: {
      ...chain.summary,
      tipHeight: mergedStatus.tipHeight ?? chain.summary.tipHeight,
      stateRoot: mergedStatus.stateRoot ?? chain.summary.stateRoot
    }
  };
}

export function canonicalHex64(value: string, what = 'identifier'): string {
  const normalized = value.trim().toLowerCase();
  if (!HEX_64.test(normalized)) {
    throw new Error(`Enter a 64-character hex ${what}`);
  }
  return normalized;
}

export function short(value: string | null | undefined, width = 24): string {
  if (!value) return '—';
  return value.length <= width ? value : `${value.slice(0, Math.max(6, width - 8))}…${value.slice(-7)}`;
}

export function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MiB`;
}

export function formatTime(value: string | number): string {
  const date = typeof value === 'number' ? new Date(value) : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function failureMessage(cause: unknown, fallback: string): string {
  if (cause && typeof cause === 'object' && 'status' in cause && 'message' in cause) {
    const error = cause as { status: number; code?: string; message: string };
    const suffix = error.status ? ` (HTTP ${error.status}${error.code ? `, ${error.code}` : ''})` : '';
    return `${error.message}${suffix}`;
  }
  return cause instanceof Error ? cause.message : fallback;
}
