export function short(value: string | null | undefined, width = 24): string {
  if (!value) return '—';
  return value.length <= width ? value : `${value.slice(0, Math.max(6, width - 8))}…${value.slice(-7)}`;
}

export function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MiB`;
}

/** Epoch seconds as the viewer's locale renders them; the chain never interprets the value. */
export function formatEpochSeconds(value: number): string {
  if (!value) return '—';
  const date = new Date(value * 1000);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function failureMessage(cause: unknown, fallback: string): string {
  if (cause && typeof cause === 'object' && 'status' in cause && 'message' in cause) {
    const error = cause as { status: number; message: string };
    const suffix = error.status ? ` (HTTP ${error.status})` : '';
    return `${error.message}${suffix}`;
  }
  return cause instanceof Error ? cause.message : fallback;
}

export function pillFor(state: string | undefined): string {
  switch (state) {
    case 'ACTIVE': case 'VALID': case 'BOUND': case 'APPLIED': case 'MATCH': case 'CONTENT_VERIFIED':
      return 'pill pill-ok';
    case 'REVOKED': case 'MISMATCH': case 'REJECTED': case 'FAILED': case 'EXPIRED': case 'ABSENT':
      return 'pill pill-bad';
    default:
      return 'pill pill-warn';
  }
}

export function flagText(flag: string): string {
  switch (flag) {
    case 'REWRITTEN': return 'This record was written more than once; the ledger shows every revision.';
    case 'DANGLING': return 'The product names a current version that has no active version record.';
    case 'FOREIGN_WRITER': return 'The writer belongs to another organization than the record names.';
    case 'EXPIRED': return 'The validity window ended before this height.';
    case 'NOT_YET_VALID': return 'The validity window starts after this height.';
    case 'MALFORMED': return 'The value does not decode with the starter profile.';
    default: return flag;
  }
}
