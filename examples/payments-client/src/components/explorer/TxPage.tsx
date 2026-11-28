import { useQuery } from '@tanstack/react-query';
import clsx from 'clsx';
import { FileCode2, ReceiptText, Rows3 } from 'lucide-react';
import type { ReactNode } from 'react';
import { ApiError, type Info, type ReceiptView, type VaultView } from '../../lib/api';
import { formatUnits } from '../../lib/amounts';
import { addressToBech32 } from '../../lib/bech32';
import { short, vaultLabel } from '../../lib/display';
import {
  decodeDepositValue, decodeReceiptValue, decodeMessage, KIND_LABELS, type DecodedTx, type Outcome
} from '../../lib/explorer';
import { fromHex } from '../../lib/hex';
import { money } from '../../lib/txrow';
import { accountHref, blockHref } from '../../route';
import { usePayments } from '../../state';
import { Badge, Copy, EmptyState, ErrorBox, Facts, Mono, Skeleton } from '../../ui';
import { ProofCheck, Stepper } from '../common';
import { lifecycleSteps } from '../Withdrawals';
import { Panel } from './Explorer';
import { useDecodeContext, useOutcome } from './hooks';
import { KIND_ICONS, L1Link, OutcomeBadge } from './TxRow';

function Hex({ value, label }: { value: string; label: string }) {
  return (
    <span className="flex items-start gap-1">
      <span className="min-w-0 break-all font-mono text-[12px] text-muted">{value}</span>
      <Copy value={value} label={`Copy ${label}`} />
    </span>
  );
}

function AccountLink({ id }: { id: string }) {
  return (
    <span className="flex items-start gap-1">
      <a href={accountHref(id)} className="min-w-0 break-all font-mono text-[12px] text-accent-strong hover:underline"
        data-testid="detail-account">{id}</a>
      <Copy value={id} label="Copy account id" />
    </span>
  );
}

function vaultText(vaults: VaultView[], vaultId: string): ReactNode {
  const vault = vaults.find((entry) => entry.vaultId === vaultId);
  return <span><b className="font-semibold">{vault ? vaultLabel(vault) : 'unregistered vault'}</b>{' '}
    <Mono className="break-all text-[11px]">{vaultId}</Mono></span>;
}

function amountText(amount: bigint, vault: VaultView | undefined, fallback: string): string {
  const view = money(amount, vault, fallback);
  return vault ? `${view.amount} ${view.ticker} (${amount} base units)` : `${view.amount} ${view.ticker}`;
}

function addressText(hex: string): ReactNode {
  let bech32: string | null = null;
  try {
    bech32 = addressToBech32(fromHex(hex));
  } catch {
    bech32 = null;
  }
  return <span className="block min-w-0">{bech32 && <span className="block break-all font-mono text-[12px]">{bech32}</span>}
    <Mono className="block break-all text-[11px]">{hex}</Mono></span>;
}

/** Every decoded field of a message, as label/value rows. */
export function txFacts(tx: DecodedTx, info: Pick<Info, 'vaults' | 'intentDomain'>, template = ''): [string, ReactNode][] {
  const { vaults } = info;
  const rows: [string, ReactNode][] = [['Kind', KIND_LABELS[tx.kind]]];
  const push = (label: string, value: ReactNode | undefined | null | false) => {
    if (value !== undefined && value !== null && value !== false) rows.push([label, value]);
  };
  push('Intent id', tx.intentId && <Hex value={tx.intentId} label="intent id" />);
  push('Action', tx.action);
  push('Signed by', tx.authorizer && (tx.authorizer === 'SESSION'
    ? <span>session key of grant <Mono className="break-all text-[11px]">{tx.grantId}</Mono></span> : 'the account owner (CIP-30 signData)'));
  push('Account', tx.from && <AccountLink id={tx.from} />);
  push(tx.kind === 'deposit' ? 'Beneficiary' : 'Recipient', tx.to && <AccountLink id={tx.to} />);
  if (tx.amount !== undefined) {
    const vault = tx.vaultId ? vaults.find((entry) => entry.vaultId === tx.vaultId)
      : vaults.find((entry) => entry.asset.policyId === tx.asset?.policyId && entry.asset.assetName === tx.asset?.assetName);
    push('Amount', amountText(tx.amount, vault, tx.asset ? 'base units of an unregistered asset' : 'base units'));
  }
  push('Asset id', tx.asset && (tx.asset.policyId ? <Mono className="break-all text-[11px]">{tx.asset.policyId}.{tx.asset.assetName}</Mono>
    : 'lovelace (ADA)'));
  push('Vault', tx.vaultId && vaultText(vaults, tx.vaultId));
  push('Pays to (L1)', tx.toAddress && addressText(tx.toAddress));
  push(tx.kind === 'deposit' ? 'Output lovelace' : 'Vault lovelace', tx.lovelace !== undefined && `${formatUnits(tx.lovelace, 6)} ADA`);
  if (tx.session) {
    push('Session key', <Hex value={tx.session.sessionKey} label="session key" />);
    push('Session actions', tx.session.actions.join(', ') || 'none');
    tx.session.limits.forEach((limit, i) => {
      const vault = vaults.find((entry) => entry.vaultId === limit.vaultId);
      push(`Limit ${i + 1}`, `${amountText(limit.perPayment, vault, 'base units')} per payment, `
        + `${amountText(limit.cumulative, vault, 'base units')} in total`);
    });
    push('Session recipients', tx.session.recipients.length
      ? <span className="space-y-0.5">{tx.session.recipients.map((id) => <AccountLink key={id} id={id} />)}</span> : 'any account');
    push('Session expires', expiry(tx.session.notAfterMs));
  }
  push('Revokes grant', tx.kind === 'revoke' && tx.grantId && <Hex value={tx.grantId} label="grant id" />);
  push('New owner', tx.newOwner && `${tx.newOwner.credentialKind === 0 ? 'payment' : 'stake'} key ${tx.newOwner.keyHash}`);
  push('Nonce', tx.nonce && <Mono className="text-[11px]">{tx.nonce}</Mono>);
  push('Intent expires', tx.notAfterMs !== undefined && expiry(tx.notAfterMs));
  if (tx.domain) {
    const domain = tx.domain;
    const own = info.intentDomain;
    const same = domain.chainId === own.chainId && domain.genesisId === own.genesisId
      && domain.networkMagic === own.networkMagic && domain.addressNetworkId === own.addressNetworkId
      && domain.applicationId === own.applicationId;
    push('Intent domain', <span>{domain.chainId} · {domain.applicationId} · magic {domain.networkMagic} · address network{' '}
      {domain.addressNetworkId}{' '}
      <Badge tone={same ? 'accent' : 'rose'}>{same ? 'this chain' : 'another domain'}</Badge></span>);
  }
  push('Observer', tx.observerId);
  push('L1 transaction', tx.l1TxId && <span className="flex flex-col gap-0.5"><Hex value={tx.l1TxId} label="L1 transaction id" />
    <L1Link txId={tx.l1TxId} template={template} label="Open in the L1 explorer" /></span>);
  push('Output index', tx.outputIndex !== undefined && String(tx.outputIndex));
  push('L1 slot', tx.slot !== undefined && String(tx.slot));
  push('L1 block hash', tx.l1BlockHash && <Mono className="break-all text-[11px]">{tx.l1BlockHash}</Mono>);
  push('L1 epoch', tx.epoch !== undefined && String(tx.epoch));
  push('Deposit id', tx.depositId && <Hex value={tx.depositId} label="deposit id" />);
  push('Deposit datum', tx.deposit && (tx.deposit.beneficiary === 'ACCOUNT' ? 'names an account'
    : tx.deposit.beneficiary === 'MALFORMED' ? 'malformed' : 'none (not a deposit-shaped output)'));
  push('Other assets in the output', tx.deposit && String(tx.deposit.otherAssetCount));
  push('Paid claims', tx.payout && <span className="space-y-0.5">{tx.payout.claimIds.map((id) =>
    <Mono key={id} className="block break-all text-[11px]">{id}</Mono>)}</span>);
  if (tx.governance) {
    push('Command', tx.governance.command === 'ASSET_STATUS' ? `asset status → ${tx.governance.status}`
      : tx.governance.command === 'PAUSE' ? 'pause the payments domain' : 'resume the payments domain');
    push('Command sequence', String(tx.governance.sequence));
  }
  push('Decode error', tx.error && <span className="text-rose">{tx.error}</span>);
  return rows;
}

function expiry(ms: bigint): string {
  return ms <= BigInt(8.64e15) ? new Date(Number(ms)).toISOString() : `${ms} ms`;
}

function chunk(hex: string): string {
  return hex.replace(/(.{64})/g, '$1 ').trim();
}

function Raw({ label, hex, testId }: { label: string; hex: string; testId: string }) {
  return (
    <details className="group rounded-xl border border-line/70 bg-surface-2" data-testid={testId}>
      <summary className="flex cursor-pointer list-none items-center justify-between gap-2 px-3 py-2 text-sm font-medium">
        <span className="flex items-center gap-2"><FileCode2 size={14} className="text-faint" /> {label}
          <span className="text-[11px] font-normal text-faint">{hex.length / 2} bytes</span></span>
        <span className="text-[11px] text-faint group-open:hidden">show</span>
        <span className="hidden text-[11px] text-faint group-open:inline">hide</span>
      </summary>
      <div className="flex items-start gap-1 border-t border-line/70 px-3 py-2">
        <pre className="max-h-72 min-w-0 flex-1 overflow-auto whitespace-pre-wrap break-all font-mono text-[11px] leading-relaxed text-muted">
          {chunk(hex)}</pre>
        <Copy value={hex} label={`Copy ${label.toLowerCase()}`} />
      </div>
    </details>
  );
}

/** The record behind an outcome, decoded from the value its proof covers, and the Verify control. */
function RecordPanel({ tx, outcome, raw, error }: { tx: DecodedTx; outcome?: Outcome; raw?: unknown; error?: boolean }) {
  const { info, config } = usePayments();
  if (!tx.intentId && tx.kind !== 'deposit') return null;
  const receipt = tx.intentId ? raw as ReceiptView | undefined : undefined;
  let rows: [string, ReactNode][] = [];
  const valueHex = outcome?.fact?.valueHex;
  if (valueHex) {
    try {
      if (tx.intentId) {
        const value = decodeReceiptValue(valueHex);
        rows = [['Outcome', value.outcome], ['Reason', value.reason || '—'], ['Action', value.action],
          ['Account', <AccountLink id={value.accountId} />], ['Amount', `${value.amount} base units`],
          ['Executed at height', String(value.height)]];
      } else {
        const value = decodeDepositValue(valueHex);
        const vault = info?.vaults.find((entry) => entry.vaultId === tx.vaultId);
        rows = [['Status', value.status], ['Reason', value.reason], ['Credited',
          amountText(value.creditedAmount, vault, 'base units')], ['Credited lovelace', `${formatUnits(value.creditedLovelace, 6)} ADA`],
          ['Recorded at height', String(value.height)]];
      }
    } catch {
      rows = [];
    }
  }
  const claims = Array.isArray(receipt?.withdrawals) ? receipt.withdrawals : [];
  return (
    <Panel title={tx.intentId ? 'Receipt' : 'Deposit record'} icon={<ReceiptText size={17} className="text-accent" />}
      data-testid="record-panel" aside={<span className="text-[11px] text-faint">state record, as reported by the node</span>}>
      <div className="space-y-3 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <OutcomeBadge tx={tx} outcome={outcome} error={error} />
          {outcome && <span className={clsx('text-sm', outcome.state === 'rejected' || outcome.state === 'inconsistent'
            ? 'text-rose' : 'text-muted')} data-testid="record-text">{outcome.text}</span>}
        </div>
        {rows.length > 0 && <Facts rows={rows} data-testid="record-facts" />}
        {outcome?.fact && (
          <div className="space-y-1">
            <ProofCheck fact={outcome.fact} label={tx.intentId ? 'Verify receipt' : 'Verify deposit record'} />
            <p className="text-[11px] text-faint">The values above are decoded from the record value this proof covers, at
              height {outcome.fact.height}; the record's key is derived here from the message, so the proof cannot be
              for another one.</p>
          </div>
        )}
        {claims.length > 0 && (
          <div className="space-y-2" data-testid="record-withdrawals">
            <div className="text-sm font-medium">Withdrawal claim{claims.length === 1 ? '' : 's'}</div>
            {claims.slice(0, 16).map((claim) => (
              <div key={String(claim.claimId)} className="space-y-2 rounded-xl border border-line/70 bg-surface-2 px-3 py-2.5">
                <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-faint">
                  <span>claim <Mono className="text-[11px]" title={String(claim.claimId)}>{short(String(claim.claimId), 6)}</Mono></span>
                  <Badge tone={claim.lifecycle === 'PAID' ? 'accent' : claim.lifecycle === 'PAID_MISMATCH' ? 'rose' : 'amber'}>
                    {String(claim.lifecycle)}</Badge>
                </div>
                <Stepper steps={lifecycleSteps(String(claim.lifecycle))} />
                {typeof claim.l1TxId === 'string' ? <L1Link txId={claim.l1TxId} template={config?.explorerTxUrl ?? ''} label="L1 payout" />
                  : <span className="text-[11px] text-faint">Awaiting settlement on L1</span>}
              </div>
            ))}
            {!config?.explorerTxUrl && claims.some((claim) => typeof claim.l1TxId === 'string') && (
              <p className="text-[11px] text-faint">Set explorerTxUrl in payments-client.json to link L1 transactions.</p>
            )}
          </div>
        )}
      </div>
    </Panel>
  );
}

/** One message: decoded fields, its position, its receipt or deposit record with a proof check, and the raw body. */
export function TxPage({ messageId }: { messageId: string }) {
  const { api, info } = usePayments();
  const ctx = useDecodeContext();
  const message = useQuery({
    queryKey: ['x-message', api?.chainId, messageId],
    queryFn: () => api!.message(messageId),
    enabled: !!api,
    staleTime: Infinity,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });
  if (message.isLoading) return <Skeleton className="h-64 w-full" />;
  if (message.isError) {
    return message.error instanceof ApiError && message.error.status === 404
      ? <EmptyState icon={<Rows3 size={18} />} title="No finalized message with this id">
        <span className="break-all font-mono">{messageId}</span></EmptyState>
      : <ErrorBox>Cannot read the message: {(message.error as Error).message}</ErrorBox>;
  }
  if (!message.data || !info) return null;
  if (message.data.messageId !== messageId) return <ErrorBox>The node returned another message than the one asked for.</ErrorBox>;
  return <TxDetail tx={decodeMessage(message.data, ctx)} info={info} />;
}

function TxDetail({ tx, info }: { tx: DecodedTx; info: Info }) {
  const { config } = usePayments();
  const { outcome, raw, error } = useOutcome(tx);
  const Icon = KIND_ICONS[tx.kind];
  return (
    <div className="space-y-6" data-testid="tx-page">
      <div className="flex flex-wrap items-center gap-3">
        <span className="grid h-11 w-11 place-items-center rounded-2xl bg-accent/10 text-accent"><Icon size={20} /></span>
        <div className="min-w-0">
          <div className="text-xl font-semibold" data-testid="tx-title">{KIND_LABELS[tx.kind]}</div>
          <div className="text-xs text-faint">in block <a href={blockHref(tx.height)} data-testid="tx-block"
            className="font-mono text-accent-strong hover:underline">#{tx.height}</a>, position {tx.index}</div>
        </div>
        <div className="ml-auto"><OutcomeBadge tx={tx} outcome={outcome} error={error} /></div>
      </div>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <div className="space-y-6">
          <Panel title="Decoded" icon={<Rows3 size={17} className="text-accent" />}
            aside={<span className="text-[11px] text-faint">decoded here from the body</span>}>
            <div className="p-4"><Facts rows={txFacts(tx, info, config?.explorerTxUrl)} data-testid="tx-facts" /></div>
          </Panel>
          <Panel title="Message" icon={<FileCode2 size={17} className="text-accent" />}
            aside={<span className="text-[11px] text-faint">as reported by the node</span>}>
            <div className="space-y-3 p-4">
              <Facts rows={[
                ['Message id', <Hex value={tx.messageId} label="message id" />],
                ['Topic', tx.topic],
                ['Block', <a href={blockHref(tx.height)} className="font-mono text-accent-strong hover:underline">#{tx.height} · index {tx.index}</a>],
                ['Sender (member key)', <Hex value={tx.sender} label="sender key" />],
                ['Sender sequence', tx.senderSeq || '—'],
                ['Body size', `${tx.size} bytes`],
              ]} />
              {tx.bodyHex && <Raw label="Raw body" hex={tx.bodyHex} testId="raw-body" />}
              {tx.envelopeHex && <Raw label="Signed intent envelope" hex={tx.envelopeHex} testId="raw-envelope" />}
            </div>
          </Panel>
        </div>
        <div className="space-y-6">
          <RecordPanel tx={tx} outcome={outcome} raw={raw} error={error} />
          {!tx.intentId && tx.kind !== 'deposit' && (
            <EmptyState icon={<ReceiptText size={18} />} title="No ledger record to check">
              This message leaves no receipt or deposit record, so there is no proof to verify for it.
            </EmptyState>
          )}
        </div>
      </div>
    </div>
  );
}
