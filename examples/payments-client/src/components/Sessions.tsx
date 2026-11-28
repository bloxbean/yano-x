import { KeyRound, Plus, Trash2, Zap } from 'lucide-react';
import { useState } from 'react';
import { decimalsOf, revokeSession, sessionPay } from '../flows';
import type { GrantView } from '../lib/api';
import { formatUnits } from '../lib/amounts';
import { short, vaultLabel } from '../lib/display';
import { accountIdBech32, shortId } from '../lib/ids';
import type { StoredSession } from '../lib/session';
import { usePayments, useSessions } from '../state';
import { Badge, Button, Card, EmptyState, ErrorBox, inputClass, Mono, Skeleton } from '../ui';
import { AmountInput, useAction } from './common';

function remaining(ms: number): string {
  if (ms <= 0) return 'expired';
  const minutes = Math.round(ms / 60_000);
  return minutes < 60 ? `${minutes} min left` : `${Math.floor(minutes / 60)} h ${minutes % 60} min left`;
}

export function SessionsPanel({ onNew }: { onNew: () => void }) {
  const { sessionKeysSupported } = usePayments();
  const sessions = useSessions(true);
  const grants = sessions.data?.grants ?? [];
  const local = sessions.data?.local ?? [];
  const pendingLocal = local.filter((session) => !grants.some((grant) => grant.grantId === session.grantId));
  return (
    <div className="space-y-3" data-testid="sessions">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-md text-sm text-muted">A session lets this browser pay within limits, without a wallet
          prompt. It may only transfer, never withdraw.</p>
        <Button variant="ghost" onClick={onNew} disabled={sessionKeysSupported === false} data-testid="action-session-new">
          <Plus size={15} /> New session
        </Button>
      </div>
      {sessionKeysSupported === false && (
        <ErrorBox data-testid="session-support">This browser lacks WebCrypto Ed25519, so session keys are unavailable
          here. Owner-signed actions still work.</ErrorBox>
      )}
      {sessions.isLoading && <Skeleton className="h-28 w-full" />}
      {sessions.isError && <ErrorBox>Cannot read the sessions: {(sessions.error as Error).message}</ErrorBox>}
      {sessions.data && grants.length === 0 && pendingLocal.length === 0 && (
        <EmptyState icon={<KeyRound size={18} />} title="No sessions">
          Authorize one with a single wallet prompt, then pay without prompts.
        </EmptyState>
      )}
      {grants.map((grant) => (
        <GrantCard key={grant.grantId} grant={grant} local={local.find((session) => session.grantId === grant.grantId)} />
      ))}
      {pendingLocal.map((session) => (
        <Card key={session.id} className="flex items-center gap-3 border-dashed p-4 text-sm text-muted">
          <KeyRound size={16} className="text-faint" />
          <span>Local key <Mono>{short(session.grantId)}</Mono>: its grant is pending, revoked or expired.</span>
        </Card>
      ))}
    </div>
  );
}

function GrantCard({ grant, local }: { grant: GrantView; local: StoredSession | undefined }) {
  const { info, flow, track, toast, now } = usePayments();
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const pay = useAction();
  const revoke = useAction();
  const limit = grant.limits[0];
  const vault = limit && info?.vaults.find((entry) => entry.vaultId === limit.vaultId);
  const decimals = limit && info ? decimalsOf(info, limit.vaultId) : 0;
  const ticker = vault ? vaultLabel(vault) : '';
  const networkId = info?.intentDomain.addressNetworkId ?? 0;

  const onPay = () => pay.run(async () => {
    if (!local || !limit) throw new Error('this browser has no key for this session');
    const submitted = await sessionPay(flow(), local, limit.vaultId, to, amount);
    track(submitted);
    toast({ tone: 'default', title: 'Paid with the session key', detail: 'No wallet prompt. Waiting for its receipt.' });
    setAmount('');
  });
  const onRevoke = () => revoke.run(async () => {
    track(await revokeSession(flow(), grant.grantId));
    toast({ tone: 'default', title: 'Revoke submitted' });
  });

  return (
    <Card className="rise overflow-hidden" data-testid={`grant-${grant.grantId}`}>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line/70 px-4 py-3">
        <div className="flex min-w-0 items-center gap-2">
          <span className="grid h-8 w-8 place-items-center rounded-lg bg-violet/10 text-violet"><KeyRound size={15} /></span>
          <div className="min-w-0">
            <div className="text-sm font-semibold">Session <Mono title={grant.grantId}>{short(grant.grantId, 6)}</Mono></div>
            <div className="text-[11px] text-faint">
              {grant.actions.join(', ')} · {remaining(grant.notAfterMs - now)} · until {new Date(grant.notAfterMs).toLocaleString()}
            </div>
          </div>
        </div>
        {local ? <Badge tone="accent">key in this browser</Badge> : <Badge>key not in this browser</Badge>}
      </div>
      <div className="space-y-3 px-4 py-3">
        {grant.limits.map((entry) => {
          const d = info ? decimalsOf(info, entry.vaultId) : 0;
          const used = Number(BigInt(entry.used) * 1000n / (BigInt(entry.cumulative) || 1n)) / 10;
          return (
            <div key={entry.vaultId} className="space-y-1.5">
              <div className="flex flex-wrap items-baseline justify-between gap-2 text-sm">
                <span data-testid="grant-usage" className="font-mono">
                  {formatUnits(entry.used, d)} / {formatUnits(entry.cumulative, d)} {ticker} used
                </span>
                <span className="text-[11px] text-faint">max {formatUnits(entry.perPayment, d)} each ·{' '}
                  <b className="text-muted">{formatUnits(entry.remaining, d)} left</b></span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-ink/5" role="progressbar" aria-valuemin={0}
                aria-valuemax={100} aria-valuenow={used} aria-label="Session limit used">
                <div className="h-full rounded-full bg-gradient-to-r from-accent to-lime transition-all" style={{ width: `${used}%` }} />
              </div>
            </div>
          );
        })}
        <div className="text-[11px] text-faint">
          Recipients: {grant.recipients.length ? grant.recipients.map((r) => (
            <span key={r} className="font-mono" title={`${accountIdBech32(r, networkId)}\n${r}`} data-testid="grant-recipient"
              data-hex={r}>{shortId(accountIdBech32(r, networkId), 6)} </span>)) : 'any registered account'}
        </div>
        <form className="grid gap-2 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_auto]"
          onSubmit={(event) => { event.preventDefault(); void onPay(); }}>
          <input className={`${inputClass} font-mono`} placeholder={`to: acct${networkId === 1 ? '' : '_test'}1… or an address`} value={to}
            onChange={(event) => setTo(event.target.value)} disabled={pay.busy || !local} aria-label="Pay to"
            spellCheck={false} data-testid="session-pay-to" />
          <AmountInput value={amount} onChange={setAmount} ticker={ticker} placeholder="amount" disabled={pay.busy || !local}
            data-testid="session-pay-amount" />
          <Button type="submit" busy={pay.busy} disabled={!local || !limit || !to.trim() || !amount.trim()}
            data-testid="session-pay">
            {!pay.busy && <Zap size={15} />} Pay
          </Button>
        </form>
        {limit && <p className="text-[11px] text-faint">Pay signs with the session key: no wallet prompt, no review.
          The ledger refuses anything over {formatUnits(limit.perPayment, decimals)} {ticker} per payment or the total.</p>}
        {pay.error && <ErrorBox>{pay.error}</ErrorBox>}
      </div>
      <div className="flex items-center justify-end gap-2 border-t border-line/70 bg-surface-2/60 px-4 py-2">
        {revoke.error && <span className="mr-auto text-xs text-rose" role="alert">{revoke.error}</span>}
        <Button variant="danger" className="px-3 py-1.5 text-xs" onClick={() => void onRevoke()} busy={revoke.busy}
          data-testid="session-revoke">{!revoke.busy && <Trash2 size={13} />} Revoke (signData)</Button>
      </div>
    </Card>
  );
}
