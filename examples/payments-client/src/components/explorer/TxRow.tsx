import clsx from 'clsx';
import {
  ArrowDownToLine, ArrowRight, ArrowUpFromLine, Banknote, CircleHelp, ExternalLink, Eye, KeyRound, Landmark, RefreshCw,
  Send, UserPlus, XCircle, Zap, type LucideIcon
} from 'lucide-react';
import type { ReactNode } from 'react';
import type { VaultView } from '../../lib/api';
import { explorerLink, short } from '../../lib/display';
import type { DecodedTx, Outcome, TxKind } from '../../lib/explorer';
import { ago, rowView, type Party, type RowTone } from '../../lib/txrow';
import { accountHref, blockHref, txHref } from '../../route';
import { usePayments } from '../../state';
import { Badge, LiveDot, Skeleton } from '../../ui';
import { ProofCheck } from '../common';
import { useOutcome } from './hooks';

export const KIND_ICONS: Record<TxKind, LucideIcon> = {
  register: UserPlus, transfer: Send, 'session-pay': Zap, 'session-authorize': KeyRound, revoke: XCircle,
  withdraw: ArrowUpFromLine, rotate: RefreshCw, deposit: ArrowDownToLine, payout: Banknote, observation: Eye,
  governance: Landmark, unknown: CircleHelp,
};

const TILE: Record<RowTone, string> = {
  accent: 'bg-accent/10 text-accent', lime: 'bg-lime/10 text-lime', amber: 'bg-amber/10 text-amber',
  cyan: 'bg-cyan/10 text-cyan', violet: 'bg-violet/10 text-violet', rose: 'bg-rose/10 text-rose',
  default: 'bg-ink/5 text-faint',
};

/** Kinds that leave a ledger record (a receipt or a deposit record) whose proof can be checked. */
export function hasRecord(tx: DecodedTx): boolean {
  return !!tx.intentId || tx.kind === 'deposit';
}

export function OutcomeBadge({ tx, outcome, error }: { tx: DecodedTx; outcome?: Outcome; error?: boolean }) {
  if (!hasRecord(tx)) {
    return <span className="text-xs text-faint" title="This message leaves no receipt or deposit record"
      data-testid="outcome">—</span>;
  }
  if (!outcome) {
    return error ? <Badge tone="amber" data-testid="outcome" title="The node did not answer">unavailable</Badge>
      : <Skeleton className="h-5 w-20" />;
  }
  const label = {
    accepted: 'Accepted', rejected: 'Rejected', credited: 'Credited', uncredited: 'Uncredited', none: 'No receipt',
    pending: 'Pending', inconsistent: 'Inconsistent',
  }[outcome.state];
  const tone = ({ accepted: 'accent', credited: 'lime', rejected: 'rose', inconsistent: 'rose', uncredited: 'amber',
    none: 'default', pending: 'default' } as const)[outcome.state];
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1">
      <Badge tone={tone} title={outcome.text} data-testid="outcome">
        {outcome.state === 'pending' && <LiveDot tone="amber" />}
        {label}
      </Badge>
      {outcome.code && <span className="min-w-0 truncate font-mono text-[10px] text-muted" title={outcome.text}
        data-testid="outcome-code">{outcome.code}</span>}
    </span>
  );
}

function PartyText({ party }: { party?: Party }) {
  if (!party) return <span className="text-faint">—</span>;
  const href = accountHref(party.account);
  return href
    ? <a href={href} title={party.title} data-testid="party-account"
      className="font-mono text-[12px] text-accent-strong hover:underline">{party.text}</a>
    : <span title={party.title} className={party.title ? 'font-mono text-[12px] text-muted' : 'text-xs text-muted'}>
      {party.text}</span>;
}

/** An L1 transaction id, linked through the configured explorer template when it is a plain 32-byte hex id. */
export function L1Link({ txId, template, label = 'L1 tx' }: { txId?: string; template: string; label?: string }) {
  if (!txId) return null;
  const link = explorerLink(template, txId);
  return link
    ? <a href={link} target="_blank" rel="noreferrer" data-testid="l1-link" title={txId}
      className="inline-flex items-center gap-1 text-[11px] font-medium text-accent-strong hover:underline">
      {label} {short(txId, 4)} <ExternalLink size={11} /></a>
    : <span className="font-mono text-[11px] text-muted" title={txId} data-testid="l1-tx">{label} {short(txId, 4)}</span>;
}

/**
 * One decoded message as a row: kind, from → to, amount, outcome, position and age. Presentational: the outcome and
 * the Verify control come from the caller, so the row renders the same with or without a node.
 */
export function TxRow({ tx, vaults, outcome, outcomeError, timestamp, now, fresh, verify, explorerTxUrl = '' }: {
  tx: DecodedTx; vaults: VaultView[]; outcome?: Outcome; outcomeError?: boolean; timestamp?: number; now: number;
  fresh?: boolean; verify?: ReactNode; explorerTxUrl?: string;
}) {
  const view = rowView(tx, vaults);
  const Icon = KIND_ICONS[tx.kind];
  const detail = txHref(tx.messageId);
  return (
    <li data-testid="tx-row" data-kind={tx.kind} data-key={tx.key}
      className={clsx('grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-1.5 px-4 py-3 transition-colors hover:bg-surface-2/70',
        '@3xl:grid-cols-[auto_minmax(0,1fr)_minmax(0,1.45fr)_minmax(0,0.85fr)_minmax(0,1.1fr)_auto]', fresh && 'row-fresh')}>
      <span className={clsx('row-span-2 grid h-9 w-9 place-items-center rounded-xl @3xl:row-span-1', TILE[view.tone])}>
        <Icon size={16} aria-hidden />
      </span>
      <div className="min-w-0">
        <div className="flex min-w-0 items-center gap-2">
          {detail
            ? <a href={detail} className="truncate text-sm font-semibold hover:text-accent-strong hover:underline" data-testid="tx-kind">
              {view.title}</a>
            : <span className="truncate text-sm font-semibold" data-testid="tx-kind">{view.title}</span>}
        </div>
        <div className="flex flex-wrap items-center gap-x-2 text-[11px] text-faint">
          <a href={blockHref(tx.height)} className="font-mono hover:text-accent-strong hover:underline" data-testid="tx-position">
            #{tx.height}·{tx.index}</a>
          {timestamp !== undefined && <span title={new Date(timestamp).toISOString()}>{ago(timestamp, now)}</span>}
        </div>
      </div>
      <div className="col-start-2 flex min-w-0 flex-wrap items-center gap-1.5 text-xs @3xl:col-start-auto" data-testid="tx-parties">
        {view.note && !view.from && !view.to
          ? <span className="min-w-0 break-words text-muted" data-testid="tx-note">{view.note}</span>
          : <>
            <PartyText party={view.from} />
            <ArrowRight size={12} className="shrink-0 text-faint" aria-label="to" />
            <PartyText party={view.to} />
            {view.note && <span className="w-full break-words text-[11px] text-rose" data-testid="tx-note">{view.note}</span>}
          </>}
      </div>
      <div className="col-start-2 min-w-0 @3xl:col-start-auto" data-testid="tx-amount">
        {view.amount !== undefined
          ? <div className="truncate text-sm"><span className="font-mono font-semibold text-ink">{view.amount}</span>
            <span className="ml-1 text-xs font-medium text-muted">{view.ticker}</span></div>
          : <span className="text-xs text-faint">—</span>}
        {view.extra && <div className="truncate text-[11px] text-faint" title={view.extra}>{view.extra}</div>}
      </div>
      <div className="col-start-2 flex min-w-0 flex-col items-start gap-0.5 @3xl:col-start-auto">
        <OutcomeBadge tx={tx} outcome={outcome} error={outcomeError} />
        {outcome?.state === 'rejected' && (
          <span className="line-clamp-2 text-[11px] text-rose" data-testid="outcome-reason">{outcome.text}</span>
        )}
        <L1Link txId={view.l1TxId} template={explorerTxUrl} />
      </div>
      <div className="col-start-2 flex items-center justify-start @3xl:col-start-auto @3xl:justify-end">{verify}</div>
    </li>
  );
}

/** A row with its outcome read from the node and a Verify control for the record's proof. */
export function LiveTxRow({ tx, timestamp, fresh }: { tx: DecodedTx; timestamp?: number; fresh?: boolean }) {
  const { info, config, now } = usePayments();
  const { outcome, error } = useOutcome(tx);
  return (
    <TxRow tx={tx} vaults={info?.vaults ?? []} outcome={outcome} outcomeError={error} timestamp={timestamp} now={now}
      explorerTxUrl={config?.explorerTxUrl}
      fresh={fresh} verify={outcome?.fact ? <ProofCheck fact={outcome.fact} label="Verify" compact /> : null} />
  );
}

export function TxList({ children, empty, ...rest }: { children: ReactNode; empty?: ReactNode; 'data-testid'?: string }) {
  return (
    <ul {...rest} className="@container divide-y divide-line/70">
      {children}
      {empty}
    </ul>
  );
}
