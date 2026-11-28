import { ArrowUpFromLine, ExternalLink } from 'lucide-react';
import { decimalsOf } from '../flows';
import type { WithdrawalView } from '../lib/api';
import { formatUnits } from '../lib/amounts';
import { explorerLink, short, vaultLabel } from '../lib/display';
import { usePayments, useWithdrawals } from '../state';
import { Badge, Card, EmptyState, ErrorBox, Mono, Skeleton } from '../ui';
import { Stepper, type Step } from './common';

/** RESERVED → SETTLING → PAID (or PAID_MISMATCH) as a stepper. */
export function lifecycleSteps(lifecycle: string): Step[] {
  const order = ['RESERVED', 'SETTLING', 'PAID'];
  const at = lifecycle === 'PAID_MISMATCH' ? 2 : order.indexOf(lifecycle);
  return [
    { label: 'Reserved', state: at >= 1 ? 'done' : at === 0 ? 'active' : 'todo' },
    { label: 'Settling', state: at >= 2 ? 'done' : at === 1 ? 'active' : 'todo' },
    { label: lifecycle === 'PAID_MISMATCH' ? 'Paid (mismatch)' : 'Paid',
      state: lifecycle === 'PAID_MISMATCH' ? 'failed' : at === 2 ? 'done' : 'todo' },
  ];
}

export function WithdrawalsPanel({ onNew }: { onNew: () => void }) {
  const withdrawals = useWithdrawals(true);
  const list = withdrawals.data?.withdrawals ?? [];
  return (
    <div className="space-y-3" data-testid="withdrawals">
      {withdrawals.isLoading && <Skeleton className="h-24 w-full" />}
      {withdrawals.isError && <ErrorBox>Cannot read the withdrawals: {(withdrawals.error as Error).message}</ErrorBox>}
      {withdrawals.data && list.length === 0 && (
        <EmptyState icon={<ArrowUpFromLine size={18} />} title="No withdrawals">
          A withdrawal reserves the amount on the ledger, then a settlement pays it on Cardano.
          <button type="button" onClick={onNew} className="ml-1 font-medium text-accent-strong hover:underline">Withdraw…</button>
        </EmptyState>
      )}
      {list.map((claim) => <ClaimCard key={claim.claimId} claim={claim} />)}
    </div>
  );
}

function ClaimCard({ claim }: { claim: WithdrawalView }) {
  const { info, config } = usePayments();
  const vault = info?.vaults.find((entry) => entry.vaultId === claim.vaultId);
  const decimals = info ? decimalsOf(info, claim.vaultId) : 0;
  const link = explorerLink(config?.explorerTxUrl ?? '', claim.l1TxId);
  const tone = claim.lifecycle === 'PAID' ? 'accent' : claim.lifecycle === 'PAID_MISMATCH' ? 'rose' : 'amber';
  return (
    <Card className="rise space-y-3 p-4" data-testid={`withdrawal-${claim.claimId}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="font-semibold">
            {formatUnits(claim.amount, decimals)} {vault ? vaultLabel(vault) : ''}
            {claim.vaultLovelace !== '0' && <span className="font-normal text-muted"> + {formatUnits(claim.vaultLovelace, 6)} ADA</span>}
          </div>
          <div className="text-[11px] text-faint">to <Mono className="text-[11px]" title={claim.recipientAddress ?? claim.recipientHex}>
            {short(claim.recipientAddress ?? claim.recipientHex, 12)}</Mono> · reserved at height {claim.reservedHeight}</div>
        </div>
        <Badge tone={tone}><span data-testid="lifecycle">{claim.lifecycle}</span></Badge>
      </div>
      <Stepper steps={lifecycleSteps(claim.lifecycle)} />
      <div className="text-[11px] text-faint">
        {claim.l1TxId
          ? link ? <a href={link} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-accent-strong hover:underline">
            L1 payout {short(claim.l1TxId)} <ExternalLink size={11} /></a>
            : <span>L1 payout <Mono className="break-all text-[11px]">{claim.l1TxId}</Mono></span>
          : claim.lifecycle === 'SETTLING' ? 'Settlement batch open' : 'Awaiting settlement'}
        <span className="ml-2">claim <Mono className="text-[11px]" title={claim.claimId}>{short(claim.claimId, 6)}</Mono></span>
      </div>
    </Card>
  );
}
