import { useQuery } from '@tanstack/react-query';
import clsx from 'clsx';
import { ArrowDownToLine, ArrowUpFromLine, History, KeyRound, Send, UserPlus, XCircle } from 'lucide-react';
import { short } from '../lib/display';
import { describeCode } from '../lib/errors';
import { intentState, receiptQuery, usePayments, type Activity, type IntentActivity } from '../state';
import { Badge, Card, EmptyState, LiveDot } from '../ui';
import { ProofCheck, Stepper, type Step } from './common';
import { DepositTracker } from './flows/DepositFlow';

const ICONS = {
  REGISTER_ACCOUNT: UserPlus, TRANSFER: Send, AUTHORIZE_SESSION: KeyRound, REVOKE_SESSION: XCircle,
  REQUEST_WITHDRAWAL: ArrowUpFromLine, ROTATE_OWNER: KeyRound,
} as const;

/** What this tab submitted, newest first, each followed to its receipt or deposit record. */
export function ActivityFeed() {
  const { activity } = usePayments();
  return (
    <section className="space-y-3 lg:sticky lg:top-20 lg:self-start" aria-labelledby="activity-title">
      <div className="flex items-center justify-between">
        <h2 id="activity-title" className="flex items-center gap-2 text-lg font-semibold">
          <History size={18} className="text-accent" /> Your activity</h2>
        <span className="text-xs text-faint">this browser tab</span>
      </div>
      <div className="space-y-3" data-testid="activity">
        {activity.length === 0 && (
          <EmptyState icon={<Send size={18} />} title="Nothing submitted yet">
            Each action shows here as it is signed, sequenced and decided, with its receipt and a state proof.
          </EmptyState>
        )}
        {activity.map((item) => <ActivityCard key={item.id} item={item} />)}
      </div>
    </section>
  );
}

function ActivityCard({ item }: { item: Activity }) {
  if (item.kind === 'deposit') {
    return (
      <Card className="rise overflow-hidden" data-testid={`deposit-${item.txId}`}>
        <div className="flex items-center gap-3 border-b border-line/70 px-4 py-3">
          <span className="grid h-8 w-8 place-items-center rounded-lg bg-lime/10 text-lime"><ArrowDownToLine size={15} /></span>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-semibold">Deposit {item.label}</div>
            <div className="text-[11px] text-faint">{new Date(item.createdAtMs).toLocaleTimeString()} · L1 transaction</div>
          </div>
        </div>
        <div className="px-4 py-3"><DepositTracker item={item} testId="deposit-record" compact /></div>
      </Card>
    );
  }
  return <IntentCard item={item} />;
}

function IntentCard({ item }: { item: IntentActivity }) {
  const { api, now } = usePayments();
  const receipt = useQuery({ ...receiptQuery(api!, item), enabled: !!api });
  const state = intentState(item, receipt.data, now);
  const Icon = ICONS[item.action] ?? Send;
  const session = item.fields.some(([label]) => label === 'Signed by');
  const final = state.state === 'accepted' || state.state === 'rejected';
  const steps: Step[] = [
    { label: session ? 'Session-signed' : 'Signed', state: 'done' },
    { label: 'Submitted', state: 'done' },
    { label: state.state === 'rejected' ? 'Rejected' : state.state === 'expired' ? 'Expired' : 'Final',
      state: state.state === 'accepted' ? 'done' : state.state === 'pending' ? 'active' : 'failed' },
  ];
  const outcome = state.state === 'accepted' ? 'ACCEPTED' : state.state === 'rejected' ? `REJECTED ${state.reason}`
    : state.state === 'expired' ? 'EXPIRED' : 'PENDING';
  const summary = item.fields.find(([label]) => ['Send', 'Withdraw', 'Limit', 'Revoke grant', 'Owner'].includes(label));
  return (
    <Card className={clsx('rise overflow-hidden', state.state === 'rejected' && 'border-rose/40',
      state.state === 'accepted' && 'border-accent/40')} data-testid={`receipt-${item.intentId}`}>
      <div className="flex items-center gap-3 border-b border-line/70 px-4 py-3">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-accent/10 text-accent"><Icon size={15} /></span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold">{item.title}{summary && item.action !== 'REVOKE_SESSION'
            ? <span className="font-normal text-muted"> · {summary[1]}</span> : null}</div>
          <div className="text-[11px] text-faint">{new Date(item.createdAtMs).toLocaleTimeString()} · intent{' '}
            <span className="font-mono" title={item.intentId}>{short(item.intentId, 6)}</span></div>
        </div>
        <Badge tone={state.state === 'accepted' ? 'accent' : state.state === 'pending' ? 'default' : 'rose'}>
          {state.state === 'pending' && <LiveDot tone="amber" />}
          <span data-testid="outcome">{outcome}</span>
        </Badge>
      </div>
      <div className="space-y-2 px-4 py-3">
        <Stepper steps={steps} />
        {state.state === 'accepted' && <p className="text-xs text-muted">Final at height <b className="font-mono text-ink">{state.height}</b>.</p>}
        {state.state === 'rejected' && (
          <p className="text-xs text-rose" data-testid="outcome-detail">{describeCode(state.reason) ?? 'The ledger rejected it.'}</p>
        )}
        {state.state === 'expired' && (
          <p className="text-xs text-rose" data-testid="outcome-detail">Not executed before it expired (refused at
            admission or never sequenced).</p>
        )}
        {final && <ProofCheck fact={state.fact} label="Verify receipt" />}
      </div>
    </Card>
  );
}
