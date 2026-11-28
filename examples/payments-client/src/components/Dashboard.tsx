import clsx from 'clsx';
import {
  AlertTriangle, ArrowDownToLine, ArrowUpFromLine, Blocks, CheckCircle2, KeyRound, PenLine, RefreshCw, Send, Store, UserPlus
} from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ownsAccount, register } from '../flows';
import type { Info } from '../lib/api';
import { PAYMENT_KEY } from '../lib/intents';
import { useAccountView, usePayments, useSessions, useWithdrawals } from '../state';
import { Badge, Button, Card, Copy, ErrorBox, LiveDot, Skeleton } from '../ui';
import { ActivityFeed } from './Activity';
import { Balances } from './Balances';
import { useAction } from './common';
import { DepositFlow } from './flows/DepositFlow';
import { PayRequestFlow } from './flows/PayRequestFlow';
import { SendFlow } from './flows/SendFlow';
import { SessionFlow } from './flows/SessionFlow';
import { WithdrawFlow } from './flows/WithdrawFlow';
import { LedgerPanel } from './Ledger';
import { SessionsPanel } from './Sessions';
import { WithdrawalsPanel } from './Withdrawals';

type Sheet = 'deposit' | 'send' | 'withdraw' | 'session' | 'payreq' | null;
type Tab = 'sessions' | 'withdrawals' | 'ledger';

/** A connected wallet: onboarding until the account is registered, then the account's dashboard. */
export function Dashboard({ info }: { info: Info }) {
  const { account, credential, payLink, takePayLink } = usePayments();
  const view = useAccountView();
  const [sheet, setSheet] = useState<Sheet>(null);
  const [link, setLink] = useState<string | null>(null);
  const registered = !!view.data?.registered;
  const owned = ownsAccount(view.data, credential);

  // Opened with a payment request link: show it once the account can pay.
  useEffect(() => {
    if (payLink && registered && owned) {
      setLink(takePayLink());
      setSheet('payreq');
    }
  }, [payLink, registered, owned, takePayLink]);

  if (!account) return null;
  return (
    <div className="space-y-6">
      <AccountHero info={info} onAction={setSheet} />
      {view.isError && <ErrorBox>Cannot read the account: {(view.error as Error).message}</ErrorBox>}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        <div className="space-y-6">
          {view.isLoading && <Card className="space-y-3 p-5"><Skeleton className="h-6 w-48" /><Skeleton className="h-16 w-full" /></Card>}
          {view.data && !registered && <Onboarding />}
          {view.data && registered && !owned && (
            <div role="alert" data-testid="not-owner"
              className="flex gap-2 rounded-2xl border border-rose/30 bg-rose/10 px-4 py-3 text-sm text-rose">
              <AlertTriangle size={16} className="mt-0.5 shrink-0" />
              This account is owned by another credential now (owner rotation). Connect its owner, or choose an account
              this wallet owns under Account details.
            </div>
          )}
          {view.data && registered && (
            <>
              <Balances view={view.data} vaults={info.vaults} loading={view.isLoading} onDeposit={() => setSheet('deposit')} />
              <Panels info={info} onAction={setSheet} />
            </>
          )}
        </div>
        <ActivityFeed />
      </div>
      {sheet === 'deposit' && <DepositFlow onClose={() => setSheet(null)} />}
      {sheet === 'send' && <SendFlow onClose={() => setSheet(null)} />}
      {sheet === 'withdraw' && <WithdrawFlow onClose={() => setSheet(null)} />}
      {sheet === 'session' && <SessionFlow onClose={() => setSheet(null)} />}
      {sheet === 'payreq' && <PayRequestFlow initialLink={link} onClose={() => { setSheet(null); setLink(null); }} />}
    </div>
  );
}

function AccountHero({ info, onAction }: { info: Info; onAction: (sheet: Sheet) => void }) {
  const { account, wallet, credential } = usePayments();
  const queries = useQueryClient();
  const view = useAccountView();
  const registered = !!view.data?.registered;
  const owned = ownsAccount(view.data, credential);
  const sessions = useSessions(registered);
  const withdrawals = useWithdrawals(registered);
  const can = registered && owned;
  const pending = withdrawals.data?.withdrawals.filter((claim) => !['PAID', 'PAID_MISMATCH'].includes(claim.lifecycle)).length;
  if (!account || !wallet) return null;
  const actions: { id: Exclude<Sheet, null>; label: string; icon: typeof Send; testId: string; disabled?: boolean }[] = [
    { id: 'deposit', label: 'Deposit', icon: ArrowDownToLine, testId: 'action-deposit' },
    { id: 'send', label: 'Send', icon: Send, testId: 'action-send' },
    { id: 'session', label: 'Session', icon: KeyRound, testId: 'action-session' },
    { id: 'withdraw', label: 'Withdraw', icon: ArrowUpFromLine, testId: 'action-withdraw' },
    { id: 'payreq', label: 'Pay request', icon: Store, testId: 'action-pay-request' },
  ];
  return (
    <section className="rise relative overflow-hidden rounded-3xl border border-line bg-surface p-6 sm:p-8">
      <div className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-gradient-to-br from-accent/20 to-transparent blur-3xl" />
      <div className="relative flex flex-wrap items-start justify-between gap-6">
        <div className="min-w-0 max-w-2xl">
          <div className="text-xs font-semibold uppercase tracking-[0.18em] text-accent-strong">
            asset-payments · {info.chainId}</div>
          <h1 className="mt-2 text-3xl font-bold sm:text-4xl">Your account</h1>
          <div className="mt-2 flex min-w-0 items-start gap-1">
            <span className="min-w-0 break-all font-mono text-[13px] text-muted" id="account-id" data-testid="account-id">
              {account.accountId}</span>
            <Copy value={account.accountId} label="Copy account id" />
          </div>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {view.data ? (
              <Badge tone={registered ? 'accent' : 'amber'} data-testid="registration">
                {registered ? <CheckCircle2 size={12} /> : <UserPlus size={12} />}
                {registered ? `Registered (owner ${view.data.owner?.credentialKind})` : 'Not registered yet'}
              </Badge>
            ) : <Skeleton className="h-5 w-32" />}
            <Badge>{wallet.name} · {account.kind === PAYMENT_KEY ? 'payment key' : 'stake key'}</Badge>
          </div>
        </div>
        <div className="grid min-w-[220px] grid-cols-2 gap-3 text-sm">
          <Stat label="Ledger height" value={view.data?.committed.height ?? info.committed.height} live />
          <Stat label="Assets" value={info.vaults.length} />
          <Stat label="Sessions" value={registered ? sessions.data?.grants.length ?? '…' : '—'} />
          <Stat label="Withdrawals pending" value={registered ? pending ?? '…' : '—'} />
        </div>
      </div>
      <div className="relative mt-6 flex flex-wrap items-center gap-2 border-t border-line/70 pt-5">
        {actions.map((action, i) => (
          <Button key={action.id} variant={i === 0 ? 'primary' : 'ghost'} disabled={!can} data-testid={action.testId}
            onClick={() => onAction(action.id)} title={can ? undefined : 'Register the account first'}>
            <action.icon size={15} /> {action.label}
          </Button>
        ))}
        <Button variant="subtle" className="ml-auto" data-testid="refresh" busy={view.isFetching && !view.isLoading}
          onClick={() => { for (const key of ['account', 'sessions', 'withdrawals', 'info']) void queries.invalidateQueries({ queryKey: [key] }); }}>
          {!(view.isFetching && !view.isLoading) && <RefreshCw size={14} />} Refresh
        </Button>
      </div>
    </section>
  );
}

function Stat({ label, value, live }: { label: string; value: ReactNode; live?: boolean }) {
  return (
    <div className="rounded-2xl border border-line/70 bg-surface-2 px-3 py-2.5">
      <div className="text-[11px] text-faint">{label}</div>
      <div className="mt-0.5 flex items-center gap-1.5 font-mono text-base text-ink">{live && <LiveDot />}{value}</div>
    </div>
  );
}

/** Registration: one signed message, no L1 transaction. */
function Onboarding() {
  const { flow, track } = usePayments();
  const action = useAction();
  return (
    <Card className="rise overflow-hidden" data-testid="onboarding">
      <div className="p-6">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-accent/10 text-accent"><UserPlus size={20} /></span>
          <div>
            <h2 className="text-xl font-semibold">Register your account</h2>
            <p className="text-sm text-muted">One step: sign a message. No L1 transaction, no fee, no funds needed.</p>
          </div>
        </div>
        <ol className="mt-5 grid gap-3 sm:grid-cols-3">
          {[
            { icon: CheckCircle2, title: 'Review', text: 'See exactly what you sign: your account, the chain and its genesis.' },
            { icon: PenLine, title: 'Sign in your wallet', text: 'CIP-30 signData over the intent bytes. Nothing is spent.' },
            { icon: Blocks, title: 'Final in a block', text: 'The ledger records your account; deposits to it are credited from then on.' },
          ].map((step, i) => (
            <li key={step.title} className="rounded-2xl border border-line/70 bg-surface-2 p-3">
              <div className="flex items-center gap-2 text-sm font-semibold"><step.icon size={15} className="text-accent" />
                <span className="text-faint">{i + 1}.</span> {step.title}</div>
              <p className="mt-1 text-xs text-muted">{step.text}</p>
            </li>
          ))}
        </ol>
        <p className="mt-4 text-xs text-faint">Register once. Deposits to an unregistered account are not credited.</p>
        {action.error && <div className="mt-3"><ErrorBox data-testid="action-error">{action.error}</ErrorBox></div>}
      </div>
      <div className="flex justify-end border-t border-line/70 bg-surface-2/60 px-6 py-3">
        <Button busy={action.busy} data-testid="register"
          onClick={() => void action.run(async () => track(await register(flow())))}>
          {!action.busy && <PenLine size={15} />} Register (sign a message)
        </Button>
      </div>
    </Card>
  );
}

function Panels({ info, onAction }: { info: Info; onAction: (sheet: Sheet) => void }) {
  const [tab, setTab] = useState<Tab>('sessions');
  const tabs: { id: Tab; label: string; icon: typeof Send }[] = [
    { id: 'sessions', label: 'Sessions', icon: KeyRound },
    { id: 'withdrawals', label: 'Withdrawals', icon: ArrowUpFromLine },
    { id: 'ledger', label: 'Ledger', icon: Blocks },
  ];
  return (
    <section className="space-y-4">
      <div role="tablist" aria-label="Account details" className="flex gap-1 rounded-2xl border border-line bg-surface p-1"
        onKeyDown={(event) => {
          if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
          const next = (tabs.findIndex((t) => t.id === tab) + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length;
          setTab(tabs[next].id);
          document.getElementById(`tab-${tabs[next].id}`)?.focus();
        }}>
        {tabs.map((t) => (
          <button key={t.id} id={`tab-${t.id}`} type="button" role="tab" aria-selected={tab === t.id}
            aria-controls={`panel-${t.id}`} tabIndex={tab === t.id ? 0 : -1} onClick={() => setTab(t.id)}
            data-testid={`tab-${t.id}`}
            className={clsx('flex flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-xl px-3 py-2 text-sm font-medium transition',
              tab === t.id ? 'bg-raised text-ink shadow-inner' : 'text-muted hover:text-ink')}>
            <t.icon size={15} className={tab === t.id ? 'text-accent' : ''} /> {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'sessions' && <SessionsPanel onNew={() => onAction('session')} />}
        {tab === 'withdrawals' && <WithdrawalsPanel onNew={() => onAction('withdraw')} />}
        {tab === 'ledger' && <LedgerPanel info={info} />}
      </div>
    </section>
  );
}
