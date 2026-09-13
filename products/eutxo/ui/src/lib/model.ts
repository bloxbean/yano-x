import type {
  AppChainStatus,
  DiscoveredChain,
  EutxoBridgeInfo,
  EutxoTransactionEntry
} from './types';

export const EUTXO_BUNDLE_ID = 'org.yanoproject.x.eutxo';
export const EUTXO_INDEX_BUNDLE_ID = 'org.yanoproject.x.eutxo.indexer';
export const EUTXO_BRIDGE_BUNDLE_ID =
  'org.yanoproject.x.eutxo.bridge.cardano';
export const EUTXO_STATE_MACHINE_ID = 'eutxo-ledger';

const IDENTIFIER = /^[0-9a-f]{64}$/;
const OUTPOINT = /^([0-9a-f]{64})#([0-9]|[1-9][0-9]{0,4})$/;
const ADA = /^([0-9]+)(?:\.([0-9]{1,6}))?$/;

export function isEutxoChain(status: AppChainStatus | null): boolean {
  return status?.stateMachine === EUTXO_STATE_MACHINE_ID
    || status?.capabilityManifest?.components.some(
      (component) => component.id === EUTXO_STATE_MACHINE_ID) === true;
}

export function withLiveStatus(
  chain: DiscoveredChain,
  status: AppChainStatus
): DiscoveredChain {
  if (status.chainId && status.chainId !== chain.summary.chainId) {
    throw new Error('Live app-chain status does not match the selected chain');
  }
  const mergedStatus = { ...chain.status, ...status };
  return {
    ...chain,
    status: mergedStatus,
    summary: {
      ...chain.summary,
      tipHeight: mergedStatus.tipHeight ?? chain.summary.tipHeight,
      stateRoot: mergedStatus.stateRoot
        ?? mergedStatus.stateCommitment?.stateRoot
        ?? chain.summary.stateRoot,
      stateCommitment: mergedStatus.stateCommitment ?? chain.summary.stateCommitment
    }
  };
}

export function canonicalIdentifier(value: string): string {
  const normalized = value.trim();
  if (!IDENTIFIER.test(normalized)) {
    throw new Error('Enter a lowercase 64-character transaction, message, or claim ID');
  }
  return normalized;
}

export function canonicalOutpoint(value: string): { transactionId: string; outputIndex: number } {
  const normalized = value.trim();
  const match = OUTPOINT.exec(normalized);
  if (!match) throw new Error('Enter a lowercase transaction ID followed by #output-index');
  const outputIndex = Number(match[2]);
  if (outputIndex > 65_535) throw new Error('Output index must be at most 65535');
  return { transactionId: match[1], outputIndex };
}

export function short(value: string, width = 24): string {
  if (!value) return '—';
  return value.length <= width ? value : `${value.slice(0, Math.max(6, width - 8))}…${value.slice(-7)}`;
}

export function formatLovelace(value: string | number | bigint): string {
  try {
    const lovelace = BigInt(value);
    const ada = lovelace / 1_000_000n;
    const fraction = (lovelace % 1_000_000n).toString().padStart(6, '0').replace(/0+$/, '');
    return `${ada.toLocaleString()}${fraction ? `.${fraction}` : ''} ADA`;
  } catch {
    return String(value);
  }
}

export function adaToLovelace(value: string): number | null {
  const match = ADA.exec(value.trim());
  if (!match) return null;
  const whole = Number(match[1]);
  const fraction = match[2] ? Number(match[2].padEnd(6, '0')) : 0;
  const result = whole * 1_000_000 + fraction;
  return Number.isSafeInteger(result) ? result : null;
}

export function validateDeposit(value: number | null, bridge: EutxoBridgeInfo): string | null {
  if (value === null) return 'Enter an ADA amount such as 5 or 5.25';
  if (value < 1_000_000) return 'Deposit at least 1 ADA';
  if (value > bridge.maxDepositLovelace) {
    return `This bridge caps deposits at ${formatLovelace(bridge.maxDepositLovelace)}`;
  }
  return null;
}

export function totalLovelace(entries: EutxoTransactionEntry[]): string {
  try {
    return formatLovelace(entries.reduce((sum, entry) => sum + BigInt(entry.lovelace), 0n));
  } catch {
    return '—';
  }
}

export function expectedWalletNetworkId(network: string): number | null {
  const value = network.trim().toLowerCase();
  if (!value) return null;
  if (value === 'mainnet' || value === 'main') return 1;
  if (['preprod', 'preview', 'testnet', 'test', 'devnet', 'dev'].includes(value)) return 0;
  return null;
}

export function indexStatusLabel(status: string): string {
  if (status.startsWith('READY_')) return 'Ready';
  if (status.startsWith('REBUILDING_')) return 'Rebuilding';
  if (status.startsWith('CATCHING_UP_')) return 'Catching up';
  if (status.includes('IDENTITY')) return 'Identity mismatch';
  return 'Unavailable';
}
