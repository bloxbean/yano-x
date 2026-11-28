import { KeyRound, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { authorizeSession } from '../../flows';
import { vaultLabel } from '../../lib/display';
import { MAX_SESSION_VALIDITY_MS } from '../../lib/session';
import { usePayments } from '../../state';
import { Button, ErrorBox, Field, inputClass, Modal } from '../../ui';
import { AmountInput, AssetSelect, useAction } from '../common';

export function SessionFlow({ onClose }: { onClose: () => void }) {
  const { info, flow, track, toast, sessionKeysSupported } = usePayments();
  const vaults = (info?.vaults ?? []).filter((vault) => vault.transfersEnabled);
  const [vaultId, setVaultId] = useState(vaults[0]?.vaultId ?? '');
  const [perPayment, setPerPayment] = useState('1');
  const [total, setTotal] = useState('5');
  const [minutes, setMinutes] = useState('60');
  const [recipient, setRecipient] = useState('');
  const action = useAction();
  const vault = vaults.find((entry) => entry.vaultId === vaultId);
  const ticker = vault ? vaultLabel(vault) : '';

  const go = () => action.run(async () => {
    const authorized = await authorizeSession(flow(), { vaultId, perPayment, total, minutes, recipient });
    track(authorized);
    toast(authorized.stored
      ? { tone: 'default', title: 'Session submitted', detail: 'Pay without wallet prompts once it is accepted.' }
      : { tone: 'amber', title: 'Session key not stored',
        detail: 'IndexedDB is unavailable, so the key works only until this tab closes. Revoke the grant afterwards.' });
    onClose();
  });

  return (
    <Modal title="Authorize a session" icon={<KeyRound size={18} />} onClose={onClose} data-testid="session-sheet"
      subtitle="One wallet prompt now; then this browser pays within the limits, without prompts."
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={() => void go()} busy={action.busy} disabled={!vault || sessionKeysSupported === false}
          data-testid="session-go">Review &amp; authorize</Button>
      </>}>
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); void go(); }}>
        {sessionKeysSupported === false && (
          <ErrorBox data-testid="session-support">This browser lacks WebCrypto Ed25519, so session keys are unavailable
            here. Owner-signed actions still work.</ErrorBox>
        )}
        <Field label="Asset">
          <AssetSelect vaults={vaults} value={vaultId} onChange={setVaultId} disabled={action.busy}
            data-testid="session-vault" />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Per payment">
            <AmountInput value={perPayment} onChange={setPerPayment} ticker={ticker} disabled={action.busy}
              data-testid="session-per" />
          </Field>
          <Field label="Total">
            <AmountInput value={total} onChange={setTotal} ticker={ticker} disabled={action.busy}
              data-testid="session-total" />
          </Field>
        </div>
        <Field label="Valid for (minutes)" hint={`At most ${MAX_SESSION_VALIDITY_MS / 3_600_000} hours.`}>
          <input className={`${inputClass} font-mono`} inputMode="numeric" value={minutes} disabled={action.busy}
            onChange={(event) => setMinutes(event.target.value)} data-testid="session-minutes" />
        </Field>
        <Field label="Only to" optional hint="Leave empty to allow any registered account.">
          <input className={`${inputClass} font-mono`} value={recipient} disabled={action.busy} spellCheck={false}
            onChange={(event) => setRecipient(event.target.value)} placeholder="account id or address"
            data-testid="session-recipient" />
        </Field>
        <div className="flex gap-2 rounded-xl border border-accent/25 bg-mint/15 px-3 py-2 text-xs text-muted">
          <ShieldCheck size={15} className="mt-0.5 shrink-0 text-accent" />
          <span>The session key is a WebCrypto Ed25519 key with a non-extractable private key, kept in this browser
            profile. It may only send transfers within these limits until it expires or you revoke it. It can never
            withdraw.</span>
        </div>
        {action.error && <ErrorBox data-testid="action-error">{action.error}</ErrorBox>}
      </form>
    </Modal>
  );
}
