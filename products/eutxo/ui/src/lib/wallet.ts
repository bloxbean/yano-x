import { expectedWalletNetworkId } from './model';

export interface Cip30WalletApi {
  getNetworkId(): Promise<number>;
  getChangeAddress(): Promise<string>;
  signTx(transactionCborHex: string, partialSign: boolean): Promise<string>;
  submitTx?(transactionCborHex: string): Promise<string>;
}

export interface WalletDescriptor {
  key: string;
  name: string;
  icon: string;
}

interface InjectedWallet {
  name?: string;
  icon?: string;
  enable(): Promise<Cip30WalletApi>;
}

declare global {
  interface Window {
    cardano?: Record<string, InjectedWallet>;
  }
}

export function installedWallets(): WalletDescriptor[] {
  const injected = window.cardano;
  if (!injected) return [];
  return Object.getOwnPropertyNames(injected)
    .filter((key) => typeof injected[key]?.enable === 'function')
    .map((key) => ({
      key,
      name: injected[key].name?.trim() || key,
      icon: injected[key].icon?.startsWith('data:image/') ? injected[key].icon : ''
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
}

export async function connectWallet(
  wallet: WalletDescriptor,
  expectedNetwork: string
): Promise<Cip30WalletApi> {
  const injected = window.cardano?.[wallet.key];
  if (!injected || typeof injected.enable !== 'function') {
    throw new Error(`${wallet.name} is no longer available in this page`);
  }
  const api = await injected.enable();
  const actualNetwork = await api.getNetworkId();
  const expected = expectedWalletNetworkId(expectedNetwork);
  if (expected !== null && actualNetwork !== expected) {
    throw new Error(
      `Wallet network ${actualNetwork === 1 ? 'mainnet' : 'testnet'} does not match ${expectedNetwork}`
    );
  }
  return api;
}
