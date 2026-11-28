import { useQuery } from '@tanstack/react-query';
import { ArrowDownToLine, ExternalLink, Info } from 'lucide-react';
import { useState } from 'react';
import { deposit, DEPOSIT_DEADLINE_MS, type DepositSubmitted } from '../../flows';
import { formatUnits } from '../../lib/amounts';
import { explorerLink, short, vaultLabel } from '../../lib/display';
import { describeCode } from '../../lib/errors';
import { depositQuery, useAccountView, usePayments } from '../../state';
import { Button, ErrorBox, Field, inputClass, Modal } from '../../ui';
import { AmountInput, AssetSelect, Stepper, useAction, type Step } from '../common';

export function DepositFlow({ onClose, initialVault }: { onClose: () => void; initialVault?: string }) {
  const { info, flow, track } = usePayments();
  const view = useAccountView().data;
  const vaults = info?.vaults ?? [];
  const [vaultId, setVaultId] = useState(initialVault ?? vaults[0]?.vaultId ?? '');
  const [amount, setAmount] = useState('');
  const [lovelace, setLovelace] = useState('1500000');
  const [step, setStep] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<DepositSubmitted | null>(null);
  const action = useAction();
  const vault = vaults.find((entry) => entry.vaultId === vaultId);

  const go = () => action.run(async () => {
    const result = await deposit(flow(), { vaultId, amount, lovelace }, setStep);
    track(result);
    setSubmitted(result);
  }).finally(() => setStep(null));

  return (
    <Modal title="Deposit from Cardano" icon={<ArrowDownToLine size={18} />} onClose={onClose} data-testid="deposit-sheet"
      subtitle="Your wallet signs an L1 transaction to the asset's vault; the ledger credits it once Cardano is stable."
      footer={submitted ? <Button variant="ghost" onClick={onClose}>Done</Button> : <>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={() => void go()} busy={action.busy} disabled={!vault || !amount.trim() || !vault.depositsEnabled}
          data-testid="deposit-go">Build &amp; review</Button>
      </>}>
      {submitted ? <DepositTracker item={submitted} testId="deposit-state" /> : (
        <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void go(); }}>
          <Field label="Asset">
            <AssetSelect vaults={vaults} value={vaultId} onChange={setVaultId} balances={view?.balances}
              disabled={action.busy} data-testid="deposit-vault" />
          </Field>
          {vault && !vault.depositsEnabled && (
            <ErrorBox>Deposits of {vaultLabel(vault)} are paused ({vault.status}). {describeCode('DEPOSITS_PAUSED')}</ErrorBox>
          )}
          <Field label="Amount" hint={vault?.asset.ada ? 'Converted at lovelace\'s 6 decimals.' : undefined}>
            <AmountInput value={amount} onChange={setAmount} ticker={vault ? vaultLabel(vault) : ''} placeholder="10"
              disabled={action.busy} data-testid="deposit-amount" />
          </Field>
          {vault && !vault.asset.ada && (
            <Field label="Lovelace sent with the token" hint="Becomes your min-ADA credit for this asset; withdrawable later.">
              <input className={`${inputClass} font-mono`} inputMode="numeric" value={lovelace} disabled={action.busy}
                onChange={(event) => setLovelace(event.target.value)} data-testid="deposit-lovelace" />
            </Field>
          )}
          <div className="flex gap-2 rounded-xl border border-lime/20 bg-lime/5 px-3 py-2 text-xs text-muted">
            <Info size={14} className="mt-0.5 shrink-0 text-lime" />
            <span>The node builds the transaction from your wallet's UTxOs, but this client decodes it and refuses it
              unless it spends only your UTxOs, pays exactly this vault with your deposit datum, returns the rest to
              you and pays at most the configured fee. You review the decoded values before the wallet signs.</span>
          </div>
          {step && <p className="text-sm text-muted" role="status" data-testid="deposit-step">{step}</p>}
          {action.error && <ErrorBox data-testid="action-error">{action.error}</ErrorBox>}
        </form>
      )}
    </Modal>
  );
}

/** A deposit from submission to its ledger record; polls until the record exists (or the deadline passes). */
export function DepositTracker({ item, testId, compact }: { item: DepositSubmitted; testId: string; compact?: boolean }) {
  const { api, config, info, now } = usePayments();
  const record = useQuery({ ...depositQuery(api!, item), enabled: !!api });
  const deposit = record.data?.deposit;
  const late = !deposit && now > item.submittedAtMs + DEPOSIT_DEADLINE_MS;
  const decimals = info?.vaults.find((vault) => vault.vaultId === item.vaultId)?.decimals ?? 0;
  const vault = info?.vaults.find((entry) => entry.vaultId === item.vaultId);
  const credited = deposit?.status === 'CREDITED';
  const steps: Step[] = [
    { label: 'Signed', state: 'done' },
    { label: 'On Cardano', state: 'done' },
    { label: deposit && !credited ? deposit.status.toLowerCase() : 'Credited',
      state: credited ? 'done' : deposit ? 'failed' : late ? 'todo' : 'active' },
  ];
  const link = explorerLink(config?.explorerTxUrl ?? '', item.txId);
  const text = credited ? `CREDITED ${formatUnits(deposit.creditedAmount, decimals)} ${vault ? vaultLabel(vault) : ''}`.trim()
    : deposit ? `${deposit.status} (${deposit.reason})`
      : late ? 'not credited within 10 minutes; check the deposit record later'
        : 'waiting for L1 stability and the next app-ledger block…';
  return (
    <div className="space-y-3">
      {!compact && <div className="text-sm">Deposit of <b>{item.label}</b> submitted.</div>}
      <Stepper steps={steps} />
      <p className={credited ? 'text-sm font-semibold text-accent-strong' : deposit ? 'text-sm text-rose' : 'text-sm text-muted'}
        data-testid={testId} aria-live="polite">{text}</p>
      {deposit && !credited && describeCode(deposit.reason) && <p className="text-xs text-muted">{describeCode(deposit.reason)}</p>}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-faint" data-testid="deposit-tx">
        <span>L1 tx <span className="font-mono">{short(item.txId, 10)}</span>#{item.outputIndex}</span>
        {link && <a href={link} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-accent-strong hover:underline">
          view on explorer <ExternalLink size={11} /></a>}
      </div>
    </div>
  );
}
