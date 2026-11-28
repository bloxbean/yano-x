/**
 * Runtime configuration, from /payments-client.json and URL parameters. Nothing here names a network: the wallet's
 * network is checked against the chain's own intent domain, so the same build runs on a devnet, preview, preprod or
 * mainnet node.
 */
export interface ClientConfig {
  /** The Yano X node's API base, e.g. /api/v1 (proxied) or https://node.example/api/v1. */
  nodeUrl: string;
  /** The asset-payments chain id; empty picks the first hosted chain that answers payments/info. */
  chainId: string;
  /** Optional topic-scoped submit key (yano.app-chain.api.keys=key=asset-payments.intents). */
  apiKey: string;
  /** Loads Yaci DevKit's CIP-30 wallet SDK, e.g. http://localhost:10000/wallet-sdk.js (devnet only). */
  devkitWalletSdkUrl: string;
  /** Optional explorer link template for L1 transactions, with {tx}. */
  explorerTxUrl: string;
  /** Display label only. */
  networkLabel: string;
}

const DEFAULTS: ClientConfig = {
  nodeUrl: '/api/v1',
  chainId: '',
  apiKey: '',
  devkitWalletSdkUrl: '',
  explorerTxUrl: '',
  networkLabel: ''
};

export async function loadConfig(location: Location): Promise<ClientConfig> {
  let file: Partial<ClientConfig> = {};
  try {
    const response = await fetch('payments-client.json', { cache: 'no-store' });
    if (response.ok) file = (await response.json()) as Partial<ClientConfig>;
  } catch {
    // The defaults serve a proxied local node.
  }
  const config: ClientConfig = { ...DEFAULTS, ...file };
  const params = new URLSearchParams(location.search);
  if (params.get('node')) config.nodeUrl = params.get('node')!;
  if (params.get('chain')) config.chainId = params.get('chain')!;
  if (params.get('devkit')) config.devkitWalletSdkUrl = params.get('devkit')!;
  config.nodeUrl = config.nodeUrl.replace(/\/+$/, '');
  return config;
}

export function loadScript(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = url;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`cannot load ${url}`));
    document.head.appendChild(script);
  });
}
