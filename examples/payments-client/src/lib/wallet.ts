/**
 * CIP-30 wallet access (#109 §3.1). The client feature-detects signData and refuses wallets without it: app-ledger
 * actions are authorized only by CIP-8 signatures (or an owner-authorized session key), never by anything weaker.
 */
import { bech32Decode } from './bech32';
import { parseCoseSign1 } from './cose';
import { equalBytes, fromHex, toHex } from './hex';

export interface DataSignature {
  signature: string;
  key: string;
}

export interface Cip30Api {
  getNetworkId(): Promise<number>;
  getUtxos(amount?: string, paginate?: { page: number; limit: number }): Promise<string[] | null | undefined>;
  getChangeAddress(): Promise<string>;
  getUsedAddresses(paginate?: { page: number; limit: number }): Promise<string[]>;
  getRewardAddresses(): Promise<string[]>;
  signTx(tx: string, partialSign?: boolean): Promise<string>;
  signData?(address: string, payload: string): Promise<DataSignature>;
  submitTx?(tx: string): Promise<string>;
}

interface InjectedWallet {
  name?: string;
  icon?: string;
  apiVersion?: string;
  enable(options?: { extensions?: { cip: number }[] }): Promise<Cip30Api>;
}

declare global {
  interface Window {
    cardano?: Record<string, InjectedWallet | undefined>;
  }
}

export interface WalletDescriptor {
  key: string;
  name: string;
  icon: string;
}

/** Wallets that inject a CIP-30 entry point. The DevKit wallet registers as {@code yacidevkit}, Yano Wallet as {@code yano}. */
export function installedWallets(): WalletDescriptor[] {
  const injected = window.cardano;
  if (!injected) return [];
  return Object.getOwnPropertyNames(injected)
    .filter((key) => typeof injected[key]?.enable === 'function')
    .map((key) => ({
      key,
      name: injected[key]?.name?.trim() || key,
      icon: injected[key]?.icon?.startsWith('data:image/') ? injected[key]!.icon! : ''
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
}

export class UnsupportedWalletError extends Error {}

export interface ConnectedWallet {
  key: string;
  name: string;
  api: Cip30Api;
  networkId: number;
  /** Raw address bytes the account signs with (payment-key address by default). */
  signingAddress: Uint8Array;
  /** The wallet's change address: refunds, deposit change and default withdrawal recipient. */
  changeAddress: Uint8Array;
  rewardAddress: Uint8Array | null;
  quirks: string[];
}

/** CIP-30 returns addresses as hex bytes; some wallets return bech32. */
export function addressBytes(value: string): Uint8Array {
  const trimmed = value.trim();
  return /^[0-9a-fA-F]+$/.test(trimmed) ? fromHex(trimmed) : bech32Decode(trimmed).bytes;
}

export async function connectWallet(descriptor: WalletDescriptor, expectedNetworkId: number,
                                    useStakeKey = false): Promise<ConnectedWallet> {
  const injected = window.cardano?.[descriptor.key];
  if (!injected || typeof injected.enable !== 'function') throw new Error(`${descriptor.name} is no longer available`);
  const api = await injected.enable();
  if (typeof api.signData !== 'function') {
    throw new UnsupportedWalletError(`${descriptor.name} does not implement CIP-30 signData, which app-ledger actions `
      + 'require (CIP-8 message signing). Use a wallet with signData support; there is no weaker fallback.');
  }
  const networkId = await api.getNetworkId();
  if (networkId !== expectedNetworkId) {
    throw new UnsupportedWalletError(`${descriptor.name} is on network id ${networkId} but this chain is bound to `
      + `address network id ${expectedNetworkId}. Switch the wallet's network.`);
  }
  const used = await api.getUsedAddresses().catch(() => [] as string[]);
  const change = addressBytes(await api.getChangeAddress());
  const rewards = await api.getRewardAddresses().catch(() => [] as string[]);
  const reward = rewards.length > 0 ? addressBytes(rewards[0]) : null;
  const payment = used.length > 0 ? addressBytes(used[0]) : change;
  if (useStakeKey && !reward) throw new UnsupportedWalletError(`${descriptor.name} exposes no reward address`);
  return {
    key: descriptor.key,
    name: descriptor.name,
    api,
    networkId,
    signingAddress: useStakeKey ? reward! : payment,
    changeAddress: change,
    rewardAddress: reward,
    quirks: []
  };
}

/**
 * CIP-30 signData over the exact envelope bytes, checked before anything is submitted: the COSE payload must be the
 * envelope and its protected address the account's signing address. Yaci DevKit 0.12's wallet backend swaps the
 * address and payload arguments; that is detected from its own output and retried once with the arguments swapped,
 * and recorded as a quirk. Any other mismatch is refused.
 */
export async function signEnvelope(wallet: ConnectedWallet, envelope: Uint8Array): Promise<DataSignature> {
  const addressHex = toHex(wallet.signingAddress);
  const payloadHex = toHex(envelope);
  const first = await wallet.api.signData!(addressHex, payloadHex);
  if (signs(first, wallet.signingAddress, envelope)) return first;
  const view = safeParse(first.signature);
  if (view?.payload && equalBytes(view.payload, wallet.signingAddress)) {
    const swapped = await wallet.api.signData!(payloadHex, addressHex);
    if (signs(swapped, wallet.signingAddress, envelope)) {
      if (!wallet.quirks.includes('signData-arguments-swapped')) wallet.quirks.push('signData-arguments-swapped');
      return swapped;
    }
  }
  throw new Error('The wallet signed different bytes or a different address than requested; nothing was submitted.');
}

function signs(result: DataSignature, address: Uint8Array, envelope: Uint8Array): boolean {
  const view = safeParse(result?.signature);
  return !!view && !!view.payload && !!view.protectedAddress && view.hashed !== true
    && equalBytes(view.payload, envelope) && equalBytes(view.protectedAddress, address);
}

function safeParse(hex: string | undefined) {
  try {
    return hex ? parseCoseSign1(fromHex(hex)) : null;
  } catch {
    return null;
  }
}
