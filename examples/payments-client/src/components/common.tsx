import { useMutation } from '@tanstack/react-query';
import clsx from 'clsx';
import { CheckCircle2, CircleDashed, ShieldCheck, ShieldX, XCircle } from 'lucide-react';
import { useCallback, useState, type ReactNode } from 'react';
import { verifyFact, type Verdict } from '../flows';
import type { Balance, VaultView } from '../lib/api';
import { formatUnits } from '../lib/amounts';
import { vaultLabel } from '../lib/display';
import { errorMessage } from '../lib/errors';
import type { Fact } from '../lib/proof';
import { usePayments } from '../state';
import { Badge, Button, inputClass, Spinner } from '../ui';

/** Runs one user action at a time: busy while it runs, a readable error when it fails, nothing on a cancel. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = useCallback(async <T,>(action: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await action();
    } catch (failure) {
      setError(errorMessage(failure));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);
  return { busy, error, setError, run };
}

export function AssetSelect({ vaults, value, onChange, balances, disabled, ...rest }: {
  vaults: VaultView[]; value: string; onChange: (vaultId: string) => void; balances?: Balance[]; disabled?: boolean;
  'data-testid'?: string; id?: string;
}) {
  return (
    <select className={inputClass} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}
      {...rest}>
      {vaults.map((vault) => {
        const balance = balances?.find((entry) => entry.vaultId === vault.vaultId);
        return (
          <option key={vault.vaultId} value={vault.vaultId}>
            {vaultLabel(vault)}{balance ? ` · ${formatUnits(balance.available, balance.decimals)} available` : ''}
            {vault.status !== 'ACTIVE' ? ` (${vault.status.toLowerCase().replace('_', ' ')})` : ''}
          </option>
        );
      })}
    </select>
  );
}

/** An amount input with the asset's ticker as a suffix and an optional Max shortcut. */
export function AmountInput({ value, onChange, ticker, onMax, disabled, ...rest }: {
  value: string; onChange: (value: string) => void; ticker: string; onMax?: () => void; disabled?: boolean;
  'data-testid'?: string; id?: string; placeholder?: string;
}) {
  return (
    <div className="relative">
      <input className={clsx(inputClass, 'pr-24 font-mono')} inputMode="decimal" autoComplete="off" value={value}
        disabled={disabled} onChange={(event) => onChange(event.target.value)} {...rest} />
      <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center gap-2 text-xs font-medium text-muted">
        {onMax && <button type="button" onClick={onMax} disabled={disabled}
          className="pointer-events-auto rounded-md border border-line px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-accent-strong hover:border-accent/50">Max</button>}
        {ticker}
      </span>
    </div>
  );
}

export interface Step {
  label: string;
  state: 'done' | 'active' | 'todo' | 'failed';
}

/** The showcase's submission stepper: done steps in accent, the running one with a spinner. */
export function Stepper({ steps, className }: { steps: Step[]; className?: string }) {
  return (
    <ol className={clsx('flex items-center gap-1', className)}>
      {steps.map((step, i) => (
        <li key={step.label} className="flex flex-1 items-center gap-1" aria-current={step.state === 'active' ? 'step' : undefined}>
          <span className={clsx('flex items-center gap-1 whitespace-nowrap text-[11px] font-medium',
            { done: 'text-accent-strong', active: 'text-ink', todo: 'text-faint', failed: 'text-rose' }[step.state])}>
            {step.state === 'done' ? <CheckCircle2 size={13} /> : step.state === 'active' ? <Spinner className="h-3 w-3" />
              : step.state === 'failed' ? <XCircle size={13} /> : <CircleDashed size={13} />}
            {step.label}
          </span>
          {i < steps.length - 1 && <span className={clsx('h-px min-w-2 flex-1', step.state === 'done' ? 'bg-accent/50' : 'bg-line')} />}
        </li>
      ))}
    </ol>
  );
}

/**
 * Verifies the native state proof of a shown fact: the proof must be for exactly the view's key, value, height and
 * root, and the node's verifier must accept it against the view's root. The verdict names the height it holds at.
 */
export function ProofCheck({ fact, label = 'Verify proof' }: { fact: Fact; label?: string }) {
  const { api } = usePayments();
  const verify = useMutation<Verdict, Error, Fact>({ mutationFn: (target) => verifyFact(api!, target) });
  const verdict = verify.data;
  return (
    <div className="flex flex-wrap items-center gap-2" aria-live="polite">
      <Button variant="ghost" className="px-2.5 py-1 text-xs" onClick={() => verify.mutate(fact)} busy={verify.isPending}
        data-testid="proof-verify" disabled={!api}>
        {!verify.isPending && <ShieldCheck size={13} />} {verdict ? 'Verify again' : label}
      </Button>
      {verdict && (
        <Badge tone={verdict.ok ? 'accent' : 'rose'} data-testid="proof-verdict" title={verdict.text}
          className="max-w-full whitespace-normal">
          {verdict.ok ? <ShieldCheck size={12} /> : <ShieldX size={12} />} {verdict.text}
        </Badge>
      )}
    </div>
  );
}

export function SectionTitle({ icon, children, aside }: { icon?: ReactNode; children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <h2 className="flex items-center gap-2 text-lg font-semibold">{icon}{children}</h2>
      {aside}
    </div>
  );
}
