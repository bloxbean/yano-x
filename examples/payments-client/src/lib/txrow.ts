/**
 * What one explorer row says about a decoded message: its kind, who to whom, and the amount in display units. Tickers
 * and decimals are the registry's display metadata from payments/info; an asset the registry does not name is shown
 * by its id in base units. Display only: nothing here is verified.
 */
import type { VaultView } from './api';
import { formatUnits } from './amounts';
import { addressToBech32 } from './bech32';
import { short, vaultLabel } from './display';
import { KIND_LABELS, type DecodedTx, type TxKind } from './explorer';
import { fromHex } from './hex';

export type RowTone = 'accent' | 'lime' | 'amber' | 'cyan' | 'violet' | 'rose' | 'default';

/** One side of a row: an account (linked), an L1 address, or a plain label. */
export interface Party {
  account?: string;
  text: string;
  title?: string;
}

export interface RowView {
  title: string;
  tone: RowTone;
  from?: Party;
  to?: Party;
  amount?: string;
  ticker?: string;
  /** A second amount or a qualifier, e.g. "+ 1.5 ADA" or "per payment, 3 ADA total". */
  extra?: string;
  l1TxId?: string;
  /** For an unknown message: the topic, size and why it did not decode. */
  note?: string;
}

const TONES: Record<TxKind, RowTone> = {
  register: 'accent', transfer: 'accent', 'session-pay': 'cyan', 'session-authorize': 'violet', revoke: 'amber',
  withdraw: 'amber', rotate: 'violet', deposit: 'lime', payout: 'lime', observation: 'default', governance: 'violet',
  unknown: 'default',
};

const account = (id: string | undefined): Party | undefined => id ? { account: id, text: short(id, 6), title: id } : undefined;

function vaultById(vaults: VaultView[], vaultId: string | undefined): VaultView | undefined {
  return vaultId ? vaults.find((vault) => vault.vaultId === vaultId) : undefined;
}

function vaultByAsset(vaults: VaultView[], asset: { policyId: string; assetName: string } | undefined): VaultView | undefined {
  return asset ? vaults.find((vault) => vault.asset.policyId === asset.policyId && vault.asset.assetName === asset.assetName)
    : undefined;
}

/** An amount in the vault's display units, or in base units with the asset id when the registry has no entry. */
export function money(amount: bigint, vault: VaultView | undefined, fallback: string): { amount: string; ticker: string } {
  if (vault) return { amount: formatUnits(amount, vault.decimals), ticker: vaultLabel(vault) };
  return { amount: amount.toString(), ticker: fallback };
}

function addressParty(hex: string | undefined): Party | undefined {
  if (!hex) return undefined;
  try {
    const bech32 = addressToBech32(fromHex(hex));
    return { text: short(bech32, 10), title: bech32 };
  } catch {
    return { text: short(hex, 8), title: hex };
  }
}

export function rowView(tx: DecodedTx, vaults: VaultView[]): RowView {
  const base: RowView = { title: KIND_LABELS[tx.kind], tone: TONES[tx.kind] };
  switch (tx.kind) {
    case 'register':
      return { ...base, from: account(tx.from), to: { text: 'opens the account' } };
    case 'transfer':
    case 'session-pay': {
      const view = tx.amount !== undefined
        ? money(tx.amount, vaultByAsset(vaults, tx.asset),
          tx.asset ? (tx.asset.policyId ? `${short(tx.asset.policyId, 4)}.${tx.asset.assetName}` : 'lovelace') : '?')
        : {};
      return { ...base, from: account(tx.from), to: account(tx.to), ...view,
        ...(tx.kind === 'session-pay' && tx.grantId ? { extra: `session ${short(tx.grantId, 4)}` } : {}),
        ...(tx.error ? { note: tx.error } : {}) };
    }
    case 'session-authorize': {
      const limit = tx.session?.limits[0];
      const vault = vaultById(vaults, limit?.vaultId);
      const per = limit ? money(limit.perPayment, vault, 'base units') : undefined;
      const total = limit ? money(limit.cumulative, vault, 'base units') : undefined;
      const more = tx.session && tx.session.limits.length > 1 ? `, +${tx.session.limits.length - 1} more` : '';
      return { ...base, from: account(tx.from),
        to: tx.session ? { text: `session key ${short(tx.session.sessionKey, 4)}`, title: tx.session.sessionKey } : undefined,
        ...(per && total ? { amount: per.amount, ticker: per.ticker, extra: `per payment, ${total.amount} ${total.ticker} total${more}` } : {}),
        ...(tx.error ? { note: tx.error } : {}) };
    }
    case 'revoke':
      return { ...base, from: account(tx.from),
        to: tx.grantId ? { text: `grant ${short(tx.grantId, 4)}`, title: tx.grantId } : undefined,
        ...(tx.error ? { note: tx.error } : {}) };
    case 'withdraw': {
      const vault = vaultById(vaults, tx.vaultId);
      const view = tx.amount !== undefined ? money(tx.amount, vault, tx.vaultId ? `vault ${short(tx.vaultId, 4)}` : '?') : {};
      const lovelace = tx.lovelace && tx.lovelace > 0n && !vault?.asset.ada ? `+ ${formatUnits(tx.lovelace, 6)} ADA` : undefined;
      return { ...base, from: account(tx.from), to: addressParty(tx.toAddress), ...view,
        ...(lovelace ? { extra: lovelace } : {}), ...(tx.error ? { note: tx.error } : {}) };
    }
    case 'rotate':
      return { ...base, from: account(tx.from),
        to: tx.newOwner ? { text: `new owner ${short(tx.newOwner.keyHash, 4)}`, title: tx.newOwner.keyHash } : undefined,
        ...(tx.error ? { note: tx.error } : {}) };
    case 'deposit': {
      const vault = vaultById(vaults, tx.vaultId);
      const view = tx.amount !== undefined ? money(tx.amount, vault, `vault ${short(tx.vaultId ?? '', 4)}`) : {};
      const lovelace = tx.lovelace && tx.lovelace > 0n && vault && !vault.asset.ada ? `+ ${formatUnits(tx.lovelace, 6)} ADA` : undefined;
      return { ...base, from: { text: `L1 output #${tx.outputIndex ?? 0}` },
        to: account(tx.to) ?? { text: tx.deposit?.beneficiary === 'MALFORMED' ? 'malformed datum' : 'no beneficiary' },
        ...view, ...(lovelace ? { extra: lovelace } : {}), l1TxId: tx.l1TxId };
    }
    case 'payout': {
      const vault = vaultById(vaults, tx.vaultId);
      const claims = tx.payout?.claimIds.length ?? 0;
      return { ...base, from: { text: vault ? `${vaultLabel(vault)} vault` : `vault ${short(tx.vaultId ?? '', 4)}` },
        to: { text: `${claims} claim${claims === 1 ? '' : 's'} paid on L1` }, l1TxId: tx.l1TxId };
    }
    case 'observation':
      return { ...base, from: { text: `observer ${tx.observerId ?? '?'}` },
        to: { text: tx.l1TxId ? 'L1 transaction' : tx.epoch !== undefined ? `epoch ${tx.epoch}` : 'L1' }, l1TxId: tx.l1TxId };
    case 'governance': {
      const command = tx.governance;
      if (!command) return base;
      const vault = vaultById(vaults, command.vaultId);
      return { ...base, from: { text: `command #${command.sequence}` },
        to: { text: command.command === 'ASSET_STATUS'
          ? `${vault ? vaultLabel(vault) : `vault ${short(command.vaultId ?? '', 4)}`} → ${command.status}`
          : command.command === 'PAUSE' ? 'pause the domain' : 'resume the domain' } };
    }
    default:
      return { ...base, note: `${tx.topic || '(no topic)'} · ${tx.size} bytes${tx.error ? ` · ${tx.error}` : ''}` };
  }
}

/** "12s", "5m", "3h", "2d" ago; "just now" under two seconds; a future time (clock skew) reads "just now". */
export function ago(timestampMs: number, nowMs: number): string {
  const seconds = Math.floor((nowMs - timestampMs) / 1_000);
  if (seconds < 2) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}
