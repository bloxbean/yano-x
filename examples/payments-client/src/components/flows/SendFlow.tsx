import { Send } from 'lucide-react';
import { useState } from 'react';
import { transfer } from '../../flows';
import { formatUnits } from '../../lib/amounts';
import { vaultLabel } from '../../lib/display';
import { useAccountView, usePayments } from '../../state';
import { Button, ErrorBox, Field, inputClass, Modal } from '../../ui';
import { AmountInput, AssetSelect, useAction } from '../common';
import { useNetworkId } from '../ids';
import { RecipientCheck, useRecipient } from '../Recipient';

export function SendFlow({ onClose, initialVault }: { onClose: () => void; initialVault?: string }) {
  const { info, flow, track, toast } = usePayments();
  const view = useAccountView().data;
  const vaults = (info?.vaults ?? []).filter((vault) => vault.transfersEnabled || vault.vaultId === initialVault);
  const [vaultId, setVaultId] = useState(initialVault ?? vaults[0]?.vaultId ?? '');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const action = useAction();
  const recipient = useRecipient(to);
  const prefix = useNetworkId() === 1 ? '' : '_test';
  const vault = info?.vaults.find((entry) => entry.vaultId === vaultId);
  const balance = view?.balances.find((entry) => entry.vaultId === vaultId);

  const go = () => action.run(async () => {
    const submitted = await transfer(flow(), { vaultId, to, amount });
    track(submitted);
    toast({ tone: 'default', title: 'Transfer submitted', detail: 'Waiting for its receipt.' });
    onClose();
  });

  return (
    <Modal title="Send" icon={<Send size={18} />} onClose={onClose} data-testid="send-sheet"
      subtitle="An owner-signed transfer to another account on this ledger. Final in about a block."
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={() => void go()} busy={action.busy} disabled={!vault || !to.trim() || !amount.trim() || !!recipient.problem || recipient.loading}
          data-testid="transfer-go">Review &amp; sign</Button>
      </>}>
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void go(); }}>
        <Field label="Asset">
          <AssetSelect vaults={vaults} value={vaultId} onChange={setVaultId} balances={view?.balances}
            disabled={action.busy} data-testid="transfer-vault" />
        </Field>
        <Field label="To" hint={`An account id (acct${prefix}1…, or 56 hex), or the recipient's Cardano address `
          + `(addr${prefix}1… / stake${prefix}1…): it pays the account that address's key owns.`}>
          <input className={`${inputClass} font-mono`} value={to} onChange={(event) => setTo(event.target.value)}
            disabled={action.busy} placeholder={`acct${prefix}1… or addr${prefix}1…`} autoComplete="off" spellCheck={false}
            data-testid="transfer-to" />
        </Field>
        <RecipientCheck state={recipient} />
        <Field label="Amount" hint={balance && `${formatUnits(balance.available, balance.decimals)} available`}>
          <AmountInput value={amount} onChange={setAmount} ticker={vault ? vaultLabel(vault) : ''} disabled={action.busy}
            onMax={balance ? () => setAmount(formatUnits(balance.available, balance.decimals)) : undefined}
            data-testid="transfer-amount" />
        </Field>
        {action.error && <ErrorBox data-testid="action-error">{action.error}</ErrorBox>}
      </form>
    </Modal>
  );
}
