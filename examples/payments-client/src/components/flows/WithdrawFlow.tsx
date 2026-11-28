import { ArrowUpFromLine } from 'lucide-react';
import { useState } from 'react';
import { withdraw } from '../../flows';
import { formatUnits } from '../../lib/amounts';
import { addressToBech32 } from '../../lib/bech32';
import { vaultLabel } from '../../lib/display';
import { describeCode } from '../../lib/errors';
import { useAccountView, usePayments } from '../../state';
import { Button, ErrorBox, Field, inputClass, Modal } from '../../ui';
import { AmountInput, AssetSelect, Stepper, useAction } from '../common';

export function WithdrawFlow({ onClose, initialVault }: { onClose: () => void; initialVault?: string }) {
  const { info, wallet, flow, track, toast } = usePayments();
  const view = useAccountView().data;
  const vaults = info?.vaults ?? [];
  const [vaultId, setVaultId] = useState(initialVault ?? vaults[0]?.vaultId ?? '');
  const [amount, setAmount] = useState('');
  const [all, setAll] = useState(false);
  const [lovelace, setLovelace] = useState('0');
  const [to, setTo] = useState(() => (wallet ? addressToBech32(wallet.changeAddress) : ''));
  const action = useAction();
  const vault = vaults.find((entry) => entry.vaultId === vaultId);
  const balance = view?.balances.find((entry) => entry.vaultId === vaultId);

  const go = () => action.run(async () => {
    const submitted = await withdraw(flow(), { vaultId, amount, all, lovelace, to }, balance);
    track(submitted);
    toast({ tone: 'default', title: 'Withdrawal submitted', detail: 'Reserved on the ledger once accepted.' });
    onClose();
  });

  return (
    <Modal title="Withdraw to Cardano" icon={<ArrowUpFromLine size={18} />} onClose={onClose} data-testid="withdraw-sheet"
      subtitle="Reserves the amount on the ledger; a settlement batch pays it to your L1 address."
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={() => void go()} busy={action.busy}
          disabled={!vault || !to.trim() || (!all && !amount.trim()) || !info?.withdrawalsEnabled}
          data-testid="withdraw-go">Review &amp; sign</Button>
      </>}>
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void go(); }}>
        {info && !info.withdrawalsEnabled && <ErrorBox>{describeCode('WITHDRAWALS_DISABLED')}</ErrorBox>}
        <Field label="Asset">
          <AssetSelect vaults={vaults} value={vaultId} onChange={setVaultId} balances={view?.balances}
            disabled={action.busy} data-testid="withdraw-vault" />
        </Field>
        <Field label="Amount" hint={<>
          {balance && `${formatUnits(balance.available, balance.decimals)} available. `}
          {vault?.asset.ada && info && `At least ${formatUnits(info.minAdaPayout, 6)} ADA.`}
        </>}>
          <AmountInput value={all && balance ? formatUnits(balance.available, balance.decimals) : amount}
            onChange={setAmount} ticker={vault ? vaultLabel(vault) : ''} disabled={action.busy || all}
            data-testid="withdraw-amount" />
        </Field>
        <label className="flex items-center gap-2 text-sm text-muted">
          <input type="checkbox" checked={all} onChange={(event) => setAll(event.target.checked)} disabled={action.busy}
            data-testid="withdraw-all" className="h-4 w-4 accent-[var(--color-accent)]" />
          Withdraw everything available
        </label>
        {vault && !vault.asset.ada && (
          <Field label="Min-ADA credit to spend (lovelace)"
            hint={balance && `${formatUnits(balance.minAdaCredit, 6)} ADA credit available for this asset.`}>
            <input className={`${inputClass} font-mono`} inputMode="numeric" value={lovelace} disabled={action.busy}
              onChange={(event) => setLovelace(event.target.value)} data-testid="withdraw-lovelace" />
          </Field>
        )}
        <Field label="Destination address" hint="Defaults to your wallet's change address.">
          <input className={`${inputClass} font-mono`} value={to} onChange={(event) => setTo(event.target.value)}
            disabled={action.busy} spellCheck={false} data-testid="withdraw-to" />
        </Field>
        <div className="space-y-2 rounded-xl border border-line/70 bg-surface-2 px-3 py-2.5">
          <div className="text-xs font-semibold text-muted">What happens next</div>
          <Stepper steps={[{ label: 'Reserved', state: 'todo' }, { label: 'Settling', state: 'todo' },
            { label: 'Paid on L1', state: 'todo' }]} />
          <p className="text-[11px] text-faint">Reserved when the ledger accepts it. Settling while a settlement batch
            is open. Paid when the payout observer sees the L1 payout, with its transaction id.</p>
        </div>
        {action.error && <ErrorBox data-testid="action-error">{action.error}</ErrorBox>}
      </form>
    </Modal>
  );
}
