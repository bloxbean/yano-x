import clsx from 'clsx';
import { AlertTriangle, Link2, QrCode, Store } from 'lucide-react';
import { useMemo, useState } from 'react';
import { transferBase } from '../../flows';
import { formatUnits, toBaseUnits } from '../../lib/amounts';
import { assetIdText, vaultLabel } from '../../lib/display';
import { errorMessage } from '../../lib/errors';
import { MAX_MEMO, parsePayRequest, payRequestLink, requestAsset, type PayRequest } from '../../lib/payrequest';
import { useAccountView, usePayments } from '../../state';
import { Badge, Button, Copy, ErrorBox, Facts, Field, inputClass, Modal } from '../../ui';
import { AmountInput, AssetSelect, useAction } from '../common';

type Mode = 'pay' | 'request';

/**
 * Payment requests: paste (or open) a link to pay it, or make one for your own account. Paying is an ordinary
 * owner-signed transfer, reviewed like any other; the request's memo is shown as unverified.
 */
export function PayRequestFlow({ onClose, initialLink }: { onClose: () => void; initialLink?: string | null }) {
  const [mode, setMode] = useState<Mode>('pay');
  return (
    <Modal title="Payment request" icon={<Store size={18} />} onClose={onClose} data-testid="payreq-sheet" wide
      subtitle="A link that asks for an exact amount of one asset to one account on this ledger.">
      <div className="space-y-4">
        <div role="tablist" aria-label="Payment request" className="flex gap-1 rounded-2xl border border-line bg-surface p-1">
          {([['pay', 'Pay a request', Link2], ['request', 'Request a payment', QrCode]] as const).map(([id, label, Icon]) => (
            <button key={id} type="button" role="tab" aria-selected={mode === id} onClick={() => setMode(id)}
              data-testid={`payreq-tab-${id}`}
              className={clsx('flex flex-1 items-center justify-center gap-2 whitespace-nowrap rounded-xl px-3 py-2 text-sm font-medium transition',
                mode === id ? 'bg-raised text-ink shadow-inner' : 'text-muted hover:text-ink')}>
              <Icon size={15} className={mode === id ? 'text-accent' : ''} /> {label}
            </button>
          ))}
        </div>
        {mode === 'pay' ? <PayRequestPay onClose={onClose} initialLink={initialLink ?? ''} /> : <PayRequestCreate />}
      </div>
    </Modal>
  );
}

function PayRequestPay({ onClose, initialLink }: { onClose: () => void; initialLink: string }) {
  const { info, account, flow, track, toast } = usePayments();
  const view = useAccountView().data;
  const [text, setText] = useState(initialLink);
  const action = useAction();
  const parsed = useMemo((): { request?: PayRequest; error?: string } => {
    if (!text.trim()) return {};
    try {
      return { request: parsePayRequest(text) };
    } catch (failure) {
      return { error: errorMessage(failure) ?? 'not a payment request' };
    }
  }, [text]);
  const request = parsed.request;
  const vault = request && info?.vaults.find((entry) => requestAsset(entry.asset) === request.asset);
  const problem = !request || !info ? parsed.error
    : request.chainId !== info.chainId ? `This request is for chain ${request.chainId}; this ledger is ${info.chainId}.`
      : !vault ? 'This ledger has no vault for the requested asset.'
        : request.toAccount === account?.accountId ? 'This request is for your own account.' : undefined;
  const balance = vault && view?.balances.find((entry) => entry.vaultId === vault.vaultId);

  const pay = () => action.run(async () => {
    if (!request || !vault || problem) return;
    const extra: [string, string][] = request.memo ? [['Memo (from the request, unverified)', request.memo]] : [];
    const submitted = await transferBase(flow(), vault.vaultId, request.toAccount, request.amount, extra);
    track(submitted);
    toast({ tone: 'default', title: 'Payment submitted', detail: 'Waiting for its receipt.' });
    onClose();
  });

  return (
    <div className="space-y-4">
      <Field label="Request link or QR payload">
        <textarea className={`${inputClass} min-h-[76px] resize-y font-mono text-xs`} value={text} spellCheck={false}
          onChange={(event) => setText(event.target.value)} placeholder="https://…#/pay?v=1&chain=…"
          data-testid="payreq-input" />
      </Field>
      {request && vault && (
        <div className="rise space-y-3 rounded-2xl border border-line bg-surface-2/60 p-4" data-testid="payreq-preview">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <div className="font-display text-3xl font-bold">
              {formatUnits(request.amount, vault.decimals)} <span className="text-lg text-muted">{vaultLabel(vault)}</span>
            </div>
            {balance && <span className="text-xs text-faint">You have {formatUnits(balance.available, balance.decimals)}</span>}
          </div>
          <Facts rows={[['To account', request.toAccount], ['Amount (base units)', request.amount.toString()],
            ['Asset id', assetIdText(vault)], ['Chain', request.chainId]]} />
          {request.memo && (
            <div className="flex items-start gap-2 text-sm">
              <Badge tone="amber"><AlertTriangle size={11} /> unverified</Badge>
              <span className="min-w-0 break-words text-muted">“{request.memo}”</span>
            </div>
          )}
        </div>
      )}
      {problem && <ErrorBox data-testid="payreq-problem">{problem}</ErrorBox>}
      {action.error && <ErrorBox data-testid="action-error">{action.error}</ErrorBox>}
      <div className="flex justify-end">
        <Button onClick={() => void pay()} busy={action.busy} disabled={!request || !vault || !!problem}
          data-testid="payreq-pay">Review &amp; pay</Button>
      </div>
    </div>
  );
}

function PayRequestCreate() {
  const { info, account } = usePayments();
  const vaults = (info?.vaults ?? []).filter((vault) => vault.transfersEnabled);
  const [vaultId, setVaultId] = useState(vaults[0]?.vaultId ?? '');
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const vault = vaults.find((entry) => entry.vaultId === vaultId);
  const built = useMemo((): { link?: string; error?: string } => {
    if (!vault || !info || !account || !amount.trim()) return {};
    try {
      const base = toBaseUnits(amount, vault.decimals);
      if (base <= 0n) return { error: 'the amount must be more than zero' };
      return { link: payRequestLink(window.location.href, { chainId: info.chainId, toAccount: account.accountId,
        asset: requestAsset(vault.asset), amount: base, memo }) };
    } catch (failure) {
      return { error: errorMessage(failure) ?? undefined };
    }
  }, [vault, info, account, amount, memo]);
  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Asset">
          <AssetSelect vaults={vaults} value={vaultId} onChange={setVaultId} data-testid="payreq-vault" />
        </Field>
        <Field label="Amount">
          <AmountInput value={amount} onChange={setAmount} ticker={vault ? vaultLabel(vault) : ''}
            data-testid="payreq-amount" />
        </Field>
      </div>
      <Field label="Memo" optional hint="Shown to the payer as unverified text; it is not signed.">
        <input className={inputClass} value={memo} maxLength={MAX_MEMO} onChange={(event) => setMemo(event.target.value)}
          placeholder="Order #1042" data-testid="payreq-memo" />
      </Field>
      {built.error && <ErrorBox>{built.error}</ErrorBox>}
      {built.link && (
        <div className="rise space-y-2 rounded-2xl border border-accent/25 bg-mint/15 p-3">
          <div className="flex items-center justify-between gap-2">
            <span className="text-sm font-semibold text-accent-strong">Share this link (or encode it as a QR code)</span>
            <Copy value={built.link} label="Copy link" />
          </div>
          <code className="block break-all font-mono text-[11px] text-muted" data-testid="payreq-link">{built.link}</code>
        </div>
      )}
    </div>
  );
}
