export function short(value: string | null | undefined, width = 24): string {
  if (!value) return '—';
  return value.length <= width ? value : `${value.slice(0, Math.max(6, width - 8))}…${value.slice(-7)}`;
}

/** Epoch seconds as the viewer's locale renders them; the chain never interprets the value. */
export function formatEpochSeconds(value: number | bigint | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const seconds = Number(value);
  if (!seconds) return '—';
  const date = new Date(seconds * 1000);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(date);
}

/** `value × 10^-scale` as a plain decimal string, exactly, for display only. */
export function decimal(value: bigint | number | string, scale: number): string {
  const big = BigInt(value);
  const negative = big < 0n;
  let digits = (negative ? -big : big).toString();
  if (scale > 0) {
    if (digits.length <= scale) digits = '0'.repeat(scale - digits.length + 1) + digits;
    digits = `${digits.slice(0, digits.length - scale)}.${digits.slice(digits.length - scale)}`;
  }
  return (negative ? '-' : '') + digits;
}

/** A decimal typed by a person ("-18.25") as the integer at the feed's scale, exactly. */
export function scaled(text: string, scale: number): bigint {
  const trimmed = text.trim();
  const match = trimmed.match(/^(-?)([0-9]+)(?:\.([0-9]*))?$/);
  if (!match) throw new Error('Enter a decimal number such as -18.25');
  const fraction = (match[3] ?? '').padEnd(scale, '0');
  if (fraction.length > scale) throw new Error(`At most ${scale} decimal place(s) at this feed's scale`);
  const magnitude = BigInt(match[2] + fraction);
  return match[1] ? -magnitude : magnitude;
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
    case 'ACTIVE': case 'BOUND': case 'APPLIED': case 'CLOSED': case 'ACCEPTED': case 'AGREES': case 'BINDS': case 'APPROVE':
      return 'pill pill-ok';
    case 'REVOKED': case 'MISMATCH': case 'REJECTED': case 'FAILED': case 'ABSENT': case 'DISAGREES': case 'REJECT':
    case 'FOREIGN_WRITER': case 'EQUIVOCATED': case 'OUT_OF_RANGE': case 'WRONG_ROUND': case 'RECORD_DISAGREES':
      return 'pill pill-bad';
    default:
      return 'pill pill-warn';
  }
}

export function dispositionText(disposition: string): string {
  switch (disposition) {
    case 'ACCEPTED': return 'Counted: written by its source once, inside the round window and the value bounds, within the outlier tolerance.';
    case 'OUTLIER': return 'Excluded: farther from the reference median than the feed policy permits.';
    case 'ABSENT': return 'No observation for this source at the observation height.';
    case 'REVOKED': return 'The observation was revoked (a tombstone).';
    case 'FOREIGN_WRITER': return 'Excluded: another actor than the source wrote this key.';
    case 'EQUIVOCATED': return 'Excluded: the source rewrote its observation (revision above 1).';
    case 'WRONG_ROUND': return 'Excluded: the signed observation time lies outside the round window.';
    case 'OUT_OF_RANGE': return 'Excluded: the value lies outside the feed bounds.';
    default: return disposition;
  }
}

export function flagText(flag: string): string {
  switch (flag) {
    case 'RECORD_DISAGREES': return 'The recorded result differs from the recomputation over the proven observations.';
    case 'WRONG_POLICY': return 'The record binds another feed policy than the one proven at the observation height.';
    case 'DATUM_MISMATCH': return 'The record\'s datum hash is not the candidate datum of this result.';
    case 'FEED_PAUSED': return 'The feed was paused at the observation height; a paused feed closes no rounds.';
    case 'REWRITTEN': return 'The record was written more than once.';
    case 'UNPROVEN_APPROVAL': return 'The record carries no proof of its approval consumption.';
    case 'LATER_HEIGHT': return 'This bundle re-answers the round at another height than the record declares; compare, do not verify.';
    case 'RECORD_REVOKED': return 'The record was revoked; the round has no agreed result.';
    case 'MALFORMED': return 'A value does not decode with the starter profile.';
    default: return flag;
  }
}
