/** Display helpers shared by the views. Nothing here decides what is signed. */
import type { Committed, ProofRef, VaultView } from './api';
import type { Fact } from './proof';

export function short(hex: string, keep = 10): string {
  return hex.length <= 2 * keep + 3 ? hex : `${hex.slice(0, keep)}…${hex.slice(-keep)}`;
}

/** The asset's display name: the registry ticker, ADA, or a shortened unit. Display metadata only. */
export function vaultLabel(vault: Pick<VaultView, 'ticker' | 'asset'>): string {
  return vault.ticker || (vault.asset.ada ? 'ADA' : short(vault.asset.unit, 8));
}

/** The full AssetId the ledger uses (policy id and asset name hex), independent of the node's ticker. */
export function assetIdText(vault: Pick<VaultView, 'asset'>): string {
  return vault.asset.ada ? 'lovelace (ADA)' : `${vault.asset.policyId}.${vault.asset.assetName}`;
}

/** A view's fact for the proof check: its key and value, with the height and root the view was read at. */
export function factOf(ref: ProofRef, committed: Committed): Fact {
  return { keyHex: ref.keyHex, valueHex: ref.valueHex, height: committed.height, stateRoot: committed.stateRoot };
}

/** The registry status as a short label and tone. */
export function vaultStatus(status: string): { label: string; tone: 'accent' | 'amber' | 'rose' | 'default' } {
  switch (status) {
    case 'ACTIVE': return { label: 'Active', tone: 'accent' };
    case 'DEPOSITS_PAUSED': return { label: 'Deposits paused', tone: 'amber' };
    case 'TRANSFERS_PAUSED': return { label: 'Transfers paused', tone: 'amber' };
    case 'WIND_DOWN': return { label: 'Winding down', tone: 'amber' };
    case 'RETIRED': return { label: 'Retired', tone: 'rose' };
    default: return { label: status, tone: 'default' };
  }
}

export function explorerLink(template: string, txId: string | null | undefined): string | null {
  return template && txId ? template.replace('{tx}', txId) : null;
}
